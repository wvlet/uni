/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.jsenv.playwright

import org.scalajs.jsenv.{Input, RunConfig}

import java.io.{PipedInputStream, PipedOutputStream, PrintStream}
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
  * Drives a single Scala.js run in a real browser on one worker thread.
  *
  *   - It serves the linked inputs (plus the [[JsBridge]] shim) to a Playwright page, then polls the
  *     page every `pollIntervalMs` for console output, errors, and com-messages, and pushes queued
  *     outbound messages back in.
  *   - `send` and `close` are safe to call from other threads (they only touch a concurrent queue
  *     and an atomic flag); all Playwright calls stay on the worker thread.
  *   - On an uncaught JS error (or any failure), it captures a screenshot and (if enabled) a trace
  *     before tearing down, then fails [[future]].
  */
private[playwright] class PlaywrightEngine(
    config: PlaywrightConfig,
    input: Seq[Input],
    runConfig: RunConfig,
    enableCom: Boolean,
    onMessage: String => Unit
):
  private val pollIntervalMs = 100L
  private val readyTimeoutMs = 30000L

  private val sendQueue   = ConcurrentLinkedQueue[String]()
  private val wantToClose = AtomicBoolean(false)
  private val promise     = Promise[Unit]()

  private val worker = Thread(() => runLoop(), "uni-playwright-jsenv")
  worker.setDaemon(true)
  worker.start()

  def future: Future[Unit] = promise.future

  /** Enqueue a message to deliver to the running JS (com mode). */
  def send(msg: String): Unit = sendQueue.offer(msg)

  /** Request shutdown; idempotent and non-blocking. */
  def close(): Unit = wantToClose.set(true)

  private def runLoop(): Unit =
    val materializer        = Materializer()
    var session: BrowserSession = null
    var streams: RunStreams     = null
    try
      streams = RunStreams.prepare(runConfig)
      session = BrowserSession.launch(
        config.browserName,
        config.headless,
        config.launchArgs,
        tracingEnabled = config.captureArtifactsOnFailure
      )
      val shimUrl = materializer.write(".js", JsBridge.setupScript(enableCom))
      val htmlUrl = materializer.write(".html", JsBridge.htmlPage(shimUrl, input))
      session.page.navigate(htmlUrl.toString)
      awaitReady(session)

      while !wantToClose.get() do
        pump(session, streams)
        Thread.sleep(pollIntervalMs)
      // Final drain so output/messages produced right before close are not lost.
      pump(session, streams)

      promise.trySuccess(())
    catch
      case NonFatal(t) =>
        captureArtifacts(session)
        promise.tryFailure(t)
      case t: Throwable =>
        promise.tryFailure(t)
    finally
      if streams != null then streams.close()
      materializer.close()
      if session != null then session.close()
  end runLoop

  private def awaitReady(session: BrowserSession): Unit =
    val deadline = System.currentTimeMillis() + readyTimeoutMs
    while !isReady(session) do
      if System.currentTimeMillis() > deadline then
        throw RuntimeException(
          s"Timed out after ${readyTimeoutMs}ms waiting for the Playwright page to load the Scala.js bridge"
        )
      Thread.sleep(pollIntervalMs)

  private def isReady(session: BrowserSession): Boolean =
    session.page.evaluate(s"() => !!${JsBridge.controlInterface}") match
      case b: java.lang.Boolean => b.booleanValue()
      case _                    => false

  /** One poll cycle: push outbound messages, drain console/errors/inbound messages. */
  private def pump(session: BrowserSession, streams: RunStreams): Unit =
    // JVM -> JS
    var msg = sendQueue.poll()
    while msg != null do
      session.page.evaluate(s"arg => ${JsBridge.controlInterface}.send(arg)", msg)
      msg = sendQueue.poll()

    // JS -> JVM
    val resp =
      session.page.evaluate(s"() => ${JsBridge.controlInterface}.fetch()") match
        case m: java.util.Map[?, ?] => m.asInstanceOf[java.util.Map[String, Object]]
        case _                      => java.util.Collections.emptyMap[String, Object]()

    strings(resp, "consoleLog").foreach(streams.out.println)
    strings(resp, "consoleError").foreach(streams.err.println)
    strings(resp, "msgs").foreach(onMessage)

    val errs = strings(resp, "errors")
    if errs.nonEmpty then
      throw RuntimeException(s"Uncaught JS error(s): ${errs.mkString("; ")}")
  end pump

  private def strings(resp: java.util.Map[String, Object], key: String): List[String] =
    resp.get(key) match
      case l: java.util.List[?] => l.asScala.iterator.map(String.valueOf).toList
      case _                    => Nil

  private def captureArtifacts(session: BrowserSession): Unit =
    if config.captureArtifactsOnFailure && session != null then
      try
        Files.createDirectories(config.artifactDir)
        val stamp = System.nanoTime()
        session.screenshot(config.artifactDir.resolve(s"failure-${stamp}.png"))
        session.stopTracing(config.artifactDir.resolve(s"trace-${stamp}.zip"))
        runConfig
          .logger
          .error(
            s"Playwright captured failure artifacts in ${config.artifactDir.toAbsolutePath}. " +
              s"View the trace with: npx playwright show-trace ${config
                  .artifactDir
                  .resolve(s"trace-${stamp}.zip")}"
          )
      catch
        case NonFatal(_) =>

end PlaywrightEngine

/**
  * Resolves the RunConfig output policy into a pair of PrintStreams. When the consumer asks for the
  * streams (`onOutputStream`), we hand it the read end of a pipe; otherwise we inherit the JVM's
  * stdout/stderr (without owning them, so closing never closes the real streams).
  */
private[playwright] class RunStreams(val out: PrintStream, val err: PrintStream, closer: () => Unit):
  def close(): Unit = closer()

private[playwright] object RunStreams:
  import java.io.{FilterOutputStream, OutputStream}

  // A stream that flushes but never closes the wrapped (shared) stream.
  private class Unowned(underlying: OutputStream) extends FilterOutputStream(underlying):
    override def close(): Unit = flush()

  def prepare(runConfig: RunConfig): RunStreams =
    val outPipe = if runConfig.inheritOutput then None else Some(pipe())
    val errPipe = if runConfig.inheritError then None else Some(pipe())

    runConfig
      .onOutputStream
      .foreach(f => f(outPipe.map(_._1), errPipe.map(_._1)))

    val out = outPipe.map(p => PrintStream(p._2)).getOrElse(PrintStream(Unowned(System.out)))
    val err = errPipe.map(p => PrintStream(p._2)).getOrElse(PrintStream(Unowned(System.err)))
    RunStreams(out, err, () => { out.close(); err.close() })

  private def pipe(): (PipedInputStream, PipedOutputStream) =
    val in  = PipedInputStream()
    val out = PipedOutputStream(in)
    (in, out)

end RunStreams

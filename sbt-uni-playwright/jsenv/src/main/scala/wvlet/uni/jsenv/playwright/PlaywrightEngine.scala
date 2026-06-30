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
  *   - It serves the linked inputs (plus the [[JsBridge]] shim) to a Playwright page, then polls
  *     the page every `pollIntervalMs` for console output, errors, and com-messages, and pushes
  *     queued outbound messages back in.
  *   - `send` and `close` are safe to call from other threads (they only touch a concurrent queue
  *     and an atomic flag); all Playwright calls stay on the worker thread.
  *   - On an uncaught JS error (or any failure), it captures a screenshot and (if enabled) a trace
  *     before tearing down, then fails [[future]].
  */
private[playwright] final class PlaywrightEngine(
    config: PlaywrightConfig,
    input: Seq[Input],
    runConfig: RunConfig,
    enableCom: Boolean,
    onMessage: String => Unit
):
  private val pollIntervalMs = 100L
  private val readyTimeoutMs = 30000L

  // Constant JS expressions evaluated each poll cycle (the control interface name is a constant).
  private val readyExpr     = s"() => !!${JsBridge.controlInterface}"
  private val fetchExpr     = s"() => ${JsBridge.controlInterface}.fetch()"
  private val sendBatchExpr = s"args => ${JsBridge.controlInterface}.sendBatch(args)"

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
    val materializer            = Materializer()
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

      if awaitReady(session) then
        while !wantToClose.get() do
          pump(session, streams)
          Thread.sleep(pollIntervalMs)
        // Final drain so output/messages produced right before close are not lost.
        pump(session, streams)

      promise.trySuccess(())
    catch
      case t: Throwable =>
        // Complete the future first, so a failure inside artifact capture can never leave it hanging.
        promise.tryFailure(t)
        captureArtifacts(session)
    finally
      if streams != null then
        streams.close()
      // Close the browser before deleting the temp files it loaded.
      if session != null then
        session.close()
      materializer.close()
    end try
  end runLoop

  /** Wait until the page's control interface is installed. Returns false if close() came first. */
  private def awaitReady(session: BrowserSession): Boolean =
    val deadline = System.currentTimeMillis() + readyTimeoutMs
    while !wantToClose.get() do
      if isReady(session) then
        return true
      if System.currentTimeMillis() > deadline then
        throw RuntimeException(
          s"Timed out after ${readyTimeoutMs}ms waiting for the Playwright page to load the Scala.js bridge"
        )
      Thread.sleep(pollIntervalMs)
    false

  private def isReady(session: BrowserSession): Boolean =
    session.page.evaluate(readyExpr) match
      case b: java.lang.Boolean =>
        b.booleanValue()
      case _ =>
        false

  /** One poll cycle: push outbound messages, drain console/errors/inbound messages. */
  private def pump(session: BrowserSession, streams: RunStreams): Unit =
    // JVM -> JS: drain the queue and deliver in a single round-trip.
    val outbound = java.util.ArrayList[String]()
    var msg      = sendQueue.poll()
    while msg != null do
      outbound.add(msg)
      msg = sendQueue.poll()
    if !outbound.isEmpty then
      session.page.evaluate(sendBatchExpr, outbound)

    // JS -> JVM
    val resp =
      session.page.evaluate(fetchExpr) match
        case m: java.util.Map[?, ?] =>
          m.asInstanceOf[java.util.Map[String, Object]]
        case _ =>
          java.util.Collections.emptyMap[String, Object]()

    strings(resp, "consoleLog").foreach(streams.out.println)
    strings(resp, "consoleError").foreach(streams.err.println)
    strings(resp, "msgs").foreach(onMessage)

    val errs = strings(resp, "errors")
    if errs.nonEmpty then
      throw RuntimeException(s"Uncaught JS error(s): ${errs.mkString("; ")}")
  end pump

  private def strings(resp: java.util.Map[String, Object], key: String): List[String] =
    resp.get(key) match
      case l: java.util.List[?] =>
        l.asScala.iterator.map(String.valueOf).toList
      case _ =>
        Nil

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
private[playwright] class RunStreams(
    val out: PrintStream,
    val err: PrintStream,
    closer: () => Unit
):
  def close(): Unit = closer()

private[playwright] object RunStreams:
  import java.io.{FilterOutputStream, OutputStream}

  // A stream that flushes but never closes the wrapped (shared) stream. Overrides the bulk write so
  // it delegates directly instead of FilterOutputStream's byte-at-a-time default.
  private class Unowned(underlying: OutputStream) extends FilterOutputStream(underlying):
    override def write(b: Array[Byte], off: Int, len: Int): Unit = underlying.write(b, off, len)
    override def close(): Unit                                   = flush()

  def prepare(runConfig: RunConfig): RunStreams =
    // Only create a pipe when there is a consumer (onOutputStream) to read its other end.
    val capture = runConfig.onOutputStream.isDefined
    val outPipe =
      if capture && !runConfig.inheritOutput then
        Some(pipe())
      else
        None
    val errPipe =
      if capture && !runConfig.inheritError then
        Some(pipe())
      else
        None
    try
      runConfig.onOutputStream.foreach(f => f(outPipe.map(_._1), errPipe.map(_._1)))

      val out = printStream(runConfig.inheritOutput, outPipe, System.out)
      val err = printStream(runConfig.inheritError, errPipe, System.err)
      RunStreams(
        out,
        err,
        () =>
          out.close();
          err.close()
      )
    catch
      case t: Throwable =>
        // The pipes aren't owned by a RunStreams yet, so close them here if setup fails.
        List(outPipe, errPipe)
          .flatten
          .foreach { (in, w) =>
            closeQuietly(in)
            closeQuietly(w)
          }
        throw t

  end prepare

  private def closeQuietly(c: AutoCloseable): Unit =
    try
      c.close()
    catch
      case NonFatal(_) =>

  // autoFlush so console output appears incrementally rather than only at close.
  private def printStream(
      inherit: Boolean,
      pipe: Option[(PipedInputStream, PipedOutputStream)],
      inherited: OutputStream
  ): PrintStream =
    if inherit then
      PrintStream(Unowned(inherited), true)
    else
      pipe match
        case Some((_, w)) =>
          PrintStream(w, true)
        // Neither inherited nor captured: discard, so an unread pipe can never block the worker.
        case None =>
          PrintStream(OutputStream.nullOutputStream(), true)

  private def pipe(): (PipedInputStream, PipedOutputStream) =
    // 64 KiB buffer (vs the 1 KiB default) to reduce writer backpressure on bursty console output.
    val in  = PipedInputStream(64 * 1024)
    val out = PipedOutputStream(in)
    (in, out)

end RunStreams

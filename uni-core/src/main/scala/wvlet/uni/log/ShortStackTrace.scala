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
package wvlet.uni.log

/**
  * A shortened stack trace for people reading a terminal: the exception line, its first few frames
  * outside test and runtime frameworks, and the first frames of each cause. The full trace is saved
  * to a file under `traceDir`, and the last line links to it with an OSC 8 hyperlink, which most
  * terminals open on click. Terminals without OSC 8 show the path as plain text.
  *
  * A trace is shortened only when its full version was saved, so where files are unavailable
  * (Scala.js) the full trace is printed. Log handlers writing to an interactive terminal use this;
  * output to files, pipes, and CI logs keeps the full trace. See [[ShortStackTrace.forConsole]].
  *
  * @param maxFrames
  *   frames to show for the top-level exception
  * @param maxCauseFrames
  *   frames to show for each `Caused by:` exception
  * @param traceDir
  *   directory to save full traces in
  */
case class ShortStackTrace(
    maxFrames: Int = 5,
    maxCauseFrames: Int = 2,
    traceDir: String = ShortStackTrace.defaultTraceDir
):
  def withMaxFrames(n: Int): ShortStackTrace      = copy(maxFrames = n)
  def withMaxCauseFrames(n: Int): ShortStackTrace = copy(maxCauseFrames = n)
  def withTraceDir(dir: String): ShortStackTrace  = copy(traceDir = dir)

  /**
    * Save the full trace of `e` and return the shortened one linking to it, or the full trace when
    * it is already short or could not be saved
    */
  def format(e: Throwable): String =
    val full = LogFormatter.formatStacktrace(e)
    if full.linesIterator.size <= shortLines(e).size then
      full
    else
      val name = s"${e.getClass.getSimpleName}-${Integer.toHexString(full.hashCode)}.txt"
      LogEnv.saveStackTrace(traceDir, name, full) match
        case Some(path) =>
          shorten(e, path)
        case None =>
          full

  /**
    * The shortened trace of `e`, ending with a link to `fullTracePath`
    */
  def shorten(e: Throwable, fullTracePath: String): String =
    val shown   = shortLines(e)
    val omitted = LogFormatter.formatStacktrace(e).linesIterator.size - shown.size
    if omitted <= 0 then
      shown.mkString("\n")
    else
      (shown :+ s"\t... ${omitted} more lines: ${ShortStackTrace.fileLink(fullTracePath)}")
        .mkString("\n")

  private def shortLines(e: Throwable): Seq[String] =
    val lines = Seq.newBuilder[String]
    lines += e.toString
    ShortStackTrace.selectFrames(e, maxFrames).foreach(f => lines += s"\tat ${f}")

    // Follow the cause chain, stopping at a cycle
    val seen = java.util.IdentityHashMap[Throwable, Boolean]()
    seen.put(e, true)
    var cause = e.getCause
    while cause != null && !seen.containsKey(cause) do
      seen.put(cause, true)
      lines += s"Caused by: ${cause}"
      ShortStackTrace.selectFrames(cause, maxCauseFrames).foreach(f => lines += s"\tat ${f}")
      cause = cause.getCause
    lines.result()

end ShortStackTrace

object ShortStackTrace:
  def defaultTraceDir: String =
    val tmp = sys.props.getOrElse("java.io.tmpdir", "/tmp")
    s"${tmp.stripSuffix("/")}/uni-stacktraces"

  // Frames of test runners, reflection, and thread pools that rarely locate a bug
  private val frameworkPrefixes = Seq(
    "sbt.",
    "org.scalajs.",
    "wvlet.uni.test.spi.",
    "wvlet.uni.test.UniTest",
    "wvlet.uni.test.compat",
    "org.junit.",
    "java.lang.reflect.",
    "java.lang.Thread",
    "jdk.internal.",
    "sun.reflect.",
    "java.util.concurrent.",
    "scala.concurrent.",
    "scala.runtime."
  )

  private[log] def isFrameworkFrame(f: StackTraceElement): Boolean =
    val cls = f.getClassName
    cls != null && frameworkPrefixes.exists(cls.startsWith)

  /**
    * The first `n` frames outside frameworks. When every frame is a framework frame, the error came
    * from a framework itself, so show its top frames as they are.
    */
  private[log] def selectFrames(e: Throwable, n: Int): Seq[StackTraceElement] =
    val frames = e.getStackTrace.toSeq
    val user   = frames.filterNot(isFrameworkFrame)
    (
      if user.isEmpty then
        frames
      else
        user
    ).take(n)

  /**
    * OSC 8 hyperlink to a local file, labeled with the path itself
    */
  def fileLink(path: String): String =
    val uri = s"file://${path.replace(" ", "%20")}"
    s"\u001b]8;;${uri}\u001b\\${path}\u001b]8;;\u001b\\"

  /**
    * Whether `CI` is set, as GitHub Actions, CircleCI, and most other CI services do
    */
  def isCI(env: Map[String, String]): Boolean = env
    .get("CI")
    .exists(v => v.nonEmpty && v != "false")

  // None: decide by the terminal; Some(style): forced by setForConsole
  @volatile
  private var consoleOverride: Option[Option[ShortStackTrace]] = None

  /**
    * The stack trace style for console log output: shortened when stderr is an interactive terminal
    * outside CI, and full (None) otherwise, unless [[setForConsole]] forced a style.
    */
  def forConsole: Option[ShortStackTrace] = consoleOverride.getOrElse(autoForConsole)

  private lazy val autoForConsole: Option[ShortStackTrace] =
    if !isCI(sys.env) && LogEnv.isStderrTerminal then
      Some(ShortStackTrace())
    else
      None

  /**
    * Force the console stack trace style: Some(style) to shorten, None for full traces
    */
  def setForConsole(style: Option[ShortStackTrace]): Unit = consoleOverride = Some(style)

  /**
    * Decide the console stack trace style by the terminal again
    */
  def resetForConsole(): Unit = consoleOverride = None

  private val current = ThreadLocal[ShortStackTrace]()

  /**
    * Format stack traces in `body` with `style` (None: full traces). Log handlers wrap their
    * formatter call with this, since only the handler knows where the output goes.
    */
  def withStyle[A](style: Option[ShortStackTrace])(body: => A): A =
    val prev = current.get()
    current.set(style.orNull)
    try body
    finally current.set(prev)

  /**
    * Format `e` with the style of the enclosing [[withStyle]], or in full
    */
  private[log] def formatInCurrentStyle(e: Throwable): String =
    current.get() match
      case null =>
        LogFormatter.formatStacktrace(e)
      case s =>
        s.format(e)

end ShortStackTrace

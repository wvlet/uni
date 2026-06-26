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

import org.scalajs.jsenv.Input

import java.net.URL

/**
  * The browser-side glue: a JS shim that buffers console output, errors, and com-messages into
  * `window` arrays, plus the HTML launcher page that loads the shim and the linked Scala.js inputs.
  *
  * The Java side never uses CDP for live messaging; it polls these buffers via `page.evaluate`. See
  * [[PlaywrightEngine]].
  */
private[playwright] object JsBridge:

  /** Name of the control object the Java side polls (must match the calls in [[PlaywrightEngine]]). */
  val controlInterface: String = "globalThis.__uniPlaywrightControl"

  /**
    * The shim, injected as the first script so it installs `scalajsCom` and the control interface
    * before any application code runs.
    *
    * @param enableCom
    *   when true, defines the global `scalajsCom` that the Scala.js test runtime talks to.
    */
  def setupScript(enableCom: Boolean): String =
    val comSetup =
      if enableCom then
        """  globalThis.scalajsCom = {
          |    init: function(onMsg) {
          |      onMessage = onMsg;
          |      // Flush any messages that arrived from the JVM before init ran.
          |      setTimeout(function() {
          |        if (inMessages !== null) { inMessages.forEach(onMessage); inMessages = null; }
          |      }, 0);
          |    },
          |    send: function(msg) { outMessages.push(msg); },
          |    close: function() {}
          |  };
          |""".stripMargin
      else
        ""

    s"""(function() {
       |  var consoleLog = [];
       |  var consoleError = [];
       |  var errors = [];
       |  var outMessages = [];   // JS -> JVM, drained by the control interface's fetch()
       |  var inMessages = [];    // JVM -> JS, buffered until scalajsCom.init() flushes them
       |  var onMessage = null;
       |
       |  var origLog = console.log.bind(console);
       |  var origErr = console.error.bind(console);
       |  console.log = function() {
       |    consoleLog.push(Array.prototype.join.call(arguments, ' '));
       |    origLog.apply(console, arguments);
       |  };
       |  console.error = function() {
       |    consoleError.push(Array.prototype.join.call(arguments, ' '));
       |    origErr.apply(console, arguments);
       |  };
       |  window.addEventListener('error', function(e) {
       |    errors.push(e.message || String(e));
       |  });
       |  window.addEventListener('unhandledrejection', function(e) {
       |    errors.push('Unhandled rejection: ' + ((e.reason && e.reason.message) || e.reason));
       |  });
       |
       |$comSetup
       |  ${controlInterface} = {
       |    fetch: function() {
       |      var r = {
       |        consoleLog: consoleLog.slice(),
       |        consoleError: consoleError.slice(),
       |        errors: errors.slice(),
       |        msgs: outMessages.slice()
       |      };
       |      consoleLog.length = 0;
       |      consoleError.length = 0;
       |      errors.length = 0;
       |      outMessages.length = 0;
       |      return r;
       |    },
       |    send: function(msg) {
       |      if (inMessages !== null) { inMessages.push(msg); } else if (onMessage) { onMessage(msg); }
       |    }
       |  };
       |})();
       |""".stripMargin
  end setupScript

  /**
    * Build the launcher HTML referencing the shim followed by each linked input, in order. `defer`
    * preserves execution order; ESModules are loaded as `type="module"`.
    */
  def htmlPage(shimUrl: URL, inputs: Seq[Input]): String =
    val tags = inputs.map(scriptTag).mkString("\n    ")
    s"""<!DOCTYPE html>
       |<html>
       |  <head><meta charset="UTF-8"></head>
       |  <body>
       |    <script defer type="text/javascript" src="${shimUrl}"></script>
       |    $tags
       |  </body>
       |</html>
       |""".stripMargin

  private def scriptTag(input: Input): String =
    input match
      case Input.Script(path) =>
        s"""<script defer type="text/javascript" src="${path.toUri.toURL}"></script>"""
      case Input.CommonJSModule(path) =>
        s"""<script defer type="text/javascript" src="${path.toUri.toURL}"></script>"""
      case Input.ESModule(path) =>
        s"""<script defer type="module" src="${path.toUri.toURL}"></script>"""
      case other =>
        throw UnsupportedInputException(other)

end JsBridge

private[playwright] class UnsupportedInputException(input: Input)
    extends Exception(s"Unsupported Scala.js input for the Playwright JSEnv: ${input}")

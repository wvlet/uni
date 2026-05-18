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
package wvlet.uni.control

import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.*
import wvlet.uni.rx.Rx
import wvlet.uni.rx.compat as rxCompat

/**
  * Scala.js implementation of [[Task]].
  *
  * Two scheduling modes:
  *
  *   - `Task.run { body }` — body runs cooperatively on the event-loop microtask queue. Works in
  *     Node and the browser. `await()` is unsupported on this path (would deadlock the event loop);
  *     use `awaitRx`.
  *   - `Task.runRegistered("id")` — on Node, body runs in a `worker_threads` worker so the main
  *     thread can block on `Atomics.wait`. Blocking `await()` works. Required for the wvlet
  *     cross-platform query-runner use case. On the browser this falls back to the microtask path
  *     (browsers don't allow `Atomics.wait` on the main thread). Bodies must be registered at
  *     module-init time (see [[TaskRegistry.register]]); the worker re-imports the same Scala.js
  *     bundle into its own isolate, so module-level `TaskRegistry.register` calls re-run there and
  *     populate the worker-side registry.
  *
  * See `plans/2026-05-18-cross-platform-thread.md` for the design rationale and uni#552 for the
  * driving requirement.
  */
private[control] object taskCompat:

  // ---- Shared-buffer layout (little-endian Int32 fields except the trailing UTF-8 error bytes).
  // Parent (main thread) reads state / writes cancel; worker (`__uniTaskInvoke`) writes state +
  // error message. The layout is symmetric across the two sides; keep the offsets single-sourced.
  //   [0..4)   state:        StateRunning / StateSuccess / StateFailed / StateCancelled
  //   [4..8)   cancelFlag:   0 = not requested, 1 = requested (set by parent.cancel)
  //   [8..12)  errorMsgLen:  bytes of the error message (Failed only; bounded by ErrorMsgCapacity)
  //   [12..)   errorMsgBody: UTF-8 bytes of the error message
  private final val OffsetState        = 0
  private final val OffsetCancelFlag   = 4
  private final val OffsetErrorMsgLen  = 8
  private final val OffsetErrorMsgBody = 12
  private final val HeaderBytes        = 12
  private final val ErrorMsgCapacity   = 4096
  private final val SabBytes           = HeaderBytes + ErrorMsgCapacity

  private final val StateRunning   = 0
  private final val StateSuccess   = 1
  private final val StateFailed    = 2
  private final val StateCancelled = 3

  // ---- Public entry points (called from Task.scala) ----

  def run(body: TaskContext => Unit): Task =
    val task = new JsTaskImpl()
    task.scheduleBody(body)
    task

  def runRegistered(taskId: String): Task =
    if isNode then
      new NodeWorkerTask(taskId)
    else
      // Browser: no worker_threads, no main-thread `Atomics.wait`. Fall back to the microtask
      // path with the looked-up body. `await()` still throws; callers use `awaitRx`.
      run(TaskRegistry.lookup(taskId))

  // ---- Microtask-scheduled Task (browser fallback, and the Task.run path everywhere on JS) ----

  private class JsTaskImpl extends TaskImpl:

    override def scheduleBody(body: TaskContext => Unit): Unit = js
      .Promise
      .resolve(())
      .`then`(_ => runBody(body))

    override def awaitTerminal(): Unit =
      throw new UnsupportedOperationException(
        "Task.await() is unsupported on Scala.js (Task.run); use awaitRx, or Task.runRegistered on Node."
      )

  end JsTaskImpl

  // ---- Node worker_threads-backed Task ----

  private class NodeWorkerTask(taskId: String) extends Task:

    private val sab        = js.Dynamic.newInstance(SharedArrayBufferCtor)(SabBytes)
    private val stateView  = Int32Array(sab.asInstanceOf[ArrayBuffer], 0, 3)
    private val view       = DataView(sab.asInstanceOf[ArrayBuffer])
    private val completion = Promise[Unit]()
    @volatile
    private var cancelReason: String = ""

    // Spawn the worker. It dynamic-imports the current Scala.js bundle (`import.meta.url`) and
    // calls `globalThis.__uniTaskInvoke(taskId, sab)` once the bundle's module init has
    // populated the registry.
    private val worker =
      val data = js.Dynamic.literal(taskId = taskId, sab = sab, bundleUrl = nodeMainScriptUrl)
      js.Dynamic
        .newInstance(workerThreads.Worker)(
          WorkerScript,
          js.Dynamic.literal(eval = true, workerData = data)
        )

    // Wake the awaitRx Promise as soon as the worker exits, even if the worker crashed before
    // writing a state (in which case we synthesise a Failed result so awaitRx doesn't hang).
    worker.applyDynamic("on")(
      "exit",
      { (_: Int) =>
        val s = atomicsLoad(stateView, 0)
        if s == StateRunning then
          // Worker crashed before writing — synthesise a Failed terminal state.
          writeError("Worker exited without producing a result")
          atomicsStore(stateView, 0, StateFailed)
          atomicsNotify(stateView, 0)
        finalizeCompletion()
      }: js.Function1[Int, Unit]
    )

    private def finalizeCompletion(): Unit =
      atomicsLoad(stateView, 0) match
        case StateSuccess =>
          completion.trySuccess(())
        case StateFailed =>
          completion.tryFailure(new RuntimeException(readError()))
        case StateCancelled =>
          completion.tryFailure(new InterruptedException(cancelMessage))
        case _ =>
          completion.tryFailure(new IllegalStateException("worker exited in non-terminal state"))

    private def cancelMessage: String =
      if cancelReason.isEmpty then
        "Task cancelled"
      else
        cancelReason

    override def state: Task.State =
      atomicsLoad(stateView, 0) match
        case StateSuccess =>
          Task.State.Succeeded
        case StateFailed =>
          Task.State.Failed
        case StateCancelled =>
          Task.State.Cancelled
        case _ =>
          Task.State.Running

    override def cancel(reason: String = ""): Unit =
      // CAS cancelFlag from 0 to 1; only the first successful caller stores the reason. The
      // worker reads cancelFlag at every `ctx.checkCancelled` / `ctx.isCancelled`.
      if atomicsCompareExchange(stateView, 1, 0, 1) == 0 then
        if reason.nonEmpty then
          cancelReason = reason

    override def await(): Unit =
      // Atomics.wait spins until state moves out of Running. The loop guards against spurious
      // wake-ups (wait can return without the state having changed).
      while atomicsLoad(stateView, 0) == StateRunning do
        atomicsWait(stateView, 0, StateRunning)
      atomicsLoad(stateView, 0) match
        case StateSuccess =>
          ()
        case StateFailed =>
          throw new RuntimeException(readError())
        case StateCancelled =>
          throw new InterruptedException(cancelMessage)
        case s =>
          throw new IllegalStateException(s"unexpected terminal state ${s}")

    override def awaitRx: Rx[Unit] =
      Rx.future(completion.future)(using rxCompat.defaultExecutionContext)

    private def readError(): String =
      val len = view.getInt32(OffsetErrorMsgLen, littleEndian = true)
      if len <= 0 then
        ""
      else
        val bytes = Int8Array(sab.asInstanceOf[ArrayBuffer], OffsetErrorMsgBody, len)
        String(bytes.toArray, "UTF-8")

    private def writeError(msg: String): Unit =
      val bytes = msg.getBytes("UTF-8")
      val len   = math.min(bytes.length, ErrorMsgCapacity)
      view.setInt32(OffsetErrorMsgLen, len, littleEndian = true)
      val target = Int8Array(sab.asInstanceOf[ArrayBuffer], OffsetErrorMsgBody, len)
      var i      = 0
      while i < len do
        target(i) = bytes(i)
        i += 1

  end NodeWorkerTask

  // ---- Worker-side dispatch entry point ----
  // The worker bootstrap (a plain JS string) imports the current Scala.js bundle into its own
  // isolate, then calls `globalThis.__uniTaskInvoke(taskId, sab)`.
  //
  // Why globalThis and not ESM `export`: bundles whose module body throws *after* our export
  // declaration (the sbt test bundle, for one, calls `Bridge.start()` at the bottom and fails
  // in worker isolates because `scalajsCom` isn't available) cause the `await import(...)`
  // promise to reject. The bundle's namespace exports — including any `@JSExportTopLevel` —
  // become inaccessible to the importer. A `globalThis` assignment performed *before* the
  // throw persists across the rejection, so the worker bootstrap can still find the entry
  // point.

  private def invokeInWorker(taskId: String, sab: js.Any): Unit =
    val ab        = sab.asInstanceOf[ArrayBuffer]
    val stateView = Int32Array(ab, 0, 3)
    val view      = DataView(ab)

    val ctx =
      new TaskContext:
        override def isCancelled: Boolean   = atomicsLoad(stateView, 1) != 0
        override def checkCancelled(): Unit =
          if isCancelled then
            throw new InterruptedException("Task cancelled")

    def writeError(msg: String): Unit =
      val bytes = msg.getBytes("UTF-8")
      val len   = math.min(bytes.length, ErrorMsgCapacity)
      view.setInt32(OffsetErrorMsgLen, len, littleEndian = true)
      val target = Int8Array(ab, OffsetErrorMsgBody, len)
      var i      = 0
      while i < len do
        target(i) = bytes(i)
        i += 1

    try
      val body = TaskRegistry.lookup(taskId)
      body(ctx)
      atomicsStore(stateView, 0, StateSuccess)
    catch
      case _: InterruptedException if ctx.isCancelled =>
        atomicsStore(stateView, 0, StateCancelled)
      case e: Throwable =>
        val msg =
          if e.getMessage != null then
            e.getMessage
          else
            e.getClass.getName
        writeError(msg)
        atomicsStore(stateView, 0, StateFailed)
    finally
      atomicsNotify(stateView, 0)

  end invokeInWorker

  // Eagerly assign `invokeInWorker` to `globalThis.__uniTaskInvoke` at bundle load. The `val`
  // initialiser fires at the @JSExportTopLevel emission point in the bundle — before the bundle
  // body's tail (which on the test bundle ends with the sbt test-bridge bootstrap that fails in
  // worker isolates).
  //
  // Why `Object.defineProperty` instead of `js.Dynamic.global.updateDynamic(...)`: the latter
  // is rewritten by the Scala.js linker into a bare identifier assignment (`__uniTaskInvoke = f`)
  // which throws ReferenceError in strict-mode ES modules. `defineProperty` is a regular method
  // call that the optimiser leaves alone.
  //
  // The exported boolean is just a marker to force eager `val` evaluation; its value is unused.
  @JSExportTopLevel("__uniTaskBundleBootstrap")
  val _bundleBootstrap: Boolean =
    if isNode then
      val f: js.Function2[String, js.Any, Unit] = (taskId, sab) => invokeInWorker(taskId, sab)
      // `js.Dynamic.global` cannot be passed as a value; obtain a concrete reference to
      // globalThis via js.eval. The eval runs in the module's evaluation context but
      // `globalThis` is a normal identifier available everywhere.
      val gt: js.Dynamic = js.eval("globalThis").asInstanceOf[js.Dynamic]
      gt.Object
        .applyDynamic("defineProperty")(
          gt,
          "__uniTaskInvoke",
          js.Dynamic.literal(value = f, writable = true, configurable = true)
        )
    true

  // ---- Helpers ----

  /**
    * True on a Node-compatible runtime (Node, Bun, Deno — all set `process.versions.node`).
    * Browsers (including Web Workers and JSDOM script sandboxes) lack it.
    *
    * Uses `js.eval("typeof process …")` instead of `js.isUndefined(js.Dynamic.global.process)`
    * because in `ModuleKind.NoModule` (e.g. the `uni-dom-test` JSDOM bundle) the latter compiles to
    * a bare `process` identifier reference, which throws `ReferenceError` in JSDOM's sandbox where
    * `process` isn't a defined global. `typeof` is the canonical undeclared-safe check.
    */
  private def isNode: Boolean = js
    .eval(
      "typeof process !== 'undefined' && typeof process.versions !== 'undefined' && typeof process.versions.node !== 'undefined'"
    )
    .asInstanceOf[Boolean]

  /**
    * URL of the current Scala.js module — `import.meta.url`. The worker uses this in its dynamic
    * `import(...)` call to re-load the same bundle into its own isolate.
    *
    * `import.meta.url` is the reliable source across `sbt-jsenv-nodejs` (which dynamic-imports the
    * bundle, leaving `process.argv[1]` empty), direct `node` invocation, Bun, and Deno. Empty on
    * the browser / non-Node, where `runRegistered` falls back to the microtask path.
    */
  private def nodeMainScriptUrl: String =
    if isNode then
      js.`import`.meta.url.asInstanceOf[String]
    else
      ""

  // `SharedArrayBuffer` has no typed Scala.js binding; access via js.Dynamic. Resolve lazily so
  // browser bundles without SAB don't crash at link time.
  private def SharedArrayBufferCtor: js.Dynamic = js.Dynamic.global.SharedArrayBuffer

  // Mirrors `NodeSyncHttpChannel.workerThreads`: load lazily at call time so the static
  // `@JSImport` is never emitted into browser bundles.
  private def workerThreads: js.Dynamic =
    val process = js.Dynamic.global.process
    if !js.isUndefined(process) && !js.isUndefined(process.getBuiltinModule) then
      process.applyDynamic("getBuiltinModule")("worker_threads")
    else
      js.Dynamic.global.applyDynamic("require")("worker_threads")

  // `Atomics.*` are accessed via applyDynamic to avoid clashing with `java.lang.Object.wait` /
  // `notify` on Scala 3 — same trick `NodeSyncHttpChannel` uses.
  private def atomicsLoad(view: Int32Array, index: Int): Int = js
    .Dynamic
    .global
    .Atomics
    .applyDynamic("load")(view, index)
    .asInstanceOf[Int]

  // `Atomics.store` is the memory-barriered counterpart to `Atomics.load` — required so the
  // parent thread blocking on `Atomics.wait` reliably observes the worker's terminal-state
  // write. Plain `view(0) = x` assignments to a SAB-backed typed array are visible across
  // threads in practice on V8/SpiderMonkey, but the spec only guarantees that visibility for
  // `Atomics.*` accesses.
  private def atomicsStore(view: Int32Array, index: Int, value: Int): Unit =
    val _ = js.Dynamic.global.Atomics.applyDynamic("store")(view, index, value)

  private def atomicsCompareExchange(
      view: Int32Array,
      index: Int,
      expected: Int,
      replacement: Int
  ): Int = js
    .Dynamic
    .global
    .Atomics
    .applyDynamic("compareExchange")(view, index, expected, replacement)
    .asInstanceOf[Int]

  private def atomicsNotify(view: Int32Array, index: Int): Unit =
    val _ = js.Dynamic.global.Atomics.applyDynamic("notify")(view, index)

  private def atomicsWait(view: Int32Array, index: Int, expected: Int): Unit =
    val _ = js.Dynamic.global.Atomics.applyDynamic("wait")(view, index, expected)

  // ---- Worker bootstrap source ----
  // The worker runs as `new Worker(code, { eval: true })`. The script must obtain `workerData`
  // via `await import('node:worker_threads')` because Deno's eval workers are ESM with no
  // `require` (see ADR 2026-05-14). It then dynamically imports the parent's bundle URL — which
  // re-runs module init in this isolate, populating Task's registry — and calls the Scala-side
  // dispatcher. Any failure during import or dispatch is reported back through the SAB.
  //
  // Layout constants are interpolated from the Scala side so the buffer contract stays
  // single-sourced.
  private val WorkerScript =
    s"""
    const STATE_FAILED = ${StateFailed};
    const OFF_STATE = ${OffsetState};
    const OFF_ERR_LEN = ${OffsetErrorMsgLen};
    const OFF_ERR_BODY = ${OffsetErrorMsgBody};
    const ERR_CAPACITY = ${ErrorMsgCapacity};
    """ +
      """
    (async () => {
      let sab;
      try {
        const wt = await import('node:worker_threads');
        const { taskId, sab: dataSab, bundleUrl } = wt.workerData;
        sab = dataSab;
        if (!bundleUrl) {
          throw new Error('uni Task.runRegistered: bundle URL empty — import.meta.url unavailable; runRegistered requires an ESM Scala.js bundle');
        }
        // bundleUrl from `import.meta.url` is already a file:// URL — pass straight to import().
        // The bundle's module init may fail in the worker because of side effects that require
        // main-thread globals (e.g. the sbt test bridge expects `scalajsCom`, which only the
        // test runner provides). Tolerate that: in ESM, side effects that completed *before*
        // the error are persistent, including our `@JSExportTopLevel` registrations and any
        // `TaskRegistry.register` calls in eagerly-initialised objects. If `__uniTaskInvoke` is present
        // after the (partial) import, the registry was populated and we can dispatch.
        let importError = null;
        try {
          await import(bundleUrl);
        } catch (e) {
          importError = e;
        }
        if (typeof globalThis.__uniTaskInvoke !== 'function') {
          throw importError || new Error('uni Task.runRegistered: __uniTaskInvoke not exported by bundle ' + bundleUrl);
        }
        globalThis.__uniTaskInvoke(taskId, sab);
      } catch (e) {
        if (!sab) {
          // No SAB to write into; nothing we can do beyond letting the worker die.
          throw e;
        }
        const stateView = new Int32Array(sab, 0, 3);
        const view = new DataView(sab);
        const msg = (e && e.stack) ? e.stack : (e && e.message ? e.message : String(e));
        const bytes = new TextEncoder().encode(msg);
        const len = Math.min(bytes.length, ERR_CAPACITY);
        view.setInt32(OFF_ERR_LEN, len, true);
        new Uint8Array(sab, OFF_ERR_BODY, len).set(bytes.subarray(0, len));
        stateView[0] = STATE_FAILED;
        Atomics.notify(stateView, 0);
      }
    })();
  """

end taskCompat

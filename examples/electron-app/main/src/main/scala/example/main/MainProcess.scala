package example.main

import example.api.{CounterApi, CounterState}
import wvlet.uni.electron.ElectronRPCServer
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.rx.Rx

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
  * The service implementation. It owns the authoritative counter state in the main process; the
  * renderer only ever sees snapshots returned by these methods.
  */
class CounterApiImpl extends CounterApi:
  private var value: Int = 0

  override def get(): Rx[CounterState] = Rx.single(CounterState(value))

  override def increment(amount: Int): Rx[CounterState] =
    value += amount
    Rx.single(CounterState(value))

  override def reset(): Rx[CounterState] =
    value = 0
    Rx.single(CounterState(value))

end CounterApiImpl

object MainProcess:

  /**
    * Registers the RPC services on Electron's IPC. Called from `src/main/index.js` once the app is
    * ready, with Electron's `ipcMain` handed in as a value (so this Scala module never has to
    * `require("electron")`).
    */
  @JSExportTopLevel("wireMainProcess")
  def wireMainProcess(ipcMain: js.Dynamic): Unit = ElectronRPCServer.serve(
    ipcMain,
    RPCRouter.of[CounterApi](CounterApiImpl())
  )

end MainProcess

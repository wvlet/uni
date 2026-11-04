package example.server

import example.api.ChatApi
import wvlet.uni.electron.ElectronRPCServer
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter}
import wvlet.uni.http.{Http, HttpServer, Request, Response, StaticFiles}
import wvlet.uni.rx.Rx

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
  * Exposes one [[ChatService]] two ways:
  *
  *   - `wireMainProcess(ipcMain)`: over Electron IPC, called from `src/main/index.js`.
  *   - `startWebServer(port, staticDir)`: over HTTP with `Http.server` (Node), for the browser. In
  *     production it also serves the built renderer from `staticDir`.
  */
object HarnessServer:

  private lazy val router = RPCRouter.of[ChatApi](ChatService())

  @JSExportTopLevel("wireMainProcess")
  def wireMainProcess(ipcMain: js.Dynamic): Unit = ElectronRPCServer.serve(ipcMain, router)

  @JSExportTopLevel("startWebServer")
  def startWebServer(port: Int, staticDir: js.UndefOr[String]): HttpServer =
    val dispatcher = RPCDispatcher(router)
    val server     = Http.server.withHost("127.0.0.1").withPort(port)
    // Unknown page paths fall back to index.html, so a reload on any view still loads the app
    staticDir
      .toOption
      .fold(server)(dir => server.withFilter(StaticFiles(dir).withSpaFallback(true)))
      .withRxHandler { (req: Request) =>
        // RPC calls are `POST /example.api.ChatApi/<method>`
        if req.path.startsWith("/example.api.") then
          dispatcher.dispatch(req)
        else
          Rx.single(Response.notFound)
      }
      .start()

end HarnessServer

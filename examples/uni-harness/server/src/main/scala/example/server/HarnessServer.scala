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
  *   - `wireMainProcess(ipcMain, dataDir)`: over Electron IPC, called from `src/main/index.js`.
  *     Conversations are saved in `dataDir` (Electron's `userData` directory).
  *   - `startWebServer(port, staticDir)`: over HTTP with `Http.server` (Node), for the browser. In
  *     production it also serves the built renderer from `staticDir`. Conversations stay in memory
  *     unless `UNI_HARNESS_DATA_DIR` names a directory to save them in.
  */
object HarnessServer:

  private def router(dataDir: Option[String]) = RPCRouter.of[ChatApi](
    dataDir.fold(ChatService())(dir => ChatService(backend = ChatService.inDirectory(dir)))
  )

  @JSExportTopLevel("wireMainProcess")
  def wireMainProcess(ipcMain: js.Dynamic, dataDir: String): Unit = ElectronRPCServer.serve(
    ipcMain,
    router(Some(dataDir))
  )

  @JSExportTopLevel("startWebServer")
  def startWebServer(port: Int, staticDir: js.UndefOr[String]): HttpServer =
    val dataDir = js
      .Dynamic
      .global
      .process
      .env
      .UNI_HARNESS_DATA_DIR
      .asInstanceOf[js.UndefOr[String]]
    val dispatcher = RPCDispatcher(router(dataDir.toOption.filter(_.nonEmpty)))
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

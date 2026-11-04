package example.server

import example.api.ChatApi
import wvlet.uni.electron.ElectronRPCServer
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter}
import wvlet.uni.http.{ContentType, Http, HttpContent, HttpMethod, HttpServer, Request, Response}
import wvlet.uni.rx.Rx

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.Int8Array

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
    val static     = staticDir.toOption.map(StaticFiles(_))
    Http
      .server
      .withHost("127.0.0.1")
      .withPort(port)
      .withRxHandler { (req: Request) =>
        // RPC calls are `POST /example.api.ChatApi/<method>`; everything else is a static file
        if req.path.startsWith("/example.api.") then
          dispatcher.dispatch(req)
        else
          Rx.single(static.map(_.serve(req)).getOrElse(Response.notFound))
      }
      .start()

end HarnessServer

/**
  * A minimal static file handler for the built renderer (`out/web/public`). Unknown paths fall back
  * to `index.html`, and paths outside the root are rejected.
  */
class StaticFiles(root: String):
  private val fs   = node("fs")
  private val path = node("path")
  private val base = path.resolve(root).asInstanceOf[String]

  private def node(name: String): js.Dynamic =
    js.Dynamic.global.process.applyDynamic("getBuiltinModule")(name)

  private val types = Map(
    "html"  -> ContentType.TextHtml,
    "js"    -> ContentType("text/javascript"),
    "css"   -> ContentType.TextCss,
    "svg"   -> ContentType.ImageSvg,
    "json"  -> ContentType("application/json"),
    "woff2" -> ContentType("font/woff2")
  )

  def serve(req: Request): Response =
    if req.method != HttpMethod.GET then
      Response.notFound
    else
      val rel =
        if req.path == "/" then
          "index.html"
        else
          req.path.stripPrefix("/")
      val file = path.resolve(base, rel).asInstanceOf[String]
      // Inside the root only: a bare prefix check would also accept a sibling like `public-old`
      val sep = path.sep.asInstanceOf[String]
      if file != base && !file.startsWith(base + sep) then
        Response.notFound
      else if isFile(file) then
        read(file)
      else
        read(path.join(base, "index.html").asInstanceOf[String])

  private def isFile(file: String): Boolean =
    fs.existsSync(file).asInstanceOf[Boolean] && fs.statSync(file).isFile().asInstanceOf[Boolean]

  private def read(file: String): Response =
    if !isFile(file) then
      Response.notFound
    else
      val buf   = fs.readFileSync(file)
      val bytes =
        Int8Array(
          buf.buffer.asInstanceOf[js.typedarray.ArrayBuffer],
          buf.byteOffset.asInstanceOf[Int],
          buf.length.asInstanceOf[Int]
        ).toArray
      val ext = file.split('.').lastOption.getOrElse("")
      // The content carries the type: the Node server takes Content-Type from it
      Response.ok(
        HttpContent.bytes(bytes, types.getOrElse(ext, ContentType.ApplicationOctetStream))
      )

end StaticFiles

package example.rest

import wvlet.uni.design.Design
import wvlet.uni.http.{HttpMethod, HttpServer, HttpSyncClient, Request, Response}
import wvlet.uni.http.Http
import wvlet.uni.http.netty.{RPCHandler, RouterHandler}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.http.rpc.{RPCClient, RPCRouter}
import wvlet.uni.log.LogSupport
import wvlet.uni.weaver.Weaver

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

case class Todo(id: Long, title: String, done: Boolean = false) derives Weaver

/** In-memory storage shared by the REST controller and the RPC service. */
class TodoStore:
  private val todos  = TrieMap.empty[Long, Todo]
  private val nextId = AtomicLong(0)

  def add(title: String): Todo =
    val todo = Todo(nextId.incrementAndGet(), title)
    todos.put(todo.id, todo)
    todo

  def get(id: Long): Option[Todo] = todos.get(id)
  def list: Seq[Todo]             = todos.values.toSeq.sortBy(_.id)

  def complete(id: Long): Option[Todo] = todos.updateWith(id)(_.map(_.copy(done = true)))

/** REST endpoints, served by Router.of[TodoController]. */
class TodoController(store: TodoStore):
  @Endpoint(HttpMethod.GET, "/api/todos")
  def list(): Seq[Todo] = store.list

  @Endpoint(HttpMethod.GET, "/api/todos/:id")
  def get(id: Long): Response =
    store.get(id) match
      case Some(todo) =>
        Response.ok.withJsonContent(Weaver.toJson(todo))
      case None =>
        Response.notFound(s"todo ${id} not found")

  // The JSON body {"title": "..."} binds to the `title` parameter
  @Endpoint(HttpMethod.POST, "/api/todos")
  def create(title: String): Todo = store.add(title)

/** The RPC interface. Clients and server share this trait. */
trait TodoApi:
  def add(title: String): Todo
  def complete(id: Long): Option[Todo]
  def list(): Seq[Todo]

class TodoApiImpl(store: TodoStore) extends TodoApi:
  def add(title: String): Todo         = store.add(title)
  def complete(id: Long): Option[Todo] = store.complete(id)
  def list(): Seq[Todo]                = store.list

/**
  * A typed RPC client for TodoApi. sbt-uni's `uniHttpClients` can generate this class; it is
  * written out here to show what the generated code does.
  */
class TodoApiClient(http: HttpSyncClient) extends TodoApi:
  private val rpc = RPCClient.of[TodoApi]

  def add(title: String): Todo         = rpc.callSync(http, "add", Seq(title))
  def complete(id: Long): Option[Todo] = rpc.callSync(http, "complete", Seq(id))
  def list(): Seq[Todo]                = rpc.callSync(http, "list", Seq.empty)

case class ServerConfig(port: Int = 8080)

/** Serves REST under /api and RPC under /example.rest.TodoApi/ on one Netty server. */
class TodoServer(config: ServerConfig, controller: TodoController, api: TodoApi) extends LogSupport:
  private val rest      = RouterHandler.withControllers(Router.of[TodoController], controller)
  private val rpcRouter = RPCRouter.of[TodoApi](api)
  private val rpc       = RPCHandler(rpcRouter)
  private val rpcPrefix = s"/${rpcRouter.serviceName}/"

  private var server: Option[HttpServer] = None

  def start(): Unit =
    val s = Http
      .server
      .withPort(config.port)
      .withRxHandler { (request: Request) =>
        if request.path.startsWith(rpcPrefix) then
          rpc.handle(request)
        else
          rest.handle(request)
      }
      .start()
    info(s"Listening on http://localhost:${s.localPort}")
    server = Some(s)

  def port: Int                = server.map(_.localPort).getOrElse(config.port)
  def awaitTermination(): Unit = server.foreach(_.awaitTermination())
  def stop(): Unit             = server.foreach(_.stop())

end TodoServer

object TodoServer:
  def design(config: ServerConfig = ServerConfig()): Design = Design
    .newDesign
    .bindInstance[ServerConfig](config)
    .bindSingleton[TodoStore]
    .bindSingleton[TodoController]
    .bindImpl[TodoApi, TodoApiImpl]
    .bindSingleton[TodoServer]
    .onStart(_.start())
    .onShutdown(_.stop())

  def main(args: Array[String]): Unit =
    val port = args.headOption.map(_.toInt).getOrElse(8080)
    design(ServerConfig(port)).build[TodoServer] { server =>
      server.awaitTermination()
    }

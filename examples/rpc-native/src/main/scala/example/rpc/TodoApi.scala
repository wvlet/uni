package example.rpc

import wvlet.uni.design.Design
import wvlet.uni.http.rpc.{RPCClient, RPCDispatcher, RPCRouter}
import wvlet.uni.http.{Http, HttpServer, HttpSyncClient, Request, Response}
import wvlet.uni.rx.Rx
import wvlet.uni.weaver.Weaver

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

case class Todo(id: Long, title: String, done: Boolean = false) derives Weaver

/** The RPC interface, served and called with the same code on the JVM and Scala Native. */
trait TodoApi:
  def add(title: String): Todo
  def complete(id: Long): Option[Todo]
  def list(): Seq[Todo]

class TodoApiImpl extends TodoApi:
  private val todos  = TrieMap.empty[Long, Todo]
  private val nextId = AtomicLong(0)

  def add(title: String): Todo =
    val todo = Todo(nextId.incrementAndGet(), title)
    todos.put(todo.id, todo)
    todo

  def complete(id: Long): Option[Todo] = todos.updateWith(id)(_.map(_.copy(done = true)))
  def list(): Seq[Todo]                = todos.values.toSeq.sortBy(_.id)

/** Serves TodoApi as `POST /example.rpc.TodoApi/<method>`. */
object TodoRpc:
  def handler(api: TodoApi): Request => Rx[Response] =
    val dispatcher = RPCDispatcher(RPCRouter.of[TodoApi](api))
    req => dispatcher.dispatch(req)

  /**
    * Wires the server with Design. `Http.server` picks the platform's server: Netty on the JVM
    * (from uni-netty) and NativeServer in a Scala Native binary. Port 0 picks a free port.
    */
  def design(port: Int, host: String = "127.0.0.1"): Design = Design
    .newSilentDesign
    .bindImpl[TodoApi, TodoApiImpl]
    .bindProvider[TodoApi, HttpServer] { api =>
      Http.server.withPort(port).withHost(host).withRxHandler(handler(api)).start()
    }
    .onShutdown(_.stop())

  /** `Main [port] [host]`: port 8080 and loopback by default; pass 0.0.0.0 in a container. */
  def parseArgs(args: Array[String]): (Int, String) =
    (args.lift(0).map(_.toInt).getOrElse(8080), args.lift(1).getOrElse("127.0.0.1"))

/**
  * A typed client: the same trait on the caller's side, so calls are compile-checked. sbt-uni's
  * `uniHttpClients` code generator produces this class for you.
  */
class TodoApiClient(http: HttpSyncClient) extends TodoApi:
  private val rpc = RPCClient.of[TodoApi].newSyncClient(http)

  def add(title: String): Todo         = rpc.call("add", title)
  def complete(id: Long): Option[Todo] = rpc.call("complete", id)
  def list(): Seq[Todo]                = rpc.call("list")

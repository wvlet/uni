/* Compile-checked examples from Book Chapter 9 — HTTP Clients and Servers.
 * Clients/servers are constructed but never connected/started here. */
package wvlet.uni.book

import wvlet.uni.http.{Http, HttpMethod, Request, Response}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.http.netty.{NettyServer, RouterHandler}
import wvlet.uni.rx.Rx

object Ch09Http:
  def clients(): Unit =
    val sync  = Http.client.newSyncClient
    val async = Http.client.newAsyncClient
    val tuned = Http.client.withConnectTimeoutMillis(5000).withMaxRetry(3).newSyncClient
    println(s"${sync} ${async} ${tuned}")

  def buildRequests(): Request = Request
    .post("https://api.example.com/users")
    .withJsonContent("""{"name":"Alice"}""")

  def asyncCompose(): Unit = Http
    .client
    .newAsyncClient
    .send(Request.get("https://httpbin.org/get"))
    .map(_.contentAsString.getOrElse(""))
    .subscribe(println)

  class UserController:
    @Endpoint(HttpMethod.GET, "/users/:id")
    def getUser(id: String): String = s"""{"id":"${id}"}"""

    @Endpoint(HttpMethod.GET, "/users")
    def listUsers(): Seq[String] = Seq("alice", "bob")

  def functionalServerConfig = NettyServer
    .withPort(8080)
    .withRxHandler { (request: Request) =>
      Rx.single(Response.ok(s"You asked for ${request.path}"))
    }

  def routedServerConfig =
    val router = Router.of[UserController]
    NettyServer.withPort(8080).withRxHandler(RouterHandler(router))

end Ch09Http

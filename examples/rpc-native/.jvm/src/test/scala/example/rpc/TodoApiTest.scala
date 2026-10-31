package example.rpc

import wvlet.uni.http.{Http, HttpSyncClient}
import wvlet.uni.http.netty.NettyServer
import wvlet.uni.test.*

class TodoApiTest extends UniTest:
  // Port 0 picks a free port. The session closes the client before it stops the server
  addTestDesign(
    _.bindInstance[TodoApi](TodoApiImpl())
      .bindProvider[TodoApi, wvlet.uni.http.HttpServer](api =>
        NettyServer.withPort(0).withRxHandler(TodoRpc.handler(api)).start()
      )
      .onShutdown(_.stop())
      .bindProvider[wvlet.uni.http.HttpServer, HttpSyncClient](server =>
        Http.client.withBaseUri(s"http://localhost:${server.localPort}").newSyncClient
      )
  )

  test("call the server through the typed client") { (http: HttpSyncClient) =>
    val client = TodoApiClient(http)
    val todo   = client.add("write an example")
    todo.title shouldBe "write an example"
    client.complete(todo.id).map(_.done) shouldBe Some(true)
    client.complete(999L) shouldBe None
    client.add("ship")
    client.list().map(_.title) shouldBe Seq("write an example", "ship")
  }

end TodoApiTest

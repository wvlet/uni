package example.rpc

import wvlet.uni.http.{Http, HttpServer, HttpSyncClient}
import wvlet.uni.test.*

/** Runs on the JVM and Scala Native: the same server code and the same typed client. */
class TodoApiTest extends UniTest:
  // The session closes the client before it stops the server
  addTestDesign(
    _ +
      TodoRpc
        .design(port = 0)
        .bindProvider[HttpServer, HttpSyncClient](server =>
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

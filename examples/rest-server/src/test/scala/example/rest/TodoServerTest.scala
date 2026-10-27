package example.rest

import wvlet.uni.http.{Http, HttpException, HttpSyncClient, Request}
import wvlet.uni.test.*
import wvlet.uni.weaver.Weaver

class TodoServerTest extends UniTest:
  // Each test gets its own server and TodoStore. Port 0 picks a free port, so the test never
  // collides with a running server
  addTestDesign(_ + TodoServer.design(ServerConfig(port = 0)))
  // The client depends on the server, so the session closes the client before it stops the server
  addTestDesign(
    _.bindProvider[TodoServer, HttpSyncClient](server =>
      Http.client.withBaseUri(s"http://localhost:${server.port}").newSyncClient
    )
  )

  test("call the server through the typed RPC client and the REST API") { (http: HttpSyncClient) =>
    val client = TodoApiClient(http)
    val todo   = client.add("write an example")
    todo.title shouldBe "write an example"
    client.complete(todo.id).map(_.done) shouldBe Some(true)
    client.complete(999L) shouldBe None

    // The REST endpoints read the same TodoStore singleton
    val json = http.send(Request.get(s"/api/todos/${todo.id}")).contentAsString.get
    Weaver.fromJson[Todo](json) shouldBe Todo(todo.id, "write an example", done = true)

    val created = http.send(Request.post("/api/todos").withJsonContent("""{"title":"ship"}"""))
    Weaver.fromJson[Todo](created.contentAsString.get).title shouldBe "ship"
    client.list().map(_.title) shouldBe Seq("write an example", "ship")
  }

  test("an unknown todo returns 404") { (http: HttpSyncClient) =>
    val e = intercept[HttpException] {
      http.send(Request.get("/api/todos/42"))
    }
    e.statusCode shouldBe Some(404)
  }

end TodoServerTest

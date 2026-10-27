# OpenAPI generation

`wvlet.uni.http.openapi.OpenAPI` builds an OpenAPI 3.0.3 document from the
`@Endpoint` routes of a REST `Router` (see [http-server.md](http-server.md)). It
runs on JVM, Scala.js, and Scala Native. RPC routers (`RPCRouter`) are not
covered.

Full reference: https://wvlet.org/uni/http/openapi.md

```scala
import wvlet.uni.http.{HttpMethod, Request}
import wvlet.uni.http.openapi.{OpenAPI, OpenAPIConfig}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.weaver.Weaver

case class User(id: String, name: String) derives Weaver
case class CreateUserRequest(name: String) derives Weaver

class UserApi:
  @Endpoint(HttpMethod.GET, "/v1/users/:id", description = "Get a user by id")
  def getUser(id: String, verbose: Boolean = false): User = User(id, "alice")

  @Endpoint(HttpMethod.POST, "/v1/users")
  def createUser(request: CreateUserRequest, httpRequest: Request): User = User("2", request.name)

val yaml: String = OpenAPI
  .of(
    Router.of[UserApi],
    OpenAPIConfig()
      .withTitle("User API")                    // default "API"
      .withVersion("1.0.0")                     // default "0.0.1"
      .withSchemaPackagePrefix("com.example.")  // strip from schema names
      .withErrorResponses(400, 404, 500)        // default 400, 500
  )
  .toYAML                                       // or .toJSON
```

- `OpenAPI.of(Seq(router1, router2), config)` merges routers.
- `operationId` is the method name, so method names must be unique across the
  routers; a duplicate throws `IllegalArgumentException`. So do two case classes
  that map to the same schema name.
- `/users/:id` becomes `/users/{id}`; `Request` parameters are left out;
  parameters with defaults or `Option` types are optional.
- `@description` (`wvlet.uni.mcp.description`) on a method, a field, or a case
  class becomes the description of the operation, the property, or the schema.

## From the build (sbt-uni)

```scala
// project/plugins.sbt
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "<version>")

// build.sbt
lazy val server = project
  .enablePlugins(UniPlugin)
  .settings(
    uniOpenAPIRouters := Seq("com.example.api.Routes.router"), // object members returning a Router
    uniOpenAPITitle   := "Example API",
    uniOpenAPIFormat  := "yaml"                                 // or "json"
  )
```

`sbt server/uniOpenAPI` writes `target/openapi/openapi.yaml` (change it with
`uniOpenAPITargetDir`); `package` regenerates it. Other keys:
`uniOpenAPIVersion`, `uniOpenAPISchemaPackagePrefix`, `uniOpenAPIErrorResponses`.

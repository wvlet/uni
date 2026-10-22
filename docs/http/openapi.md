# OpenAPI

Generate an [OpenAPI 3.0.3](https://spec.openapis.org/oas/v3.0.3) document from the `@Endpoint` routes of a [Router](./router.md).

## From Code

```scala
import wvlet.uni.http.{HttpMethod, Request}
import wvlet.uni.http.openapi.{OpenAPI, OpenAPIConfig}
import wvlet.uni.http.router.{Endpoint, Router}

class UserApi:
  @Endpoint(HttpMethod.GET, "/v1/users/:id", description = "Get a user by id")
  def getUser(id: String, verbose: Boolean = false): User = ???

  @Endpoint(HttpMethod.POST, "/v1/users")
  def createUser(request: CreateUserRequest, httpRequest: Request): User = ???

val yaml: String = OpenAPI
  .of(
    Router.of[UserApi],
    OpenAPIConfig()
      .withTitle("User API")
      .withVersion("1.0.0")
      .withSchemaPackagePrefix("com.example.api.")
  )
  .toYAML
```

`OpenAPI.of(Seq(router1, router2), config)` combines several routers. `.toJSON` renders JSON instead of YAML. Both are available on JVM, Scala.js, and Scala Native.

| `OpenAPIConfig` | Default | Meaning |
|---|---|---|
| `withTitle` / `withVersion` | `API` / `0.0.1` | `info.title` and `info.version` |
| `withSchemaPackagePrefix(p)` / `noSchemaPackagePrefix()` | none | Strip `p` from schema names (`com.example.api.model.User` → `model.User`); other types use their simple name |
| `withErrorResponses(codes*)` | `400, 500` | Error responses every operation refers to, defined once in `components/responses` |

## Mapping

- **Paths:** `/v1/users/:id` becomes `/v1/users/{id}`.
- **Operations:** `operationId` and `summary` are the method name, so method names must be unique across the routers (a duplicate throws `IllegalArgumentException`). `description` comes from `@Endpoint(description = ...)`, or else from the method's `@description` (`wvlet.uni.mcp.description`, or any annotation named `description` with a `value`, such as Airframe's), and `tags` is the controller's simple name.
- **Parameters** follow the router's [request body rules](./server.md#request-body):
  - path parameters are `in: path`;
  - a parameter that takes the whole body becomes the `requestBody` (JSON and MessagePack), and the primitives next to it are `in: query`;
  - otherwise, for POST, PUT, and PATCH the body is an object with one property per parameter. For other methods, primitives are `in: query` and any object parameters form the body object, since the router reads them from the body whatever the method;
  - `Request` and `RequestAttributes` parameters are not part of the API;
  - a parameter is required unless it has a default value, is an `Option`, or is a collection or map not marked `@required`.
- **Content types:** request bodies list `application/json` and `application/x-msgpack`. Responses never list `application/x-msgpack`, because the router does not produce it; see Responses for the content types they do list.
- **Responses:** `200` with the return type's schema (`Rx[A]` and `Option[A]` are unwrapped; an `Option` also lists `204`, the response for `None`). `String` is `text/plain`, `Array[Byte]` is `application/octet-stream`, `Unit` is `204`, and a `Response` return has no schema.
- **Schemas:** case classes become `$ref`s into `components/schemas`, including recursive ones. Two types that would get the same schema name (e.g. `a.User` and `b.User` without a package prefix) fail generation with `IllegalArgumentException`; generic types are named like `Page_User_`. `Seq`/`Set`/`Array` are arrays, `Map[String, A]` is an object with `additionalProperties`, and enums are strings (their case names are not listed). Numbers carry a `format` (`int32`, `int64`, `float`, `double`), fields carry their `@description`, and a field's default is emitted as `default` when it is a number (other than `BigInt`/`BigInteger`), `Boolean`, `Char`, or `String`. Class-level `@description` is not emitted.

## With sbt-uni

The `uniOpenAPI` task writes the document from the build. It runs the generator in a forked JVM on the project's runtime classpath, so it uses the uni version your app depends on, and the build never loads your classes.

```scala
// project/plugins.sbt
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "__UNI_VERSION__")

// build.sbt
lazy val server = project
  .enablePlugins(UniPlugin)
  .settings(
    // Scala object members that return a Router
    uniOpenAPIRouters             := Seq("com.example.api.Routes.router"),
    uniOpenAPITitle               := "Example API",          // default: project name
    uniOpenAPIVersion             := version.value,          // default
    uniOpenAPISchemaPackagePrefix := Some("com.example.api."),
    uniOpenAPIErrorResponses      := Seq(400, 500, 503),     // default: Seq(400, 500)
    uniOpenAPIFormat              := "yaml",                 // or "json"
    uniOpenAPITargetDir           := baseDirectory.value / "doc" // default: target/openapi
  )
```

```scala
package com.example.api

object Routes:
  val router: Router = Router.of[UserApi].andThen(Router.of[AdminApi])
```

Run `sbt server/uniOpenAPI` to write `doc/openapi.yaml`. Packaging (`sbt server/package`) also regenerates it; with no `uniOpenAPIRouters` the task does nothing. A router spec can also name an object that is itself a `Router`.

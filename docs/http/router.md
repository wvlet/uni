# Router

The router framework extracts endpoint definitions from controllers at compile-time and matches incoming requests to handler methods.

## Router Builder

### Creating a Router

Use `Router.of[T]` to extract routes from a controller class:

```scala
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.router.{Endpoint, Router}

class UserController:
  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): String = "[]"

  @Endpoint(HttpMethod.GET, "/users/:id")
  def getUser(id: String): String = s"""{"id":"${id}"}"""

val router = Router.of[UserController]

// Check extracted routes
println(router.routeSummary)
// Router:
//   GET /users -> listUsers
//   GET /users/:id -> getUser
```

The macro extracts all methods annotated with `@Endpoint` and creates route definitions.

### Combining Routers

Use `andThen` to combine multiple routers:

```scala
class UserController:
  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): String = "[]"

class ItemController:
  @Endpoint(HttpMethod.GET, "/items")
  def listItems(): String = "[]"

class HealthController:
  @Endpoint(HttpMethod.GET, "/health")
  def health(): String = "ok"

val router = Router.of[HealthController]
  .andThen(Router.of[UserController])
  .andThen(Router.of[ItemController])
```

Routes are matched in the order they are added.

### Empty Router

Create an empty router to combine with others:

```scala
val baseRouter = Router.empty
val fullRouter = baseRouter
  .andThen(Router.of[Controller1])
  .andThen(Router.of[Controller2])
```

## Controller Provider

The `ControllerProvider` interface determines how controller instances are created.

### SimpleControllerProvider

The default provider creates instances using reflection:

```scala
import wvlet.uni.http.router.SimpleControllerProvider
import wvlet.uni.http.netty.RouterHandler

val router = Router.of[UserController]
val handler = RouterHandler(router)  // Uses SimpleControllerProvider

// Equivalent to:
val handler2 = RouterHandler(router, SimpleControllerProvider())
```

Controllers must have a no-argument constructor.

### MapControllerProvider

For pre-configured instances (useful for testing or object wiring):

```scala
import wvlet.uni.http.router.MapControllerProvider

class UserController(userService: UserService):
  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): Seq[User] = userService.findAll()

// Create controller with dependencies
val userService = UserService()
val controller = UserController(userService)

// Register pre-created instance
val provider = MapControllerProvider(controller)
val handler = RouterHandler(router, provider)
```

Multiple controllers:

```scala
val provider = MapControllerProvider(
  UserController(userService),
  ItemController(itemService),
  HealthController()
)
```

### Custom Provider

Implement `ControllerProvider` for custom instantiation logic:

```scala
import wvlet.uni.http.router.ControllerProvider
import wvlet.uni.surface.Surface

class DIControllerProvider(container: DIContainer) extends ControllerProvider:
  override def get(surface: Surface): Any =
    container.getInstance(surface.rawType)

  override def getFilter(surface: Surface): Any =
    container.getInstance(surface.rawType)
```

## Route Matching

### Match Order

Routes are matched sequentially in the order they were added:

```scala
val router = Router.of[SpecificController]  // Matched first
  .andThen(Router.of[GeneralController])    // Matched second
```

More specific routes should be added before general ones.

### Path Matching

Routes match when:
1. HTTP method matches exactly
2. Number of path segments matches
3. Each segment matches (literal or parameter)

```scala
// Route: GET /users/:id
// Matches: GET /users/123      -> id = "123"
// Matches: GET /users/alice    -> id = "alice"
// No match: GET /users/123/posts  (different segment count)
// No match: POST /users/123       (different method)
```

### Path Components

Paths are parsed into components:

| Pattern | Component Type | Matches |
|---------|---------------|---------|
| `users` | Literal | Exact string "users" |
| `:id` | Parameter | Any string (captured) |

```scala
// GET /users/:userId/posts/:postId
// Matches: GET /users/1/posts/42
// Extracted: userId = "1", postId = "42"
```

## Router with Filters

Add filters that apply to all routes in a router:

```scala
import wvlet.uni.http.RxHttpFilter

class AuthFilter extends RxHttpFilter:
  def apply(request: Request, next: RxHttpHandler): Rx[Response] =
    if isAuthenticated(request) then
      next.handle(request)
    else
      Rx.single(Response.unauthorized)

val router = Router
  .filter[AuthFilter]
  .andThen(Router.of[ProtectedController])
```

The filter is instantiated using the same `ControllerProvider` as controllers.

## RouterHandler

The `RouterHandler` connects a router to the Netty server:

```scala
import wvlet.uni.http.netty.{RouterHandler, NettyServer}

val router = Router.of[MyController]
val handler = RouterHandler(router)

NettyServer
  .withPort(8080)
  .withRxHandler(handler)
  .start()
```

### With Custom Provider

```scala
val handler = RouterHandler(router, myControllerProvider)
```

### With Pre-registered Controllers

```scala
val handler = RouterHandler.withControllers(
  router,
  controller1,
  controller2
)
```

## Request Mapping

The `HttpRequestMapper` binds request data to method parameters in this order:

1. **Path parameters** - From URL path (`:id` -> value)
2. **Query parameters** - From URL query string
3. **Default values** - Method parameter defaults
4. **Request object** - If parameter type is `Request`

```scala
@Endpoint(HttpMethod.GET, "/search/:category")
def search(
    category: String,        // From path: /search/books
    query: String,           // From query: ?query=scala
    limit: Int = 10,         // From query or default
    request: Request         // The full request
): Response = ???
```

Request: `GET /search/books?query=scala&limit=20`
- `category` = "books"
- `query` = "scala"
- `limit` = 20
- `request` = full Request object

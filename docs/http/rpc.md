# RPC

Trait-based RPC framework with automatic route generation, type-safe serialization, and structured error handling.

```scala
import wvlet.uni.http.rpc.*

// Define a service trait
trait UserService:
  def getUser(id: Long): User
  def createUser(name: String, email: String): User

// Create a router from the implementation
val router = RPCRouter.of[UserService](UserServiceImpl())
// Generates: POST /UserService/getUser, POST /UserService/createUser
```

## Defining an RPC Service

Define your service API as a Scala trait, then provide an implementation:

```scala
case class User(id: Long, name: String, email: String)

trait UserService:
  def getUser(id: Long): User
  def createUser(name: String, email: String): User
  def listUsers(limit: Int = 10): Seq[User]

class UserServiceImpl extends UserService:
  def getUser(id: Long): User =
    User(id, "Alice", "alice@example.com")

  def createUser(name: String, email: String): User =
    User(1L, name, email)

  def listUsers(limit: Int): Seq[User] =
    Seq(User(1L, "Alice", "alice@example.com"))
```

Methods can return `Rx[T]` for asynchronous responses — the router unwraps the return type automatically:

```scala
import wvlet.uni.rx.Rx

trait AsyncUserService:
  def getUser(id: Long): Rx[User]
```

## Creating an RPC Router

`RPCRouter.of[T]` uses compile-time reflection to extract method metadata and build routes:

```scala
val impl = UserServiceImpl()
val router = RPCRouter.of[UserService](impl)

// Inspect generated routes
router.routes.foreach { route =>
  println(s"${route.path}")
}
// /UserService/getUser
// /UserService/createUser
// /UserService/listUsers

// Look up a specific route
val route = router.findRoute("getUser")
```

All routes use `POST` with the path format `/{serviceName}/{methodName}`.

### Request Format

Parameters are sent as JSON in the request body:

```json
{
  "request": {
    "id": 42
  }
}
```

Parameter names are matched flexibly — `userId`, `user_id`, and `user-id` all match the same parameter. Missing optional parameters (`Option[T]`) default to `None`, and parameters with default values use those defaults.

## RPC Status Codes

`RPCStatus` provides structured error codes organized by category:

| Category | HTTP Status | Retryable | Description |
|----------|-------------|-----------|-------------|
| `SUCCESS` (S) | 2xx | — | Request completed successfully |
| `USER_ERROR` (U) | 4xx | No | Client error |
| `INTERNAL_ERROR` (I) | 5xx | Yes | Server error |
| `RESOURCE_EXHAUSTED` (R) | 429 | Yes (with backoff) | Rate/quota limits exceeded |

### Common Status Codes

```scala
import wvlet.uni.http.rpc.RPCStatus

// User errors (not retryable)
RPCStatus.INVALID_REQUEST_U1     // 400 - Malformed request
RPCStatus.INVALID_ARGUMENT_U2    // 400 - Bad parameter values
RPCStatus.NOT_FOUND_U5           // 404 - Resource not found
RPCStatus.ALREADY_EXISTS_U6      // 409 - Resource already exists
RPCStatus.UNAUTHENTICATED_U13    // 401 - Authentication required
RPCStatus.PERMISSION_DENIED_U14  // 403 - Forbidden

// Server errors (retryable)
RPCStatus.INTERNAL_ERROR_I0      // 500 - Generic server error
RPCStatus.UNAVAILABLE_I2         // 503 - Service unavailable
RPCStatus.TIMEOUT_I3             // 504 - Timeout

// Resource errors (retry with backoff)
RPCStatus.EXCEEDED_RATE_LIMIT_R2 // 429 - Rate limited
```

## Error Handling

### Throwing RPC Errors

```scala
import wvlet.uni.http.rpc.{RPCStatus, RPCException}

class UserServiceImpl extends UserService:
  def getUser(id: Long): User =
    if id <= 0 then
      throw RPCStatus.INVALID_ARGUMENT_U2
        .newException(s"Invalid user id: ${id}")
    // ...
```

### RPCException

`RPCException` carries a status code and serializes to JSON or MessagePack for wire transport:

```scala
val ex = RPCStatus.NOT_FOUND_U5.newException("User not found")

// Serialize
val json: String = ex.toJson
val response = ex.toResponse  // HTTP response with status header

// Deserialize
val recovered = RPCException.fromJson(json)
val fromResp  = RPCException.fromResponse(response)

// Application-specific error codes
val appEx = RPCStatus.USER_ERROR_U0
  .newException("Insufficient balance", appErrorCode = Some(1001))
```

## Best Practices

1. **No method overloading** in RPC traits — the router rejects overloaded method names
2. **Use specific status codes** (`NOT_FOUND_U5`) over generic ones (`USER_ERROR_U0`)
3. **Keep service traits in a shared module** so clients can reference the same interface
4. **Use `Rx[T]` return types** for async operations to avoid blocking server threads
5. **Use default parameter values** to maintain backward compatibility when adding new parameters

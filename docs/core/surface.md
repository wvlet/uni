# Type Introspection

Surface provides compile-time type reflection for Scala 3, enabling introspection of types without runtime reflection.

## Basic Usage

```scala
import wvlet.uni.surface.Surface

case class User(id: Long, name: String, email: Option[String])

val surface = Surface.of[User]

println(surface.name)       // "User"
println(surface.fullName)   // "com.example.User"
```

## Inspecting Parameters

```scala
val surface = Surface.of[User]

for param <- surface.params do
  println(s"${param.name}: ${param.surface.name}")
// id: Long
// name: String
// email: Option[String]
```

## Generic Types

Surface handles generic types:

```scala
val listSurface = Surface.of[List[String]]
println(listSurface.name)  // "List[String]"

val mapSurface = Surface.of[Map[String, Int]]
println(mapSurface.name)   // "Map[String,Int]"
```

## Type Aliases

Surface resolves type aliases:

```scala
type UserId = Long

val surface = Surface.of[UserId]
println(surface.name)  // "Long"
```

## Methods

Inspect method signatures:

```scala
trait UserService:
  def findUser(id: Long): Option[User]
  def createUser(name: String, email: String): User

val methods = Surface.methodsOf[UserService]
for m <- methods do
  println(s"${m.name}(${m.args.map(_.name).mkString(", ")}): ${m.returnType.name}")
// findUser(id): Option[User]
// createUser(name, email): User
```

## Zero Values

Surface can provide zero/default values:

```scala
import wvlet.uni.surface.Zero

Zero.of[Int]           // 0
Zero.of[String]        // ""
Zero.of[Option[Int]]   // None
Zero.of[List[String]]  // Nil
Zero.of[Map[K, V]]     // Map.empty
```

## Use Cases

### Object Wiring

Surface powers uni's Design system:

```scala
// Design uses Surface to understand type structure
val design = Design.newDesign
  .bindImpl[UserService, UserServiceImpl]
```

### Serialization

Weaver uses Surface for automatic serialization:

```scala
// Surface describes the structure for serialization
val json = Weaver.toJson(user)
val restored = Weaver.fromJson[User](json)
```

### Validation

Build validators based on type structure:

```scala
import scala.collection.mutable.ListBuffer

def validate[T](value: T)(using surface: Surface): List[String] =
  val errors = ListBuffer[String]()
  for param <- surface.params do
    val fieldValue = param.get(value)
    if param.surface.name == "String" && fieldValue == "" then
      errors += s"${param.name} cannot be empty"
  errors.toList
```

## Annotations

Surface captures annotations on parameters:

```scala
import wvlet.uni.surface.Annotation

case class User(
  @required id: Long,
  @maxLength(100) name: String
)

val surface = Surface.of[User]
for param <- surface.params do
  val annotations = param.findAnnotationOf[required]
  // Check if @required is present
```

## Performance

Surface uses compile-time macros, so:

- **No runtime reflection** - Fast at runtime
- **Compile-time verification** - Errors caught during compilation
- **Cross-platform** - Works on JVM, JS, Native

## Best Practices

1. **Cache Surface instances** when used repeatedly
2. **Use at compile time** when possible
3. **Prefer Surface.of[T]** over runtime reflection
4. **Leverage Zero** for default values in serialization

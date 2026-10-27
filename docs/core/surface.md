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
import wvlet.uni.surface.{Surface, Zero}

Zero.zeroOf(Surface.of[Int])              // 0
Zero.zeroOf(Surface.of[String])           // ""
Zero.zeroOf(Surface.of[Option[Int]])      // None
Zero.zeroOf(Surface.of[List[String]])     // Nil
Zero.zeroOf(Surface.of[Map[String, Int]]) // Map.empty
```

## Use Cases

### Object Wiring

Surface powers uni's Design system:

```scala
import wvlet.uni.design.Design

// Design uses Surface to understand type structure
val design = Design.newDesign
  .bindImpl[UserService, UserServiceImpl]
```

### Serialization

Weaver uses Surface for automatic serialization:

```scala
import wvlet.uni.weaver.Weaver

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

Surface captures annotations, with their literal arguments, on parameters, methods, and classes
with a public constructor:

```scala
import scala.annotation.StaticAnnotation

class label(val value: String) extends StaticAnnotation
class required extends StaticAnnotation
class maxLength(val value: Int) extends StaticAnnotation

@label("account")
case class User(
  @required id: Long,
  @maxLength(100) name: String
)

val surface = Surface.of[User]
for param <- surface.params do
  // Check if @required is present
  val required = param.hasAnnotation("required")

// Class-level annotations, matched by simple or full name
surface.findAnnotation("label").flatMap(_.getAs[String]("value")) // Some("account")
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

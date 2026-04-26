package wvlet.uni.weaver

import wvlet.uni.test.UniTest

// Test case classes defined outside the test class
case class Person(name: String, age: Int) derives Weaver

case class Address(city: String, country: String) derives Weaver

case class Employee(name: String, address: Address) derives Weaver

case class Profile(name: String, email: Option[String] = None) derives Weaver

case class Team(name: String, members: List[Person]) derives Weaver

case class User(firstName: String, lastName: String) derives Weaver

case class ConfigWithDefaults(name: String, timeout: Int = 30, retries: Int = 3) derives Weaver

class CaseClassWeaverTest extends UniTest:

  test("simple case class round-trip") {
    val person   = Person("Alice", 30)
    val msgpack  = Weaver.weave(person)
    val restored = Weaver.unweave[Person](msgpack)
    restored shouldBe person
  }

  test("simple case class to/from JSON") {
    val person   = Person("Bob", 25)
    val json     = Weaver.toJson(person)
    val restored = Weaver.fromJson[Person](json)
    restored shouldBe person
  }

  test("nested case classes") {
    val employee = Employee("Charlie", Address("Tokyo", "Japan"))
    val msgpack  = Weaver.weave(employee)
    val restored = Weaver.unweave[Employee](msgpack)
    restored shouldBe employee
  }

  test("nested case classes to/from JSON") {
    val employee = Employee("Diana", Address("New York", "USA"))
    val json     = Weaver.toJson(employee)
    val restored = Weaver.fromJson[Employee](json)
    restored shouldBe employee
  }

  test("optional fields with Some value") {
    val profile  = Profile("Eve", Some("eve@example.com"))
    val msgpack  = Weaver.weave(profile)
    val restored = Weaver.unweave[Profile](msgpack)
    restored shouldBe profile
  }

  test("optional fields with None") {
    val profile  = Profile("Frank", None)
    val msgpack  = Weaver.weave(profile)
    val restored = Weaver.unweave[Profile](msgpack)
    restored shouldBe profile
  }

  test("optional fields missing in input") {
    // JSON without the optional field should use default None
    val json     = """{"name":"Grace"}"""
    val restored = Weaver.fromJson[Profile](json)
    restored shouldBe Profile("Grace", None)
  }

  test("collections of case classes") {
    val team     = Team("Engineers", List(Person("Alice", 30), Person("Bob", 25)))
    val msgpack  = Weaver.weave(team)
    val restored = Weaver.unweave[Team](msgpack)
    restored shouldBe team
  }

  test("collections of case classes to/from JSON") {
    val team     = Team("Designers", List(Person("Carol", 28), Person("Dave", 32)))
    val json     = Weaver.toJson(team)
    val restored = Weaver.fromJson[Team](json)
    restored shouldBe team
  }

  test("empty collection") {
    val team     = Team("Empty", List.empty)
    val msgpack  = Weaver.weave(team)
    val restored = Weaver.unweave[Team](msgpack)
    restored shouldBe team
  }

  test("default values for missing fields") {
    val json     = """{"name":"MyConfig"}"""
    val restored = Weaver.fromJson[ConfigWithDefaults](json)
    restored shouldBe ConfigWithDefaults("MyConfig", 30, 3)
  }

  test("default values can be overridden") {
    val json     = """{"name":"MyConfig","timeout":60,"retries":5}"""
    val restored = Weaver.fromJson[ConfigWithDefaults](json)
    restored shouldBe ConfigWithDefaults("MyConfig", 60, 5)
  }

  test("partial default values") {
    val json     = """{"name":"MyConfig","timeout":120}"""
    val restored = Weaver.fromJson[ConfigWithDefaults](json)
    restored shouldBe ConfigWithDefaults("MyConfig", 120, 3)
  }

  test("canonicalized name matching - snake_case") {
    val json     = """{"first_name":"Alice","last_name":"Smith"}"""
    val restored = Weaver.fromJson[User](json)
    restored shouldBe User("Alice", "Smith")
  }

  test("canonicalized name matching - kebab-case") {
    val json     = """{"first-name":"Bob","last-name":"Jones"}"""
    val restored = Weaver.fromJson[User](json)
    restored shouldBe User("Bob", "Jones")
  }

  test("canonicalized name matching - UPPER_SNAKE_CASE") {
    val json     = """{"FIRST_NAME":"Charlie","LAST_NAME":"Brown"}"""
    val restored = Weaver.fromJson[User](json)
    restored shouldBe User("Charlie", "Brown")
  }

  test("canonicalized name matching - camelCase") {
    val json     = """{"firstName":"Diana","lastName":"Prince"}"""
    val restored = Weaver.fromJson[User](json)
    restored shouldBe User("Diana", "Prince")
  }

  test("unknown fields are ignored") {
    val json     = """{"name":"Alice","age":30,"extra":"ignored","another":123}"""
    val restored = Weaver.fromJson[Person](json)
    restored shouldBe Person("Alice", 30)
  }

  test("missing required field throws error") {
    val json   = """{"name":"Alice"}"""
    val result =
      try
        Weaver.fromJson[Person](json)
        None
      catch
        case e: Exception =>
          Some(e)
    result.isDefined shouldBe true
    result.get.getMessage.contains("Missing required field") shouldBe true
  }

  test("null value for required field throws error") {
    val json   = """{"name":"Alice","age":null}"""
    val result =
      try
        Weaver.fromJson[Person](json)
        None
      catch
        case e: Exception =>
          Some(e)
    result.isDefined shouldBe true
    result.get.getMessage.contains("Null value not allowed for non-optional field") shouldBe true
  }

  test("explicitly given weaver works") {
    case class SimpleData(value: Int)
    given Weaver[SimpleData] = Weaver.derived[SimpleData]

    val data     = SimpleData(42)
    val msgpack  = Weaver.weave(data)
    val restored = Weaver.unweave[SimpleData](msgpack)
    restored shouldBe data
  }

  test("fromMap hydrates a flat case class") {
    val map      = Map[String, Any]("name" -> "Alice", "age" -> 30)
    val restored = Weaver.fromMap[Person](map)
    restored shouldBe Person("Alice", 30)
  }

  test("fromMap hydrates nested case classes") {
    val map = Map[String, Any](
      "name"    -> "Charlie",
      "address" -> Map[String, Any]("city" -> "Tokyo", "country" -> "Japan")
    )
    val restored = Weaver.fromMap[Employee](map)
    restored shouldBe Employee("Charlie", Address("Tokyo", "Japan"))
  }

  test("fromMap hydrates collection fields") {
    val map = Map[String, Any](
      "name"    -> "RPC",
      "members" ->
        Seq(
          Map[String, Any]("name" -> "Alice", "age" -> 30),
          Map[String, Any]("name" -> "Bob", "age"   -> 25)
        )
    )
    val restored = Weaver.fromMap[Team](map)
    restored shouldBe Team("RPC", List(Person("Alice", 30), Person("Bob", 25)))
  }

  test("fromMap fills defaults for missing fields") {
    val map      = Map[String, Any]("name" -> "svc")
    val restored = Weaver.fromMap[ConfigWithDefaults](map)
    restored shouldBe ConfigWithDefaults("svc")
  }

  test("fromMap leaves None for missing optional fields") {
    val map      = Map[String, Any]("name" -> "Frank")
    val restored = Weaver.fromMap[Profile](map)
    restored shouldBe Profile("Frank", None)
  }

  test("toMap round-trips a flat case class") {
    val person = Person("Alice", 30)
    val map    = Weaver.toMap(person)
    map("name") shouldBe "Alice"
    // AnyWeaver decodes integers as Long
    map("age") shouldBe 30L
    Weaver.fromMap[Person](map) shouldBe person
  }

  test("toMap round-trips nested case classes") {
    val employee = Employee("Charlie", Address("Tokyo", "Japan"))
    val map      = Weaver.toMap(employee)
    Weaver.fromMap[Employee](map) shouldBe employee
  }

end CaseClassWeaverTest

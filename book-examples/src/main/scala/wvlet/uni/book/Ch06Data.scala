/* Compile-checked examples from Book Chapter 6 — Data In, Data Out. */
package wvlet.uni.book

import wvlet.uni.json.*
import wvlet.uni.weaver.Weaver

object Ch06Data:
  def navigateJson(): Unit =
    val doc = JSON.parse("""
      { "user": { "id": 123, "name": "Alice" },
        "posts": [ { "title": "Hello" }, { "title": "World" } ] }
    """)
    val name  = doc("user")("name").toStringValue
    val id    = doc("user")("id").toLongValue
    val first = doc("posts")(0)("title").toStringValue
    println(s"${name} ${id} ${first}")

  case class User(id: Long, name: String) derives Weaver

  def codec(): Unit =
    val alice = User(123, "Alice")

    val json: String = Weaver.toJson(alice)
    val back: User   = Weaver.fromJson[User](json)

    val bytes: Array[Byte] = Weaver.weave(alice)
    val u: User            = Weaver.unweave[User](bytes)

    println(s"${json} ${back} ${u} ${bytes.length}")

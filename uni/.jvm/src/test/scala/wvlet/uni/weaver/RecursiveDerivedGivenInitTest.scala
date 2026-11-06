package wvlet.uni.weaver

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import wvlet.uni.test.UniTest

object RecursiveDerivedGivenInitTest:
  // Types used only by this test, so their derived givens are first initialized here
  sealed trait Node derives Weaver
  object Node:
    case class Split(children: Seq[Node])             extends Node derives Weaver
    case class Leaf(name: String, next: Option[Node]) extends Node derives Weaver

  enum ENode derives Weaver:
    case Split(children: Seq[ENode])
    case Leaf(name: String)

  case class Chain(value: Int, next: Option[Chain]) derives Weaver

class RecursiveDerivedGivenInitTest extends UniTest:
  import RecursiveDerivedGivenInitTest.*

  // Regression: derived givens used to build their inner weavers eagerly, so initializing
  // Weaver[Node] re-entered its own lazy initializer through Split and blocked forever. Force
  // the first initialization on another thread so a regression fails here instead of hanging.
  // JVM-only: Scala.js has no threads to time out.
  private def initWithin[A](body: => A): A =
    val pool = Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = Thread(r, "weaver-init")
      t.setDaemon(true)
      t
    }
    try
      val f = pool.submit(() => body)
      try
        f.get(30, TimeUnit.SECONDS)
      catch
        case e: TimeoutException =>
          fail("Initializing a derived Weaver for a recursive type did not finish in 30 seconds")
    finally
      pool.shutdownNow()

  test("recursive sealed trait given initializes without deadlock") {
    val json = initWithin(
      Weaver.toJson[Node](Node.Split(Seq(Node.Leaf("a", Some(Node.Split(Nil))))))
    )
    json shouldContain """"@type":"Leaf""""
  }

  test("recursive enum given initializes without deadlock") {
    val json = initWithin(Weaver.toJson[ENode](ENode.Split(Seq(ENode.Leaf("a")))))
    json shouldContain """"@type":"Leaf""""
  }

  test("self-recursive case class given initializes without deadlock") {
    val json = initWithin(Weaver.toJson(Chain(1, Some(Chain(2, None)))))
    json shouldBe """{"value":1,"next":{"value":2,"next":null}}"""
  }

end RecursiveDerivedGivenInitTest

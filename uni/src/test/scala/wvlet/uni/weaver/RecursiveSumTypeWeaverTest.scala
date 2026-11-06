package wvlet.uni.weaver

import wvlet.uni.test.UniTest

object RecursiveSumTypeWeaverTest:
  enum Dir derives Weaver:
    case Row,
      Column

  // A layout tree shaped like the uni-ui Dock's: recursion through Seq
  sealed trait Node derives Weaver
  object Node:
    case class Split(direction: Dir, children: Seq[Node], sizes: Seq[Double]) extends Node
        derives Weaver

    case class Group(panels: Seq[String], active: Option[String]) extends Node derives Weaver

  case class Saved(version: Int, root: Node) derives Weaver

  // The same tree as an enum with parameterized cases
  enum ENode derives Weaver:
    case Split(direction: Dir, children: Seq[ENode], sizes: Seq[Double])
    case Group(panels: Seq[String], active: Option[String])

  case class ESaved(version: Int, root: ENode) derives Weaver

  // Recursion through Option, and an enum mixing singleton and parameterized cases
  enum Expr derives Weaver:
    case Zero
    case Succ(prev: Expr)
    case Add(left: Expr, right: Option[Expr])

  // A self-recursive case class with derives
  case class Tree(value: Int, children: List[Tree], next: Option[Tree]) derives Weaver

  // Sealed trait children without `derives Weaver` are derived inline
  sealed trait Shape derives Weaver
  case class Circle(radius: Double)          extends Shape
  case class Composite(parts: Vector[Shape]) extends Shape
  case object Empty                          extends Shape

end RecursiveSumTypeWeaverTest

class RecursiveSumTypeWeaverTest extends UniTest:
  import RecursiveSumTypeWeaverTest.*

  private val tree: Node = Node.Split(
    Dir.Row,
    Seq(
      Node.Group(Seq("a", "b"), Some("a")),
      Node.Split(
        Dir.Column,
        Seq(Node.Group(Seq("c"), None), Node.Group(Seq.empty, None)),
        Seq(0.3, 0.7)
      )
    ),
    Seq(0.5, 0.5)
  )

  test("round-trip a recursive sealed trait through JSON") {
    val saved = Saved(1, tree)
    val json  = Weaver.toJson(saved)
    json shouldContain """"@type":"Split""""
    json shouldContain """"@type":"Group""""
    json shouldContain """"panels":["a","b"]"""
    Weaver.fromJson[Saved](json) shouldBe saved
  }

  test("round-trip a recursive sealed trait through MessagePack") {
    Weaver.unweave[Node](Weaver.weave(tree)) shouldBe tree
  }

  test("enum with parameterized cases keeps its fields") {
    val root = ENode.Split(
      Dir.Row,
      Seq(ENode.Group(Seq("a"), Some("a")), ENode.Split(Dir.Column, Seq.empty, Seq.empty)),
      Seq(0.5)
    )
    val saved = ESaved(1, root)
    val json  = Weaver.toJson(saved)
    json shouldBe
      """{"version":1,"root":{"@type":"Split","direction":"Row","children":[{"@type":"Group","panels":["a"],"active":"a"},{"@type":"Split","direction":"Column","children":[],"sizes":[]}],"sizes":[0.5]}}"""
    Weaver.fromJson[ESaved](json) shouldBe saved
  }

  test("enum mixing singleton and parameterized cases, recursive through Option") {
    val e: Expr = Expr.Add(Expr.Succ(Expr.Zero), Some(Expr.Add(Expr.Zero, None)))
    val json    = Weaver.toJson(e)
    json shouldContain """{"@type":"Zero"}"""
    Weaver.fromJson[Expr](json) shouldBe e
    Weaver.fromJson[Expr]("""{"@type":"Zero"}""") shouldBe Expr.Zero
  }

  test("enum with only singleton cases still packs as a string") {
    Weaver.toJson(Dir.Column) shouldBe "\"Column\""
  }

  test("self-recursive case class with derives") {
    val t = Tree(
      1,
      List(Tree(2, Nil, None), Tree(3, List(Tree(4, Nil, None)), None)),
      Some(Tree(5, Nil, None))
    )
    val json = Weaver.toJson(t)
    Weaver.fromJson[Tree](json) shouldBe t
  }

  test("sealed trait children without derives are derived inline") {
    val s: Shape = Composite(Vector(Circle(1.5), Empty, Composite(Vector.empty)))
    val json     = Weaver.toJson(s)
    json shouldContain """"@type":"Circle""""
    Weaver.fromJson[Shape](json) shouldBe s
  }

  test("unknown case name reports the known cases") {
    val e = intercept[Exception] {
      Weaver.fromJson[ENode]("""{"@type":"Tab"}""")
    }
    e.getMessage shouldContain "Unknown type 'Tab'"
  }

end RecursiveSumTypeWeaverTest

package example.gallery.stories

import example.gallery.{ComponentPage, Story}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*

import scala.language.implicitConversions

/**
  * The minimal app of the Get started page. Its region is a whole `Main.scala` under the imports
  * the page lists, so readers can copy it into a new project as it is. The gallery renders
  * `Hello()`; `Main.main` is what the reader's own build runs.
  */
object StartStories:

  // #region start/app
  class Hello extends RxElement:
    private val name = Rx.variable("World")

    override def render: RxElement = div(
      Input(name).withLabel("Name"),
      p(name.map(n => s"Hello, ${n}!")),
      Button("Reset").onClick(() => name := "World")
    )

  object Main:
    def main(args: Array[String]): Unit = Hello().renderTo("app")
  // #endregion

  val pages: Seq[ComponentPage] = Seq(
    ComponentPage(
      "start",
      "Get started",
      "Set up a Scala.js project with Uni and uni-ui, then build and run your first app.",
      Seq(
        Story(
          "start/app",
          "Your first app",
          "src/main/scala/Main.scala: type a name, then press Reset."
        )(Hello())
      ),
      api = Nil,
      guide = true
    )
  )

end StartStories

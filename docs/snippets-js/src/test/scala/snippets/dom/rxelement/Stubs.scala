package snippets.dom.rxelement

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions

// Names that docs/dom/rxelement.md uses without defining
def handle(e: dom.Event): Unit = ()
val isLoggedIn                 = true
val items                      = Seq("a", "b")
def send(text: String): Unit   = ()
val sidebarContent: RxElement  = div("sidebar")

class MyApp extends RxElement:
  override def render: RxElement = div("app")

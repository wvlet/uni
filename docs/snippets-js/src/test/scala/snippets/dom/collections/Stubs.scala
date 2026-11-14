package snippets.dom.collections

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import scala.language.implicitConversions

// Names that docs/dom/collections.md uses without defining
def openFile(item: Any): Unit = ()

case class LogLine(id: Long, text: String)

object log:
  val lines = Rx.variable(Seq(LogLine(1, "started")))

case class Conversation(id: String, title: String)

object store:
  val conversations            = Rx.variable(Seq(Conversation("1", "Hello")))
  def newChat(): Unit          = ()
  def select(id: String): Unit = ()

package snippets.dom.controls

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import scala.language.implicitConversions

// Names that docs/dom/controls.md uses without defining
def send(text: String): Unit = ()
def close(): Unit            = ()
val closeIcon: RxElement     = span("x")

package snippets.dom.overlays

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import scala.language.implicitConversions

// Names that docs/dom/overlays.md uses without defining
def rename(): Unit       = ()
def delete(): Unit       = ()
val closeIcon: RxElement = span("x")
val moreIcon: RxElement  = span("...")
val query                = Rx.variable("")

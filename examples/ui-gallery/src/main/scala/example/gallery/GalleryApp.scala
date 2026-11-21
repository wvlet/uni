package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.Storage
import wvlet.uni.dom.all.renderTo
import wvlet.uni.rx.Rx
import wvlet.uni.ui.ColorScheme

/**
  * The entry point. The URL holds the state worth sharing: the hash names the story
  * (`#/overlays/menu`), `?embed` drops the header and sidebar for an iframe, and `?theme=dark` or
  * `?theme=light` forces a color scheme, as the docs pages do to match their own theme.
  */
object GalleryApp:

  def main(args: Array[String]): Unit =
    val params  = dom.URLSearchParams(dom.window.location.search)
    val embed   = params.has("embed")
    val current = Rx.variable(Gallery.storyOf(dom.window.location.hash).getOrElse(Stories.all.head))
    dom
      .window
      .addEventListener(
        "hashchange",
        (_: dom.Event) => Gallery.storyOf(dom.window.location.hash).foreach(current := _)
      )

    val scheme =
      Option(params.get("theme")).flatMap(t =>
        ColorScheme.values.find(_.toString.equalsIgnoreCase(t))
      ) match
        case Some(forced) =>
          Rx.variable(forced)
        case None =>
          Storage.local("uni-gallery.colorScheme", ColorScheme.System)
    ColorScheme.bind(scheme)

    Gallery(current, scheme, embed).renderTo("app")

end GalleryApp

package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.Storage
import wvlet.uni.dom.all.renderTo
import wvlet.uni.rx.Rx
import wvlet.uni.ui.ColorScheme

import scala.scalajs.js

/**
  * The entry point. The URL holds the state worth sharing: the hash names the story
  * (`#/overlays/menu`), `?embed` shows only the story card for an iframe, `?view=preview`, `code`,
  * or `split` picks how the card shows the code, and `?theme=dark` or `?theme=light` forces a color
  * scheme. A page that embeds the gallery changes the theme later with
  * `postMessage({ type: 'uni-gallery-theme', theme: 'dark' })`, which keeps the story and its
  * state.
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

    val forcedTheme = Option(params.get("theme")).flatMap(t =>
      ColorScheme.values.find(_.toString.equalsIgnoreCase(t))
    )
    val scheme =
      if embed || forcedTheme.isDefined then
        Rx.variable(forcedTheme.getOrElse(ColorScheme.System))
      else
        Storage.local("uni-gallery.colorScheme", ColorScheme.System)
    ColorScheme.bind(scheme)

    dom
      .window
      .addEventListener(
        "message",
        (e: dom.MessageEvent) =>
          val msg = e.data.asInstanceOf[js.Dynamic]
          if js.typeOf(msg) == "object" && msg != null &&
            msg.selectDynamic("type").asInstanceOf[Any] == "uni-gallery-theme"
          then
            ColorScheme
              .values
              .find(_.toString.equalsIgnoreCase(msg.selectDynamic("theme").toString))
              .foreach(scheme := _)
      )

    // Side by side when there is room for both, else the preview with a tab for the code
    val defaultMode =
      if dom.window.innerWidth >= 1100 then
        ViewMode.Split
      else
        ViewMode.Preview
    val forcedMode = Option(params.get("view")).flatMap(ViewMode.parse)
    val mode       =
      if embed || forcedMode.isDefined then
        Rx.variable(forcedMode.getOrElse(defaultMode))
      else
        Storage.local("uni-gallery.view", defaultMode)

    Gallery(current, scheme, mode, embed).renderTo("app")

  end main

end GalleryApp

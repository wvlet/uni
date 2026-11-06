package example.renderer

import wvlet.uni.dom.StorageCodec

import scala.scalajs.js

/** How `ChatApi` calls travel: chosen once at startup in [[HarnessApp]]. */
enum Transport(val label: String, val detail: String):
  case ElectronIPC extends Transport("Electron IPC", "ipcRenderer.invoke → ipcMain")
  case HttpFetch   extends Transport("HTTP", "fetch → Http.server on Node")

/** The sidebar views selected from the icon rail. */
enum View(val label: String) derives StorageCodec:
  case Chats    extends View("Chats")
  case Files    extends View("Files")
  case Settings extends View("Settings")

/**
  * The panels of the right dock. Each one's [[id]] names it in the saved `DockLayout`; the codec
  * reads the `dockTab` key that older versions saved.
  */
enum DockTab(val label: String) derives StorageCodec:
  case Details  extends DockTab("Details")
  case Activity extends DockTab("Activity")
  case Terminal extends DockTab("Terminal")

  def id: String = toString.toLowerCase

/** "just now", "5m ago", "3h ago", or a date, for timestamps. */
def ago(millis: Long, now: Double = js.Date.now()): String =
  val s = (now - millis.toDouble) / 1000
  if s < 45 then
    "just now"
  else if s < 3600 then
    s"${(s / 60).toInt}m ago"
  else if s < 86400 then
    s"${(s / 3600).toInt}h ago"
  else
    // `new` is required: in Scala.js, `js.Date(...)` calls JS `Date()`, which returns a string
    new js.Date(millis.toDouble).toLocaleDateString()

/** Hour and minute, in the viewer's locale. */
def clock(millis: Long): String = new js.Date(millis.toDouble)
  .asInstanceOf[js.Dynamic]
  .toLocaleTimeString(js.Array[String](), js.Dynamic.literal(hour = "2-digit", minute = "2-digit"))
  .asInstanceOf[String]

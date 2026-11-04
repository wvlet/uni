package example.renderer

import wvlet.uni.dom.StorageCodec

import scala.scalajs.js

/** How `ChatApi` calls travel: chosen once at startup in [[HarnessApp]]. */
enum Transport(val label: String, val detail: String):
  case ElectronIPC extends Transport("Electron IPC", "ipcRenderer.invoke → ipcMain")
  case HttpFetch   extends Transport("HTTP", "fetch → Http.server on Node")

/** The sidebar views selected from the icon rail. */
enum View(val label: String):
  case Chats    extends View("Chats")
  case Files    extends View("Files")
  case Settings extends View("Settings")

enum Theme(val label: String):
  case System extends Theme("System")
  case Light  extends Theme("Light")
  case Dark   extends Theme("Dark")

enum DockTab(val label: String):
  case Details  extends DockTab("Details")
  case Activity extends DockTab("Activity")

object Theme:
  /** style.css reads `data-theme` on <html>; System leaves it to `prefers-color-scheme`. */
  def applyTo(root: org.scalajs.dom.Element, theme: Theme): Unit =
    theme match
      case Theme.System =>
        root.removeAttribute("data-theme")
      case t =>
        root.setAttribute("data-theme", t.toString.toLowerCase)

/** Store enum values in localStorage by name, falling back to the default on unknown names. */
private def enumCodec[E](values: Array[E]): StorageCodec[E] =
  new StorageCodec[E]:
    def encode(value: E): String       = value.toString
    def decode(raw: String): Option[E] = values.find(_.toString == raw)

given StorageCodec[View]    = enumCodec(View.values)
given StorageCodec[Theme]   = enumCodec(Theme.values)
given StorageCodec[DockTab] = enumCodec(DockTab.values)

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

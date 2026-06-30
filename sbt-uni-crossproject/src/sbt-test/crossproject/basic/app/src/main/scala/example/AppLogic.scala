package example

// Shared, cross-platform app logic depending on `core` (via dependsOn). Uses only the standard
// library so it compiles on JVM, JS, and Native alike.
object AppLogic:
  def result: String = Shared.detail

package example

// Lives in the cross-version shared dir <base>/src/main/scala-3. If makeCrossSources did not add
// the `scala-3` variant to unmanagedSourceDirectories, Shared.detail would fail to compile.
object Versioned:
  def tag: String = "scala3"

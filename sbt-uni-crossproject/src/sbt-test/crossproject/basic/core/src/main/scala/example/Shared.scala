package example

// Shared across all platforms (CrossType.Pure: <base>/src/main/scala).
// Combines a per-platform value (Platform.name) with a cross-version value (Versioned.tag),
// proving both the platform-specific and the scala-version shared source dirs are wired in.
object Shared:
  def greeting: String = s"hello-${Platform.name}"
  def detail: String   = s"${greeting}/${Versioned.tag}"

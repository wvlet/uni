package example

// Shared test source: <base>/src/test/scala. Compiling coreJVM/Test proves the shared test
// directory is wired into Test / unmanagedSourceDirectories.
object CoreTest:
  def check: Boolean = Shared.greeting.startsWith("hello-")

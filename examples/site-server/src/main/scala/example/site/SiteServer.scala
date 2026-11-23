package example.site

import wvlet.uni.http.{Http, StaticFiles}

/**
  * Serves a directory of built web files at http://localhost:<port> for local development, such as
  * the ui-gallery site or the web-ui example. Run it in the background with sbt-uni's `uniRestart`:
  *
  * {{{
  * ./sbt "exampleSiteServer/uniRestart examples/ui-gallery/target/site 8080"
  * }}}
  *
  * Arguments: the directory (relative to the repository root) and an optional port (default 8080).
  */
object SiteServer:
  def main(args: Array[String]): Unit =
    val dir    = args.headOption.getOrElse(".")
    val port   = args.lift(1).map(_.toInt).getOrElse(8080)
    val server = Http.server.withPort(port).withFilter(StaticFiles(dir)).start()
    // stdout: uniRestart tags the app's stderr lines as errors
    println(s"Serving ${dir} at http://localhost:${port}")
    server.awaitTermination()

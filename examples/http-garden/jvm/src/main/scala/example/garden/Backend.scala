package example.garden

import wvlet.uni.http.HttpServerConfig
import wvlet.uni.http.netty.Netty

/**
  * On the JVM, `Http.server` uses uni-netty when it is on the classpath, and the built-in server
  * otherwise. The Garden runs this app both ways, so touch Netty classes only when they are there.
  */
object Backend:
  def configure(config: HttpServerConfig): HttpServerConfig =
    if nettyPresent then
      NettyBackend.configure(config)
    else
      // The built-in server has no options; it always decodes gzip/deflate request bodies
      config

  private def nettyPresent: Boolean =
    try
      Class.forName("wvlet.uni.http.netty.Netty")
      true
    catch
      case _: ClassNotFoundException =>
        false

private object NettyBackend:
  // Echo the bytes as sent, not the decompressed body
  def configure(config: HttpServerConfig): HttpServerConfig = config.withBackend(
    Netty.options.noRequestDecompression
  )

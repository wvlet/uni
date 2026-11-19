package example.garden

import wvlet.uni.http.{HttpServerConfig, Native}

object Backend:
  // Echo the bytes as sent, not the decompressed body
  def configure(config: HttpServerConfig): HttpServerConfig = config.withBackend(
    Native.options.noRequestDecompression
  )

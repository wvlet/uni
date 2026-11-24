package example.rpc

import wvlet.uni.http.HttpServerConfig

/** `RPC_WORKERS` tunes only the Scala Native server; the JVM servers keep their defaults */
object BenchTuning:
  def apply(config: HttpServerConfig): HttpServerConfig = config

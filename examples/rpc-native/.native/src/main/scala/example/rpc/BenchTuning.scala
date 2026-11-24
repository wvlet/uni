package example.rpc

import wvlet.uni.http.HttpServerConfig
import wvlet.uni.http.Native

/**
  * For bench/: `RPC_WORKERS=n` sets NativeServer's worker count, so rpc-bench.yml can sweep it.
  * Unset or empty, the server keeps its default (about one worker per core).
  */
object BenchTuning:
  def apply(config: HttpServerConfig): HttpServerConfig =
    sys.env.get("RPC_WORKERS").filter(_.nonEmpty).map(_.toInt) match
      case Some(n) =>
        config.withBackend(Native.options.withWorkerThreads(n))
      case None =>
        config

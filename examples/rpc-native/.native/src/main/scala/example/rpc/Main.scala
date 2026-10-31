package example.rpc

import wvlet.uni.http.NativeServer

/** The TodoApi RPC server as a native binary: no JVM, no libcurl, only libc. */
object Main:
  def main(args: Array[String]): Unit =
    val (port, host) = TodoRpc.parseArgs(args)
    val server       = NativeServer
      .withPort(port)
      .withHost(host)
      .withRxHandler(TodoRpc.handler(TodoApiImpl()))
      .start()
    println(s"ready ${server.localPort}")
    server.awaitTermination()

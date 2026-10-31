package example.rpc

import wvlet.uni.http.netty.NettyServer

/** The same TodoApi RPC server on the JVM, served by Netty. */
object Main:
  def main(args: Array[String]): Unit =
    val (port, host) = TodoRpc.parseArgs(args)
    val server       = NettyServer
      .withPort(port)
      .withHost(host)
      .withRxHandler(TodoRpc.handler(TodoApiImpl()))
      .start()
    println(s"ready ${server.localPort}")
    server.awaitTermination()

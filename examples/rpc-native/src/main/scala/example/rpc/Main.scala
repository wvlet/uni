package example.rpc

import wvlet.uni.http.HttpServer

/** The TodoApi RPC server. The same code builds a JVM server and a Scala Native binary. */
object Main:
  def main(args: Array[String]): Unit =
    val (port, host) = TodoRpc.parseArgs(args)
    TodoRpc
      .design(port, host)
      .build[HttpServer] { server =>
        println(s"ready ${server.localPort}")
        server.awaitTermination()
      }

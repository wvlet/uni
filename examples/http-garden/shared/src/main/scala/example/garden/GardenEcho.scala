package example.garden

import wvlet.uni.http.{Http, HttpStatus, Request, Response}
import wvlet.uni.rx.Rx

import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.Base64

/**
  * An HTTP Garden origin: every request gets a 200 whose body is a JSON echo of how the server
  * parsed it, so the Garden can compare Uni's parser with other servers. Each field is base64 of
  * the raw bytes, in the shape Garden's `parse_response_json` reads.
  */
object GardenEcho:

  def handler(request: Request): Rx[Response] = Rx.single(
    Response(HttpStatus.Ok_200).withBytesContent(echo(request).getBytes(ISO_8859_1))
  )

  def echo(request: Request): String =
    val headers = request
      .headers
      .entries
      .map { (name, value) =>
        s"""["${b64(name)}","${b64(value)}"]"""
      }
      .mkString(",")
    // Uni's Request has no HTTP version, so the echo leaves it empty (the
    // `doesnt-support-version` quirk)
    s"""{"version":"","method":"${b64(request.method.name)}","uri":"${b64(
        request.uri
      )}","headers":[${headers}],"body":"${Base64
        .getEncoder
        .encodeToString(request.content.toContentBytes)}"}"""

  private def b64(s: String): String = Base64.getEncoder.encodeToString(s.getBytes(ISO_8859_1))

  /** `GardenEcho <port>`: serve on 127.0.0.1 until killed. */
  def main(args: Array[String]): Unit =
    val port   = args.headOption.map(_.toInt).getOrElse(8080)
    val config = Http
      .server
      .withPort(port)
      .withHost("127.0.0.1")
      // Garden sends arbitrary Host values
      .noHostCheck
      // Echo the bytes as sent, not the decompressed body
      .noRequestDecompression
      .withRxHandler(handler)
    val server = config.start()
    println(s"ready ${server.localPort}")
    server.awaitTermination()

end GardenEcho

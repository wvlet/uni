/* Names the http-server.md snippets use without defining them. */
package snippets.skill.references.http_server

import wvlet.uni.http.{Request, Response}
import wvlet.uni.rx.Rx

val handler: Request => Rx[Response] = _ => Rx.single(Response.ok("ok"))

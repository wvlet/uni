package example

import wvlet.uni.http.HttpMethod
import wvlet.uni.http.router.{Endpoint, Router}

case class Greeting(message: String)

class GreetingApi:
  @Endpoint(HttpMethod.GET, "/greet/:name", description = "Greet someone")
  def greet(name: String): Greeting = Greeting(s"Hello ${name}")

object Api:
  val router: Router = Router.of[GreetingApi]

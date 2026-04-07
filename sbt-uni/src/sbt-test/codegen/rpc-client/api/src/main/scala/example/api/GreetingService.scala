package example.api

case class Greeting(message: String)

trait GreetingService:
  def hello(name: String): Greeting
  def goodbye(name: String): Greeting

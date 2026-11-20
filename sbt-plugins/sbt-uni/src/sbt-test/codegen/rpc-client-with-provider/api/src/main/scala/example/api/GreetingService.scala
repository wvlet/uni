package example.api

import wvlet.uni.http.router.RxRouter
import wvlet.uni.http.router.RxRouterProvider

case class Greeting(message: String)

trait GreetingService:
  def hello(name: String): Greeting
  def goodbye(name: String): Greeting

object GreetingService extends RxRouterProvider:
  override def router: RxRouter = RxRouter.of[GreetingService]

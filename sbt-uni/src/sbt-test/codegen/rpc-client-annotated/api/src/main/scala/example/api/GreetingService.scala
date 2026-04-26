package example.api

import wvlet.uni.http.router.RxRouter
import wvlet.uni.http.router.RxRouterProvider
import wvlet.uni.http.rpc.RPC

case class Greeting(message: String)

@RPC
trait GreetingService:
  def hello(name: String): Greeting
  def goodbye(name: String): Greeting

object GreetingService extends RxRouterProvider:
  override def router: RxRouter = RxRouter.of[GreetingService]

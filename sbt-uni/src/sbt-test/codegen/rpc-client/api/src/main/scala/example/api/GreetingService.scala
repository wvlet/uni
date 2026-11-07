package example.api

import wvlet.uni.http.rpc.rpcStream
import wvlet.uni.rx.Rx

case class Greeting(message: String)

trait GreetingService:
  def hello(name: String): Greeting
  def goodbye(name: String): Greeting
  def greetAll(names: Seq[String]): Seq[Greeting]
  def find(name: String): Rx[Option[Greeting]]
  @rpcStream
  def greetEach(names: Seq[String]): Rx[Greeting]

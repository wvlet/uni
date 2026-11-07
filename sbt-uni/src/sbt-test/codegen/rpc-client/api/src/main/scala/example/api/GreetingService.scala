package example.api

import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxStream

case class Greeting(message: String)

trait GreetingService:
  def hello(name: String): Greeting
  def goodbye(name: String): Greeting
  def greetAll(names: Seq[String]): Seq[Greeting]
  def find(name: String): Rx[Option[Greeting]]
  def greetEach(names: Seq[String]): RxStream[Greeting]

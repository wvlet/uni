package example.app

import example.api.Greeting
import example.client.GreetingServiceClient
import wvlet.uni.rx.Rx

// This file verifies that the generated client code compiles.
// The generated GreetingServiceClient should be available in example.client package.
object Main:
  // If this compiles, the code generation worked correctly.
  // At runtime we'd need an actual HttpSyncClient, but compilation is the key test.
  val clientClass = classOf[GreetingServiceClient.SyncClient]

  // Generic and Rx return types keep their type arguments, and an @rpcStream method streams
  def greetAll(c: GreetingServiceClient.SyncClient): Seq[Greeting]      = c.greetAll(Seq("a"))
  def find(c: GreetingServiceClient.SyncClient): Option[Greeting]       = c.find("a")
  def findAsync(c: GreetingServiceClient.AsyncClient): Rx[Option[Greeting]] = c.find("a")
  def greetEach(c: GreetingServiceClient.AsyncClient): Rx[Greeting]     = c.greetEach(Seq("a"))

  def main(args: Array[String]): Unit = println(s"Generated client class: ${clientClass.getName}")

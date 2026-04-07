package example.app

// This file verifies that the generated client code compiles.
// The generated GreetingServiceClient should be available in example.client package.
object Main:
  // If this compiles, the code generation worked correctly.
  // At runtime we'd need an actual HttpSyncClient, but compilation is the key test.
  val clientClass = classOf[example.client.GreetingServiceClient.SyncClient]
  def main(args: Array[String]): Unit =
    println(s"Generated client class: ${clientClass.getName}")

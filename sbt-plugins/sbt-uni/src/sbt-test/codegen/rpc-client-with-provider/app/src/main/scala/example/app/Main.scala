package example.app

// Verifies that the codegen works for an RPC trait whose companion extends RxRouterProvider.
// The generated GreetingServiceClient should be available in the example.client package, just
// like the rpc-client (no companion) case.
object Main:
  val clientClass = classOf[example.client.GreetingServiceClient.SyncClient]

  // RxRouter.of[GreetingService] is wired through the API project's companion.
  val routerExists = example.api.GreetingService.router != null

  def main(args: Array[String]): Unit =
    println(s"Generated client class: ${clientClass.getName}")
    println(s"Router available: ${routerExists}")

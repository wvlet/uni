# Chat Sessions

Manage conversations with LLM agents.

## Creating a Session

```scala
import wvlet.uni.agent.chat.*

// From an agent
val session = agent.newSession(runner)

// With tool support
val toolSession = agent.newSession(runner, Some(toolExecutor))
```

## Basic Chat

### Single Message

```scala
import wvlet.uni.agent.chat.ChatMessage.AIMessage

val response = session.chat("What is the capital of France?")

// Access the response text from the last AI message
response.messages.collect { case ai: AIMessage => ai.text }.lastOption.foreach(println)
// "The capital of France is Paris."
```

### With Observer

```scala
val response = session.chat("Explain quantum computing", observer)
```

## Conversation History

### Continue a Conversation

```scala
// First message
val response1 = session.chat("What is Scala?")

// Continue with context
val response2 = session.continueChat(response1, "Show me an example")

// Continue again
val response3 = session.continueChat(response2, "Now explain pattern matching")
```

### Manual History

```scala
val history = Seq(
  ChatMessage.user("What is Scala?"),
  ChatMessage.assistant("Scala is a programming language...")
)

val response = session.continueChat(history, "Show me an example")
```

## Message Types

### User Messages

```scala
val message = ChatMessage.user("Hello!")
```

### Assistant Messages

```scala
val message = ChatMessage.assistant("Hello! How can I help?")
```

### System Messages

```scala
val message = ChatMessage.SystemMessage("You are a helpful assistant")
```

### Tool Results

```scala
val result = ChatMessage.ToolResultMessage(
  id = "call_123",
  toolName = "calculator",
  text = "42"
)
```

## Chat Response

`ChatResponse` contains the full conversation history, stats, and finish reason:

```scala
val response = session.chat("Hello")

// Get all messages (includes history)
val messages = response.messages

// Get token usage stats
val stats = response.stats  // ChatStats(latencyMs, inputTokens, outputTokens, totalTokens)

// Check why the response finished
val reason = response.finishReason  // END_TURN, STOP_SEQUENCE, TOOL_CALL, MAX_TOKENS, etc.

// Extract text from the last AI message
response.messages.collect { case ai: AIMessage => ai.text }.lastOption

// Check if any AI message has tool calls
response.messages.exists {
  case ai: AIMessage => ai.hasToolCalls
  case _             => false
}
```

## Streaming

### Chat Observer

```scala
import wvlet.uni.agent.chat.{ChatObserver, ChatEvent, ChatResponse}

val observer = new ChatObserver:
  def onPartialResponse(event: ChatEvent): Unit =
    event match
      case ChatEvent.PartialResponse(text) =>
        print(text)  // Print as it streams
      case ChatEvent.PartialReasoningResponse(text) =>
        print(text)  // Reasoning output
      case ChatEvent.PartialToolRequestResponse(text) =>
        print(text)  // Tool request fragments

  def onComplete(response: ChatResponse): Unit =
    println("\n[Complete]")

  def onError(e: Throwable): Unit =
    println(s"Error: ${e.getMessage}")

val response = session.chat("Tell me a story", observer)
```

### Default Observer

```scala
// Uses default observer (no streaming output)
val response = session.chat("Hello")
```

## Tool Calls

### Detecting Tool Calls

```scala
import wvlet.uni.agent.chat.ChatMessage.AIMessage

val response = session.chat("What's the weather in Tokyo?")

// Check AI messages for tool calls
response.messages.collect { case ai: AIMessage if ai.hasToolCalls => ai }.foreach { ai =>
  for call <- ai.toolCalls do
    println(s"Tool: ${call.name}")
    println(s"Args: ${call.args}")
}
```

### Handling Tool Results

For manual tool handling, create tool result messages and continue the conversation:

```scala
import wvlet.uni.agent.chat.ChatMessage.{AIMessage, ToolResultMessage}

// Find AI message with tool calls
response.messages.collect { case ai: AIMessage if ai.hasToolCalls => ai }.foreach { ai =>
  // Execute tools and create results
  val results = ai.toolCalls.map { call =>
    val result = executeMyTool(call.name, call.args)
    ToolResultMessage(call.id, call.name, result)
  }

  // Continue conversation by including tool results in history
  val updatedHistory = response.messages ++ results
  val finalResponse = session.continueChat(updatedHistory, "Please summarize the results")
}
```

## Tool-Enabled Session

For automatic tool execution, use `chatWithTools`:

```scala
import wvlet.uni.agent.chat.withToolSupport

// Create a tool-enabled session
val toolSession = agent.newSession(runner, Some(toolExecutor))

// Tools are executed automatically in a loop until the model stops calling tools
val response = toolSession.chatWithTools("Calculate 2 + 2").toSeq.head
```

## Example: Interactive Chat

```scala
import scala.io.StdIn
import wvlet.uni.agent.chat.ChatMessage.AIMessage

val session = agent.newSession(runner)
var lastResponse: Option[ChatResponse] = None

println("Chat with AI (type 'quit' to exit)")

while true do
  print("> ")
  val input = StdIn.readLine()

  if input.toLowerCase == "quit" then
    System.exit(0)

  lastResponse = lastResponse match
    case Some(prev) =>
      Some(session.continueChat(prev, input))
    case None =>
      Some(session.chat(input))

  lastResponse.get.messages.collect { case ai: AIMessage => ai.text }.lastOption.foreach(println)
  println()
```

## Best Practices

1. **Preserve history** for context-aware responses
2. **Use observers** for streaming UIs
3. **Handle tool calls** appropriately
4. **Limit history size** to manage token usage
5. **Log conversations** for debugging

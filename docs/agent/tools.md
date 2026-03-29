# Tool Integration

Enable LLM agents to call functions and interact with external systems.

## Defining Tools

### ToolSpec

```scala
import wvlet.uni.agent.chat.{ToolSpec, ToolParameter}
import wvlet.uni.agent.core.DataType

val calculatorTool = ToolSpec(
  name = "calculator",
  description = "Perform mathematical calculations",
  parameters = List(
    ToolParameter(
      name = "expression",
      description = "Mathematical expression to evaluate",
      dataType = DataType.StringType
    )
  ),
  returnType = DataType.StringType
)
```

### Parameter Types

```scala
// String parameter
ToolParameter("query", "Search query", DataType.StringType)

// Integer parameter
ToolParameter("count", "Number of results", DataType.IntegerType)

// Boolean parameter
ToolParameter("verbose", "Enable verbose output", DataType.BooleanType)

// With default value
ToolParameter("limit", "Max results", DataType.IntegerType, defaultValue = Some(10))
```

## Adding Tools to Agent

```scala
val agent = LLMAgent(
  name = "assistant",
  description = "Helpful assistant with tools",
  model = LLM.Bedrock.Claude3_7Sonnet_20250219V1_0
).withTools(List(
  calculatorTool,
  searchTool,
  fileReadTool
))
```

## Tool Executor

### Implementing a Tool Executor

The `ToolExecutor` trait returns `Rx[ToolResultMessage]` for asynchronous execution:

```scala
import wvlet.uni.agent.tool.ToolExecutor
import wvlet.uni.agent.chat.{ToolSpec, ChatMessage}
import wvlet.uni.agent.chat.ChatMessage.{ToolCallRequest, ToolResultMessage}
import wvlet.airframe.rx.Rx

class MyToolExecutor extends ToolExecutor:
  override def executeToolCall(toolCall: ToolCallRequest): Rx[ToolResultMessage] =
    val result = toolCall.name match
      case "calculator" =>
        val expr = toolCall.args("expression").toString
        evaluateExpression(expr)
      case "web_search" =>
        val query = toolCall.args("query").toString
        searchWeb(query)
      case _ =>
        s"Unknown tool: ${toolCall.name}"
    Rx.single(ToolResultMessage(toolCall.id, toolCall.name, result))

  override def availableTools: Seq[ToolSpec] = Seq(calculatorTool, webSearchTool)
```

### Local Tool Executor

For quick tool registration, use `LocalToolExecutor` with `registerTool`:

```scala
import wvlet.uni.agent.tool.LocalToolExecutor

val executor = LocalToolExecutor()
  .registerTool(calculatorTool, { args =>
    val expr = args("expression").toString
    evaluate(expr).toString
  })
  .registerTool(weatherTool, { args =>
    val city = args("city").toString
    getWeather(city)
  })

// Or create with pre-registered tools
val executor2 = LocalToolExecutor(
  calculatorTool -> { args => evaluate(args("expression").toString) },
  weatherTool    -> { args => getWeather(args("city").toString) }
)
```

## Using Tools in Sessions

### Automatic Execution

Use `chatWithTools` for automatic tool call handling:

```scala
import wvlet.uni.agent.chat.withToolSupport

val session = agent.newSession(runner, Some(toolExecutor))

// Tools are executed automatically in a loop
val response = session.chatWithTools("What's 25 * 4?").toSeq.head
// Agent calls calculator, gets result, responds
```

### Manual Execution

```scala
import wvlet.uni.agent.chat.ChatMessage.{AIMessage, ToolResultMessage}

val session = agent.newSession(runner)
val response = session.chat("What's 25 * 4?")

// Check if any AI message contains tool calls
response.messages.collect { case ai: AIMessage if ai.hasToolCalls => ai }.foreach { ai =>
  // Execute tools manually
  val results = ai.toolCalls.map { call =>
    val result = myExecutor.executeToolCall(call)
    // result is Rx[ToolResultMessage] — await or subscribe as needed
  }
}
```

## Tool Call Flow

```
User: "What's the weather in Tokyo?"
     ↓
Agent: [Decides to use weather tool]
     ↓
Tool Call: weather(city="Tokyo")
     ↓
Tool Executor: Calls weather API
     ↓
Tool Result: "Sunny, 22°C"
     ↓
Agent: "The weather in Tokyo is sunny with a temperature of 22°C."
```

## Example Tools

### Web Search Tool

```scala
val webSearchTool = ToolSpec(
  name = "web_search",
  description = "Search the web for information",
  parameters = List(
    ToolParameter("query", "Search query", DataType.StringType),
    ToolParameter("num_results", "Number of results", DataType.IntegerType, Some(5))
  ),
  returnType = DataType.StringType
)
```

### File Operations

```scala
val readFileTool = ToolSpec(
  name = "read_file",
  description = "Read contents of a file",
  parameters = List(
    ToolParameter("path", "File path", DataType.StringType)
  ),
  returnType = DataType.StringType
)

val writeFileTool = ToolSpec(
  name = "write_file",
  description = "Write content to a file",
  parameters = List(
    ToolParameter("path", "File path", DataType.StringType),
    ToolParameter("content", "Content to write", DataType.StringType)
  ),
  returnType = DataType.StringType
)
```

### Database Query

```scala
val queryDbTool = ToolSpec(
  name = "query_database",
  description = "Execute a SQL query",
  parameters = List(
    ToolParameter("query", "SQL query to execute", DataType.StringType),
    ToolParameter("database", "Database name", DataType.StringType, Some("main"))
  ),
  returnType = DataType.StringType
)
```

## Best Practices

1. **Clear descriptions** - Help the model understand when to use tools
2. **Validate inputs** - Check parameters before execution
3. **Handle errors** - Return meaningful error messages
4. **Limit side effects** - Be careful with write operations
5. **Log tool calls** - Track usage for debugging
6. **Set timeouts** - Prevent hanging on slow operations

# AWS Bedrock Integration

Connect to AWS Bedrock for Claude model access.

## Setup

### Dependencies

```scala
libraryDependencies += "org.wvlet.uni" %% "uni-bedrock" % "2026.1.0"
```

### AWS Credentials

Ensure AWS credentials are configured:

```bash
# Environment variables
export AWS_ACCESS_KEY_ID=your-access-key
export AWS_SECRET_ACCESS_KEY=your-secret-key
export AWS_REGION=us-east-1

# Or use AWS CLI profile
aws configure
```

## Creating a Bedrock Chat Model

`BedrockChat` requires an `LLMAgent` and a `BedrockClient`:

```scala
import wvlet.uni.agent.{LLMAgent, LLM}
import wvlet.uni.agent.chat.bedrock.{BedrockChat, BedrockClient, BedrockConfig}

val agent = LLMAgent(
  name = "assistant",
  description = "Bedrock-powered assistant",
  model = LLM.Bedrock.Claude3_7Sonnet_20250219V1_0
)

val bedrockClient = BedrockClient()
val bedrockChat = BedrockChat(agent, bedrockClient)
```

### With Custom Configuration

Configure the underlying `BedrockConfig` for region and credentials:

```scala
import software.amazon.awssdk.regions.Region

val config = BedrockConfig()
  .withRegion(Region.US_WEST_2)

val bedrockClient = BedrockClient(config)
val bedrockChat = BedrockChat(agent, bedrockClient)
```

### Custom Credentials

```scala
import software.amazon.awssdk.auth.credentials.*

val credentialsProvider = StaticCredentialsProvider.create(
  AwsBasicCredentials.create("key", "secret")
)

val config = BedrockConfig()
  .withCredentials(credentialsProvider)

val bedrockClient = BedrockClient(config)
```

## Using with LLMAgent

```scala
import wvlet.uni.agent.{LLMAgent, LLM}
import wvlet.uni.agent.chat.ChatMessage.AIMessage
import wvlet.uni.agent.chat.bedrock.{BedrockChat, BedrockClient}

// Create agent
val agent = LLMAgent(
  name = "assistant",
  description = "Bedrock-powered assistant",
  model = LLM.Bedrock.Claude3_7Sonnet_20250219V1_0
).withSystemPrompt("You are a helpful assistant.")

// Create Bedrock chat model
val bedrockClient = BedrockClient()
val bedrockChat = BedrockChat(agent, bedrockClient)

// Create session and chat
val session = agent.newSession(bedrockChat)
val response = session.chat("Hello!")

// Access the response text
response.messages.collect { case ai: AIMessage => ai.text }.lastOption.foreach(println)
```

## Available Models

| Model | Identifier |
|-------|------------|
| Claude 4 Sonnet | `LLM.Bedrock.Claude4Sonnet_20250514V1_0` |
| Claude 4 Opus | `LLM.Bedrock.Claude4Opus_20250514V1_0` |
| Claude 3.7 Sonnet | `LLM.Bedrock.Claude3_7Sonnet_20250219V1_0` |
| Claude 3.5 Sonnet v2 | `LLM.Bedrock.Claude3_5Sonnet_20241022V2_0` |
| Claude 3.5 Haiku | `LLM.Bedrock.Claude3_5Haiku_20241022V1_0` |
| Claude 3 Haiku | `LLM.Bedrock.Claude3Haiku_20240307V1_0` |

## Configuration Options

### Region

```scala
import software.amazon.awssdk.regions.Region

BedrockConfig().withRegion(Region.EU_WEST_1)
```

### Credentials Provider

```scala
import software.amazon.awssdk.auth.credentials.*

val credentialsProvider = StaticCredentialsProvider.create(
  AwsBasicCredentials.create("key", "secret")
)

BedrockConfig().withCredentials(credentialsProvider)
```

### Custom Async Client Configuration

```scala
BedrockConfig().withAsyncClientConfig { builder =>
  builder.overrideConfiguration(/* ... */)
}
```

## Streaming Responses

```scala
import wvlet.uni.agent.chat.{ChatObserver, ChatEvent, ChatResponse}

val observer = new ChatObserver:
  def onPartialResponse(event: ChatEvent): Unit =
    event match
      case ChatEvent.PartialResponse(text) => print(text)
      case _                               => ()

  def onComplete(response: ChatResponse): Unit =
    println()

  def onError(e: Throwable): Unit =
    println(s"Error: ${e.getMessage}")

val response = session.chat("Tell me a story", observer)
```

## Error Handling

```scala
import wvlet.uni.agent.core.{AIException, StatusCode}

try
  val response = session.chat("Hello")
catch
  case e: AIException =>
    e.statusCode match
      case StatusCode.RESOURCE_EXHAUSTED => println("Rate limited, retry later")
      case StatusCode.INVALID_MODEL_CONFIG => println("Invalid model configuration")
      case _ => println(s"Error: ${e.message}")
```

## Example: Complete Application

```scala
import wvlet.uni.agent.*
import wvlet.uni.agent.chat.*
import wvlet.uni.agent.chat.ChatMessage.AIMessage
import wvlet.uni.agent.chat.bedrock.{BedrockChat, BedrockClient, BedrockConfig}
import software.amazon.awssdk.regions.Region

object BedrockExample:
  def main(args: Array[String]): Unit =
    // Define agent
    val agent = LLMAgent(
      name = "coder",
      description = "Scala coding assistant",
      model = LLM.Bedrock.Claude3_7Sonnet_20250219V1_0
    )
      .withSystemPrompt("""
        You are an expert Scala 3 programmer.
        Write clean, idiomatic code with explanations.
      """)
      .withTemperature(0.3)
      .withMaxOutputTokens(2048)

    // Initialize Bedrock
    val config = BedrockConfig().withRegion(Region.US_EAST_1)
    val bedrockClient = BedrockClient(config)
    val bedrockChat = BedrockChat(agent, bedrockClient)

    // Create session
    val session = agent.newSession(bedrockChat)

    // Chat
    val response = session.chat(
      "Write a function to calculate the nth Fibonacci number"
    )

    response.messages.collect { case ai: AIMessage => ai.text }.lastOption.foreach(println)
```

## IAM Permissions

Required IAM permissions for Bedrock:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel",
        "bedrock:InvokeModelWithResponseStream"
      ],
      "Resource": "arn:aws:bedrock:*:*:model/*"
    }
  ]
}
```

## Best Practices

1. **Use environment variables** for credentials
2. **Select appropriate region** for latency
3. **Handle rate limits** with retry logic
4. **Monitor costs** - Bedrock charges per token
5. **Use streaming** for better UX on long responses

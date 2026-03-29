# Agent Framework

uni-agent provides a framework for building LLM-powered agents with tool calling, chat sessions, and provider integrations.

## Overview

| Component | Description |
|-----------|-------------|
| [LLM Agent](./llm-agent) | Agent configuration and creation |
| [Chat Sessions](./chat-session) | Conversation management |
| [Tool Integration](./tools) | Function calling support |
| [AWS Bedrock](./bedrock) | Bedrock chat model integration |

## Quick Start

```scala
import wvlet.uni.agent.*
import wvlet.uni.agent.chat.*
import wvlet.uni.agent.chat.ChatMessage.AIMessage
import wvlet.uni.agent.chat.bedrock.BedrockRunner

// Define an agent
val agent = LLMAgent(
  name = "assistant",
  description = "A helpful assistant",
  model = LLM.Bedrock.Claude3_7Sonnet_20250219V1_0
).withSystemPrompt("You are a helpful assistant.")
 .withTemperature(0.7)

// Create a Bedrock runner and session
val runner = BedrockRunner(agent)
val session = agent.newSession(runner)
val response = session.chat("Hello, how are you?")

// Access the response text from the last AI message
response.messages.collect { case ai: AIMessage => ai.text }.lastOption.foreach(println)
```

## Key Concepts

### LLMAgent

Defines an agent with:
- Name and description
- LLM model selection
- System prompt
- Tools (functions)
- Model configuration

### ChatSession

Manages conversations:
- Send messages
- Continue conversations
- Stream responses
- Handle tool calls

### ToolSpec

Defines callable functions:
- Name and description
- Parameter definitions
- Return type

### ChatMessage Types

| Type | Description |
|------|-------------|
| `SystemMessage` | System instructions |
| `UserMessage` | User input |
| `AIMessage` | LLM response (may include tool calls) |
| `AIReasoningMessage` | Reasoning process output |
| `ToolResultMessage` | Result of tool execution |

## Modules

```scala
// Core agent framework
libraryDependencies += "org.wvlet.uni" %% "uni-agent" % "2026.1.0"

// AWS Bedrock integration
libraryDependencies += "org.wvlet.uni" %% "uni-bedrock" % "2026.1.0"
```

## Package

```scala
import wvlet.uni.agent.*
import wvlet.uni.agent.chat.*
import wvlet.uni.agent.tool.*
import wvlet.uni.agent.runner.*
```

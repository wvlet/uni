# 11. Building LLM Agents with uni-agent

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

`uni-agent` is Uni's LLM agent framework: a small set of abstractions
for chat sessions, tool calling, and model orchestration. This
chapter builds a working agent from the ground up.

Concepts introduced:

- What an "agent" is in this framework, as opposed to a raw chat
  completion.
- `ChatSession` for turn-taking conversations with persistent state.
- Registering Scala functions as tools the model can call.
- Switching between providers (AWS Bedrock, etc.) without changing
  your business logic.
- Testing agent code — what is deterministic, what is not, and how to
  assert on either.

## Reference you can read now

- [Agent Framework — Overview](/agent/)
- [LLM Agent](/agent/llm-agent)
- [Chat Sessions](/agent/chat-session)
- [Tool Integration](/agent/tools)
- [AWS Bedrock](/agent/bedrock)

[← 10. Cross-Platform Development](./ch10-00-cross-platform) | [Next → 12. Testing with UniTest](./ch12-00-testing)

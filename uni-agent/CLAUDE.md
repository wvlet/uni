# uni-agent

LLM agent interface, orchestration, and tool integration. JVM only.

## Constraints

- Depends on `uni` (JVM) and airframe (DI + codec)
- JVM-only module — no cross-platform requirement
- Keep agent abstractions generic; provider-specific code goes in `uni-agent-bedrock`

## Testing

- Unit tests run without AWS credentials
- Integration tests go in `uni-integration-test/` (requires AWS creds)

```bash
./sbt agent/test                          # Unit tests
./sbt "agent/testOnly *LLMAgentTest"      # Specific test
./sbt integrationTest/test                # Integration (needs AWS creds)
```

Use `Design` for wiring test dependencies; avoid mocks.

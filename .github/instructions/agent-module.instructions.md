---
applyTo: "uni-agent/**/*.scala,uni-agent-bedrock/**/*.scala"
---

# Agent Module Development Guide

## Module Structure

- `uni-agent/`: Core agent interfaces (JVM only, depends on airframe)
- `uni-agent-bedrock/`: AWS Bedrock integration (JVM only, depends on AWS SDK)
- `uni-integration-test/`: Integration tests requiring AWS credentials

## Dependencies

- Agent modules are JVM-only; do not use cross-platform project settings
- `uni-agent` depends on `uni` (JVM) and airframe for DI and codec
- `uni-agent-bedrock` adds AWS SDK bedrockruntime; keep AWS SDK usage isolated here

## Testing

- Unit tests in `uni-agent` and `uni-agent-bedrock` run without AWS credentials
- Integration tests requiring AWS credentials go in `uni-integration-test/`
- Run integration tests with: `./sbt integrationTest/test`
- Use `Design` for wiring test dependencies; avoid mocks

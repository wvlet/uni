# uni-agent-bedrock

AWS Bedrock integration for uni-agent. JVM only.

## Constraints

- Depends on `uni-agent` and AWS SDK bedrockruntime
- Keep all AWS SDK usage isolated to this module
- SLF4J is redirected to airframe-log via `slf4j-jdk14`

## Testing

- Unit tests run without AWS credentials
- Integration tests requiring real AWS calls go in `uni-integration-test/`

```bash
./sbt bedrock/test                        # Unit tests
./sbt integrationTest/test                # Integration (needs AWS creds)
```

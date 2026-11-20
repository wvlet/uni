# More AWS credential sources for wvlet.uni.storage

## Goals

- `ObjectStore.s3()` with `AwsCredentialsProvider.default` finds credentials on EKS (IRSA), with AWS IAM Identity Center (SSO) profiles, with `credential_process` tools, and with role profiles (`role_arn` + `source_profile`), so users stop copying keys into `withCredentials(...)`.
- The chain order and per-profile precedence match the AWS SDKs, and the docs say what they are.

## Background

- PR #1025 shipped environment, static profile keys, the ECS/EKS Pod Identity container endpoint, and IMDSv2; the ADR lists SSO, web identity, and `credential_process` as left out.
- Providers read the environment through `StorageEnv`, a fixed `System.getenv` / `process.env` lookup. Tests can't set environment variables, so the new paths need an injectable environment to point at fake endpoints and temp profile files.
- `wvlet.uni.io.IO.run` runs processes on the JVM and Native (`ProcessBuilder`) but throws on all of Scala.js, including Node.js.
- STS answers in XML (the private `S3Xml` reader handles it); the SSO portal and SSO-OIDC answer in JSON. The SigV4 signer is in place (needed for `AssumeRole`). `wvlet.uni.http.Sha1` is `private[http]`; it widens to `private[uni]` for SSO cache file names.
- The chain swallows each provider's exception at `debug` and reports only the provider names, so today a present-but-broken source gives no hint of the fix.
- `IO.call` puts the full command line and stderr into `NonZeroExitCodeException`; a credential command can carry secrets.
- On Node.js an in-process `Http.server` can't answer a sync client in the same thread (`Atomics.wait` blocks the event loop, see `adr/2026-05-14-nodejs-sync-http.md`).
- `Cached` already refreshes 5 minutes before `expirationMillis`; every new source returns temporary credentials with an expiration, so caching needs no new mechanism.

## Design

Lane: Standard — credentials are an always-gate area, and the change adds new user-visible behaviour (new sources, token refresh, process execution).

User flow:
- An EKS pod with IRSA, a laptop after `aws sso login`, or a profile with `credential_process` calls `ObjectStore.s3()` and it just works; nothing to configure in code.
- When a source is present but unusable (SSO token expired and can't be refreshed, STS rejects the token, the process exits non-zero), the chain's `NoCredentials` error lists each failed source with a cleaned reason and the fix (e.g. "run `aws sso login --profile dev`"), never a token, secret, command line, or response body. This needs a small `Chain` change: collect each provider's reason instead of only logging it.
- To pick one explicitly: `AwsCredentialsProvider.profile("dev")` (now covers every profile kind) or the new `AwsCredentialsProvider.webIdentity`.

Decisions:
- **Chain order** follows Java SDK v2's `DefaultCredentialsProvider` (minus JVM system properties): environment → web identity from env (`AWS_WEB_IDENTITY_TOKEN_FILE` + `AWS_ROLE_ARN`) → profile → container → IMDS. The docs note where botocore differs (it tries a profile `role_arn` before env web identity).
- **Inside a profile**, first match wins, in Java SDK v2's `ProfileCredentialsUtils` order: `role_arn` + `web_identity_token_file` → SSO (`sso_session`, or legacy `sso_start_url` + `sso_account_id` + `sso_role_name`) → `role_arn` + `source_profile` / `credential_source` (`Environment`, `Ec2InstanceMetadata`, `EcsContainer`) → `credential_process` → static keys. A profile's keys merge the credentials and config files, the credentials file winning.
- **Role parameters**: `RoleSessionName` from `role_session_name` / `AWS_ROLE_SESSION_NAME`, else `uni-<millis>`; `external_id` and `duration_seconds` are honoured. `mfa_serial` profiles fail with a clear "not supported" (no prompt in a library).
- **Role chaining**: `source_profile` is resolved recursively, with a loop check; the source's own static keys are used when a profile sources itself, as the SDKs do. `AssumeRole` is a SigV4-signed STS POST; `AssumeRoleWithWebIdentity` is unsigned and sends the token in the form body, never in the URL.
- **Endpoints**: STS is regional `sts.<region>.amazonaws.com` when a region is known (`AWS_REGION`, `AWS_DEFAULT_REGION`, profile `region`), else global. SSO portal and SSO-OIDC use `sso_region` (`portal.sso.<sso_region>.amazonaws.com`, `oidc.<sso_region>.amazonaws.com`). Only the service-specific `AWS_ENDPOINT_URL_STS`, `AWS_ENDPOINT_URL_SSO`, and `AWS_ENDPOINT_URL_SSO_OIDC` override them; the generic `AWS_ENDPOINT_URL` does not (unlike the SDKs), because Uni users often point it at R2 or MinIO, and following it would send web-identity and SSO tokens to a non-AWS host.
- **Web identity token file** is re-read on every refresh, since EKS rotates it.
- **SSO token refresh**: for `sso_session` profiles, refresh an expired (or <5 min left) cached access token through SSO-OIDC `CreateToken` with the cached refresh token, and write the new token back to the cache file atomically, as botocore and Java SDK v2 do. The file is `~/.aws/sso/cache/<sha1 hex>.json`, hashing the `sso_session` name (legacy profiles: the `sso_start_url`). The temp file is made owner-only (0600) before the rename, since it holds the refresh token and client secret. Writing back matters: refresh tokens rotate, so refreshing only in memory could leave the CLI's cached token stale. Legacy `sso_start_url` profiles have no refresh token; expired means "run `aws sso login`".
- **`credential_process`**: run through the platform shell (`sh -c`, or `cmd.exe /C` on Windows), as Java SDK v2 and the Go SDK do, so quoted paths and arguments work. Parse the `Version: 1` JSON; no `Expiration` means the credentials never refresh. The provider calls `IO.run` and checks the exit code itself (not `IO.call`, whose exception carries the command line and stderr); a non-zero exit fails with the profile name and exit code only.
- **Process execution on Node.js**: implement `IO.run` / `IO.call` on Node through `child_process.spawnSync`, loading `child_process` at runtime (`process.getBuiltinModule`, falling back to `require`) as the Node sync-HTTP ADR requires, never a static import that would break browser bundles; uni-core gets its own small helper for this, so `credential_process` uses `wvlet.uni.io` on all three runtimes. Browsers keep throwing; `IO.spawn` stays JVM/Native-only. This is a small uni-core improvement that also helps other Node users.
- **Injectable environment**: providers take a private `name => Option[String]` lookup (defaulting to `StorageEnv.get`); tests pass a map pointing `HOME`/`AWS_CONFIG_FILE` at temp dirs and endpoint variables at a local `Http.server`. Not public API.
- **Caching**: every new provider sits behind the existing `Cached` wrapper; profile caches now expire for temporary credentials (static keys still cache until the process ends).
- **Secret hygiene**: no token, secret, or response body in logs or exception messages; `AwsCredentials.toString` masking unchanged; STS/SSO errors surface only the AWS error code and message.
- **Tests**: fake STS, SSO portal, and SSO-OIDC endpoints on `Http.server`; profile and SSO-cache files in temp dirs; a real `credential_process` script; precedence, chaining loops, expiry/refresh, and write-back. The fake-endpoint tests run on JVM and Native; Node tests cover profile parsing and `IO.run` (a same-thread server would deadlock the Node sync client).
- **Docs**: `docs/core/object-storage.md` (sources, order, precedence), `for-ai-agents.md`, the plugin skill's `references/storage.md` with a version bump, `docs/core/io.md` (Node support for `IO.run`), and an update to the S3 ADR.

## Alternatives and Why Not?

- **SSO: no refresh, fail with "run `aws sso login`".** Simpler and never writes user files, but `sso_session` access tokens last about an hour, so long jobs would fail hourly while the AWS CLI keeps working.
- **SSO: refresh in memory only.** Avoids writing the cache, but rotated refresh tokens can strand the CLI's copy.
- **`credential_process` split into argv (botocore style) instead of a shell.** No shell injection surface, but the command comes from the user's own config file, and Java/Go SDK users expect shell quoting to work.
- **Node process support private to `storage`.** Keeps uni-core unchanged, but duplicates what `IO.run` already promises on the other platforms.
- **Public `AwsCredentialsProvider.assumeRole(source, roleArn)` builder.** Useful, but not needed for the profile path; a follow-up.

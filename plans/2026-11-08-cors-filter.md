# CORS filter

## Goals

- Add a CORS filter to uni, so a browser app on another origin can call a uni server. The filter handles preflight `OPTIONS` and adds `Access-Control-*` headers to responses.
- Unblock td-llm-proxy: `CorsConfig.scala` is its last use of airframe-http's `wvlet.airframe.http.filter.Cors`. The proxy should need only a policy value, not its own preflight wrapper.
- Keep uni's secure defaults ([ADR: HTTP server secure defaults](../adr/2026-10-14-http-server-secure-defaults.md)): no origin is allowed unless configured, and credentials are never paired with a `*` origin.

## Background

- uni has the header names (`HttpHeader.scala:94-101`) but no CORS filter, policy, or preflight handling. No doc or ADR rules CORS out. `docs/http/server.md:413` names "CORS headers" as an example of a filter you write yourself, and the intro blog post (`docs/blog/2026-10-27-introducing-uni.md:199`) says the server "does not add CORS headers".
- The parts are in place:
  - `RxHttpFilter` (`RxHttpHandler.scala:40`).
  - `HttpServerConfig.withFilter` (`HttpServerConfig.scala:72`). Netty, Node.js, and Native all pass `config.filters` on to the handler (`NettyServerProvider.scala:36`, `.js`/`.native` `HttpCompat.scala`), so a filter in `uni/src/main` works on all three.
  - `HttpServerSecurity.normalizeOrigin` (`HttpServerSecurity.scala:143`), used by the WebSocket origin check.
- The [scoped router filters ADR](../adr/2026-10-22-scoped-router-filters.md) already plans for this case: a router-wide filter runs before route matching, so it sees a preflight to any path.
- Netty runs the host check before any filter (`NettyRequestHandler.scala:102`). WebSocket upgrades skip server filters (`NettyRequestHandler.scala:134`) and use `withAllowedOrigins` instead, which is right: CORS does not apply to WebSockets.
- What the proxy does with Airframe today:
  - It allows origins by regex: TD domains over HTTPS, and localhost.
  - It allows `GET, POST, OPTIONS`, echoes the requested headers, and sets `supportsCredentials = true`.
  - A preflight from a disallowed origin gets 403. An ordinary request from a disallowed origin is handled, without CORS headers.
  - An empty `CORS_ALLOWED_ORIGINS` turns CORS off.

## Design

Lane: **Standard**. It adds public API at an HTTP security boundary (which origins may read responses). The user approved this note ("implement it with auto-pr"). It adds one new public type pair in `uni`, docs, and tests, with no backend change.

### API (`uni/src/main/scala/wvlet/uni/http/Cors.scala`)

```scala
case class CorsPolicy(
    allowsOrigin: String => Boolean = _ => false,   // deny by default
    allowAnyOrigin: Boolean = false,                 // set by withAllowedOrigins("*")
    allowedMethods: Seq[HttpMethod] = Seq(GET, HEAD, POST),
    allowedHeaders: Option[Seq[String]] = None,      // None: echo Access-Control-Request-Headers
    exposedHeaders: Seq[String] = Nil,
    allowCredentials: Boolean = false,
    maxAgeSeconds: Option[Int] = Some(600)
):
  def withAllowedOrigins(origins: String*): CorsPolicy     // exact match, normalized; "*" = any
  def withOriginFilter(f: String => Boolean): CorsPolicy   // regexes, suffix rules, env config
  def withAllowedMethods(methods: HttpMethod*): CorsPolicy
  def withAllowedHeaders(names: String*): CorsPolicy
  def noAllowedHeaders(): CorsPolicy                     // back to echoing
  def withExposedHeaders(names: String*): CorsPolicy
  def withAllowCredentials(b: Boolean): CorsPolicy
  def withMaxAge(seconds: Int): CorsPolicy
  def noMaxAge(): CorsPolicy

class CorsFilter(policy: CorsPolicy) extends RxHttpFilter
```

- `CorsFilter` checks the policy when it is constructed. `allowAnyOrigin` together with `allowCredentials` throws `IllegalArgumentException`. Browsers reject that pair, and quietly echoing the origin would grant every site credentialed access.
- Origins in `withAllowedOrigins` are compared after `HttpServerSecurity.normalizeOrigin`: trimmed, trailing slash dropped, lower-cased. `withOriginFilter` gets the raw `Origin` value. The proxy passes `o => patterns.exists(_.matches(o))`.

### Behavior

1. **No `Origin` header.** The request is not a CORS request. Pass it to `next` unchanged.
2. **Preflight** (`OPTIONS`, with `Origin` and `Access-Control-Request-Method`). Answer it without calling `next`:
   - If the origin, the requested method, and every requested header (when `allowedHeaders` is set) are allowed, return `204`. The response carries:
     - `Access-Control-Allow-Origin`: the request origin, or `*` for any origin without credentials.
     - `Access-Control-Allow-Methods` and `Access-Control-Allow-Headers`: the allowed list, or the requested headers echoed back.
     - `Access-Control-Max-Age`.
     - `Access-Control-Allow-Credentials: true`, when credentials are allowed.
     - `Vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers`.
   - Otherwise return `403` with no CORS headers. This matches the proxy's wrapper.
3. **`OPTIONS` without `Access-Control-Request-Method`.** Treat it as an ordinary request.
4. **Ordinary request from an allowed origin.** Call `next`, then add `Access-Control-Allow-Origin`, plus `Allow-Credentials` and `Expose-Headers` when configured. Append `Origin` to any `Vary` header already on the response; don't replace it. Server-Sent Events responses get the headers before the first event is sent.
5. **Ordinary request from a disallowed origin.** Call `next` and add no headers, so the browser blocks the page from reading the response. Rejecting with 403 wouldn't add security, because a server must not rely on CORS to block requests that need no preflight.
6. **Failed handler `Rx`.** Leave it unchanged, so an outer `HttpAccessLogFilter` still logs the exception class. The server renders the generic 500 without CORS headers, so the browser reports a CORS error rather than a 500. This is a documented limitation, the same as `server.md:413` already describes for decorating filters. A user who wants CORS headers on errors puts an error-recovering filter inside `CorsFilter`.

### Placement

Recommend a server-level filter, which runs on every backend and sees requests to unmatched paths:

```scala
Http.server
  .withFilter(accessLog)                       // outermost
  .withFilter(CorsFilter(CorsPolicy().withAllowedOrigins("https://app.example.com")))
  .withRxHandler(RouterHandler(router))
```

The proxy's `CorsConfig.filter`, including its wrapper, becomes a `CorsPolicy` built with `withOriginFilter(isAllowedOrigin)`, `withAllowedMethods(GET, POST, OPTIONS)`, and `withAllowCredentials(true)`. It keeps the env-var parsing and the "empty disables" rule in its own code.

### Docs

- `docs/http/server.md`: add a `### CORS` section under Filters, covering placement, the credentials rule, and the error limitation. Point the line at `:413` to it.
- `docs/guide/for-ai-agents.md`: add `wvlet.airframe.http.filter.Cors` → `wvlet.uni.http.CorsFilter` / `CorsPolicy` to the Airframe→Uni table. Mirror it in `plugins/uni/skills/uni/`, check that the snippet compiles with `docSnippets/test`, and bump the plugin `version`.
- Leave the dated blog post as written.

### Tests

- `uni/src/test/scala/wvlet/uni/http/CorsFilterTest.scala`, shared across platforms. It calls the filter directly with a recording `RxHttpHandler`, with no server. Cases:
  - no `Origin` → passed through untouched;
  - allowed preflight → 204 with every header, and `next` is not called;
  - preflight from a disallowed origin, with a disallowed method, or with a disallowed header → 403;
  - `OPTIONS` without a request method → `next`;
  - allowed ordinary request → headers added, and `Vary` appended to an existing value;
  - disallowed ordinary request → handled, with no CORS headers;
  - `*` without credentials → `Allow-Origin: *`;
  - `*` with credentials → throws at construction;
  - echoed headers versus a fixed header list;
  - origin normalization (case, trailing slash);
  - a failed `Rx` stays failed.
- One uni-netty test: a preflight to a path with no route gets 204 through `withFilter`, which shows the filter runs before route matching.

No ADR. The design decisions are in this note, and none of them changes an existing contract.

## Alternatives and Why Not?

- **Port Airframe's `Cors.Policy` function signatures as they are** (`allowsOrigin: String => Option[String]`, and so on). This would ease migration, but the functions can return anything, including `*` with credentials. A plain predicate plus lists is easier to check and covers the proxy.
- **A `withCors(policy)` setting on `HttpServerConfig`.** It reads well, but it needs a backend-neutral place to run, which is exactly what `withFilter` is. It would also be a second way to do the same thing. It can be added later as a one-line shortcut for `withFilter`.
- **Turn a failed `Rx` into a 500 that carries CORS headers.** The browser would then see the real status, but outer filters would lose the exception (`HttpAccessLogFilter` records `exception_class`), and the filter doesn't know the server's `detailedErrors` setting.
- **Allow same-origin requests implicitly, as the WebSocket check does.** Same-origin requests don't need CORS headers, and implicit allowance would make the policy harder to read. Explicit lists only.
- **Return 403 for ordinary requests from a disallowed origin.** It would break non-browser clients that send `Origin`, and it doesn't stop requests that need no preflight. Airframe and the proxy don't do it either.

## Decided

- `allowedHeaders` defaults to echoing the requested headers, as the proxy does. `withAllowedHeaders(...)` restricts them.
- `Router.filter[CorsFilter]` is out of scope. Use `withFilter`.

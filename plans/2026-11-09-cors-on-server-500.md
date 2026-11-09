# CORS headers on the server's generic 500

## Goals

- When a handler fails without recovering, a cross-origin browser app should see the 500, not a CORS error. Today `CorsFilter` passes the failed `Rx` through, and the server renders its generic 500 with no `Access-Control-*` headers (known limitation in #886).
- Keep the failure visible to operators: the server log gets the stack trace, and an outer `HttpAccessLogFilter` gets `exception_class`.

## Background

- All three backends render the generic 500 through `HttpServerSecurity.errorResponse(e, detailedErrors)` after every filter has returned, so no filter can add headers to it.
- airframe-http had the same gap: its `RPCResponseFilter` turns exceptions into responses outside the user filters, so its `CorsFilter` never saw them either.
- #886's docs told users to put an error-recovering filter inside `CorsFilter`. Every app would write the same filter, with the same loss of exception details in the access log.
- `HttpAccessLogFilter.addEntry` lets an inner filter add fields to the access log entry.

## Design

Lane: **Express**. A fix with a clear diagnosis, and no new public API. It doesn't change which origins are allowed.

- `CorsFilter` recovers a handler failure (a failed `Rx` or a synchronous throw) into the generic 500, then adds the usual CORS headers. It does this only for requests from an allowed origin.
- Before recovering, it logs the exception with its stack trace, and records `exception_class` and `exception_message` with `HttpAccessLogFilter.addEntry`.
- The 500 body is always `Internal Server Error`, even with `withDetailedErrors`. That setting is for development only, and the server log has the full exception.
- Requests with no `Origin` or from a disallowed origin keep today's behavior: the failure reaches the server unchanged. A browser can't read a disallowed origin's response anyway.
- No changes to the server backends.
- Docs: replace the limitation in the `CorsFilter` scaladoc, `docs/http/server.md`, and the `uni` skill's http-server reference with the new behavior. Bump the plugin version.
- Tests: unit tests for allowed and other origins, and a Netty server test checking the 500's CORS headers and the access log's exception fields.

## Alternatives and Why Not?

- Keep #886's advice (a user-written recovering filter): every app has to remember it and write the same code.
- A server-attached holder on which filters register headers for the server's 500: keeps `detailedErrors` and the server log as they are, but adds hidden coupling to all three backends.
- Build the 500 in a filter inside all user filters: reverses the ADR decision that exceptions reach filters as failures.

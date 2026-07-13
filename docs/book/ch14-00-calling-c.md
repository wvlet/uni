# 14. Calling C from Scala Native

Compile a Uni app with Scala Native ([Chapter 11](./ch11-00-cross-platform))
and you get a real native binary — and with it, direct access to the
entire C ecosystem: `libcurl`, `sqlite`, `openssl`, anything with a C
ABI. No JNI, no subprocess, no serialization across a boundary. You call
a C function the way you call a Scala one, once you've declared it.

This chapter is the Scala-Native counterpart to
[Chapter 12](./ch12-00-npm-facades): there you wrote a facade for a
JavaScript module; here you write one for a C library.

## An `@extern` binding

To call C, you declare the functions you need in an `@extern` object and
tell the linker which library provides them. Here is the shape, taken
from how Uni's own Native HTTP client binds `libcurl`:

```scala
import scala.scalanative.unsafe.*

@link("curl")
@extern
object curl:
  @name("curl_easy_init")
  def easyInit(): Ptr[Byte] = extern

  @name("curl_easy_setopt")
  def easySetOpt(handle: Ptr[Byte], option: CInt, value: CString): CInt = extern

  @name("curl_easy_perform")
  def easyPerform(handle: Ptr[Byte]): CInt = extern

  @name("curl_easy_strerror")
  def easyStrError(code: CInt): CString = extern

  @name("curl_easy_cleanup")
  def easyCleanup(handle: Ptr[Byte]): Unit = extern

// Option codes come from curl.h; 10002 is CURLOPT_URL
val CURLOPT_URL: CInt = 10002
```

Four pieces, mirroring the JavaScript facade from Chapter 12:

- **`@link("curl")`** tells the linker to link `libcurl` into the binary —
  the native equivalent of `npm install`.
- **`@extern`** marks the object as a set of declarations whose bodies
  live in that C library.
- **`@name("curl_easy_init")`** maps a Scala method to the actual C
  symbol, so your Scala name can be idiomatic while the binding still
  finds `curl_easy_init`.
- **`= extern`** is the body you don't write, because C already did.

You declare only the handful of functions you call, exactly as with a JS
facade. `libcurl` exports hundreds; a working HTTP client needs a dozen.
C constants (like `CURLOPT_URL`) don't cross the boundary at all — you
copy their values out of the header file into ordinary Scala `val`s.

## C types in Scala

C has its own types, and Scala Native gives you a vocabulary for them
from `scala.scalanative.unsafe`:

| C | Scala Native |
|---|--------------|
| `int` | `CInt` |
| `char*` (string) | `CString` |
| `void*` / opaque pointer | `Ptr[Byte]` |
| `size_t` | `CSize` |
| a `struct` | `CStruct2[A, B]`, `CStruct3[...]`, … |

A `CString` is a pointer to bytes, not a Scala `String` — the two are
different worlds, and you convert at the boundary. An opaque C handle
(like libcurl's `CURL*`) is just a `Ptr[Byte]` you pass back to the
library; you never look inside it.

## Crossing the string boundary

Two helpers move strings across. `fromCString` reads a C string into a
Scala `String`. `toCString` does the reverse — but it has to allocate
memory somewhere, and where decides how long the result lives.

For a string you pass *into* a C call and don't need afterward, allocate
it in a `Zone`: a scoped arena that frees everything in it when the block
ends.

```scala
import scala.scalanative.unsafe.*

Zone.acquire { implicit z =>
  val handle = curl.easyInit()
  curl.easySetOpt(handle, CURLOPT_URL, toCString("https://example.com"))
  curl.easyPerform(handle)
}   // every toCString allocation in this zone is freed here
```

Reading a result back out uses `fromCString`:

```scala
val message: String = fromCString(curl.easyStrError(code))
```

The `Zone` is the key idea for memory safety at the boundary: temporary C
allocations get a clear lifetime tied to a block, so they can't leak and
can't be used after they're freed. (You'll see the one case where `Zone`
is *wrong* — a value handed back to C that must outlive the call — in
[Chapter 15](./ch15-00-exposing-native).)

## A Uni service over the binding

The binding compiles, but nothing about it feels like the code you've
written in the rest of this book: it traffics in pointers and integer
return codes, and a failure is a `CInt` you have to remember to check.
Don't spread that through your application. Wrap the binding in one
ordinary Scala class, and let Uni take over from there.

```scala
import scala.scalanative.unsafe.*
import wvlet.uni.log.LogSupport

class CurlError(code: Int, message: String)
    extends Exception(s"curl error ${code}: ${message}")

class Downloader extends LogSupport with AutoCloseable:
  private val handle = curl.easyInit()

  def fetch(url: String): Unit = Zone.acquire { implicit z =>
    curl.easySetOpt(handle, CURLOPT_URL, toCString(url))
    val code = curl.easyPerform(handle)
    if code != 0 then
      val message = fromCString(curl.easyStrError(code))
      error(s"GET ${url} failed: ${message}")
      throw CurlError(code, message)
    debug(s"GET ${url} succeeded")
  }

  override def close(): Unit = curl.easyCleanup(handle)
```

Notice what this class is: the *only* place where both vocabularies
appear. Above it, pure Scala (`fetch(url: String)`, exceptions,
`LogSupport`); below it, pure C (`Ptr`, `CString`, return codes). The
mixing of the two worlds is the wrapper's entire job, and it happens
here and nowhere else — everything C-shaped is translated at this edge:

- **Error codes become exceptions.** C reports failure by returning a
  nonzero `CInt`; the compiler doesn't force anyone to look at it. The
  wrapper checks once, converts the code to a message with
  `easyStrError`, and throws — callers can't silently ignore a failed
  download.
- **`LogSupport` works here like everywhere else.** The logging you
  learned in [Chapter 5](./ch05-00-logging) is pure Scala, so it
  cross-compiles to Native unchanged — `error` and `debug` at the C
  boundary behave exactly as they did on the JVM. When a native binary
  misbehaves in production, the log line with curl's own error message
  is what you'll want.
- **Callers see `fetch(url: String)`.** No `Ptr`, no `CString`, no
  `Zone`. The rest of the application cannot tell — and should not care —
  that a C library sits underneath.

## Give the C handle a lifecycle

There is one problem left: `handle` is C memory. Scala's garbage
collector doesn't know about it, so nothing frees it unless `close()`
runs. That is precisely what `Design`'s lifecycle hooks from
[Chapter 3](./ch03-00-design) are for:

```scala
import wvlet.uni.design.Design

val design = Design
  .newDesign
  .bindSingleton[Downloader]
  .onShutdown(_.close())

design.build[Downloader] { downloader =>
  downloader.fetch("https://example.com")
}   // session ends here: close() runs, curl_easy_cleanup frees the handle
```

The session guarantees `close()` runs exactly once, when the session
ends — the same deterministic teardown you'd want for a database
connection, applied to a C resource. And because `Downloader` is now
just a binding in a design, tests can substitute it the way
[Chapter 3](./ch03-00-design) substituted a database: put a trait in
front of it, `bindImpl` the C-backed class in the production design, and
bind a stub in the test design — no test ever opens a real curl handle.

This layering — raw `@extern` facade at the bottom, one safe wrapper
class, Uni services on top — is how Uni's own Native HTTP client is
built, and it is the shape to copy for any C library you bind.

## Why bind C directly?

On the JVM, reaching a C library means JNI: a separate C shim, a build
step, and a marshaling layer between the JVM and native code. Scala
Native removes the gap — your Scala compiles to native code already, so
an `@extern` call is a direct call, with no bridge and no per-call
overhead.

This is not a toy capability. Uni's Scala Native HTTP client *is* a
`libcurl` binding written exactly this way — the same `@link` / `@extern`
/ `Zone` pattern above, in production, behind the same `Http.client` API
you used on the JVM in [Chapter 9](./ch09-00-http). The cross-platform
client works on Native because someone wrote this facade once; you can
wrap any C library the same way.

## What you have, what comes next

You can now call C libraries from Scala Native — and make them feel
like Uni code:

- An **`@extern` object** with **`@link`** and **`@name`** declares the C
  functions you use — a facade, like Chapter 12's, but for C.
- **C types** (`CInt`, `CString`, `Ptr`, `CStruct…`) describe the values;
  a `CString` is not a Scala `String`.
- **`Zone.acquire`** + **`toCString`** allocate temporary arguments with a
  scoped lifetime; **`fromCString`** reads results back.
- **One wrapper class** turns error codes into exceptions and logs
  through `LogSupport`; the rest of the app never sees a `Ptr`.
- **`Design` + `onShutdown`** give the C handle a deterministic
  lifecycle, and a trait in front of the wrapper keeps it swappable in
  tests.

Next, [Chapter 15](./ch15-00-exposing-native) turns the arrow around:
instead of Scala calling C, you'll expose your Scala Native code *as* a C
library that Rust, C, and C++ can call.

[← 13. Bundling with Vite](./ch13-00-vite) | [Next → 15. Exposing Scala Native to C and Rust](./ch15-00-exposing-native)

# 14. Calling C from Scala Native

Compile a Uni app with Scala Native ([Chapter 10](./ch10-00-cross-platform))
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

## Why bind C directly?

On the JVM, reaching a C library means JNI: a separate C shim, a build
step, and a marshaling layer between the JVM and native code. Scala
Native removes the gap — your Scala compiles to native code already, so
an `@extern` call is a direct call, with no bridge and no per-call
overhead.

This is not a toy capability. Uni's Scala Native HTTP client *is* a
`libcurl` binding written exactly this way — the same `@link` / `@extern`
/ `Zone` pattern above, in production, behind the same `Http.client` API
you used on the JVM in [Chapter 8](./ch08-00-http). The cross-platform
client works on Native because someone wrote this facade once; you can
wrap any C library the same way.

## What you have, what comes next

You can now call C libraries from Scala Native:

- An **`@extern` object** with **`@link`** and **`@name`** declares the C
  functions you use — a facade, like Chapter 12's, but for C.
- **C types** (`CInt`, `CString`, `Ptr`, `CStruct…`) describe the values;
  a `CString` is not a Scala `String`.
- **`Zone.acquire`** + **`toCString`** allocate temporary arguments with a
  scoped lifetime; **`fromCString`** reads results back.

Next, [Chapter 15](./ch15-00-exposing-native) turns the arrow around:
instead of Scala calling C, you'll expose your Scala Native code *as* a C
library that Rust, C, and C++ can call.

[← 13. Bundling with Vite](./ch13-00-vite) | [Next → 15. Exposing Scala Native to C and Rust](./ch15-00-exposing-native)

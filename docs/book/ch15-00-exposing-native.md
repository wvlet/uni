# 15. Exposing Scala Native to C and Rust

You've written something valuable in Scala — a parser, a query compiler,
a domain calculation — and now a Rust service and a C++ application both
want to call it. Porting it twice is the wrong answer. Instead, compile
your Scala Native code into a **C-ABI library** and let any language that
speaks C link against it.

This is [Chapter 14](./ch14-00-calling-c) in reverse: there Scala called
into C; here C, C++, and Rust call into Scala.

## Export a function

Mark a method `@exported` with the C symbol name you want, and Scala
Native gives it a C calling convention:

```scala
import scala.scalanative.unsafe.*

object MyLib:
  @exported("greet")
  def greet(name: CString): CString =
    val who = fromCString(name)        // C string → Scala String
    toCStringHeap(s"Hello, ${who}!")   // Scala String → C string (see below)
```

`fromCString` reads the incoming `char*` into a Scala `String`, the same
helper from Chapter 14. The interesting part is the return value.

## Returning data: not in a Zone

In Chapter 14 you allocated temporary C strings in a `Zone`, which frees
them when the block ends. For a value you *return to C*, that is exactly
wrong: the `Zone` would free the string the instant `greet` returns, and
the caller would read freed memory.

A returned value has to outlive the call, so you allocate it on the heap
with `malloc` and hand ownership across the boundary:

```scala
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.stdlib

def toCStringHeap(str: String): CString =
  val bytes  = str.getBytes("UTF-8")
  val buffer = stdlib.malloc((bytes.length + 1).toCSize)
  var i      = 0
  while i < bytes.length do
    buffer(i) = bytes(i)
    i += 1
  buffer(bytes.length) = 0.toByte
  buffer.asInstanceOf[CString]
```

This is the one genuinely subtle rule of exporting: **arguments use a
`Zone`, return values use the heap.** Wvlet's real C library uses exactly
this split — `fromCString` on the way in, a `malloc`-backed `toCString` on
the way out.

Heap memory raises the question a `Zone` answered for you: who frees it?
The answer at a C boundary is a convention — the library that `malloc`s
must be the one that `free`s — so a well-behaved library also exports a
matching free function. You'll see one in the next section.

## Ship Uni logic, not toy strings

`greet` shows the mechanics, but nobody builds a library to concatenate
strings. The real question is: your Scala logic works on *rich* values —
case classes, collections — and the C ABI only speaks integers and
pointers. How do you get a `PriceRequest` across?

You don't define C structs for it. You pass **JSON strings** and let
[Weaver](./ch06-00-data) do the translating — and you keep the two
worlds in two separate files, meeting at a plain Scala `String`.

The first file is your actual library. It is pure Uni code: no
`CString`, no `Ptr`, no `unsafe` import. Following
[Chapter 11](./ch11-00-cross-platform)'s layout it lives in the shared
`src/` folder, because nothing in it is native-specific:

```scala
// src/ — shared code. Compiles on JVM, JS, and Native alike.
import wvlet.uni.log.LogSupport
import wvlet.uni.weaver.Weaver

case class PriceRequest(product: String, quantity: Int) derives Weaver
case class PriceQuote(product: String, quantity: Int, total: Double) derives Weaver

object PricingService extends LogSupport:
  def quote(requestJson: String): String =
    val request = Weaver.fromJson[PriceRequest](requestJson)
    debug(s"Quoting ${request.quantity} x ${request.product}")
    val response = PriceQuote(request.product, request.quantity, request.quantity * 9.99)
    Weaver.toJson(response)
```

The second file is the C bridge, and it is *only* the bridge. Each
exported function is one line: convert, delegate, convert back. It goes
in the `.native` folder, because it can compile nowhere else:

```scala
// .native/ — the C bridge. Conversions only; no logic lives here.
import scala.scalanative.unsafe.*
import scala.scalanative.libc.stdlib

object PricingLib:
  @exported("pricing_quote")
  def quote(requestJson: CString): CString =
    toCStringHeap(PricingService.quote(fromCString(requestJson)))

  @exported("pricing_free")
  def free(str: CString): Unit = stdlib.free(str)
```

The split is the point:

- **The two worlds meet in exactly one file.** `PricingService` speaks
  Scala (`String` in, `String` out); `PricingLib` speaks C (`CString`
  in, `CString` out); `fromCString` / `toCStringHeap` are the only
  words they exchange. When something goes wrong at the boundary, there
  is one short file to look in — the same one-place rule Chapter 14
  applied to the `Downloader` wrapper, pointing the other way.
- **The service never left the ordinary Uni world.** The logging from
  [Chapter 5](./ch05-00-logging), the `derives Weaver` codecs from
  [Chapter 6](./ch06-00-data), `Design`-built services if the logic
  needs them — all available, because `PricingService` is just Scala.
  And because it lives in shared `src/`, you can unit-test it with
  UniTest on the JVM ([Chapter 4](./ch04-00-testing)) — no native
  toolchain, no C caller — or serve the same logic over RPC
  ([Chapter 10](./ch10-00-rpc)). The C ABI is one more front door, not
  a fork of your code.
- **JSON is the boundary contract.** Every language that can call C can
  also build a JSON string, so callers get rich structured input and
  output without you maintaining a C struct layout for each type. Add a
  field to `PriceQuote` and existing callers keep working.
- **`pricing_free` completes the ownership story.** The returned string
  was `malloc`ed inside the Scala library, so the Scala library exports
  the function that frees it. Callers treat the pair as
  open/close: call `pricing_quote`, read the result, call
  `pricing_free`.

Only static object methods can be exported, and their parameter and
return types must be C-representable (primitives and pointers) — which
is exactly why the JSON-string shape works so well.

## Build it as a library

By default Scala Native produces an executable. A `nativeConfig` setting
tells it to produce a library instead — dynamic (`.so` / `.dylib`) or
static (`.a`):

```scala
import scala.scalanative.build.BuildTarget

// Dynamic library: libmylib.so / libmylib.dylib
lazy val mylib = project
  .enablePlugins(ScalaNativePlugin)
  .settings(
    libraryDependencies += "org.wvlet.uni" %%% "uni" % "__UNI_VERSION__",
    nativeConfig ~= {
      _.withBuildTarget(BuildTarget.libraryDynamic).withBaseName("mylib")
    }
  )
```

The `uni` dependency is the same line as any Native project from
[Chapter 11](./ch11-00-cross-platform) — Weaver and logging compile into
the library like any other Scala code. Swap `libraryDynamic` for
`BuildTarget.libraryStatic` to get a static `libmylib.a` instead.

`sbt mylib/nativeLink` then emits the shared library, and Scala Native
also generates a C header declaring your exported functions.

One initialization rule: the Scala Native runtime must start before the
first exported call. A *dynamic* library does this itself — Scala Native
generates a constructor that runs when the library loads. A *static*
library can't, so C callers linking `libmylib.a` must call the generated
`ScalaNativeInit()` once (it returns `0` on success) before anything
else.

## Call it from Rust

Rust links the library and declares the exported functions in an
`extern "C"` block — its native equivalent of Chapter 14's `@extern`
object, pointing the other way:

```rust
use std::ffi::{CStr, CString};
use std::os::raw::c_char;

extern "C" {
    fn pricing_quote(request_json: *const c_char) -> *mut c_char;
    fn pricing_free(response_json: *mut c_char);
}

fn main() {
    let request = CString::new(r#"{"product":"keyboard","quantity":3}"#).unwrap();
    unsafe {
        let response = pricing_quote(request.as_ptr());
        println!("{}", CStr::from_ptr(response).to_str().unwrap());
        pricing_free(response);
    }
}
```

The Rust side mirrors the memory rules from the Scala side exactly:
`CString::new` plays the role of `toCString` for the argument (freed by
Rust when `request` drops), and the response — `malloc`ed inside the
Scala library — goes back through `pricing_free`.

Compile it against the library by pointing the linker at the output
directory and naming the lib, then run it with the shared library on
the loader's path:

```bash
$ sbt mylib/nativeLink
$ rustc -L target/scala-3.x -lmylib test.rs -o test
$ LD_LIBRARY_PATH=target/scala-3.x ./test    # DYLD_LIBRARY_PATH on macOS
{"product":"keyboard","quantity":3,"total":29.97}
```

`-L` is the search path (where `libmylib.so` lives) and `-lmylib` is the
library — the same two flags whatever the consuming language is. At run
time the dynamic loader needs the same directory, hence
`LD_LIBRARY_PATH`.

## Call it from C and C++

C and C++ are the same story: declare the functions, link the library.

```c
// test.c
#include <stdio.h>

char* pricing_quote(const char* request_json);
void  pricing_free(char* response_json);

int main() {
    char* response = pricing_quote("{\"product\":\"keyboard\",\"quantity\":3}");
    printf("%s\n", response);
    pricing_free(response);
    return 0;
}
```

```bash
$ gcc -L target/scala-3.x -lmylib test.c -o test   # g++ for C++
```

Wvlet ships its query compiler this way, in exactly the JSON-in/JSON-out
shape you just built: one Scala Native module exports
`wvlet_compile_query_json`, which takes a JSON string of arguments and
returns a `char*` of JSON (`{"success":true,"sql":...}`), builds to
`libwvlet`, and its
[test suite](https://github.com/wvlet/wvlet/tree/main/wvc-lib) links the
same `.so` from Rust, C, *and* C++ — three languages, one implementation,
no ports.

## Why ship Scala as a native library

The usual ways to share logic across languages are a network service (now
you operate a server and pay serialization on every call) or a rewrite
(now you maintain the same logic twice and they drift). Exporting a
C-ABI library is neither: the consumer makes a direct in-process function
call into your compiled Scala, and there is exactly one implementation to
maintain.

Because the contract is the C ABI — the lingua franca every systems
language speaks — you are not committing to Rust or to C++. Anything that
can call C can call your Scala. You write the logic once, in the language
you prefer, and hand it to everyone else as a `.so`.

## What you have, what comes next

You can now ship Uni-powered Scala to the systems world:

- **`@exported("name")`** gives a method a C ABI; **`fromCString`** reads
  arguments.
- Return values go on the **heap** (`malloc`), never a `Zone` — and the
  library exports the matching **free function**, because whoever
  `malloc`s must `free`.
- **Two files, one meeting point**: a pure-Scala service (`String` in,
  `String` out, Uni everywhere) in shared `src/`, and a
  conversions-only C bridge in `.native/` — `fromCString` /
  `toCStringHeap` are the only words the two worlds exchange.
- **JSON strings are the boundary contract**: `Weaver.fromJson` inside
  the service on the way in, `Weaver.toJson` on the way out.
- **`BuildTarget.libraryDynamic` / `libraryStatic`** + `nativeLink`
  produce a `.so` / `.dylib` / `.a` and a C header; static libraries
  need one **`ScalaNativeInit()`** call before use.
- **`-L<dir> -l<name>`** links it from Rust, C, or C++ alike.

That closes Part VIII — and, with it, Uni's reach into both neighboring
ecosystems: the JavaScript world through Scala.js
([Part VII](./ch12-00-npm-facades)) and the C/Rust world through Scala
Native. One library, three runtimes, and a door into each runtime's
native ecosystem.

From here, the appendices collect supporting material:
[Appendix A](./appendix-a-scala3) on Scala 3 syntax,
[Appendix B](./appendix-b-airframe) on Uni and Airframe, and
[Appendix C](./appendix-c-glossary), the glossary.

[← 14. Calling C from Scala Native](./ch14-00-calling-c) | [Next → 16. Capstone — A Bookmarks Service](./ch16-00-capstone)

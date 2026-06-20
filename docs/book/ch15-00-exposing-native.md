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
import scala.scalanative.libc.stdlib

private def toCStringHeap(str: String): CString =
  val bytes  = str.getBytes("UTF-8")
  val buffer = stdlib.malloc(bytes.length + 1).asInstanceOf[CString]
  // copy bytes into buffer and null-terminate...
  buffer
```

This is the one genuinely subtle rule of exporting: **arguments use a
`Zone`, return values use the heap.** Wvlet's real C library uses exactly
this split — `fromCString` on the way in, a `malloc`-backed `toCString` on
the way out.

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
    nativeConfig ~= { _.withBuildTarget(BuildTarget.libraryDynamic) }
  )

// Static library: libmylib.a (and .withBaseName to set the lib name)
lazy val mylibStatic = project
  .enablePlugins(ScalaNativePlugin)
  .settings(
    nativeConfig ~= {
      _.withBuildTarget(BuildTarget.libraryStatic).withBaseName("mylib")
    }
  )
```

`sbt mylib/nativeLink` then emits the shared library, and Scala Native
also generates a C header declaring your exported functions.

## Call it from Rust

Rust links the library and declares the function in an `extern "C"`
block:

```rust
use std::ffi::CString;
use std::os::raw::c_char;

extern "C" {
    fn greet(name: *const c_char) -> *const c_char;
}

fn main() {
    let name = CString::new("Rust").unwrap();
    unsafe {
        let reply = greet(name.as_ptr());
        // ... read the returned C string ...
    }
}
```

Compile it against the library by pointing the linker at the output
directory and naming the lib:

```bash
$ sbt mylib/nativeLink
$ rustc -L target/scala-3.x -lmylib test.rs -o test
```

`-L` is the search path (where `libmylib.so` lives) and `-lmylib` is the
library — the same two flags whatever the consuming language is.

## Call it from C and C++

C and C++ are the same story: declare the function, link the library.

```c
// test.c
#include <stdio.h>

const char* greet(const char* name);   // declare the exported function

int main() {
    printf("%s\n", greet("C"));
    return 0;
}
```

```bash
$ gcc -L target/scala-3.x -lmylib test.c -o test   # g++ for C++
```

Wvlet ships its query compiler this way: one Scala Native module exports
`wvlet_compile_query`, builds to `libwvlet`, and its
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

You can now ship Scala Native code to the systems world:

- **`@exported("name")`** gives a method a C ABI; **`fromCString`** reads
  arguments.
- Return values go on the **heap** (`malloc`), never a `Zone` — the one
  subtle rule.
- **`BuildTarget.libraryDynamic` / `libraryStatic`** + `nativeLink`
  produce a `.so` / `.dylib` / `.a` and a C header.
- **`-L<dir> -l<name>`** links it from Rust, C, or C++ alike.

That closes Part IX — and, with it, Uni's reach into both neighboring
ecosystems: the JavaScript world through Scala.js
([Part VIII](./ch12-00-npm-facades)) and the C/Rust world through Scala
Native. One library, three runtimes, and a door into each runtime's
native ecosystem.

From here, the appendices collect supporting material:
[Appendix A](./appendix-a-scala3) on Scala 3 syntax,
[Appendix B](./appendix-b-airframe) on Uni and Airframe, and
[Appendix C](./appendix-c-glossary), the glossary.

[← 14. Calling C from Scala Native](./ch14-00-calling-c) | [Next → Appendix A: Scala 3 Syntax Notes](./appendix-a-scala3)

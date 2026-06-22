# The Uni Book

*A guided tour of Scala 3 application development with Uni.*

This book teaches you to build real applications with
[Uni](https://github.com/wvlet/uni) on Scala 3, across the JVM,
Scala.js, and Scala Native. It is written for developers who already
read Scala comfortably and want to understand not only *how* Uni works
but *why* its pieces are shaped the way they are.

The Book is narrative. It is meant to be read front to back at first,
and used as a jumping-off point afterwards. The short-form API
documentation under [Guide](/guide/) and the module references
(e.g. [Core](/core/), [HTTP](/http/)) stay where they
are, and the Book links into them when you need depth.

## How to read it

- Work from Part I to the end of Part II in order. Everything that
  follows assumes the mental model built in those two parts.
- Each part opens with a short intro page; each chapter ends with a
  *"what you have, what comes next"* recap. If the recap doesn't feel
  true yet, re-read the chapter before moving on.
- Type the examples. Reading them is not the same exercise.

## Table of contents

### [Foreword](./foreword)

### Part I — Getting Started

- [Chapter 1: Getting Started](./ch01-00-getting-started)
  - [1.1 Installation](./ch01-01-installation)
  - [1.2 Hello, Uni!](./ch01-02-hello-uni)

### Part II — Building a CLI Application

- [Chapter 2: A URL Fetcher](./ch02-00-cli-app)

### Part III — Core Concepts

- [Chapter 3: Wiring with Design](./ch03-00-design)
- [Chapter 4: Testing with UniTest](./ch04-00-testing)
- [Chapter 5: Logging That Finds You](./ch05-00-logging)
- [Chapter 6: Data In, Data Out — JSON & MessagePack](./ch06-00-data)

### Part IV — Async & Control Flow

- [Chapter 7: Rx, the Composable Stream](./ch07-00-rx)
- [Chapter 8: Living With Failure — Retry, Circuit Breakers, Resources](./ch08-00-control)

### Part V — HTTP & RPC

- [Chapter 9: HTTP Clients and Servers](./ch09-00-http)
- [Chapter 10: Typed RPC](./ch10-00-rpc)

### Part VI — Cross-Platform Development

- [Chapter 11: One Codebase, Three Runtimes](./ch11-00-cross-platform)

### Part VII — Scala.js in the JavaScript Ecosystem

- [Chapter 12: Calling NPM Modules from Scala.js](./ch12-00-npm-facades)
- [Chapter 13: Bundling with Vite](./ch13-00-vite)

### Part VIII — Scala Native and the C/Rust World

- [Chapter 14: Calling C from Scala Native](./ch14-00-calling-c)
- [Chapter 15: Exposing Scala Native to C and Rust](./ch15-00-exposing-native)

### Part IX — Capstone

- [Chapter 16: A Bookmarks Service](./ch16-00-capstone)

### Appendices

- [Appendix A: Scala 3 Syntax Notes for This Book](./appendix-a-scala3)
- [Appendix B: Uni and Airframe — History and Relationship](./appendix-b-airframe)
- [Appendix C: Glossary](./appendix-c-glossary)

## Status

All parts are drafted, Foreword through the appendices. The book is best
read front to back the first time; afterwards, use it as a jumping-off
point into the [reference docs](/guide/).

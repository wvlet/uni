# Audit reference docs for wrong APIs by compiling snippets

## Goals

- Users and AI agents paste snippets from the reference pages. #765 compiled four pages and
  found 5 wrong APIs, so the unchecked pages likely contain more.
- Fix every page whose snippets do not compile against current source, or that describe
  behavior the code does not have (docs/AGENTS.md Rule #0).

## Background

- #765 checked docs/http/{client,rpc,server}.md and docs/core/unitest.md.
- `book-examples` already compile-checks the book's runnable snippets in CI, so book
  chapters are lower priority and go to a separate PR if anything turns up.
- Follow-up 1 builds a committed snippet-compile check for the skill references; this PR
  builds no tooling.

## Design

Lane: **Express** — docs fixes with a clear method; no always-gate area. A library fix is
included only if it is small, clearly correct, and has a regression test.

- Scope: docs/rx/*, docs/http/{sse,websocket,router,openapi,retry,electron,index}.md,
  docs/core/{design,weaver,json,msgpack,logging,surface,result,io,filesystem,utilities}.md,
  docs/control/*, docs/cli/*, docs/dom, docs/mcp.
- Method: paste each page's snippets into an uncommitted scratch test (uni-netty test dir
  for JVM; uni JS/Native test dirs for platform-only APIs), compile, fix the page, then
  delete the scratch file. Fragments that are not self-contained get minimal stubs.
- Also check behavior claims in prose next to failing snippets against source.
- Mirror fixes into docs/guide/for-ai-agents.md and plugins/uni/skills/uni/ when the same
  mistake appears there; bump the plugin version in that case.
- Library bugs: report in the PR; fix only small, clearly-correct ones with a test.

# Temporal Example Project for Scala 3

## Objective

Create a small Temporal example project inside `uni` to evaluate the Temporal workflow engine's usability, SDK ergonomics, and fit for durable workflow orchestration in the Scala 3 ecosystem.

## Background

Temporal is a workflow orchestration platform built for durability. Workflows survive crashes; activities are retried automatically. This exploration aims to understand:

1. How Temporal's Java SDK feels in Scala 3
2. How to write workflows and activities idiomatically
3. How retries, error handling, and observability work
4. Whether Temporal complements the lightweight `uni-task` approach

## Module: `uni-temporal-example`

A JVM-only example module (not published) that demonstrates:

### Workflows

1. **HelloWorkflow** — minimal "hello world" to understand the basics
2. **DataPipelineWorkflow** — multi-step pipeline: fetch → transform → store

### Activities

- `GreetingActivities` — simple greeting activity
- `DataActivities` — fetch, transform, and store with simulated failures for retry demo

### Key Concepts Covered

- Workflow interface + implementation separation (required by Temporal)
- Activity interface + implementation
- `ActivityOptions` with retry policy
- Test server (`TestWorkflowEnvironment`) for unit tests without a real server
- Workflow signals and queries (stretch goal)

## Implementation Plan

1. Add `uni-temporal-example` module to `build.sbt`
2. Add Temporal SDK dependencies:
   - `io.temporal:temporal-sdk`
   - `io.temporal:temporal-testing` (test scope)
3. Implement `HelloWorkflow` with `GreetingActivities`
4. Implement `DataPipelineWorkflow` with `DataActivities`
5. Write `UniTest`-based tests using `TestWorkflowEnvironment`
6. Add a `TemporalWorkerApp` entry point for running against a real local server

## Findings (to be filled in)

- SDK ergonomics:
- Test experience:
- Comparison to uni-task:
- Recommendation:

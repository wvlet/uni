# Launcher reads class-level @command

## Goals

- Fix #770: `@command(description = "...", usage = "...") class Tool(...)`, the pattern `docs/cli/launcher.md` documents, has no effect. Top-level help prints `usage: command [options] <command>` and no description.

## Background

- `Launcher.newCommandLauncherFromSurface` looks for `@command` on the first constructor parameter, then on a `<init>` method surface. Both were workarounds from before Surface recorded class annotations; neither sees a class annotation.
- Since `adr/2026-10-21-surface-class-annotations.md`, `Surface.annotations` / `findAnnotation` hold class-level annotations for classes with a public constructor, on JVM, JS, and Native (the macro fills it, so no platform split).

## Design

Lane: **Express** — a bug fix with a clear diagnosis, no always-gate area.

- Check `surface.findAnnotation("command")` first, keeping the two old lookups as fallbacks.
- An explicit non-empty `description` passed to `addModule(name, description)` wins over the annotation's description; otherwise the annotation's applies. `usage` and `isDefault` come from the annotation.
- Add LauncherTest cases: class-level description and usage appear in `of[A]` help; `addModule` respects the explicit description.
- Add `usage = "uni-gh [options] <command>"` to `examples/cli-tool`'s `GitHubCli`. `docs/cli/launcher.md` already documents this behaviour; the skill and `for-ai-agents.md` need changes only if they state otherwise.

# Launcher: nested config-class method args (#509, PR #512)

## Problem

`@command` methods that take a nested config class (e.g.
`def start(config: ServerConfig)`) crashed with NPE.

Two paths were broken:

1. `MethodOptionSchema` registered the nested param as a single positional
   argument, so its inner `--port`/`--host` flags were never parsed.
2. `CommandLauncher.buildMethodArgs` then fell through to
   `getDefaultForType(p.surface)`, which returns `null` for non-primitive types.

Constructor injection (`buildInstanceFromSurface`) already handled this via
recursion — the same logic just had to mirror in the method-arg path.

## Why it matters

This pattern blocks the airframe → uni launcher migration in
[wvlet/wvlet#1635](https://github.com/wvlet/wvlet/pull/1635) — every
`@command def` in `WvletMain` takes a `WvletXxxOption` config class.

## Decisions made during the PR cycle

What started as a minimal "mirror the recursion" fix expanded as codex/Gemini
review surfaced edge cases. Each decision below is something a future reader
might wonder "why isn't this simpler" about.

### Schema-build: when to flatten vs treat as positional

| Param shape                                                        | Treatment                                                |
| ------------------------------------------------------------------ | -------------------------------------------------------- |
| `@option foo: T`                                                   | Single CLOption                                          |
| `@argument foo: T`                                                 | Single CLArgument                                        |
| Plain scalar method param (no annotation)                          | Positional (existing behavior)                           |
| Nested config class (no annotation), method arg                    | Flatten via `ClassOptionSchema(treatUnannotatedAsPositional=true)` |
| Plain scalar inside a nested config used as a method arg           | Positional (NEW — propagated via the flag above)         |
| Plain scalar inside a constructor-injected nested config           | Ignored (existing behavior)                              |
| `KeyValue` (anywhere)                                              | Scalar — has custom string parser                        |

The asymmetry between method-arg and constructor-injected nested configs is
deliberate: existing constructor users have shipped without per-field
annotations and we don't want to surprise them.

### Default-value handling: three cases

When a nested config method arg is encountered:

1. No method-level default + no inner flags parsed → build from surface (inner field defaults).
2. Method-level default + no inner flags parsed → use the default object as-is.
3. Method-level default + some inner flags parsed → **merge**: use the default
   object as the base, override only fields whose flags were parsed.

Case 3 (`mergeWithDefault`) is the one most people would get wrong. Without it,
`start --port 1234` resets `host` from the method default `"default-host"`
to the inner field default `"localhost"` — a silent regression.

Reading the default object's fields uses `Parameter.get(default)`, which only
works for case classes. `readFromDefault` falls back to the param's declared
default (or type zero) when `get` returns null, so partial overrides on
non-case-class configs degrade gracefully instead of smuggling nulls in.

### Method default accessor: belt-and-suspenders fallback

`StaticMethodParameter.getMethodArgDefaultValue` reads via a runtime accessor.
For inherited/trait command methods the accessor isn't synthesized, so it
returns `None` — losing the compile-time `defaultValue`.

`buildMethodArgs` chains `.orElse(p.getDefaultValue)` defensively. If the
surface ever fixes the override to fall back, this is a no-op.

### Conflict detection (raise early, not silently misparse)

Three classes of collision are now rejected at schema-build / pre-parse time:

1. **Field-name collision** (within a method's flattened schema) —
   `groupBy(_.name)`. Hits `def collide(a: ServerConfig, b: ServerConfig)`.
2. **Option-prefix collision** (within a method's flattened schema) —
   `groupBy(identity)` on prefixes. Hits two nested configs that both expose
   `@option(prefix = "--host")` for differently-named fields.
3. **Outer/inner shadowing** (between command-class options and method options)
   — checked in `CommandLauncher.executeMethod` before parsing. Hits
   `class App(@option("--host") host)` + `def run(cfg: ServerConfig)`.

All three could otherwise silently misroute or overwrite values.

### Variadic positional must be last

`OptionParser` greedily consumes all remaining tokens for the first multi-valued
positional argument. Nested-config flattening makes it easy to introduce a
non-last `Seq[String]` positional unintentionally — e.g.
`def run(cfg: MultiPositionalConfig, mode: String)` where `cfg.files: Seq[String]`
would swallow `mode`. `MethodOptionSchema` rejects this configuration.

### Excluding `KeyValue` from the nested-class predicate

`KeyValue(key, value)` matches the heuristic "non-primitive + has params" but
has its own scalar parser. Added `KeyValue.SurfaceName` constant and excluded
it from `isNestedConfigClass` so unannotated `KeyValue` method args still parse
from a single `key=value` token instead of being flattened.

## Out of scope (intentionally)

- Plain non-case-class configs with non-readable constructor params: partial
  overrides degrade to declared/zero defaults via `readFromDefault`. Full
  reflection-based field discovery is a bigger change.
- Help-printer support for "the nested config sub-flags": currently they show
  up flat in `--help`, which is consistent with how flattening works, but
  there's no grouping by source config.

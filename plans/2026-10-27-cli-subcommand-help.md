# CLI launcher: `--help` after a sub-command

## Goals

- `wvlet compile --help` must print the `compile` command's options, not the top-level help.
- Same for nested modules added with `Launcher.addModule` (`wvlet flow run --help`).
- Keep `cmd --help` / `cmd -h` (no sub-command) printing the top-level help.

Found while verifying the AI-agent docs in wvlet PR wvlet/wvlet#2141 (bug d). The user approved
fixing it in uni ("Fix in uni, follow up"); the uni release and the wvlet `UNI_VERSION` bump are
separate follow-ups.

## Background

- `CommandLauncher.execute` parses all args with the parent schema first. Parent options after the
  sub-command name (`cmd sub --verbose`) are still consumed by the parent; that is intended and stays.
- `OptionParser.parse` treats any help prefix anywhere in `args` as a help request. So for
  `compile --help` the parent sets `showHelp` and prints its own help; `executeMethod`'s
  `printMethodHelp` and a sub-launcher's `printHelp` are never reached.

## Design

Lane: **Express** — clear diagnosis, small non-UI library fix, no always-gate area.

- `OptionParser.parse` gets a `deferHelpAfterCommand: Boolean = false` parameter. When true, once a
  non-option token lands in `unusedArguments` (the sub-command candidate), later help prefixes are
  kept in `unusedArguments` in order instead of setting `showHelp`.
- `CommandLauncher.execute` passes `deferHelpAfterCommand = subCommands.nonEmpty || hasMethodCommands`.
  The help flag then flows in `remainingArgs` to the sub-launcher (`printHelp`) or to
  `executeMethod` (`printMethodHelp`). Recursion handles `<module> <method> --help`.
- A help flag before the sub-command name (`cmd --help compile`) still prints the parent help.
- When help is deferred, the parent instance is not built, so a missing required top-level option
  does not block `cmd build --help`.
- If the name before a deferred help flag is not a known sub-command or method (`cmd clon --help`),
  the parent help is printed, since it lists the valid commands.
- Commands without sub-commands keep the old behaviour (`cmd file --help` prints help).

Tests in `LauncherTest`, using a capturing `HelpPrinter` to assert which command's help rendered:

- top-level `--help` and `-h`
- `<method> --help` shows the method's options
- `<module> <method> --help` via `addModule`
- global option before the sub-command: `--verbose clone --help`

## Alternatives and Why Not?

- **Stop parent parsing at the sub-command name**: also fixes it, but breaks parent options placed
  after the sub-command (`cmd sub --verbose`), which users rely on.
- **Detect the sub-command in `CommandLauncher` after parsing**: the parser drops help-flag positions,
  so the launcher can't tell whether help came before or after the name without re-scanning args.

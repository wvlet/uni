# Release script version validation

## Problem

`project/release.sh` prompts for the next release version but performs no
validation. If the user presses the wrong key and hits Enter, any string is
accepted and immediately becomes a git tag. This is not hypothetical — the
repository already contains a stray tag `vy` from such an incident.

## Expected version format

The script comment (`# Versioning scheme: YYYY.(milestone).patch`) and recent
tags (`v2026.1.0`, `v2026.1.1`) show the expected shape:

- `YYYY` — 4-digit year
- `milestone` — one or more digits
- `patch` — one or more digits

Regex: `^[0-9]{4}\.[0-9]+\.[0-9]+$`

## Fix

After reading `next_version` (including when the default was accepted), check
the value against the regex. On mismatch, print the expected format and exit
non-zero before any tag is created or pushed.

## Out of scope

- Cleaning up the existing `vy` tag — that is a separate decision for the
  maintainer; this PR only prevents future occurrences.
- Stricter rules (e.g. monotonic version, year must match current year). The
  default already handles the common case; validation only needs to catch
  obvious typos.

# Contributing to RigelMCMod

Thanks for your interest in contributing! A few project-specific rules before you dive in.

## The two rules that matter most

**1. Never call Bukkit/Paper API off the main thread.** RigelMCMod's data layer is fully
async (HikariCP-backed DAOs — see `data/DataSourceFactory` and `data/dao/*`), which means
it's easy to accidentally touch a `Player`, `World`, or other Bukkit object from a
database callback running on the async executor. This is the single most common way a
well-intentioned PR introduces a race condition or a
`IllegalStateException`/async-catcher crash in this codebase. When in doubt, hop back
onto the main thread via the plugin's scheduler before touching any Bukkit API.

**2. Every permission node this plugin checks must be explicitly registered with an
explicit `PermissionDefault`** (almost always `FALSE`) via
`PluginManager#addPermission`, *before* it's ever checked with `hasPermission(...)`.
This isn't boilerplate - it's a real, non-obvious footgun specific to a Free-OP server:
every player is auto-op'd (`core.AutoOpModule`), and Bukkit's fallback for any
permission string that was *never registered at all* is "true if the player is op" -
meaning an unregistered permission check silently grants itself to literally everyone
the moment auto-op is enabled. This bit `protect.BlockedCommandListener`'s own bypass
permission during development (see `ProtectModule#registerListeners`'s comment for the
full story) - it's exactly the kind of bug that's invisible in testing (nobody's op'd in
a unit test) and only shows up in production. Note this only applies to raw
`Permissible#hasPermission(String)` checks; RigelMCMod's own rank-gating
(`PermissionGate#hasAtLeastCached`) is a separate, unaffected in-memory rank comparison
that never touches Bukkit's permission system at all - prefer it over a raw permission
string wherever a rank check is what you actually mean.

**3. Never use an Adventure `Component` *builder* chain (`Component.text()....build()`).**
Always build components via direct `Component`-to-`Component` `#append(...)` chaining
instead (`Component.empty().append(...).append(...)`, or `Component.text("x",
color).append(...)`), never the `TextComponent.Builder` form. This isn't a style
preference - it's a confirmed, real crash, and the reason `plugin/` now compiles directly
against `paper-api` 26.1.2 instead of 26.2 (see `gradle/libs.versions.toml` and
`paper-plugin.yml`'s `api-version` comment): compiling against 26.2 and running on a live
26.1.2 server threw a runtime-only `NoSuchMethodError` from exactly this pattern, despite
compiling and testing cleanly - `TextComponent.Builder`'s exact bytecode-level method
signatures aren't binary-compatible across those two Adventure versions.  Compiling
directly against the actual deployment target closes that specific gap, but the
underlying lesson still stands generally (e.g. if this project ever needs to support
multiple Paper versions again, or a future Paper release changes Adventure's builder
internals again): direct `Component#append` chaining doesn't have this failure mode and
is the pattern already used everywhere else in this codebase (e.g.
`chat.PlayerDisplayService`). If you catch exceptions around any Adventure API call for
defensive-logging purposes, catch `LinkageError` alongside `RuntimeException` -
`NoSuchMethodError` is a `LinkageError`, not a `RuntimeException`, and won't be caught by
`catch (RuntimeException e)` alone (see `chat.ChatFormatListener` for the concrete fix).
Also remember that `event.renderer(...)` only *registers* a callback Paper invokes later,
potentially per-viewer - error handling needs to live *inside* that lambda, not just
around the code that calls `.renderer(...)`.

## Project structure

- `api/` — public interfaces and events other plugins can integrate against. Keep this
  module free of any dependency beyond `paper-api` (`compileOnly`).
- `plugin/` — the actual implementation. See `docs/architecture.md` for the full
  package-by-package breakdown and the reasoning behind each design decision (why
  commands are consolidated under a few Brigadier roots, why the ban system is
  IP-primary for non-premium identities, why there's no embedded HTTP server on by
  default, etc.) — read it before proposing a structural change.

## Every command node needs a usage-help fallback

Any Brigadier literal/argument node that has children (subcommands or required
arguments) but can itself be the end of the input needs its own `.executes(ctx ->
CommandUsage.show(ctx.getSource().getSender(), "..."))` — see `command.CommandUsage`.
Without it, Brigadier's own error for incomplete input is Minecraft's generic "Unknown
or incomplete command. See below for error", which is technically accurate but gives the
player no idea what they did wrong (confirmed confusing in real testing - `/tag` and
`/nick` typed with no argument both hit exactly this before the fallbacks were added).
Every existing command in this codebase follows this pattern now; keep new ones
consistent with it. If a command has its own access restriction that isn't a rank check
(e.g. `/adminconfig`'s console-only requirement), the usage fallback must run that check
*first* and only show usage text to callers who'd pass it anyway - never leak usage
text (or worse, behavior) to someone the restriction should have blocked outright.

## Before opening a PR

- `./gradlew check` (tests + Checkstyle + SpotBugs) must pass.
- New feature modules should implement `core.PluginModule` and be gated behind a
  `modules.<id>.enabled` config toggle, consistent with every existing module.
- If you're integrating with a third-party plugin (CoreProtect, LibsDisguises, etc.),
  follow the established pattern: soft-dependency in `paper-plugin.yml`
  (`join-classpath: false`), detect at enable time, degrade gracefully if absent, defer
  to the specialized plugin's own logic rather than duplicating it.
- Add or update a unit test for anything touching persistence or business logic. Most of
  this codebase doesn't need MockBukkit at all: services are written as plain JDBC +
  plain Java with no Bukkit types, tested directly against a temp-file SQLite database
  (see `data/TestDatabase` and e.g. `BanServiceTest`, `RankServiceTest`). Keep new
  services structured the same way — push Bukkit-touching code to a thin
  listener/module layer around a testable core, rather than mixing the two.

## Reporting security issues

If you find a security issue (especially anything related to the optional web panel),
please report it privately rather than opening a public issue — see the security policy
once one is published, or contact the maintainers directly in the meantime.

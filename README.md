# RigelMCMod

A modern, better-optimized, open-source admin-rank / anti-grief / punishment toolkit for
**Free-OP** PaperMC servers - the kind of server where every player is granted operator
and the plugin's job is to make that survivable.

RigelMCMod is inspired by the feature set of [TotalFreedomMod](https://github.com/TotalFreedom/TotalFreedomMod),
but is an original, from-scratch implementation - no TotalFreedomMod code is reused. See
`docs/architecture.md` for the full design rationale, including concrete responses to
TotalFreedomMod's documented issues (an on-by-default, unauthenticated embedded HTTP
server with critical vulnerabilities; a username-keyed data model that broke on name
changes; crash-on-empty-list bugs; fragile hard dependencies).

Original author: **LightWarp**.

## Status

Feature-complete through Sub-phase F1 and running on a live, mixed Java/Bedrock/Eaglercraft
server. Implemented: the rank/title ladder with permission fallback; the ban system
(`/ban`, `/tban`, `/permban` cascade, `/unban`, chat mute, audit log); tiered command
access control; the anti-grief core (anti-nuke, anti-spam, freeze including hard-freeze,
movement validator, command-spy/`/bookspy`); event- and packet-level crash-exploit guards
(`protect/crash/`, TFM-parity "E1"/"E2" tiers); `WorldEditBridge`
(FAWE/WorldEdit/naive-fallback) with `/protectarea`, `/cage`, and a CoreProtect rollback
bridge; admin worlds with a guest system; the flatlands sandbox (CleanroomGenerator-backed
for Eaglercraft/1.8-protocol compatibility, in-place wipe/autowipe by default with an
opt-in restart-based mode, EssentialsX warp cleanup on wipe); disguise and skin bridges;
the Discord bridge (Discord4J, account linking, rank-gated console-via-Discord, a
TFM-style full server console mirror, and join/leave relay to both the public and staff
channels); a read-only, multi-page web dashboard; and a separate public ban-appeal web form
with Discord Approve/Deny buttons (auto-unban on approval). See `docs/architecture.md` for the
full roadmap - the one
remaining tracked item is Sub-phase F2 (`fun/`: jump pads, landmines, novelty guns,
particle trails, novelty/troll commands), not yet built. Still under active hardening as
issues surface from real-world use.

## Requirements

- **PaperMC 26.1.2 or 26.2**, Java 25. Compiled and tested directly against **26.1.2**
  (the primary target - `api-version: '26.1'` in `paper-plugin.yml` also lets 26.2
  servers load it, and every API this plugin uses has been confirmed identical between
  the two). Compiling against 26.2 while targeting 26.1.2 previously caused a real
  runtime `NoSuchMethodError` from an Adventure `Component` builder-chain incompatibility
  - see `CONTRIBUTING.md` rule 3 - which is why the build now targets 26.1.2 directly
  instead of treating it as just a compatibility floor.
- [EssentialsX](https://essentialsx.net/) (for general QoL commands - RigelMCMod deliberately
  does not reimplement homes/warps/teleport commands; see the Essentials collision policy
  in the architecture doc)

Optional companion plugins RigelMCMod integrates with if present (all soft dependencies -
none are required to run):

| Plugin | Used for |
|---|---|
| [LuckPerms](https://luckperms.net/) / [Vault](https://www.spigotmc.org/resources/vault.34315/) | Permission bridging (RigelMCMod works standalone without either) |
| [CoreProtect](https://www.spigotmc.org/resources/coreprotect.8631/) | `/punish rollback`, and the automatic rollback triggered by `/ban` |
| [LibsDisguises](https://www.spigotmc.org/resources/libs-disguises-free.81/) | `/disguise` |
| [SkinsRestorer](https://skinsrestorer.net/) | `/skin` |
| [WorldGuard](https://enginehub.org/worldguard) / [FastAsyncWorldEdit](https://www.spigotmc.org/resources/fastasyncworldedit.13932/) | Admin-defined protected zones; fast bulk block operations for cage build/restore, world resets |
| [Floodgate](https://geysermc.org/) | Detecting Bedrock players bridged in via GeyserMC |
| [CleanroomGenerator](https://github.com/nvx/CleanroomGenerator) | Legacy (y=0-start) flatlands generation so Eaglercraft/1.8-protocol clients can see the sandbox world; falls back to vanilla `WorldType.FLAT` if absent |
| [PacketEvents](https://github.com/retrooper/packetevents) | Packet-level ("E2") crash-exploit guards - inbound chat/command/movement rate limiting, outbound entity-metadata sanitization; event-level ("E1") guards work without it |

## Network topology note (important setup step)

RigelMCMod is designed to run on a single backend Paper server behind a **Velocity**
proxy that bridges Bedrock (GeyserMC/Floodgate) and browser/cracked (Eaglercraft-style)
clients. If that's your setup:

> **You must enable Velocity's modern IP forwarding on the backend** (`paper-global.yml`:
> `proxies.velocity.enabled: true` with a matching secret). Without it, every player -
> not just Bedrock/Eaglercraft ones - appears to originate from the proxy's own IP to
> this plugin, which silently breaks IP-based bans and anti-nuke correlation for
> **everyone**, not just non-premium clients. This is an easy thing to miss and a common
> footgun; see `docs/architecture.md` for the full explanation.

> **If your network is premium-only, also set `identity.online-mode: true` in
> `config.yml`.** The backend server's own `online-mode` in `server.properties` is
> typically `false` behind Velocity even for legitimately premium players (Velocity's
> modern forwarding handles real authentication itself), so RigelMCMod can't trust
> Bukkit's own online-mode flag to mean "this account is verified." Without this set
> explicitly, every player - admins included - gets treated as a spoofable
> non-premium identity for ban/security purposes, which is the wrong default on a
> premium-only network.

RigelMCMod's own ban system is IP-primary for non-premium (Bedrock/offline) identities
specifically because their UUIDs are trivially regenerated by picking a new username -
see the "Network topology & multi-client identity" and "Ban system" sections of the
architecture doc.

## Getting started on a fresh install

Every joining player is automatically granted **vanilla Bukkit operator status** - that's
the entire premise of a Free-OP server. That is *not* the same thing as holding a real
RigelMCMod rank, though, and rank is what actually gates RigelMCMod's own commands. So
after your first boot:

1. Start the server and join once (this registers you in RigelMCMod's own player table).
2. From the **server console** (not in-game - this is deliberately console-only, see
   `/adminconfig` below), run:
   ```
   adminconfig add <your-in-game-name>
   adminconfig setrank <your-in-game-name> senior_admin
   ```
   (`add` always grants the baseline Moderator rank - it deliberately takes no rank
   argument; `setrank` is the separate, explicit step for granting a specific rank.)
3. You now hold the top RigelMCMod rank and can use its ranked commands in-game.

## Commands implemented so far

| Command | Does |
|---|---|
| `/ban <player> [reason]` (alias `/gtfo`) | 24h ban, automatic CoreProtect rollback if installed. Also bans the target's current (or most-recently-seen) IP alongside the name, linked as one case (`/punish unban <name> -c` lifts both) |
| `/tban <player> <duration> [reason] [-r]` | Custom-duration ban, opt-in rollback |
| `/permban <name\|ip> [reason]` | Permanent ban, cascades to every name/IP the target has ever shared |
| `/unban <name\|ip> [-c]` (alias `/punish unban`) | Lifts a ban issued by `/ban`, `/tban`, or `/permban`; `-c` lifts the whole permban cascade case. Deliberately shadows Essentials' own `/unban` |
| `/punish mute <player> [reason]` / `/punish unmute <player>` | Indefinite, staff-facing chat mute - explicitly unmuted later |
| `/mute <player> [reason]` / `/unmute <player>` | TFM's `/stfu`: quick, always-public, fixed 5-minute **toggle**-mute (running it again unmutes early). Deliberately, unconditionally shadows Essentials' own `/mute` - not gated behind `aliases.enable-shadowing` |
| `/o <message>` | Staff-only broadcast - in-game, plus the Discord admin channel if the bridge is connected |
| `/discord link` / `/discord unlink` | Link/unlink your Discord account (finish by running `/link code:CODE` as a Discord slash command, in a DM to the bot) |
| `/wipeflatlands` | Immediately wipe and regenerate the flatlands sandbox world (in-place by default - Senior Admin+, in-game or console; console/RCON-only if `world.flatlands.wipe-requires-restart: true`). Also removes any EssentialsX `/warp` left pointing into it |
| `/announce <message>` | One-off server-wide broadcast, MiniMessage-formatted (colors, gradients, etc.) |
| `/rvanish` | Vanish, hidden from regular players *and* non-staff auto-op'd players (Moderator+) |
| `/op [player]` / `/opall` / `/deop <player>` / `/deopall` | `/op` is open to everyone (self or another online player) - vanilla op status carries no RigelMCMod rank of its own. `/opall`/`/deop`/`/deopall` are Moderator+ |
| `/lockdown [on\|off]` | **Admin+, in-game or console/RCON.** While enabled, only Moderator+ may join - session-only, always starts off |
| `/mobpurge [radius]` (alias `/mp`) / `/entitywipe [radius]` (alias `/ew`) | Remove living non-player entities / dropped items+orbs+stuck projectiles. Moderator+, public broadcast |
| `/freeze [on\|off\|purge]` (alias `/fr`) / `/freeze <player> [on\|off]` / `/freezeall` | Senior Admin+. Bare `/freeze` (and `/freezeall`) toggles a global freeze on every online player, matching TFM |
| `/smite <player> [reason]` | Senior Admin+. Deop, survival, clear inventory, 3×3 lightning strike, kill - matches TFM's `Command_smite` |
| `/orbit <player>` | Senior Admin+. Toggle repeatedly launching a player upward |
| `adminconfig add\|remove\|setrank\|list\|reset <player> [rank]` | **Console/RCON only.** `add` always grants Moderator (no rank argument - use `setrank` for a specific rank); `reset` fully resets every player's rank and requires `adminconfig reset confirm` |
| `/tag set <text>` / `/tag clear` | Personal **prefix** shown before your name in chat (anyone). Session-only - resets when you leave, never persisted |
| `/nickname <name>` (alias `/nick`) / `/nickname clear [player]` | Nickname shown in place of your real name (anyone, persists across sessions, `&`-color codes work); staff can force-clear another player's. Hovering a nickname in chat/tab reveals the real name |
| `/rmcm info` | Plugin name, version, original author, license, website - open to everyone |
| `/rmcm reload` | Reloads `config.yml` live (Senior Admin+) - see the caveat below |

Rank/title prefixes now actually render (chat + tab list), not just exist as data -
exactly **one** prefix shows at a time, never stacked: every title outranks every rank
(titles conceptually sit above the rank ladder), so a Senior Admin who also holds the
Owner title shows only `[Owner]`, not `[SrA][Owner]`. Every unranked player shows `[OP]`
in red, since everyone is vanilla-op'd on a Free-OP server - that's what visually
separates real ranked staff from the auto-op'd masses. Setting a `/tag` **fully replaces**
whatever prefix would otherwise show - `MyTag Name`, never `[Owner]MyTag Name` - as the
sole prefix for the rest of that session, regardless of rank or title. Tags and nicknames
can't spoof rank/title prefixes (text containing `[Owner]`, `[Admin]`, etc. is rejected).
`config.yml`'s `titles` section can also auto-grant a title to specific usernames on join,
no command needed - defaults `owner` to `LightWarp`.

Each rank's prefix can also be retinted/relabeled straight from `config.yml`'s
`rank-prefixes` section (e.g. `senior_admin: "&8[&6SrA&8] &r"`) - picked up live via
`/rmcm reload`, no rebuild needed. Leave an entry blank to keep the hardcoded default.

A ranked/titled player's **name itself** (not just the bracketed prefix) renders in their
rank/title's color too - the same color everywhere: chat, the tab list, and their
overhead nametag in the 3D world (via a scoreboard team - Adventure text APIs alone can't
color that). Unranked, untitled players (the generic auto-op'd `[OP]` case) keep the
vanilla white nametag and an uncolored name - "OPs have no tag color" by design, so a
glance at someone's nametag tells you whether they're real staff.

`/rmcm reload` re-reads `config.yml` and picks up new values immediately for anything
read live (durations, messages, `protect.command-access` rules, tab-list/scoreboard
text, ...). It does **not** retroactively apply `modules.*.enabled` toggles - flipping a
module on/off still needs a restart, since that means registering/unregistering Bukkit
listeners and command trees, which isn't supported live.

### If chat/tab-list prefixes still look wrong with Essentials installed

RigelMCMod renders prefixes itself by default (no dependency needed) - but some
EssentialsX setups run their *own* chat/tab-list formatter driven by whatever a
permissions/chat-bridge plugin (LuckPerms, GroupManager, ...) has registered with
**Vault**, which silently overrides RigelMCMod's own rendering regardless of event
priority. If that's your setup and you see a *stale or foreign* prefix (e.g. an old
LuckPerms group prefix, not one of RigelMCMod's own `Rank`/`Title` values) instead of no
prefix at all, that's the tell.

When Vault plus a Chat provider is detected, RigelMCMod now also pushes its own
rank/title prefix into Vault's `Chat` API on join and on every rank change
(`rank.VaultChatBridge`) - cooperating with whichever plugin is actually doing the
rendering, rather than fighting it for final control of the chat event. This fixes it in
most cases, but RigelMCMod can't control Essentials' *own* `config.yml` - if its chat
format string doesn't include a prefix placeholder at all (`{DISPLAYNAME}` or similar),
no upstream data source will show a prefix; check that separately.

The tab list shows a header ("RigelMC" by default) and a live online-count footer, and
an independently-toggleable sidebar scoreboard (`modules.scoreboard.enabled`) mirrors the
same MiniMessage-configurable title/content style - see `tablist.*` and `scoreboard.*` in
`config.yml`.

All ban/mute commands support `-s`/`--silent` to suppress the staff broadcast. Tiered
command access control is configured under `protect.command-access` in `config.yml`,
using TotalFreedomMod's own rule syntax verbatim (`rank:action:/command [args]:message`,
including `{?}`/`{*}` argument patterns and per-sub-command carve-outs) - studied
directly from TFM's source, not reinvented, so if you already know TFM's config format
you already know this one. Ships with ~50 rules ported from TFM's own default blocklist.
This is also how dangerous op-gated commands from other plugins get clawed back from the
auto-op'd masses (e.g. Essentials' `/tpo`, `/tpohere`, and `/vanish` all default to
Moderator+ here, regardless of which plugin's registration ends up answering the bare
command name) - and an explicitly plugin-namespaced command (`/essentials:tp` instead of
the bare `/tp`) is always blocked outright, matching TFM. The flatlands world also
auto-wipes on a configurable schedule (`world.flatlands.autowipe` in `config.yml`, with a
broadcast warning some minutes before it fires), a rotating broadcaster can cycle through
a configured message list (`announce.broadcast.messages`), and the server-list MOTD can
rotate through configured entries too (`motd.entries`) - all three MiniMessage-colored.

## Building

### Prerequisites

- **JDK 25** to run the actual compile/test steps. You don't need to install it
  yourself first - the Gradle wrapper is configured with the
  [Foojay toolchain resolver](https://github.com/gradle/foojay-toolchains) and will
  download a JDK 25 automatically if your machine doesn't already have one on `PATH`.
- **JDK 17+** to run Gradle itself (any modern JDK works here; it doesn't need to be 25 -
  Gradle provisions the JDK 25 toolchain separately for the actual build).
- No local Paper/Spigot install needed to build - `paper-api` is resolved from Maven
  Central / PaperMC's repository automatically.

### Clone and build

```bash
git clone https://github.com/RigelMC-Org/RigelMCMod.git
cd RigelMCMod
./gradlew build
```

(Windows: use `gradlew.bat build` from Command Prompt/PowerShell, or `./gradlew build`
from Git Bash/WSL.)

This runs the full pipeline - compile, unit tests, Checkstyle, SpotBugs, then packages
the plugin jar. On a clean checkout the first run will take a while (Gradle needs to
download the wrapper's own distribution, then every dependency); subsequent builds are
much faster.

The finished plugin jar is written to:

```
plugin/build/libs/RigelMCMod-<version>.jar
```

This is a **shadow jar**: only this project's own `api` module gets merged in.
Third-party runtime libraries (HikariCP, the SQLite/MariaDB drivers, Discord4J) are
deliberately *not* shaded in - they're resolved at plugin-*load* time instead, via
Paper's `PluginLoader`/`MavenLibraryResolver` mechanism (see `RigelPluginLoader`). This
keeps the jar small and gives clear, version-pinned failure messages if a dependency
can't be resolved, instead of a cryptic `NoClassDefFoundError` at runtime.

### Build script

For convenience, `scripts/build.sh` (Linux/macOS/Git Bash/WSL) and `scripts/build.ps1`
(Windows PowerShell) wrap the commands above into one call, print a clear success/failure
result, and can optionally drop the built jar straight into a test server:

```bash
scripts/build.sh                                   # full build (compile, test, package)
scripts/build.sh --skip-tests                       # faster local iteration
scripts/build.sh --install /path/to/server/plugins  # also copies the jar there
```

```powershell
scripts\build.ps1
scripts\build.ps1 -SkipTests
scripts\build.ps1 -Install C:\mc-server\plugins
```

Both exit non-zero on failure, so they're safe to use in your own scripts/CI.

### Other useful Gradle tasks

```bash
./gradlew test              # unit tests only, skips Checkstyle/SpotBugs/packaging
./gradlew check              # tests + Checkstyle + SpotBugs, no jar
./gradlew build -x test      # skip tests (not recommended, but useful for a quick local jar)
./gradlew clean build        # full rebuild from scratch
```

### Installing on a server

Copy `plugin/build/libs/RigelMCMod-<version>.jar` into your Paper server's `plugins/`
folder and restart. See [Requirements](#requirements) above for the Paper version and
`docs/architecture.md` for the full config reference once it generates `config.yml` on
first run.

## Running a local test server

```bash
./gradlew runServer
```

Downloads Paper 26.1.2 and boots a local test server in `run/`, with the freshly built
plugin already installed. EssentialsX and any of the optional companion plugins above
aren't Gradle-resolvable dependencies - drop their jars into `run/plugins/` manually for
integration testing.

## The web panel

RigelMCMod ships an optional, **off-by-default**, read-only web dashboard (player stats,
active bans, permban cascade cases, schematics) on its own port, split across a few small
pages: `/status`, `/players`, `/bans`, `/mutes`, `/schematics`. It is disabled by default,
binds to `127.0.0.1` when enabled, and has **no authentication at all** - every endpoint is
read-only, so there's nothing to authenticate or CSRF-protect; the security boundary is the
module being off by default plus binding to localhost, not a token. If you expose it beyond
localhost, put it behind your own reverse proxy with TLS. See `modules.webpanel` and the
`webpanel` section in `config.yml`.

## Ban appeals

A second, **separate**, off-by-default web server for a public ban-appeal form
(`modules.appeal`, `appeal` section in `config.yml`) - deliberately its own port/process
from the read-only panel above, since this one accepts public form submissions rather than
only serving GET requests, and meant to live on its **own dedicated subdomain**
(e.g. `appeals.rigelmc.org`, not a path under another site - so its routes sit at the domain
root, no path-rewriting needed in your reverse proxy). A banned player's kick screen shows
a clickable appeal link (once `web.appeal.public-url` is set, e.g.
`https://appeals.your-domain.com`) pointing at `GET /?case=<ref>`. A visitor who didn't
follow that exact link can instead land on `GET /` (no params) and search by their
Minecraft username - it resolves to their current active ban automatically, same appeal
form either way.

Submitting validates the message isn't empty or over `appeal.max-message-length` (default
1000 characters), that the target ban is still active, and that there isn't already a
pending appeal on file for it, then posts to a configured Discord channel
(`discord.appeal-channel-id`, falls back to `discord.admin-channel-id`) with
**Approve**/**Deny** buttons - both require a linked Discord account at least
`discord.appeal-decision-min-rank` (default Moderator). Approving auto-unbans; if the ban
was one entry of a linked case (e.g. `/ban`'s own name+IP pairing, or a `/permban`
cascade), the whole case is lifted, not just that one entry. `GET /status?id=<id>` lets an
appellant check their submission's status afterward.

Submissions are rate-limited per submitter IP (`appeal.rate-limit-window-minutes` /
`-max-per-window`, default 1 per hour) - this is the only genuinely public, unauthenticated,
write-capable surface this plugin exposes, so it gets its own scoped abuse prevention.
Binds to `127.0.0.1` by default - meant to sit behind your own reverse proxy (which is what
actually answers your real subdomain and terminates TLS), not to be exposed directly. If
you're behind **Cloudflare**, set `appeal.client-ip-header` to `CF-Connecting-IP` (defaults
to the more generic `X-Forwarded-For`) so the rate limiter sees real visitor IPs instead of
Cloudflare's own.

## License

[RigelMCMod License](LICENSE) - source-available, not OSI-certified open source (see
`LICENSE` Section 7 for why that distinction matters). You can freely use, study, modify,
and fork RigelMCMod's source for non-commercial purposes. Two restrictions on top of that:

- **Source-only redistribution.** Sharing a built/precompiled `.jar` - of RigelMCMod itself
  or of a fork - requires prior written permission.
- **Commercial rights are reserved.** Selling, sublicensing, or otherwise commercially
  exploiting RigelMCMod or any fork is reserved to RigelMC and Throwdown Media LLP (LLPIN:
  ACF-2930, Chennai, India), who own and operate RigelMC. This does **not** restrict running
  your own Minecraft server (including one that sells ranks/cosmetics to its players) with
  RigelMCMod installed - only reselling the plugin/software itself. Email
  legal@throwdownmedia.net for commercial-use consent.

See `NOTICE` for the standard copyright header.

## Contributing

See `CONTRIBUTING.md`.

# Manual verification checklist

`./gradlew check` (tests + Checkstyle + SpotBugs) covers every piece of *logic* this
project has - state machines, permission gating, protection flag semantics, DAO/service
behavior against a real temp-file SQLite database. It cannot cover anything that needs a
live Bukkit server, a real WorldEdit/FAWE install, or an actual Discord gateway connection
- none of which exist in the sandbox this project is developed in. This file is the
re-runnable manual pass for exactly that gap: re-run the relevant section after any change
that touches the area it covers, and before any release.

Use `xyz.jpenilla.run-paper`'s `runServer` Gradle task to get a local Paper 26.1.2 instance
in `run/` - drop EssentialsX, CoreProtect, LibsDisguises, SkinsRestorer, WorldGuard,
FastAsyncWorldEdit, and PacketEvents jars into `run/plugins/` for full integration coverage
(none of these are on a Gradle-resolvable Maven repo suitable for automated download).

## Economy + Discord invite tracking (newest area - see docs/architecture.md "Economy: Discord-invite-tracked currency")

- [ ] `/balance` (self) and `/balance <player>` (another player, Moderator+) both resolve correctly.
- [ ] `/pay <player> <amount>` moves Coins between two online players; `/pay` with more than the sender's balance is rejected and moves nothing.
- [ ] `/economy give|take|set <player> <amount>` (Moderator+) each work and land a `rigel_audit_log` row; `/economy top` shows the highest balances in order.
- [ ] Set `discord.invite-tracking-guild-id` to a real test Discord server's id and flip `modules.economy.enabled: true`.
- [ ] Confirm the `GUILD_MEMBERS` privileged intent is toggled on for the bot in the Discord Developer Portal (Bot page → Privileged Gateway Intents) - **without this the bot will simply fail to connect once the intent is requested**, not silently degrade.
- [ ] Create a real invite link in the tracked Discord server, have a second Discord account join through it, and confirm a `PENDING` row appears in `rigel_pending_invite_credits` (via direct DB inspection - there's no admin command to list these yet).
- [ ] Set `economy.invites.min-stay-minutes` low (e.g. `1`) for testing, wait past it, confirm the next sweep cycle (`economy.invites.sweep-interval-seconds`) credits the inviter's Coins balance - **only if the inviter has already run `/discord link` in-game and `/link code:...` on Discord**.
- [ ] Repeat without the inviter linked first - confirm the credit stays `PENDING` indefinitely, then link the inviter's account and confirm the *next* sweep cycle pays it out (no expiry).
- [ ] Have a freshly-invited test account leave the Discord server before the minimum-stay window elapses - confirm the pending credit flips to `CANCELLED` and is never paid.
- [ ] Delete the invite link mid-test and confirm `InviteDeleteEvent` stops the bridge from misattributing a later, unrelated join to the deleted code.

## Guild system + plot world (see docs/architecture.md "Guild system: roster, roles, and the plot world")

- [ ] `/guild create <name>` allocates a plot and teleports work via `/guild plot tp`; confirm the plot world (`guild.plotworld.world-name`, default `guildplots`) was created automatically on first enable.
- [ ] `/guild invite <player>` → `/guild accept`/`/guild deny` round-trips; an accepted member shows up in `/guild info`.
- [ ] As a **non-member**, attempt to break/place a block inside another guild's plot - confirm it's denied (Bukkit-event layer).
- [ ] With WorldEdit or FAWE actually installed, attempt a `//set`/`//fill`-style edit spanning into another guild's plot as a non-member - confirm the edit is blocked *inside* the protected region specifically (the `protect.worldedit.extent.ProtectAreaExtent` layer - this is the one piece of protection that genuinely cannot be verified by unit tests, since WorldEdit's own extent pipeline bypasses ordinary Bukkit block events entirely).
- [ ] Place a monster spawner or use a spawn egg inside a guild plot as a non-member - confirm `AreaFlag.MOB_SPAWN` denies it; confirm natural/ambient mob spawns are *not* affected.
- [ ] `/guild kick`, `/guild leave`, `/guild transferowner` each correctly update who can still build on the plot afterward (membership sync).
- [ ] `/guild disband confirm` deletes the plot's protected region and its slot becomes assignable to the next `/guild create`.
- [ ] `/guild plot cosmetic buy <key>` charges the buyer's personal balance and immediately applies the cosmetic; `/guild plot cosmetic apply <key>` re-applies an already-owned one for free with no charge.
- [ ] Load the plot world on an Eaglercraft (1.8-protocol) client and confirm the floor actually renders (the whole reason `CleanroomGeneratorBridge` exists over vanilla superflat).

## Discord bridge (see docs/architecture.md "Discord bridge & admin chat")

- [ ] `/discord link` in-game → `/link code:<code>` as a Discord DM to the bot links the account; confirm the code is single-use (retrying the same code fails).
- [ ] `/console <command>` and, if `discord.console-channel-id` is set, a plain message in that channel, both dispatch correctly for a linked Senior Admin+ account and are rejected for anyone else.
- [ ] `/list` and `/help` (Discord slash commands) both reply visibly in-channel with correct content.
- [ ] Public/admin channel chat relays both directions correctly, including a poster's linked in-game rank prefix rendering on the Discord→game side.
- [ ] Global slash command registration can take up to an hour to actually appear after first connecting - if commands seem missing right after setup, that's expected, not a bug; set `discord.command-guild-id` to a test guild for near-instant propagation while iterating.
- [ ] Ban appeal Approve/Deny buttons (if `modules.appeal.enabled`) correctly gate on `discord.appeal-decision-min-rank` and both approve/deny paths auto-unban / finalize the message correctly.

## Everything else (pre-existing, unrelated to this session's work - re-run before any release)

- [ ] `WorldEditBridge`'s `EditSessionEvent` extent chain against a real WorldEdit/FAWE install - dependency resolution and every API signature used were confirmed via `javap`, but the actual runtime block-interception behavior needs a live server (see architecture.md's "Protected areas: /protectarea" section).
- [ ] Essentials command-name collisions (`/vanish`, `/mute`, `/unban`, ...) - confirm RigelMCMod's own Brigadier registration wins where intended; Bukkit's same-literal-collision resolution order is otherwise unverified outside a real server.
- [ ] `/disguise` (LibsDisguises) and `/skin` (SkinsRestorer) bridges against the real plugins installed.
- [ ] `/punish rollback` against a real CoreProtect install; graceful degradation (a clear message, not an error) when CoreProtect isn't installed.
- [ ] Cage build/restore and world reset actually route through FAWE when installed, with no TPS collapse on a reasonably large area.
- [ ] The public ban-appeal web form (`modules.appeal.enabled`) end-to-end: submit → posts to Discord → Approve/Deny → auto-unban.
- [ ] The read-only web panel (`modules.webpanel.enabled`) - confirmed unreachable when disabled, localhost-only when enabled, schematics download can't escape its configured base directory.

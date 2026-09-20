# Run lifecycle

Read this before implementing death handling, the shop transition, new-run creation, first-join
behaviour, or any state that must reset between runs.

## Product definition

The design rule is:

> **One run has one life.** Death ends that run permanently, the player gets the shop/meta step, and
> the next run is a brand-new world while permanent progression remains.

Until issue #38 this was realised as "one Minecraft world = one run": a new run meant deleting the
save and making another one. It is now realised inside **one save**, as a persistent lobby plus
three disposable run dimensions that are deleted and regenerated while the server keeps running.

A new run therefore no longer needs restarting Minecraft or re-opening the save.

## The shape of it

```
Persistent save
├── hardcore_roguelite:lobby   persistent, never deleted
├── hardcore-roguelite-run.json  the loop's own state, in the save root
│
├── minecraft:overworld        disposable, this run only
├── minecraft:the_nether       disposable, this run only
└── minecraft:the_end          disposable, this run only

Outside the save, shared by every save on the installation:
└── config/hardcore-roguelite-unlocks.json   permanent purchases
```

**A run is the three vanilla dimensions; the lobby is the extra one**, not the other way round.
That is deliberate and load-bearing: vanilla hard-codes which dimension a nether portal and an end
portal lead to, so keeping the run on the vanilla three makes "dimension travel stays inside this
run" true with no code at all. The lobby has no portals, so the only way into a run is starting one.

| Code | Job |
| --- | --- |
| `run/RunPhase`, `run/RunRecord` | The state machine, as plain data. Decides which move is legal. |
| `run/RunStorage` | Reads and writes the record as JSON in the save root. |
| `run/RunLifecycle` | The coordinator. The only thing allowed to change the phase. |
| `run/RunWorlds` | Deleting and rebuilding the three run dimensions. The only Minecraft-internals part. |
| `run/Lobby` | The persistent dimension and getting a player into it. |
| `run/RunEvents` | The seam features hook into: `RUN_STARTED`, `RUN_ENDED`. |
| `run/RunCommand` | `/mhr run`, `/mhr run start [seed]`, `/mhr run end`. |
| `mixin/MinecraftServerAccessor` | The four private server fields building a level out of band needs. |

## The state machine

```
LOBBY --startRun()--> CREATING_RUN --worlds built--> RUNNING --death--> ENDING_RUN --> LOBBY
```

`RunRecord` is a record of six values — phase, run id, seed, start time, completed runs, and the id
of the last run whose reward was committed — and every transition on it throws when the phase does
not allow it. That throw is the enforcement, not a convention:

- a run cannot be started while one is in progress or being built;
- only a `RUNNING` save can end, so a second death in the same tick finds nothing to end;
- the reward can only be committed while `ENDING_RUN` and only when it is still outstanding;
- the lobby cannot be reached until the reward has been committed.

`RunRecord` has no Minecraft in it and is unit-tested in `src/test/.../run/RunRecordTest.java`.
**Put lifecycle rules there rather than in `RunLifecycle`**, where they would need a running game to
test.

## Persistence and recovery

The record lives in `<save>/hardcore-roguelite-run.json`, written whole to a temporary file and
moved into place. It is in the save root rather than in world saved data because the three
dimensions it describes are the ones being thrown away — and it is per save rather than per
installation because two saves are two separate loops.

What a reload makes of each phase (`RunRecord.recovered()`):

| Found on load | Meaning | What happens |
| --- | --- | --- |
| `LOBBY` | between runs | nothing |
| `CREATING_RUN` | crashed while building a run | back to `LOBBY`; that run id is spent and never reused |
| `RUNNING` | the player quit mid-run | the run continues. **Quitting is not dying.** |
| `ENDING_RUN` | crashed while finishing a run | `RunLifecycle` finishes it: commits the reward if `rewardedRunId != runId`, then returns to the lobby |

A **missing** file is a save nobody has played. A file that exists and cannot be read is not, and is
refused: `RunStorage.load` throws, `RunLifecycle` quarantines the save, and no run starts, no run
ends and nothing is written over the file until somebody has looked at it. The unreadable record is
copied to `hardcore-roguelite-run.json.unreadable` first, because it is the only evidence of what
that save was doing.

Reading it as a new save instead would be worse than it sounds. A `RUNNING` record is somebody's run
in progress, and starting over deletes its worlds. An `ENDING_RUN` record is a run owing a reward,
and a new save skips the recovery that hands it over — the payout is gone for good. Neither may be
guessed at.

Parsing is strict for the same reason: every field is required, the phase has to be one this build
knows, and a record that contradicts itself (playing run zero, rewarded for a run that never
started) is refused. Filling a gap in produces a plausible state that never existed, which is
harder to notice than a refusal.

## Once-only boundaries

These operations must not happen twice, and each is guarded by the record rather than by "this
callback normally fires once":

| Operation | Guard |
| --- | --- |
| ending a run | phase must be `RUNNING` |
| counting a completed run | only the `ENDING_RUN -> LOBBY` transition increments it |
| creating the next run | phase must be `LOBBY` |
| granting once-per-run starter items | `RUN_STARTED` fires once per successful run start |

### The reward is at least once, not exactly once

`RUN_ENDED` is the exception and the documentation used to overstate it. The run is written down as
rewarded only *after* the listeners return, so there is a window — a crash, or a failed write —
where the payout happened and the note saying so did not. The next start finds the reward still
owed and calls the listeners again for the same run id.

The lifecycle cannot close that window on its own; only the store being written to can. **A listener
that grants permanent progression must therefore be idempotent for `run.runId()`**: credit against
the id and make a repeat call a no-op. When currency arrives, that means a ledger of paid run ids
next to the balance, not a bare "add N".

The other side is enforced here: a listener that throws is **not** swallowed. The run stays in
`ENDING_RUN` with the reward still outstanding, `/mhr run end` retries it, and the next server start
finishes it. Recording a failed payout as done would lose it for good.

### A run start is all-or-nothing

`RUN_STARTED` fires while the record still says `CREATING_RUN`, and `RUNNING` is written only after
every listener has returned. A crash in that window, or a listener that throws, therefore leaves a
`CREATING_RUN` record — which the next start recovers to the lobby — rather than a `RUNNING` save
that quietly skipped its starter chest, its border, or whatever the shop sells next. The run id is
spent either way, so the retry is a different run and cannot be confused with the abandoned one.

### Nothing advances past the disk

`RunLifecycle.set` writes the record and only then assigns the field, and throws when the write
fails. What the running process believes can never be ahead of what the save says. A failed run-world
delete is the same idea: `RunWorlds.recreate` rebuilds the levels so the server still has an
overworld, then throws, and the run is abandoned rather than played on top of the last one's chunks.

## The run-start hook

Features must not watch unrelated events and infer that a run has begun. They listen:

```java
RunEvents.RUN_STARTED.register((server, overworld, run) -> { ... });
RunEvents.RUN_ENDED.register((server, run) -> { ... });
```

`RUN_STARTED` fires on the server thread after the three dimensions exist and before any player is
in them, so a listener can change the world the player is about to arrive in. The overworld it is
handed is **a different object from the previous run's** — a listener that cached the old one is
holding a closed level.

Two listeners exist today and are the model to copy:

- `border/WorldBorders` puts the selected tier on the run's three new dimensions;
- `starter/RunStart` places the starter chest at the run's overworld spawn.

Neither is called by name from `RunLifecycle`, and `RunLifecycle` does not import either.

## What `RunWorlds` actually does

In order, on the server thread, with every player already in the lobby:

1. replace the server's `WorldGenSettings` with one holding the new seed — `ServerLevel.getSeed()`
   reads it off the server, not off the level, so nothing else would change the terrain;
2. remove the three run levels from the server's level map and close them with `noSave` set;
3. delete their files;
4. build three new `ServerLevel`s the way `MinecraftServer.createLevels` does and put them back;
5. find a spawn in the new overworld and set it.

Step 3 has an asymmetry worth knowing: **the overworld's dimension directory *is* the save
directory.** The nether and the end own `DIM-1` and `DIM1` and are deleted whole; the overworld is
picked apart instead — `region/`, `entities/`, `poi/`, and by name the two pieces of saved data that
belong to a run rather than to a save, `minecraft:raids` and `minecraft:chunk_tickets`. Anything
else you add under `<save>/data` that is run-local has to be added to `RunWorlds.RUN_SAVED_DATA` or
it will survive into the next run.

Step 5 matters because vanilla only chooses a spawn for a world that has never been initialised. By
run two the save has been initialised for a long time, so without this every run after the first
would start at run one's coordinates in terrain that no longer exists.

### The old run's worlds are deleted at the *start* of the next run

Not the moment the player returns to the lobby. Minecraft has an overworld at all times and a great
deal of code, vanilla included, assumes so; deleting the three the instant a run ends would leave
the server without one for as long as the player browsed the shop. Deleting them immediately before
their replacements are built gives the same guarantee — no run ever reuses another run's chunks — at
a moment when there is no gap for anything to notice. The finished run's levels do stay loaded and
ticking while the player is between runs; with no player in them that is cheap, but it is a known
simplification rather than an oversight.

## Moving a player in and out of the lobby

Both of the lobby's doorways go through `Lobby.moveThroughRespawn`, and this is the single most
expensive thing to rediscover in this feature.

**A player must leave and enter the lobby by respawn, not by teleport.** An ordinary
cross-dimension teleport hands the *same* player entity to the destination. That is fine for a
dimension the entity has never been in — every run's overworld is a brand-new `ServerLevel`, so
arriving in a run always works however it is done. The lobby is the same `ServerLevel` for the whole
session, and a player who leaves it by teleport stays registered there. The next arrival collides
with that registration, is only half added, and is never sent a single chunk: the server thinks
they are in the lobby, the client agrees it is in the lobby, and the player stands in what their
client draws as empty void. Every server-side assertion you can write passes while this is
happening, which is why the client GameTest asserts that the lobby's blocks actually arrived.

**A respawn is not finished until the connection knows about it.** `PlayerList.respawn` builds a new
`ServerPlayer` and moves the network connection onto it, but the connection's own `player` field is
separate and still points at the destroyed one. Vanilla's caller — the end-credits branch of
`ServerGamePacketListenerImpl.handleClientCommand` — does:

```java
this.player = this.server.getPlayerList().respawn(this.player, true, RemovalReason.CHANGED_DIMENSION);
this.resetPosition();
```

Take the returned object and stop there and the server holds a connection pointing at a dead
player; in this repository's tests that shows up as `Timeout loading world` on the next reconnect,
not as anything that mentions the lobby.

One residue is left and is deliberately tolerated: the lobby's entity lookup keeps the *destroyed*
entity's entry until the lookup is next disturbed. It is inert — the next arrival is a different
object and `ServerLevel.addPlayer` discards the dead one before adding it — and the client test
asserts the weaker, true property, that no **live** registration is left behind. Do not reach into
entity managers to tidy the dead one.

## Death

`ServerLivingEntityEvents.ALLOW_DEATH` is the hook. Returning false cancels the death outright, so
vanilla's hardcore game-over never becomes part of the loop — there is no spectator mode, no
"delete world" button and no respawn screen in the roguelite.

- dying in a run dimension during a `RUNNING` save ends the run;
- dying in the lobby is revived and logged as a warning, because nothing there should be able to
  kill anybody and a game-over between runs would be the loop breaking;
- anything else is left to vanilla.

The phase moves to `ENDING_RUN` inside the event, so nothing else can end the same run, but the
rest of it — committing the reward, moving the players — is queued for the next tick. Teleporting a
player between dimensions from inside the damage that would have killed them leaves their client
still rendering the world it was told to leave. A second death that arrives while the run is
winding up is revived and ignored.

## State taxonomy

Before adding a field, decide which column it belongs in.

| State | Lifetime | Home |
| --- | --- | --- |
| Purchased unlock / repeatable level | Across saves | `config/hardcore-roguelite-unlocks.json` |
| Future currency balance | Across saves unless design says otherwise | same file |
| Starter item ownership | Across saves | same file |
| Phase, run id, seed, run count, reward committed | The save, across runs | `<save>/hardcore-roguelite-run.json` |
| Generated chunks/entities | One run | the run's dimensions, deleted between runs |
| Player inventory, ender chest, XP | One run | cleared by `RunLifecycle` at run start |
| Lobby contents | The save, across runs | the lobby dimension |
| Selected/active run setup derived from purchases | One run; recomputable at start | applied by a `RUN_STARTED` listener |
| Shop UI screen state | transient | client/server session, not progression |

The deletion test is still useful, with a sharper question than before:

> If the three run dimensions were deleted right now, should the value disappear?

If yes, it is run-local. If no, it belongs in the run record or in permanent progression.

## Where the shop goes

The shop is the lobby's screen, and the between-runs state is `RunPhase.LOBBY`. It should:

- read and write permanent progression (the unlock file, and currency when it exists);
- call `RunLifecycle.get().startRun(OptionalLong.empty())` for "start next run", and show the
  `IllegalStateException` message if that refuses;
- read `RunLifecycle.get().record()` for what to display — runs completed, last run's seed;
- credit currency from a `RUN_ENDED` listener rather than from the death path.

It should not touch `RunWorlds`, mixins, or any feature's internals:

```
shop -> purchase service -> permanent state
RunLifecycle -> RunEvents.RUN_STARTED -> features read permanent state and configure the new run
```

## Multiplayer assumption

Unchanged: one progression profile for the running installation, not per-Minecraft-account
profiles. `RunLifecycle` moves **every** connected player at a run boundary and any player's death
during a run ends it. If per-player or shared co-op progression is wanted later, that is a
product-level migration that has to redefine who owns currency and unlocks, whose death ends a run,
and who enters the shop.

## New run means genuinely new generation

World unlocks affect generation, not already-generated terrain. A new run must therefore create
genuinely new chunks, not merely:

- teleport the player far away;
- clear the inventory in the old world;
- move the world border;
- reuse previously generated chunks.

`RunWorlds` does this by deleting the region files and changing the seed. The client GameTest proves
it by putting a diamond block in each run dimension and requiring it to be gone in the next run.

## Verification

- `src/test/.../run/RunRecordTest.java` — the transitions, the exactly-once rules, and what each
  phase means after a reload.
- `src/test/.../run/RunStorageTest.java` — the record surviving a round trip, a missing file, a
  corrupt file, a partial file, and a crash on either side of the reward.
- `src/gametest/.../client/RunLifecycleClientTest.java` — the whole loop with a real client and a
  real dedicated server: lobby, run, nether travel, death, lobby, second run with a different seed,
  and the lobby and the purchases still standing at the end.
- `src/gametest/.../client/StarterChestClientTest.java` — the run-start hook doing its job once per
  run, across a reconnect and across two runs.

**Known automation gap:** the harness cannot restart a dedicated server against the same save, so
"a process restart resumes the run rather than counting a death" is proven in two halves — the
client test asserts that the file on disk says `RUNNING` mid-run and that a reconnect changes
nothing, and the unit tests assert what each phase is recovered to when that file is loaded. Nothing
exercises a genuine second server process over the same world directory. If the Fabric client
gametest API grows a server restart, close that gap.

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
└── config/hardcore-roguelite-progress.json  permanent purchases and currency
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

The same block covers moving the players in **and** the final write. By the time the record is
committed, everybody is already standing in the new run — reset, upgraded and admitted — so a write
that failed outside that block would leave the save saying `CREATING_RUN` with people playing a run
it does not admit to. That is not merely untidy: the death hook only claims a run-world death while
the record says `RUNNING`, so a death in that state would fall through to vanilla's hardcore game
over. Failing at the commit therefore rolls back exactly like a failed player entry.

### `LOBBY` is a permission, not a description

The rollback has the mirror-image rule, and it is the one that is easy to get backwards. Writing
`LOBBY` is what gives the *next* start leave to delete the three run worlds without asking who is
in them. So the rollback evacuates everybody first, checks afterwards that they are actually out —
"we moved them" and "they are out" are different claims — and only then writes the record.

If evacuation cannot be proved, or the write itself fails, the save **stops** instead: a quarantine
flag is set, the record is left saying the unfinished phase that is true, and every operation that
would move the loop on or write over the record refuses until somebody has looked at it and
restarted the server. An unfinished phase that is true is recoverable. A tidy `LOBBY` that is false
cannot be told from a save that is genuinely fine, and the cost of believing it is a player deleted
along with the world they are standing in.

While a save is stopped, a death in a run world is still taken over and the player revived. The loop
is already in trouble in that state; adding a lost world to it would be the worst available answer.

### Nothing advances past the disk

`RunLifecycle.set` writes the record and only then assigns the field, and throws when the write
fails. What the running process believes can never be ahead of what the save says. A failed run-world
delete is the same idea: `RunWorlds.recreate` rebuilds the levels so the server still has an
overworld, then throws, and the run is abandoned rather than played on top of the last one's chunks.

## The run-start hooks

Features must not watch unrelated events and infer that a run has begun. They listen:

```java
RunEvents.RUN_STARTED.register((server, overworld, run) -> { ... });        // the world
RunEvents.PLAYER_ENTERED_RUN.register((server, player, run) -> { ... });    // one player
RunEvents.RUN_ENDED.register((server, run) -> { ... });
```

**Which of the first two you want depends on whether you are setting up a place or a person**, and
getting it wrong fails quietly:

| | `RUN_STARTED` | `PLAYER_ENTERED_RUN` |
| --- | --- | --- |
| Fires | once, before anybody is in the run | once per player, as they cross into it |
| Good for | border, starter chest, anything about the world | permanent status effects, personal upgrades |
| Relative to the fresh-run reset | before it | **after** it |
| A player who joins the run later | never sees it | sees it |

A permanent status-effect upgrade — the design's own example — belongs on `PLAYER_ENTERED_RUN` and
only works there. On `RUN_STARTED` it would be cleared moments later by `resetForNewRun`, and a
player who was offline when the run started would never have been given it at all.

`RUN_STARTED` fires on the server thread after the three dimensions exist and before any player is
in them, so a listener can change the world the player is about to arrive in. The overworld it is
handed is **a different object from the previous run's** — a listener that cached the old one is
holding a closed level.

Two listeners exist today and are the model to copy, both on the world hook:

- `border/WorldBorders` puts the selected tier on the run's three new dimensions;
- `starter/RunStart` places the starter chest at the run's overworld spawn.

Neither is called by name from `RunLifecycle`, and `RunLifecycle` does not import either.

## What `RunWorlds` actually does

In order, on the server thread, with every player already in the lobby:

1. check that the save can describe all three run dimensions, and refuse if it cannot;
2. replace the server's `WorldGenSettings` with one holding the new seed — `ServerLevel.getSeed()`
   reads it off the server, not off the level, so nothing else would change the terrain;
3. remove the three run levels from the server's level map and close them with `noSave` set;
4. delete their files;
5. build three new `ServerLevel`s the way `MinecraftServer.createLevels` does and put them back;
6. find a spawn in the new overworld and set it.

Step 1 is first for the reason everything destructive is ordered here. A `LevelStem` is the recipe
a dimension is built from and all three come out of the save's own registry, so one can be missing:
a world preset that never defined it, a datapack removed since the save was made. That used to be
discovered inside step 5, by which point the seed was gone and so were the old worlds, and the only
choices left were to carry on a dimension short or to stop with nothing to go back to. It carried
on, and the run was then written down as playable with a third of it missing and its portals
leading nowhere. Asked before step 2, the answer is free: nothing has happened yet, so nothing has
to be undone and the save is exactly as it was.

The general rule, since this is the third round to land on it: **anything that can refuse a run
start must refuse before the first destructive step.** The lobby check and the stem check both do.

Step 4 has an asymmetry worth knowing: **the overworld's dimension directory *is* the save
directory.** The nether and the end own `DIM-1` and `DIM1` and are deleted whole; the overworld is
picked apart instead — `region/`, `entities/`, `poi/`, and by name the two pieces of saved data that
belong to a run rather than to a save, `minecraft:raids` and `minecraft:chunk_tickets`. Anything
else you add under `<save>/data` that is run-local has to be added to `RunWorlds.RUN_SAVED_DATA` or
it will survive into the next run.

Step 6 matters because vanilla only chooses a spawn for a world that has never been initialised. By
run two the save has been initialised for a long time, so without this every run after the first
would start at run one's coordinates in terrain that no longer exists.

### A run is not only its chunks

Since 26.1 a good deal of what one run accumulates does not live in a level at all. It lives on
`MinecraftServer` and in `<save>/data/`, so replacing the three levels leaves it untouched: run 2
would open in run 1's thunderstorm, at run 1's time of day, with the loot tables carrying on the
random sequence run 1 left them in. `recreate` resets it — most of it before the rebuild, because
the new levels read it as they are constructed, and the clocks after it, because setting a clock
tells the connected players and that reads the game rules off an overworld that does not exist in
between.

**The rule, so this does not have to be rediscovered one item at a time.** Something belongs to one
run when *ordinary play inside a run* is what produces it. Something belongs to the save when only
an operator, a datapack or this mod's own progression can have made it — because then the lobby may
own it just as easily as the run did, and nothing in `RunWorlds` can tell which. A roguelite run
boundary is not a save wipe.

Applied to 26.3's server-global state:

| State | Side | Why |
| --- | --- | --- |
| `WeatherData` | per run | rain and thunder countdowns a run rolls through |
| `ServerClockManager` (overworld + end clocks) | per run | time of day, advanced by playing |
| `WanderingTraderData` | per run | spawn delay and chance ratchet over a playthrough |
| `RandomSequences` | per run | loot-table RNG streams advance on every chest opened |
| `GameRuleMap` | per save | server-global in 26.3, and shared with the lobby |
| `ScoreboardSaveData`, `CustomBossEvents`, `Stopwatches`, `TimerQueue`, command storage | per save | only a command or a datapack creates these, and none of them says whether it was for the run or the lobby |
| `StructureTemplateManager`, save version/brand metadata | per save | not gameplay accumulation at all |
| Permanent progression | across saves | the whole point of the loop |

Two traps inside that, both found the hard way:

- **Being reset in memory is not being reset.** Only dirty `SavedData` is written, so a reset that
  never marks it survives until the next restart and no further. `SavedDataStorage.set` marks what
  it is handed — which is how the trader data gets there — but `RandomSequences.clear()` does not,
  because vanilla only ever clears sequences in a world that goes on to re-roll them and re-rolling
  is what marks it. `recreate` calls `setDirty()` itself.
- **A new seed does not reseed an existing sequence.** `RandomSequence` stores the stream's current
  position, not its seed, so a sequence that already exists is unaffected by the world seed
  changing. It has to be removed, not re-derived.

One known gap: filled maps and their id counter are server-global too, so a map drawn in run 1 still
holds run 1's terrain. Nothing can reference one afterwards — map items live in inventories and
ender chests, both emptied at the player boundary — and `SavedDataStorage` has no
cache-invalidation API to drop the orphaned files with. Left alone deliberately.

### The old run's worlds are deleted at the *start* of the next run

Not the moment the player returns to the lobby. Minecraft has an overworld at all times and a great
deal of code, vanilla included, assumes so; deleting the three the instant a run ends would leave
the server without one for as long as the player browsed the shop. Deleting them immediately before
their replacements are built gives the same guarantee — no run ever reuses another run's chunks — at
a moment when there is no gap for anything to notice. The finished run's levels do stay loaded and
ticking while the player is between runs; with no player in them that is cheap, but it is a known
simplification rather than an oversight.

## The run stops at the lobby door

**Run-scoped player state is taken off at the run → lobby crossing, not at the next run start.**
`RunLifecycle.leaveRunForLobby` is that boundary and it is the only one that has to be right.

This was the wrong way round for twelve rounds of review and each round fixed one more field —
respawn point, hunger internals, admission. The list was never the problem. Clearing at the *next*
run start cannot work however complete the list is, because between the two the player is standing
in the lobby, and the lobby is the one world that is never deleted. A chest they place there is
lobby state. An item they drop on its floor is lobby state. Neither is player state any more, and
no later pass over an inventory will ever reach it.

What may cross: the player's identity, and permanent progression. What may not: anything a run gave
them. The list lives once, in `stripRunState`, and both boundaries call it.

Three orderings inside it are load-bearing:

- **The reward is committed before anybody crosses** (`finishEnding` pays, then evacuates). A
  reward listener may want to read the run that is being taken away; strip first and it is handed
  an already-emptied player.
- **The admission is cleared last, and it doubles as the marker.** Being marked as admitted now
  means "has not crossed back out yet". That is what covers the player who was offline when their
  run ended: they log in still marked, the join path notices, and finishes the crossing they never
  made. Without it they would walk into the lobby carrying a deleted world's inventory.
- **Starting a run establishes defaults; it no longer destroys anything.** `enterRun` still calls
  `stripRunState`, because a player can reach a run start without having crossed the other boundary
  — a brand-new save, an interrupted crossing — and what a fresh run begins with should not depend
  on how the player got there. It is a baseline now, not a cleanup.

The test for this is `a-run-does-not-get-out-through-the-lobby-door`, and it asks both halves: the
player arrives with nothing, **and** the lobby is holding nothing of theirs. A strip that dropped
the items instead of destroying them would pass the first on its own.

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

- dying in a run dimension during a `RUNNING` save **while admitted to that run** ends the run;
- dying in a run dimension any other way is revived and ends nothing;
- dying in the lobby is revived and logged as a warning, because nothing there should be able to
  kill anybody and a game-over between runs would be the loop breaking;
- anything else is left to vanilla.

### Being in the run's world is not being in the run

The first rule used to stop at the dimension, and the two are different questions.
`minecraft:overworld` says the run's world is underfoot. `RunAdmission.isAdmittedTo` says this
player crossed *this* run's start boundary, was reset for it and was given what it owes them.

Anyone can be in the first position without the second: an entry that threw part-way, an operator
who teleported in, a player still being got back out after a failed join. Letting a death there end
the run means somebody who never joined it can finish a shared run for everybody else, with the
reward paid and the worlds deleted. So admission is the single authority on membership, and a
death without it is that player's own business — revived, nothing else touched.

The other half of the same rule is that nobody is left in that position on purpose. A late join
whose `PLAYER_ENTERED_RUN` listener throws has already moved the player and reset them, so the
catch takes them back out to the lobby, and disconnects them if even that fails. They stay
un-admitted either way, so rejoining runs the whole entry again. Note that the catch has to look
the player up by id: leaving the lobby is a respawn, so the object it is holding may be the
destroyed one and moving that moves nobody.

The phase moves to `ENDING_RUN` inside the event, so nothing else can end the same run, but the
rest of it — committing the reward, moving the players — is queued for the next tick. Teleporting a
player between dimensions from inside the damage that would have killed them leaves their client
still rendering the world it was told to leave. A second death that arrives while the run is
winding up is revived and ignored.

## State taxonomy

Before adding a field, decide which column it belongs in.

| State | Lifetime | Home |
| --- | --- | --- |
| Purchased unlock / repeatable level | Across saves | `config/hardcore-roguelite-progress.json` |
| Future currency balance | Across saves unless design says otherwise | same file |
| Starter item ownership | Across saves | same file |
| Phase, run id, seed, run count, reward committed | The save, across runs | `<save>/hardcore-roguelite-run.json` |
| Generated chunks/entities | One run | the run's dimensions, deleted between runs |
| Player inventory, ender chest, cursor, XP, hunger, respawn point, effects | One run | `RunLifecycle.stripRunState`, as the player **leaves** the run |
| Weather, world clocks, wandering-trader timer, named random sequences | One run | server-global; reset by `RunWorlds.recreate` |
| Game rules, scoreboard, bossbars, stopwatches, scheduled events, command storage | The save, across runs | server-global; deliberately **not** reset — see the rule above |
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
- `src/gametest/.../client/ProgressionCycleClientTest.java` — the loop as one player experience:
  a restricted run, a death, currency spent on a real shop screen in the lobby, and a second run
  that is both a fresh world and a better one. It is the only test that crosses all four seams in
  one sequence, and it is one scenario walked in six named steps rather than six scenarios.

`a-run-cannot-start-without-all-three-of-its-dimensions` is the one that needs a save in a state no
world preset can be asked for: the nether's `LevelStem` is lifted out of the frozen registry for the
length of the scenario and put straight back (`gametest/mixin/MappedRegistryAccessor`). It asserts
the refusal *and* that the previous run's block and seed are untouched, because "nothing was
destroyed" is the claim that makes refusing safe.

The failure paths are driven rather than reasoned about. A listener that throws covers a failed
run start, a failed player entry and a failed reward. The record write is the one thing a test
cannot make fail by asking, so `fi.vilpponen.mhr.gametest.TestFaults` arms a countdown and a
test-only mixin turns that many `RunStorage.save` calls into failures. One armed failure catches
the commit and proves the rollback; two catch the rollback's own write and prove the save stops
rather than claiming the lobby. The fault is armed from inside the `RUN_STARTED` hook, because that
is the only place between a start's two record writes.

**Known automation gap:** the harness cannot restart a dedicated server against the same save, so
"a process restart resumes the run rather than counting a death" is proven in two halves — the
client test asserts that the file on disk says `RUNNING` mid-run and that a reconnect changes
nothing, and the unit tests assert what each phase is recovered to when that file is loaded. Nothing
exercises a genuine second server process over the same world directory. If the Fabric client
gametest API grows a server restart, close that gap.

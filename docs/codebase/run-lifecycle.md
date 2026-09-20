# Run lifecycle

Read this before implementing death handling, the shop transition, new-world creation, first-join
behaviour, or any state that must reset between runs.

## Product definition

The design rule is:

> **One Minecraft world = one run.**

A run has one life. Death eventually ends that run permanently, the player gets the shop/meta step,
and the next run starts in a brand-new world while permanent progression remains.

That full loop is **not implemented yet**.

Do not infer otherwise from the existence of `RunStart`: today it owns the starter-chest
once-per-world behaviour, not the whole roguelite lifecycle.

## What exists today

### Permanent progression survives outside the world

`UnlockState` is stored under the Fabric config directory, not in the world.

Deleting the current world therefore leaves unlock ownership intact.

See `docs/codebase/progression.md`.

### A world can own run-local flags

`starter.RunStart` stores whether the starter chest has already been granted in Minecraft
`SavedData`.

The flag is read from the **overworld's** data storage even if another dimension is involved.

That choice is important: "once per run" means once per whole world/server save, not once per
dimension.

### Starter chest happens on first player join

The current chest is placed when the first relevant player joins because placement is relative to a
player and no player exists at raw world creation time.

The once-per-run flag is claimed before placement. A placement failure must not create a new chest
on every reconnect.

Reconnect therefore is not a new run.

A second player joining the same world is not a new run.

Deleting that world and creating another is a new run, so the new world's run-local flag begins
unset and the permanent starter-item purchases are granted again.

### World border is currently only partially integrated

The world-border feature applies a selected tier to a running world and all dimensions.

The selected tier currently lives in memory and resets to its default when the process starts.
Permanent shop ownership/selection of border tiers has not been wired into the lifecycle yet.

Do not create a second border-specific run persistence mechanism when that integration is built.

## State taxonomy

Before adding a field, decide which column it belongs in.

| State | Lifetime | Home |
| --- | --- | --- |
| Purchased unlock / repeatable level | Across runs | permanent progression outside world |
| Future currency balance | Across runs unless design says otherwise | permanent progression outside world |
| Starter item ownership | Across runs | permanent progression outside world |
| "Starter chest already granted" | One run/world | overworld SavedData |
| Generated chunks/entities | One run/world | Minecraft world |
| Player inventory during a run | One run/world | Minecraft player/world data |
| Selected/active run setup derived from purchases | One run; recomputable at start | run setup/world state |
| Shop UI screen state | transient | client/server session, not progression |
| Test-only setup | one scenario | GameTest state |

The deletion test is useful:

> If the current world directory disappeared, should the value disappear?

If yes, it is run-local. If no, it belongs in permanent progression.

## Planned lifecycle contract

The intended high-level transition is:

```
permanent progression
        |
        v
create fresh run/world
        |
        v
derive run setup from purchases
        |
        v
first join / starter delivery
        |
        v
play one-life Hardcore run
        |
      death
        |
        v
finalize run rewards
        |
        v
shop / permanent purchases
        |
        v
dispose old world
        |
        +----> create next fresh run
```

The implementation may refine the mechanics, but it should preserve these ownership boundaries.

## The transition must be explicit

When the death/shop/new-run system is implemented, avoid a collection of unrelated event listeners
that each infer whether a new run has happened.

Prefer one lifecycle coordinator/state machine with explicit transitions and narrow collaborators.

The coordinator should own questions such as:

- is this run active or ended?
- has its reward been finalized?
- is the player in the shop/meta phase?
- has a next world been created?
- which world is the current run?

Feature code should not independently decide that "a death probably means reset now".

This is future architecture guidance; there is no `RunManager` to preserve today.

## Exactly-once boundaries

The lifecycle will contain several operations that must happen exactly once:

- ending a run;
- computing/finalizing its reward;
- crediting permanent currency;
- opening/entering the shop state;
- applying one purchase;
- creating the next run;
- granting once-per-run starter items.

Design persistence so a crash/reconnect/retry cannot silently execute those twice.

The starter chest's world-owned `Granted` flag is the current small example of this principle.

Future currency/reward code should have equivalent idempotency rather than relying on "this callback
normally fires once".

## New world means genuinely new generation

World unlocks affect generation, not already-generated terrain.

A new run must therefore create a genuinely new world, not merely:

- teleport the player far away;
- clear the inventory in the old world;
- move the world border;
- reuse previously generated chunks.

This matters for trees, ores, villages and chunk-populated animals.

The permanent unlock state is applied to generation of the next world/fresh chunks.

## Dimension scope

Overworld, Nether and End are dimensions of the **same run**, not separate runs.

Run-local flags that mean "once per run" need one authoritative home, normally the overworld or a
server/world-level coordinator.

Do not grant meta rewards, starter kits or run transitions once per dimension.

World-border setup is dimension-specific geometry but one run-level tier.

## Multiplayer assumption

The current design and progression storage are effectively one progression profile for the running
installation, not per-Minecraft-account profiles.

Do not casually introduce player UUID keyed permanent state inside one feature.

If per-player or shared-co-op progression is desired later, that is a product-level migration that
must redefine:

- who owns currency;
- who owns unlocks;
- whose death ends a run;
- who enters the shop;
- how starter items are granted.

Until such a decision exists, preserve the current single progression-state model.

## Shop is between runs, not a gameplay feature

The future shop should operate on the permanent catalogue/currency layer and request the next run;
it should not directly mutate every mixin or world object.

A clean direction is:

```
shop -> purchase service -> permanent state
run coordinator -> reads permanent state -> configures new run
features -> read authoritative progression/run setup
```

Avoid:

```
shop -> TreeGenerationMixin
shop -> OreGenerationMixin
shop -> equipment internals
```

## Verification requirements for lifecycle work

Lifecycle changes need end-to-end tests because helper tests cannot prove exactly-once behaviour.

Important future scenarios include:

- first join of fresh run grants once-per-run setup;
- reconnect to same run does not duplicate it;
- death finalizes a run exactly once;
- reconnect/restart around death cannot double-credit currency;
- old world is not reused for the next run;
- permanent unlocks survive into a genuinely new world;
- run-local flags do not survive into that new world;
- worldgen in the next run reflects purchases made in the shop;
- entering Nether/End does not create another run;
- a failed transition leaves recoverable state rather than half-crediting permanent progression.

Use Client GameTests for real connect/reconnect/screen flow and fresh dedicated servers/worlds where
that is the acceptance criterion.

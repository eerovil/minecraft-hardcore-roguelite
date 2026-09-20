# Writing gameplay tests

Read this before adding or changing tests under `src/gametest/`.

`docs/dev-environment.md` explains **how the test environment runs**. This document explains
**how to write tests that prove gameplay rather than merely turn green**.

## Canonical verification

The whole repository gameplay check is:

```sh
scripts/dev.sh gametest
```

It runs, in order:

1. plain JUnit;
2. Fabric server GameTests;
3. Fabric Client GameTests.

It runs in the dedicated Kubernetes GameTest pod. Client tests use a real Minecraft client under
Xvfb with Mesa software rendering.

Use focused Gradle tasks while iterating if useful, but the final runtime claim should be backed by
the canonical command.

## Pick the right tier

### JUnit

Use for code that is truly Minecraft-independent:

- JSON/balance parsing;
- validation;
- arithmetic;
- pure transformations.

Do not mock half of Minecraft just to keep a gameplay rule in JUnit.

### Server GameTest

Use when the server can prove the real behaviour:

- direct feature placement;
- spawn-rule hooks;
- block/entity state;
- border arithmetic/application;
- command behaviour that needs no real client.

This is the preferred gameplay tier when it is sufficient because it is much faster than booting a
client.

### Client GameTest

Use when the acceptance criterion needs a real client:

- actual inventory/crafting GUI interaction;
- mouse/keyboard paths;
- recipe book;
- client/server synchronized display state;
- first join/reconnect;
- screens;
- a real portal/player transition when server-only simulation would hide the bug.

Do not choose Client GameTest merely because it is "more end-to-end"; use it when it proves
something the server tier cannot.

### Fresh-world Client GameTest

Some behaviours require an ordinary generated overworld or a new dedicated server, not the
controlled/superflat server GameTest world.

Current examples:

- tree worldgen;
- ore worldgen including deep veins;
- animal chunk population;
- villages;
- true first-join/new-world starter chest semantics.

Use the existing fresh-world patterns before inventing another launcher.

## Scenario structure

A good scenario has four visible parts:

1. establish all required state;
2. perform the real action;
3. assert the authoritative result;
4. include enough context in a failure to diagnose it.

Tests should log/label scenarios consistently so one full run can report multiple independent
failures.

Do not make scenario B depend on scenario A having passed or having left state behind.

A failed scenario may stop halfway. The next one still has to establish a clean starting point.

## Establish state explicitly

Each scenario must set what it depends on:

- relevant unlock levels;
- inventory/equipment;
- gamerules;
- border tier;
- config override;
- world/region freshness;
- entities/blocks;
- client screen state.

Do not rely on defaults merely because they happen to match today if the test's meaning depends on
them.

This is especially important for tests that travel far from spawn: the roguelite border and harness
mob-spawning defaults can otherwise make "nothing generated" look like a successful lock.

## Positive controls

A suppression test that only asserts absence is weak.

Examples:

Bad:

> locked cow test generated no cows.

That could pass because mob spawning was disabled.

Good:

> the same controlled path produces cows when unlocked, and suppresses cows when locked.

The same principle applies to:

- trees;
- ores;
- villages;
- enchant rolls;
- portal transitions;
- any hook whose failure mode is "nothing happened".

Whenever practical, put the negative and positive/control cases next to each other.

## Worldgen rules

Generation is one-shot per chunk.

Therefore:

- set unlock state before the chunk exists;
- use a fixed seed for comparisons;
- use fresh coordinates/worlds for different generation states;
- do not unlock something and rescan already generated terrain expecting it to change.

For expensive fresh-world scans, add a control block/count proving the world/seed actually matched
between comparison runs.

The ore tests' bedrock/seed controls and the village tests' separate worlds are examples to copy.

## Random vanilla behaviour

Do not make CI depend on one lucky random roll.

Options, in preferred order:

1. make the input seed/state deterministic;
2. test the rule before the random choice if that still proves the contract;
3. run enough controlled attempts that failure probability is negligible;
4. pair the result with a positive control.

Document why the chosen sample size is reliable if probability remains.

Do not "fix" a flaky test with retries.

## Real client input

Use helpers in
`src/gametest/java/fi/vilpponen/mhr/gametest/client/TestPlayer.java`.

The helper intentionally distinguishes:

- sending real input;
- querying the authoritative server state;
- querying what the client has been told.

That separation is useful. A GUI can visually update while the server still rejects the action, or
vice versa; test the side relevant to the acceptance criterion.

Before adding a new direct input hack, check whether `TestPlayer` should grow one reusable helper.

Known 26.3 traps are documented in `docs/dev-environment.md`, including:

- SDL mouse button numbering;
- shift-click modifier handling.

Do not duplicate those workarounds in each feature test.

## Authoritative assertions

Prefer asserting the state that owns the rule.

Examples:

- equipment legality -> server equipment/inventory;
- client padlock -> synchronized client display state plus screenshot where useful;
- starter chest -> world block/inventory state;
- unlock persistence -> persisted/reloaded `UnlockState`;
- border -> server world border and real arrival where geometry matters.

A screenshot alone is not an assertion that a server rule worked.

### A command aimed at a player can miss

`/kill Player0` and friends resolve the name through the player list, and moving a player between
dimensions builds a **new** `ServerPlayer`. A scenario that teleports and then kills can therefore
hit an object the server has already replaced: nothing happens, and the command still reports
"Killed Player0". The same is true of `/tp`, `/setblock` at a player's coordinates and anything
else that resolves a player or a position — a server command also runs in the **overworld** unless
an `execute in <dimension>` says otherwise, which matters constantly now that the lobby exists.

Two habits fall out of it, and both are already in the helpers:

- read player state through `TestRuns.livePlayer`, never through a `ServerPlayer` the test has
  been holding across a dimension change;
- make the effect visible rather than assuming it. Wounding a player before killing them turns
  "they were revived" into a real assertion; without it, a kill that silently never happened
  leaves a healthy player and looks exactly like one that was correctly taken over.

## Conservation assertions

Whenever gameplay moves/rejects/grants a resource, assert conservation.

For items, consider all legitimate locations:

- inventory;
- equipment;
- carried/cursor stack;
- nearby world drops;
- container/chest contents.

`TestPlayer.reachableCount` and `whereItIs` are the current equipment-test pattern.

Future currency tests should do the analogous accounting: one purchase must deduct once and grant
once.

## Screenshots

Screenshots are valuable evidence for UI/client/world tests.

Use them to:

- explain a failed client scenario;
- preserve a useful visual example of a feature;
- debug what the headless client actually saw.

Failure screenshots should have deterministic names tied to the scenario.

Stable examples that are useful to humans may be copied/committed under `docs/images/`.

Do not make pixel-byte equality the only correctness criterion unless a future issue explicitly
builds a visual-regression system.

## Negative control

A new/changed gameplay feature is not considered fully verified until the test has demonstrated that
it can catch a meaningful regression.

During implementation:

1. deliberately break or bypass an important rule;
2. run the relevant test;
3. confirm it fails for the intended reason;
4. restore the correct code;
5. rerun green.

Good negative controls break the production seam, not the assertion.

Examples already used:

- remove Nether border coordinate scaling;
- remove starter-chest once-per-run protection;
- cap a double chest incorrectly;
- bypass ore-generation suppression.

Record the negative control in the PR.

## Fresh server / reconnect tests

When the contract talks about:

- first join;
- reconnect;
- process reload;
- new run;
- persistence;

exercise that boundary rather than calling only the inner helper.

It is fine to have a fast helper-level server test **and** a slower client/dedicated-server test.
They answer different questions.

Starter chest is the model: fast exact chest-content tests plus real join/reconnect/new-world
scenarios.

## Config/balance reload tests

If a feature promises data-driven tuning:

- modify a controlled override;
- run `/mhr reload` or the real reload path;
- verify the next relevant gameplay action observes the new value;
- restore/clean the override.

Do not only assert that `BalanceManager` parsed the value if the acceptance criterion is that live
gameplay changes.

## Test helpers are production-quality test infrastructure

Shared helpers should:

- have narrow semantic names;
- wait for packets/ticks rather than arbitrary long sleeps;
- fail near the real cause;
- verify that input landed on the intended control;
- expose server/client queries separately;
- avoid feature-specific policy when a generic primitive will do.

If a helper discovery is Minecraft-version-specific and likely to bite the next agent, document it
in `docs/dev-environment.md`.

## Failure diagnosis

First classify the failure:

**Before any named scenario starts**

Likely harness/environment: stale client JVM, occupied port, Xvfb/GL context, cold pod, source
workspace collision.

**Named scenario fails with assertion**

Treat as product/test feedback. Read the assertion, log and screenshot.

**Many unrelated scenarios suddenly fail**

Suspect shared workspace/port contention or common harness state before refactoring multiple
features.

Use a unique workspace for concurrent agent runs:

```sh
MHR_GAMETEST_WORKSPACE=/pvc/gametest/workspace-<unique-name> scripts/dev.sh gametest
```

See `docs/dev-environment.md` for the stale-`KnotClient` cleanup command and artifact paths.

## What to put in a PR

State:

- which scenarios were added/changed;
- what real path each proves;
- canonical verification result;
- negative control and the failure it produced;
- screenshots/artifacts if they materially help;
- any genuine remaining automation gap.

Do not write "tested manually" for deterministic behaviour the harness can cover.

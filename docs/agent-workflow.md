# Working on this repo as an agent

This document describes how an implementation or review agent should drive work in this repository.
The root `CLAUDE.md` is the repo-wide index and non-negotiable rules; this file is the workflow
behind them.

## Issue tracker and ownership

Implementation work is driven from GitHub Issues.

- The `claude` label means the AgentDeck/Poller workflow owns the card.
- Respect explicit dependencies in the issue body such as `Blocked by #N`. Do not implement a
  blocked card while the dependency is still unresolved. The fact that a worker session was
  started does not cancel the blocker.
- Treat the issue's acceptance criteria as the contract. If implementation reveals a real
  contradiction, record it on the issue instead of silently weakening the criterion.
- Keep one issue focused on one feature or infrastructure seam. Do not opportunistically fold
  unrelated refactors or balance changes into it.

For an implementation PR, use the issue number in the PR body and close the issue with the PR when
the work really satisfies it. Existing work uses `Closes #N`.

## Before changing code

Read the issue, then the smallest set of repository docs that establishes the contract:

- `README.md` — current product shape and implemented unlocks.
- `docs/design-v0.2.fi.md` — progression/game-design intent.
- `docs/open-questions.md` — decisions deliberately not settled yet.
- `docs/balance.md` — balance schema, overrides and reload behaviour.
- `docs/dev-environment.md` — build, Kubernetes, GameTests and known harness traps.

Then inspect the existing implementation and its tests. Extend the established seam rather than
creating a parallel subsystem.

If the issue changes a balance-sensitive value, confirm whether it belongs in
`src/main/resources/default-balance.json` before writing a number in Java.

## Shape of a change

Prefer thin, isolated feature changes.

- Gameplay mechanics live in their feature package under `src/main/java/fi/vilpponen/mhr/`.
- Cross-cutting Minecraft hooks live in `mixin/`, but a mixin should delegate quickly to feature
  code rather than becoming the feature itself.
- Balance/config loading belongs in `core/`.
- Dev commands are adapters for development and verification, not the source of game state.
- Client-only presentation stays client-side; server-authoritative rules must not depend on the
  client's synchronized display cache.
- Tests belong with their appropriate tier: `src/test/` for pure Java and `src/gametest/` for
  Minecraft behaviour.

Do not refactor adjacent features just because the same Minecraft class is convenient to hook.
Several early issues were intentionally split to avoid overlapping code; keep that property where
possible.

## Implementation loop

For gameplay work, the normal loop is:

```
read issue + relevant docs
-> implement the smallest complete behaviour
-> add executable acceptance tests
-> scripts/dev.sh gametest
-> inspect logs/screenshots/state
-> fix
-> repeat until green
-> run a negative control
-> restore
-> scripts/dev.sh gametest green
-> open/update PR
```

A failed GameTest is feedback, not a reason to hand the remaining verification to a human.

Use `scripts/dev.sh build` or a focused Gradle task while iterating when it gives a faster answer,
but the final gameplay claim still needs the canonical `scripts/dev.sh gametest` run unless the
issue is purely documentation or otherwise cannot affect runtime behaviour.

## Verification tiers

Use the cheapest tier that proves the acceptance criterion:

1. **JUnit** — pure parsing, balance, arithmetic and data transformations.
2. **Server GameTest** — Minecraft behaviour that does not need a real client.
3. **Client GameTest** — GUI, input, joining/reconnecting, recipe book, synchronization, or a path
   whose correctness depends on a real client.
4. **Fresh-world Client GameTest** — worldgen or run-start behaviour that needs an ordinary world,
   fresh chunks, or a real first join.

Do not replace an end-to-end criterion with a helper-unit test. A helper test is useful in addition
to the game-facing path, not instead of it.

Every meaningful gameplay change needs a negative control before completion: break or bypass one
important rule on purpose, prove the intended test fails for the right reason, restore the code,
then prove green again.

## Worldgen tests

Worldgen has stricter rules than ordinary state tests:

- Generate under the state you are testing; changing an unlock cannot rewrite already-generated
  chunks.
- Use fixed seeds and fixed coordinates for comparisons.
- Use different fresh regions/worlds for before/after states when generation is one-shot.
- Include a positive/control case. "Found zero" is not proof if the setup could also generate
  nothing in vanilla.
- Remember that the test harness normally suppresses incidental mob spawning and the roguelite
  world border starts small. Worldgen/spawn tests that need distant normal terrain must explicitly
  establish the necessary gamerules and border state.

## Client tests and screenshots

The headless client is a real Minecraft client rendered under Xvfb/Mesa. Use the shared helpers in
`src/gametest/java/fi/vilpponen/mhr/gametest/client/` before adding new input machinery.

For visual/client failures:

- assert game state programmatically;
- capture a screenshot when it helps explain the failure;
- use deterministic scenario names so artifacts are easy to associate with tests;
- do not make human screenshot inspection the ordinary pass/fail gate.

Stable screenshots that are useful as project evidence may be copied into `docs/images/`.
Transient failure evidence belongs in `build/gametest/`.

## Shared-cluster rules

The Kubernetes environment is shared.

- Do not assume the persistent dev server contains your branch or your world.
- Runs are serialized: `sync`, `build`, `deploy` and `go` take the build pod's lock and `gametest`
  takes the gametest pod's, for the whole command. If another worker is active your run **waits**
  and says so. That wait is normal, not a failure — do not work around it by starting a second run
  or by execing into the pod by hand. See `docs/dev-environment.md#the-run-lock`.
- A run that dies on `Address already in use` for port 25565, or on a SIGTERM from another worker's
  cleanup, is harness residue and not evidence about your change. It should not happen now; if it
  does, report it against the harness rather than editing product code to suit it.
- A private GameTest workspace is still available when you want one, for instance to keep an
  experiment around between runs:
  ```sh
  MHR_GAMETEST_WORKSPACE=/pvc/gametest/workspace-<unique-name> scripts/dev.sh gametest
  ```
- Never infer a product regression from a run that failed before a named scenario started.

## Reviewing and fixing a PR

Review the behaviour the issue asked for, not just the diff.

Check:

- acceptance criteria versus executable coverage;
- whether the test actually reaches the claimed Minecraft path;
- positive and negative/control cases;
- persistence and conservation invariants where state/items move;
- fresh-world semantics for worldgen/run-start features;
- hard-coded balance values that should be data;
- mixin scope and unintended effects on vanilla behaviour;
- client/server authority boundaries.

When fixing a review finding on the same branch, rerun the smallest focused tests that prove the
fix while iterating. Before the PR is finally claimed green, run the canonical gameplay verification
if runtime behaviour changed.

Do not "fix" a failing test by weakening the expectation unless the issue contract itself was
wrong and that change has been made explicit.

## PR evidence

A gameplay PR should say, concisely:

- what changed;
- which acceptance criteria are executable;
- which GameTest/JUnit scenarios cover them;
- the result of `scripts/dev.sh gametest`;
- what negative control was run and which test caught it;
- any real automation gap and its concrete technical cause.

If screenshots materially demonstrate the feature, mention where they are.

Compilation alone is never evidence for a gameplay acceptance criterion.

## When human playtesting still matters

Human playtesting is valuable for:

- feel and pacing;
- balance;
- subjective UI quality;
- whether the roguelite loop is fun and understandable.

It is not the fallback for deterministic behaviour that the GameTest harness can exercise.

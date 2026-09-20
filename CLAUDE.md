# Claude development instructions

These rules apply to every implementation task in this repository.

## Definition of done: executable acceptance criteria

A gameplay feature is **not done when the code is written**. It is done only when its important
acceptance criteria are covered by automated executable tests and the repository verification
command is green.

For every new gameplay feature or gameplay bug fix:

1. Implement the behaviour.
2. Add or update automated tests that exercise the real behaviour described by the issue.
3. Run the repository verification loop:
   ```sh
   scripts/dev.sh gametest
   ```
4. Inspect failures and artifacts, fix the implementation, and rerun until green.
5. Before claiming the feature verified, perform a **negative control**: deliberately break or
   simulate breaking at least one important rule and prove that the relevant test turns red.
6. Restore the correct implementation and rerun the suite green.

Do not leave "verify manually in Minecraft" as the normal completion path when the behaviour can be
exercised by the existing GameTest harness.

## Choose the lowest-level test that proves the behaviour

Use the cheapest test that still proves the real acceptance criterion:

- Plain JUnit for pure Java/data/balance logic.
- Fabric server GameTests for gameplay that does not require a client.
- Fabric Client GameTests when real client behaviour matters: GUI interaction, mouse/keyboard input,
  client/server synchronization, joining/reconnecting, recipe-book interaction, or other paths that
  cannot honestly be proven server-side.
- For world-generation behaviour, use deterministic seeds and genuinely fresh chunks/worlds when
  the feature depends on generation time. Do not "verify" worldgen by scanning chunks generated
  before the state change.

Prefer testing the public/game-facing path over calling a helper directly when the acceptance
criterion is specifically about that path. Helpers may have fast focused tests in addition to, not
instead of, the end-to-end scenario.

## Reuse the existing harness

Do not build a second test system for a feature.

The canonical command is:

```sh
scripts/dev.sh gametest
```

It runs unit tests, server GameTests and Client GameTests headlessly in the Kubernetes gametest pod
and returns a failing exit status if verification fails.

The harness already provides:

- isolated test run directories,
- a real dedicated server for server/client scenarios,
- a real Minecraft client under Xvfb + software OpenGL,
- reusable client interaction helpers,
- logs and screenshots copied to `build/gametest/`,
- deterministic scenario markers and failure messages.

Read `docs/dev-environment.md#automated-gameplay-tests` before changing the harness or adding a new
kind of client interaction. Reuse existing test helpers and patterns from `src/gametest/`.

## Test isolation and determinism

Every scenario must explicitly establish the state it depends on. Tests must not rely on:

- a previous scenario having unlocked or locked something,
- a persistent development world,
- existing player inventory,
- a previous config override,
- test execution order,
- terrain generated under a different unlock state.

Use fixed seeds/coordinates where randomness or worldgen would otherwise make the result flaky.
When vanilla randomness is itself part of the path, use enough deterministic/repeated attempts that
the test is reliable and include a control proving the setup can succeed.

A test that can pass because "nothing happened" is incomplete unless it has a positive/control case
showing that the same setup produces the vanilla result when the feature permits it.

## Client tests and screenshots

When a client-side scenario fails, capture a screenshot when it gives useful evidence. Screenshots
are debugging/evidence artifacts, not the sole assertion for gameplay state.

For UI features, combine visual evidence with programmatic assertions of the underlying state.
Prefer failure screenshots with deterministic names tied to the scenario.

Do not require a human to inspect screenshots for the normal pass/fail decision unless the issue is
explicitly about subjective visual quality.

## Item/state conservation

For mechanics that reject, move, replace or grant items, test conservation explicitly where
relevant. A rejected action must not silently destroy or duplicate an item. Count the item anywhere
the player can legitimately reach it, including inventory/equipment/cursor/world drops when the
feature can move it between those locations.

Apply the same principle to currency, unlock state and other persistent resources: test the
observable before/after invariant, not only that one field changed.

## Issue and PR evidence

The implementation PR should explain:

- which acceptance criteria are covered,
- which tests exercise them,
- the result of `scripts/dev.sh gametest`,
- the negative control that was used and which test caught it,
- any genuine remaining gap that cannot be automated, with the concrete technical reason.

Do not claim a scenario was tested if only compilation or an internal helper was exercised.

## Iteration rule

When a test fails during implementation, treat the failure as feedback and continue iterating on the
implementation in the same task. Do not stop at "tests fail and need manual verification" when the
failure can be investigated from logs, screenshots, game state or additional automated assertions.

The desired loop is:

```
implement -> gametest -> inspect -> fix -> gametest -> ... -> green
```

Human playtesting is still useful for feel, balance and subjective presentation. It is not a
substitute for executable acceptance tests for deterministic gameplay behaviour.

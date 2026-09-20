# Minecraft Hardcore Roguelite

A Fabric mod that turns Minecraft Hardcore into a roguelite.

One world is one run. Death ends the run; permanent progression survives into future runs. The
starting game is deliberately missing pieces of vanilla Minecraft, and the player buys those pieces
back. After vanilla is restored, more expensive Vanilla+ unlocks can push the run beyond vanilla.

This file is the repo-wide map and the rules that apply to every agent session. Detailed operational
guidance lives in `docs/agent-workflow.md`, `docs/dev-environment.md` and `docs/codebase/`.

## Product rules that should shape implementation

These are deliberate design constraints, not implementation accidents:

- A good unlock is obvious and easy to understand.
- Prefer binary/on-off restrictions over hidden percentage nerfs.
- Difficulty comes from an incomplete world, not from making ordinary actions grindier.
- Balance-sensitive numbers belong in data, not feature code.
- Permanent unlock state survives runs; world/run state does not.
- Vanilla behaviour should remain vanilla whenever the relevant thing is unlocked.
- Vanilla+ features should be additive and data-driven where practical.

Do not silently settle an unresolved design question in code. Check `docs/open-questions.md` and
the issue first.

## The map

| Area | Where it lives |
| --- | --- |
| Mod entry point and registration | `src/main/java/fi/vilpponen/mhr/HardcoreRoguelite.java` |
| Balance/config loading | `src/main/java/fi/vilpponen/mhr/core/`, `src/main/resources/default-balance.json` |
| Permanent progression: what is owned, the currency, and buying it | `src/main/java/fi/vilpponen/mhr/progression/` — and [progression](docs/codebase/progression.md) before changing how any of it is stored |
| Unlock ids, and the views feature code asks | `src/main/java/fi/vilpponen/mhr/Unlock.java`, `UnlockState.java` |
| Shop screen, its networking and its layout data | `src/main/java/fi/vilpponen/mhr/shop/`, `src/main/resources/shop-layout.json` |
| Development commands | `src/main/java/fi/vilpponen/mhr/command/` |
| Equipment-slot locks and client sync | `src/main/java/fi/vilpponen/mhr/equipment/` |
| Crafted-item Vanilla+ enchant | `src/main/java/fi/vilpponen/mhr/enchant/` |
| World border | `src/main/java/fi/vilpponen/mhr/border/` |
| Ore gating | `src/main/java/fi/vilpponen/mhr/ore/` |
| Village gating | `src/main/java/fi/vilpponen/mhr/village/` |
| Starter items / run-start chest | `src/main/java/fi/vilpponen/mhr/starter/` |
| Minecraft hooks | `src/main/java/fi/vilpponen/mhr/mixin/` |
| Datapack tags | `src/main/resources/data/hardcore_roguelite/` |
| Pure Java tests | `src/test/` |
| Server + client gameplay tests | `src/gametest/` |
| Kubernetes dev environment | `k8s/dev.yaml` |
| Dev/build/test driver | `scripts/dev.sh` |
| Codebase architecture notes | `docs/codebase/` |

Prefer extending the existing feature seam over creating a second path that owns the same rule.

## Read before you work on it

- [README](README.md) — current product state and implemented features.
- [design](docs/design-v0.2.fi.md) — read before changing progression or game-design behaviour.
- [open questions](docs/open-questions.md) — read before deciding something the design has
  deliberately left unsettled.
- [balance](docs/balance.md) — read before adding/changing prices, rewards, sizes, multipliers,
  starter stacks, or other tuning.
- [dev environment](docs/dev-environment.md) — read before building, testing, changing GameTests,
  or touching Kubernetes.
- [agent workflow](docs/agent-workflow.md) — read before implementing or reviewing a GitHub issue.
- [progression](docs/codebase/progression.md) — read before touching permanent state, currency,
  purchases or repeatable upgrades. `Progress` owns the one snapshot all of it lives in.
- [run lifecycle](docs/codebase/run-lifecycle.md) — read before death handling, run reset/new-world
  creation, first-join logic or deciding whether state is permanent versus per-run.
- [Minecraft hooks](docs/codebase/minecraft-hooks.md) — read before adding/changing mixins, Fabric
  hooks, datapack membership or client/server authority boundaries.
- [GameTest authoring](docs/codebase/gametest.md) — read before adding/changing gameplay tests or
  client interaction helpers.

## What will catch you out

These have already cost implementation/debugging time.

- **Minecraft 26.3 is unobfuscated.** Do not add Yarn/intermediary mappings or follow older Fabric
  setup guides blindly. The names in the Minecraft jar are the real names.
- **The real dev environment is Kubernetes.** The build pod, GameTest pod and dedicated server have
  separate jobs. Do not infer that the persistent dev server is running your branch.
- **Gameplay verification has a real headless client now.** Do not write "requires manual client
  verification" for deterministic behaviour before checking whether Client GameTest can exercise it.
- **Worldgen only answers once.** An unlock change cannot rewrite already-generated chunks. Use
  fresh regions/worlds with fixed seeds for before/after tests.
- **A zero result can be a fake pass.** Worldgen/spawn tests need positive controls proving the test
  setup can actually produce the vanilla thing when it is allowed.
- **The roguelite border can invalidate distant-world tests.** Tests that intentionally generate
  normal terrain far away must establish the border state they need.
- **The GameTest harness suppresses incidental state on purpose.** Establish gamerules, unlocks,
  inventories, config and player state explicitly in each scenario.
- **Client input is real enough to have version-specific traps.** Minecraft 26.3/SDL mouse numbering
  and shift-click behaviour are documented in `docs/dev-environment.md`; reuse `TestPlayer`
  helpers instead of re-discovering them.
- **A failed Client GameTest can leave a JVM holding port 25565.** If a run fails before any named
  scenario starts, diagnose the harness/port state before changing product code. `gametest` finds
  such a JVM once it holds the lock, but it waits and then stops rather than killing it — branches
  older than the lock still run without it, and their runs look exactly like debris from here.
  Rerun with `MHR_KILL_STRAYS=1` once you know it is debris. See
  `docs/dev-environment.md#the-run-lock`.
- **The cluster is shared, so runs queue.** `scripts/dev.sh` takes a lock on the pod it uses and
  waits when another worker holds it. Waiting is the expected behaviour; do not route around it.
  See `docs/dev-environment.md#the-run-lock`.
- **Balance values are not Java constants.** If a number is a tuning decision, first look for its
  home in `default-balance.json` / `Balance`.
- **Server rules are authoritative.** Client-synchronized unlock state is for presentation and
  interaction feedback, not a replacement for server enforcement.
- **A mixin is a hook, not a feature module.** Keep injections narrow and delegate to feature code
  so unrelated vanilla behaviour remains untouched.

## Issue and agent workflow

GitHub Issues drive implementation.

- The `claude` label means the AgentDeck/Poller workflow owns the card.
- Respect explicit `Blocked by #N` dependencies in the issue body. A worker starting does not make
  the blocker disappear.
- Treat the issue's acceptance criteria as the contract.
- Keep one issue / PR focused; avoid unrelated cleanup.
- Existing implementation PRs use `Closes #N` when the issue is genuinely complete.
- If implementation uncovers a real conflict in the requested behaviour, record it explicitly
  instead of silently weakening the requirement.

See `docs/agent-workflow.md` for the full implementation/review loop.

## Definition of done: executable acceptance criteria

A gameplay feature is **not done when the code is written**. It is done only when its important
acceptance criteria are covered by automated executable tests and the repository verification
command is green.

For every new gameplay feature or gameplay bug fix:

1. Implement the behaviour.
2. Add or update automated tests that exercise the real behaviour described by the issue.
3. Run:
   ```sh
   scripts/dev.sh gametest
   ```
4. Inspect failures and artifacts, fix the implementation, and rerun until green.
5. Perform a **negative control**: deliberately break or simulate breaking at least one important
   rule and prove that the relevant test turns red for the right reason.
6. Restore the correct implementation and rerun the suite green.

Do not leave "verify manually in Minecraft" as the normal completion path when the existing
GameTest harness can exercise the behaviour.

## Choose the lowest-level test that proves the behaviour

Use the cheapest test that still proves the real acceptance criterion:

- Plain JUnit for pure Java/data/balance logic.
- Fabric server GameTests for gameplay that does not require a client.
- Fabric Client GameTests when real client behaviour matters: GUI interaction, mouse/keyboard input,
  client/server synchronization, joining/reconnecting, recipe-book interaction, or other paths that
  cannot honestly be proven server-side.
- Fresh-world Client GameTests when the real criterion depends on ordinary worldgen, a true first
  join, or other state that server GameTest's controlled world cannot prove.

Prefer testing the public/game-facing path over calling a helper directly when the acceptance
criterion is specifically about that path. Helpers may have fast focused tests in addition to, not
instead of, the end-to-end scenario.

## Reuse the existing harness

Do not build a second test system for a feature.

The canonical command is:

```sh
scripts/dev.sh gametest
```

It runs unit tests, server GameTests and Client GameTests headlessly in the Kubernetes GameTest pod
and returns a failing exit status if verification fails.

The harness already provides:

- isolated runtime directories,
- a real dedicated server for server/client scenarios,
- a real Minecraft client under Xvfb + software OpenGL,
- reusable client interaction helpers,
- logs and screenshots copied to `build/gametest/`,
- named scenario markers and readable failure messages.

Read `docs/dev-environment.md#automated-gameplay-tests` before changing the harness or adding a new
kind of client interaction. Reuse existing helpers and patterns from `src/gametest/`.

## Test isolation and determinism

Every scenario must explicitly establish the state it depends on. Tests must not rely on:

- a previous scenario having unlocked or locked something,
- the persistent development world,
- existing player inventory,
- a previous config override,
- test execution order,
- terrain generated under a different unlock state.

Use fixed seeds/coordinates where randomness or worldgen would otherwise make the result flaky.
When vanilla randomness is itself part of the path, use enough controlled/repeated attempts that the
test is reliable and include a control proving the setup can succeed.

A test that can pass because "nothing happened" is incomplete unless it has a positive/control case
showing that the same setup produces the vanilla result when the feature permits it.

## Client tests and screenshots

When a client-side scenario fails, capture a screenshot when it gives useful evidence. Screenshots
are debugging/evidence artifacts, not the sole assertion for gameplay state.

For UI features, combine visual evidence with programmatic assertions of the underlying state.
Prefer deterministic screenshot names tied to the scenario.

Do not require a human to inspect screenshots for the normal pass/fail decision unless the issue is
explicitly about subjective visual quality.

## Item/state conservation

For mechanics that reject, move, replace or grant items, test conservation explicitly where
relevant. A rejected action must not silently destroy or duplicate an item. Count the item anywhere
the player can legitimately reach it, including inventory, equipment, cursor and world drops when
the feature can move it between those locations.

Apply the same principle to future currency, unlock state and other persistent resources: test the
observable before/after invariant, not only that one field changed.

## PR evidence

A gameplay PR should explain:

- what changed;
- which acceptance criteria are executable;
- which tests exercise them;
- the result of `scripts/dev.sh gametest`;
- the negative control and which test caught it;
- any genuine remaining automation gap and the concrete technical reason.

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

## Conventions

- Code, comments, commits, issues and PRs are in English unless the task explicitly requires
  otherwise. Product/design discussion may be in Finnish; `docs/design-v0.2.fi.md` is intentionally
  Finnish.
- Keep balance separate from mechanics.
- Keep feature packages independent where practical.
- Prefer a narrow targeted Minecraft hook over a broad world/game refactor.
- Update the relevant documentation when a discovery would otherwise make the next agent repeat the
  same debugging.
- If a decision is likely to outlive the issue, write it down in the appropriate design/balance/dev
  documentation instead of leaving it only in a PR comment.

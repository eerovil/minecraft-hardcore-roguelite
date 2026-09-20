# Minecraft hooks and feature boundaries

Read this before adding or moving a mixin, injecting into a new Minecraft class, or changing the
client/server boundary of a gameplay rule.

## A mixin is a hook, not the feature

The repository uses mixins to reach vanilla control points that Fabric does not expose directly.

The rule is:

> **Inject narrowly, decide in feature code.**

A mixin should normally:

1. observe the vanilla operation;
2. ask one semantic feature API what should happen;
3. cancel/replace/adjust the smallest possible thing;
4. return to vanilla.

Do not let a mixin become the place that owns prices, progression, UI state, persistence or broad
gameplay policy.

Examples of the intended split:

- ore mixins ask the ore feature whether a block is suppressed;
- village mixin asks whether village generation is enabled;
- equipment mixins delegate to the equipment rule;
- crafting-result mixin delegates to crafted-enchant behaviour.

## Current hook surface

The production mixin config is
`src/main/resources/hardcore_roguelite.mixins.json`.

Current hooks cover:

- passive animal natural spawning;
- chicken-jockey animal creation;
- crafted-result enchanting;
- equipment write/placement/visibility paths;
- client locked-slot overlay rendering;
- normal ore feature placement;
- deep iron/copper vein material placement;
- tree generation;
- village structure generation.

The config has `defaultRequire: 1`. A missing required injection point should fail loudly rather
than quietly shipping a feature that no longer hooks Minecraft.

Do not lower that globally to hide a broken injection after a Minecraft update.

## Preserve vanilla outside the exact restriction

A locked feature should remove only what the design says is missing.

Examples already encoded in the project:

- locked ore generation does not block recipes, loot, trading or already obtained ore items;
- locked animals do not block explicit `/summon`, spawn eggs, breeding, drops or combat;
- locked equipment slots do not block crafting or storing the equipment;
- village locking suppresses villages, not unrelated structures;
- tree locking must not suppress all vegetation;
- an unlocked feature should follow vanilla behaviour, not a home-grown approximation.

When picking a hook, prefer the point where the unwanted vanilla behaviour is still one narrow
operation. Avoid filtering broad systems upstream if that changes unrelated probabilities or
behaviour.

## Find the real path, do not guess it

Minecraft 26.3 is unobfuscated. The class/method names in the jar are the real names.

When a hook is uncertain:

- inspect the actual 26.3 bytecode/classes;
- trace the path the game really uses;
- identify alternate paths that bypass the obvious method;
- write a GameTest that proves the claimed path.

Existing examples matter:

- ore generation needed both ordinary ore features **and** the deep vein material rule;
- passive animals needed natural spawn checks **and** the jockey construction path;
- equipment writes had paths that bypassed ordinary UI placement;
- world border correctness needed real portal transitions, not only reading back a number.

Do not stop after finding the first method whose name sounds right.

## Server authority

Gameplay restrictions are server-authoritative.

A connected client may receive synchronized state to draw UI or give immediate feedback, but client
state must not be the authority that decides whether an action is legal.

Equipment is the worked example:

- server enforcement reads `UnlockState`;
- the client receives a compact synchronized copy for padlock rendering;
- `EquipmentLocks.isUnlockedForDisplay` is presentation;
- the authoritative rule is still server-side.

Apply the same separation to future shop UI, currency displays and run/death screens.

A client packet saying "I bought this" must never be sufficient evidence that the purchase exists.

## Client-only hooks

Put genuinely client-only injections in the `client` section of the mixin config and client
packages.

Client code may:

- render;
- expose state the server already authorized;
- send an intent/action to the server;
- support interaction tests.

It must not read the server's config file as a shortcut, even in integrated single-player where both
sides happen to share a JVM.

If a rule works only because integrated server and client can see the same singleton, it is not a
valid multiplayer design.

## Accessor mixins

`MinecraftServerAccessor` is the one mixin here that injects no behaviour at all. It exposes four
private `MinecraftServer` fields — the level map, the executor, the save directory handle and the
world-generation settings — because `fi.vilpponen.mhr.run.RunWorlds` has to create and destroy
levels inside a running server and Minecraft creates every level exactly once, in a method that
offers to do it again for nobody.

The rules that keep that honest are the same ones as for any other hook:

- the accessor holds no decisions. Whether a run starts is `RunLifecycle`'s business, and how a
  level is built is `RunWorlds`'; the mixin only hands over fields.
- exactly one field is written to (`worldGenSettings`, because `ServerLevel.getSeed()` reads the
  seed off the server rather than off the level) and the `@Mutable` on it says so.
- the construction it enables copies `MinecraftServer.createLevels` deliberately, so that a run
  world is an ordinary world in every respect a feature could notice.

Reach for an accessor when a vanilla internal is genuinely not reachable any other way, and keep
the logic that uses it in the feature package. See `docs/codebase/run-lifecycle.md`.

## Prefer Fabric events/APIs when they express the contract

Not everything should be a mixin.

Current non-mixin hooks include Fabric lifecycle/network/command events for things such as:

- startup/shutdown;
- player join;
- player death (`ServerLivingEntityEvents.ALLOW_DEATH`, which the run loop cancels so that
  vanilla's hardcore game-over never becomes part of the game);
- client synchronization;
- command registration.

The mod also publishes events of its own. `fi.vilpponen.mhr.run.RunEvents` is the seam features use
to react to a run beginning or ending; a feature must not infer a run boundary by watching some
other event. See `docs/codebase/run-lifecycle.md`.

Use a Fabric event/API when it gives the needed semantic hook cleanly. Use a mixin when the rule
really depends on a vanilla internal decision that no public event exposes.

Do not add a mixin merely because it is convenient.

## Datapack/tag versus Java hook

Data should describe sets that server owners or future balance/design work may reasonably vary.

The crafted-enchant feature is the worked example:

- which items are enchantable is a datapack tag;
- which enchantments may be rolled is a datapack tag;
- Java implements the algorithm/invariant.

Prefer tags/data for membership/configuration and Java for behaviour.

Do not hard-code a long list in a mixin if vanilla/tag data already describes the category reliably.

## Side effects inside injection points

Keep injected code small and predictable.

Be especially careful with:

- recursive calls back into the method being mixed into;
- registry access before registries are ready;
- blocking I/O on game/worldgen threads;
- mutating persistent state from a read/check hook;
- client UI calls from server code;
- random draws whose timing changes vanilla RNG streams unintentionally.

If an unlock query is read from worldgen threads, its backing state must remain safe for those
threads. `UnlockState` currently synchronizes its map for that reason.

## Hook failures after Minecraft updates

When updating Minecraft/Fabric:

1. keep `defaultRequire: 1`;
2. boot tests and let bad injection points fail visibly;
3. inspect the new real class/bytecode;
4. re-establish the narrow semantic hook;
5. rerun feature GameTests, including negative/positive controls;
6. only then change comments/docs describing the path.

Never "fix" an update by setting an injection optional without proving the feature has another valid
path.

## Tests for a new hook

A hook-level feature needs evidence at two levels when practical:

**Focused path test**

Proves the exact vanilla operation is intercepted (fast server GameTest is ideal).

**Game-facing test**

Proves the player/world sees the intended result in the real path (fresh-world or Client GameTest
when needed).

Also include a control proving unrelated vanilla behaviour still works.

A negative control should break/bypass the hook itself at least once during implementation and show
that the intended scenario turns red. That is stronger evidence than only testing helper methods.

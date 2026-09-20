# Progression and permanent state

Read this before changing unlock storage, adding a shop purchase, adding currency, or introducing a
new permanent upgrade.

The central rule is simple:

> **A run is disposable; progression is not.**

The implementation already has one permanent progression model. Extend it. Do not build a second
shop-owned or feature-owned progression store beside it.

## Current model

Everything permanent lives in one file, owned by one class.

`progression/Progress` holds it and is the only thing that writes it. It is deliberately in the
Fabric config directory as `hardcore-roguelite-progress.json`, outside every Minecraft world:
deleting a run/world must not delete what the player has bought.

Currency and ownership are one file because they are one purchase. The persisted shape is:

```json
{
  "currency": 35,
  "unlocks": {
    "world.trees": 1,
    "player.slot.helmet": 1,
    "player.craft.enchant": 3,
    "starter.bread": 1
  }
}
```

An unlock's value is the purchased level. Missing means level 0 / not owned.

`UnlockState` and `Wallet` are **views** over that snapshot and own nothing. They are the names the
rest of the mod asks by — `UnlockState` for what is owned, and the place the rules about unlock ids
live; `Wallet` for the currency — and both read and write through `Progress`.

`Progress` also owns migration. A profile written by an older build is read once from the two files
it used to live in, with renamed ids carried over and known levels clamped, and written back as one
snapshot; the old files are left where they are. Changing a stable id after it has been used is
therefore a save migration, not a cosmetic refactor.

How that one file is written, and what happens when it cannot be, is
[One snapshot, one write](#one-snapshot-one-write) below. Read it before changing anything about
persistence — several of the rules there exist because the alternative was tried.

## Stable ids are the join key

One dotted string is intentionally reused across the system:

- the key in the balance catalogue;
- the key in the unlock save file;
- the dev command argument;
- the value Java feature code asks `UnlockState` about;
- eventually, the id the shop buys.

Examples:

- `world.trees`
- `world.ore.iron`
- `world.animal.cow`
- `player.slot.helmet`
- `player.craft.enchant`
- `starter.bread`

Do not introduce a second numeric/database/shop id for the same unlock unless a real requirement
demands it. The stable string id is the canonical identity today.

The namespaces are meaningful:

- `world.*` — things restored to future worlds;
- `player.*` — capabilities/upgrades restored to the player;
- `starter.*` — catalogue-only starter items.

Future namespaces should carry similarly useful grouping meaning rather than requiring a parallel
category table just to render the shop.

## `Unlock` enum versus catalogue-only unlocks

`Unlock` exists for permanent upgrades that Java feature code needs to name directly.

Examples:

- trees;
- villages;
- ores;
- animals;
- equipment slots;
- crafted-item enchant.

Not every shop entry needs an enum constant.

Starter items deliberately do **not** have one constant per item. The entire definition lives in
`default-balance.json`, and `UnlockState` owns them by id. This is the pattern to prefer when an
entry is data-only and no feature code needs a named constant.

Rule of thumb:

- if Java behaviour needs to ask "does the player own this specific capability?", an `Unlock`
  constant may be appropriate;
- if the catalogue entry itself fully describes the reward, keep it catalogue-driven and keyed by
  id.

Do not add enum constants just to make the future shop enumerate products. The balance catalogue is
already the product catalogue.

## Binary and repeatable unlocks

Most unlocks are binary. Their maximum level is 1.

Repeatable unlocks use the same `UnlockState` map; they are not a second progression system.
`Unlock.maxLevel()` reads the maximum from balance data.

The first example is `player.craft.enchant`.

Important consequences:

- level limits are balance, not Java constants;
- `UnlockState.setLevel` clamps known ids to the current configured maximum;
- lengthening/shortening a repeatable curve is a balance edit;
- `/mhr reload` should reach balance-driven behaviour without creating another save shape.

When adding another repeatable upgrade, prefer the same level semantics unless the design requires a
different model.

## Balance catalogue versus ownership

These are different concepts:

**Balance/catalogue answers:**

- what can be bought;
- what it costs;
- how a repeatable upgrade scales;
- what a starter item contains;
- other tuning values.

**UnlockState answers:**

- what this player/progression profile owns;
- at what level.

Do not write prices, item definitions, strength curves or similar tuning into `UnlockState`.
Do not write ownership into the balance override.

The bundled `default-balance.json` is the catalogue/schema. The local override retunes existing
catalogue entries; it is not a second source of ownership.

See `docs/balance.md` for the exact merge and validation rules.

## Unknown ids are preserved on purpose

`UnlockState` does not throw away an id just because the current build cannot act on it.

A permanent purchase should not disappear because one release temporarily removed a catalogue
entry or feature implementation. Unknown ids are retained in the save and can become active again
when the matching feature returns.

Do not "clean up" unknown ids during load unless the product explicitly introduces an irreversible
migration with a replacement policy.

## Feature boundaries

A gameplay feature should expose a narrow semantic API and read the central progression state rather
than maintaining its own ownership flag.

Current examples:

- `Ore.generates()`
- `AnimalSpecies.isEnabled()`
- `Villages.generationEnabled()`
- `EquipmentLocks.isUnlocked(...)`
- `CraftEnchant` reads the repeatable level
- `StarterItems.ownedStacks(...)` enumerates owned catalogue entries

The shop should call progression/catalogue APIs and feature APIs where needed; features must not
depend on the shop UI.

The long-term dependency direction is:

```
balance/catalogue + permanent state
            |
            v
        gameplay features

shop  ---> progression purchase operation ---> permanent state
currency ------------------------------^
```

Avoid:

```
feature -> shop
feature -> GUI
mixin   -> shop
shop    -> private internals of every mixin
```

## Currency and purchasing

Both exist now, in `fi.vilpponen.mhr.progression`, sitting above the catalogue and `UnlockState`
rather than replacing either:

- `Progress` — the one snapshot of permanent progression, and the only writer of it. See below.
- `Wallet` — a view of the currency in that snapshot.
- `Catalogue` — what is for sale and at what price, read from balance data every time. The `unlocks`
  section plus the `worldBorder` tiers under their `world.border.*` ids.
- `Purchase.buy(id)` — the one operation that turns currency into ownership. The shop screen's click
  and `/mhr unlock` both end up here or in `UnlockState` directly; nothing else moves currency.

**Currency is still not earned.** Nothing in gameplay pays into the wallet, because how it is earned
is the blocking open question in `docs/open-questions.md`. `/mhr currency give` is a development
stand-in. Do not add an earning rule as a side effect of another change.

`Purchase.buy` makes one authoritative decision:

1. resolve an id from the catalogue;
2. read price/current level/max level;
3. verify sufficient currency;
4. deduct currency exactly once;
5. advance ownership/level exactly once;
6. persist both sides safely;
7. notify/apply runtime effects where necessary.

## One snapshot, one write

Currency and what is owned are two halves of the same thing: a purchase moves both, and a state
where one has moved and the other has not is not a state the game should ever be in.

They used to be a file each. That made a purchase two writes with a gap in the middle, and no
amount of ordering, journalling or recovery turns two writes into one — three review rounds went by
adding machinery to the gap before the gap itself was removed. **There is now one file**,
`config/hardcore-roguelite-progress.json`, holding both:

```json
{
  "currency": 35,
  "unlocks": { "world.trees": 1, "player.craft.enchant": 2 }
}
```

`progression/Progress` owns it and is the only thing that writes it. Every change follows one rule:

> **write, then adopt.**

A change is built as a whole new snapshot, written through `core/AtomicFile` — temporary file,
flush the file, atomic rename, and the rename is last, so the file is either wholly old or wholly
new — and only once that write has landed does `Progress` start answering with the new values. A write that fails
changes nothing at all: not the file, not memory, not what the running game believes. There is
nothing half-applied to notice, report or recover from, and no recovery machinery.

`Purchase.buy` therefore works out what progression should look like afterwards and asks for it
once. Either the write lands and the purchase happened, or it does not and the purchase is refused
with `NOT_SAVED`.

Rules to keep:

- **A reward is described by whoever will hand it over.** A starter item's whole effect is a stack
  in the balance catalogue, and an override may replace it. So the offer carries that stack and the
  screen draws its icon, count and name from it. Do not write any of those facts into
  `shop-layout.json` or the language file as well — `ShopLayoutTest` fails if you do, because the
  two copies drift the moment anybody retunes a count.
- **One writer.** `Progress` writes; nothing else does. `Wallet` and `UnlockState` are views over it
  and own nothing. Do not add a second file for a new kind of permanent progression — add a key to
  the snapshot.
- **Never change memory before the write.** That is the whole of the guarantee.
- **One storage rule: temp file, flush it, atomic rename — and the rename is last.** So every
  failed write is a write that did not happen, and there is one case to handle rather than a
  taxonomy of them. `Progress` keeps the old snapshot and the purchase is refused.

  This replaced a growing set of failure classifications — a post-rename exception type, an
  injectable directory flusher, a sticky session state, and a guess about whether a platform
  refusing to open a directory was a missing capability or a real error. Each was a correct answer
  to a narrow question, and together they were a bespoke storage protocol nobody could reason
  about. **If a new filesystem edge case turns up, do not add the next classifier here.** Either it
  is covered by the one rule, or the requirement belongs behind a persistence library written to
  provide it.
- **What this does and does not promise.** The file's own contents are flushed before the rename, so
  a reader never sees a half-written snapshot. The directory entry is not flushed, so on some
  filesystems a power cut in the instant after the rename can lose the rename and leave the previous
  snapshot in place — an older one, never a broken one. That is a documented limit, not an
  oversight: losing the last purchase to a power cut costs a player one purchase, and a storage
  protocol nobody can reason about costs them the lot.
- **Fail closed at the file boundary.** Both halves of this matter:
  - `AtomicFile` does not fall back. A filesystem that will not promise an atomic rename gets an
    error rather than a quiet plain replace, because a caller told "written" would sell something on
    the strength of a guarantee that was never made.
  - Reading invents nothing. What a snapshot is — both fields, in the right shape — is decided once,
    where it is read. `{"currency": 100}` parses perfectly and is a damaged file, not a player who
    owns nothing; loading it as empty is how the next purchase writes that emptiness over something
    repairable. No file is a new player and starts from nothing; a file that is there
    and cannot be read stops the game with the path named. Starting empty is the one mistake that
    cannot be undone, because the next purchase writes the empty profile over the real one.
  - A migration commits only once **every** legacy file that is present has been read whole. A
    present-but-unreadable source is not an empty one.
- `Progress.commit` throws `PersistenceException` rather than logging and returning. A failed write
  that reports success is how a purchase ends up claimed but not stored.
- The two old files are read once, on a profile written by an older build, and left where they are.
  A purchase that has already been paid for is not something to risk on a tidy-up.

## World border reads permanent progression

`WorldBorders.selectOwnedTier()` takes the tier from the `world.border.*` unlocks the player owns —
the furthest one along `BorderTier`, since the tiers are steps rather than choices — and is called
when a server starts and again whenever the owned unlocks change.

There is no border-specific save file, and there must not be one. The tier is derived state.

The tiers are steps, so the catalogue sells them as steps too: owning one satisfies every tier no
bigger than it, and `Catalogue` reports those as owned rather than offering a purchase that cannot
change anything. Bigger is by size, from the `worldBorder` data the catalogue already reads — not
the `BorderTier` constants, which are the border feature's business. Those two are the same ordering
because the border feature refuses a balance whose tiers do not get bigger going up; without that
rule they would be free to disagree. Nothing extra is written to the
snapshot for it; what the player bought stays what is recorded.

`/mhr border <tier>` still overrides it by hand, and once it has, purchases stop deciding the size
of *that* world; the flag is cleared when the next server starts. Tests depend on this, because they
generate ordinary terrain thousands of blocks from spawn and the roguelite border would otherwise
stop the chunks being populated. Removing the override would make several worldgen tests fail in a
way that looks like a worldgen bug.

## Persistence ownership: where state belongs

Ask one question first:

> Should deleting the current Minecraft world delete this state?

If **no**, it is permanent/meta progression and must live outside the world (currently
`UnlockState`, and future currency/profile persistence).

If **yes**, it is run state and should live with the world, normally through Minecraft
`SavedData` or another world-owned mechanism.

The starter chest is the worked example:

- owning `starter.bread` is permanent -> the `Progress` snapshot, via `UnlockState`;
- "this run already received its chest" is run state -> overworld `SavedData`.

Do not put permanent purchases in world NBT, and do not put once-per-run flags in the progression
snapshot.

## Tests to require for progression changes

At minimum cover the invariant the change introduces.

Examples:

- binary purchase persists across a reload;
- repeatable level persists and clamps correctly;
- old save shapes/renamed ids migrate without losing ownership;
- an unknown owned id is preserved;
- a starter catalogue entry needs no enum constant;
- currency purchase conserves currency and cannot double-buy on one action;
- a purchase whose snapshot cannot be written changes nothing, on the disk or in memory;
- a refused write leaves the previous snapshot wholly intact;
- both halves of a purchase are in the file together after a reload;
- deleting/replacing a world does not erase permanent state;
- a new run sees the permanent purchase again.

For any operation that moves currency or ownership, test the before/after conservation invariant,
not only one field.

# Progression and permanent state

Read this before changing unlock storage, adding a shop purchase, adding currency, or introducing a
new permanent upgrade.

The central rule is simple:

> **A run is disposable; progression is not.**

The implementation already has one permanent progression model. Extend it. Do not build a second
shop-owned or feature-owned progression store beside it.

## Current model

Permanent unlock ownership lives in `UnlockState`.

`UnlockState` is deliberately stored in the Fabric config directory as
`hardcore-roguelite-unlocks.json`, outside every Minecraft world. Deleting a run/world must not
delete what the player has bought.

The persisted shape is:

```json
{
  "world.trees": 1,
  "player.slot.helmet": 1,
  "player.craft.enchant": 3,
  "starter.bread": 1
}
```

The value is the purchased level. Missing means level 0 / not owned.

Old list-shaped saves and renamed ids are migrated by `UnlockState`; changing a stable id after it
has been used is therefore a save migration, not a cosmetic refactor.

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

- `Wallet` — the permanent purse, in `config/hardcore-roguelite-currency.json`, outside every world
  for the same reason `UnlockState` is.
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

The order matters and is deliberate: currency comes out first, because `Wallet.spend` is the one
call that checks and deducts under the same lock — a separate "can I afford it?" followed by a
deduction is the shape that lets two clicks pay once. Ownership goes up second, and if it somehow
does not, the currency goes straight back. The whole thing holds `Purchase`'s monitor.

That operation is the single path used by the shop. `/mhr unlock` remains a development adapter that
grants without charging, and is not the model for charging currency.

Features are told to look again through `fi.vilpponen.mhr.UnlockEffects`. A feature whose rule is
about state that already exists — an equipment slot that just closed, the size of the world —
registers a listener at init, and whoever changed the state fires it once. Do not call a feature
directly from the shop or from a command; that is how one of the two callers ends up forgetting.

Until currency semantics are specified by the relevant issue/design decision, do not invent:

- refunds;
- dynamic prices;
- hidden prerequisites;
- random shop availability;
- per-run ownership;
- alternative currencies.

The design currently says everything is visible/buyable from the start if affordable and prices are
fixed.

## World border reads permanent progression

`WorldBorders.selectOwnedTier()` takes the tier from the `world.border.*` unlocks the player owns —
the furthest one along `BorderTier`, since the tiers are steps rather than choices — and is called
when a server starts and again whenever the owned unlocks change.

There is no border-specific save file, and there must not be one. The tier is derived state.

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

- owning `starter.bread` is permanent -> `UnlockState`;
- "this run already received its chest" is run state -> overworld `SavedData`.

Do not put permanent purchases in world NBT, and do not put once-per-run flags in the permanent
unlock file.

## Tests to require for progression changes

At minimum cover the invariant the change introduces.

Examples:

- binary purchase persists across a reload;
- repeatable level persists and clamps correctly;
- old save shapes/renamed ids migrate without losing ownership;
- an unknown owned id is preserved;
- a starter catalogue entry needs no enum constant;
- currency purchase (once implemented) conserves currency and cannot double-buy on one action;
- deleting/replacing a world does not erase permanent state;
- a new run sees the permanent purchase again.

For any operation that moves currency or ownership, test the before/after conservation invariant,
not only one field.

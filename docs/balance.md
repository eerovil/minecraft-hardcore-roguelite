# Balance configuration

Every number the game's difficulty depends on — prices, advancement payouts, world border sizes,
how hard mobs hit — lives in data, not in Java. Retuning the game is editing a JSON file and
running one command. Feature code asks what something costs; it never knows the answer itself.

## Where the numbers live

| Layer | Where | Editable |
| --- | --- | --- |
| Bundled defaults | `src/main/resources/default-balance.json`, shipped inside the jar | In git, by changing the mod |
| Local override | `config/hardcore-roguelite-balance.json` in the game directory | On the server, while playtesting |

The override is deep-merged over the defaults, so it only names what it changes:

```json
{
	"unlocks": {
		"world.ore.diamond": { "price": 60 }
	}
}
```

That file is complete. Every other price, every reward, every border tier keeps its bundled value.
Objects merge key by key; anything else — a number, a string, an array — replaces what was there.

If the override does not exist, the defaults are used as they are. Nothing writes the file for you,
on purpose: a generated copy of the whole catalogue would go stale the moment a default changed.

## The file

```json
{
	"currency": {
		"advancements": {
			"minecraft:story/mine_diamond": 30
		}
	},

	"unlocks": {
		"world.trees": { "price": 3 },
		"player.slot.helmet": { "price": 3 }
	},

	"worldBorder": {
		"tiny": { "size": 128, "price": 0 },
		"infinite": { "price": 30 }
	},

	"difficulty": {
		"mobDamageMultiplier": 1.5
	}
}
```

- **`currency.advancements`** — currency for completing a vanilla advancement, by its full id. An
  advancement that is not listed pays nothing.
- **`unlocks`** — what the shop sells, keyed by stable unlock id. `price` is in currency.
- **`worldBorder`** — one entry per tier. `size` is the edge-to-edge width in blocks; leaving it out
  means the border is never in the way. A tier's unlock id is `world.border.` plus its key, but its
  price sits here so the size and the price stay next to each other.
- **`difficulty.mobDamageMultiplier`** — how much harder than vanilla mobs hit. `1.0` is vanilla.

Top-level sections nothing reads yet are allowed, which is how a new vanilla+ system gets balanced
from data before it has a typed accessor — see [Adding values](#adding-values). Inside the sections
above, an unknown key is an error, so `mobDamageMultipler` is caught rather than silently ignored.

## Unlock ids

Unlocks are identified by a stable dotted string, never by an enum ordinal or an enum's position:

```
world.trees
world.village
world.ore.iron
world.animal.cow
player.slot.helmet
player.slot.offhand
world.border.medium
```

There is exactly one id per unlock. The same string is the key in the balance file, the value
written to `config/hardcore-roguelite-unlocks.json`, the argument the dev command takes, and what
`Unlock.id()` returns — so `balance.unlockPrice(unlock.id())` finds the price, and nothing needs a
table translating one id into another.

An id is written into config files and saved state, so it has to survive constants being reordered,
renamed or removed. Adding an unlock later is adding a key to the balance file — no change to how
state is persisted, how the shop is built, or how balance is loaded.

Renaming one is a save migration, not a rename. `UnlockState.RENAMED_IDS` holds the old names that
still have to be understood: a save written before the rename is migrated on load and rewritten
once, so a purchase is never orphaned. Adding an unlock never needs an entry there — only changing
the name of one that already shipped.

## When a broken file is noticed

Balance data is never quietly replaced by a fallback: a run played at the wrong prices looks exactly
like a run played at the right ones, so a mistake has to be loud.

- **At startup** a malformed or invalid override stops the mod loading, with a message naming the
  file and the exact key: `Balance value 'unlocks.world.ore.diamond.price' should be a whole number,
  not 60.5`.
- **On reload** the running game keeps the balance it already had and the error goes to whoever ran
  the command. A typo mid-playtest does not disturb the session.

Missing values are not errors — they fall back to the bundled default through the merge.

## Reloading while the game runs

```
/mhr reload     # re-read the balance files
/mhr balance    # print what is currently in effect
```

Both need the usual gamemaster permission level. From the dev environment:

```sh
scripts/dev.sh rcon "mhr reload"
scripts/dev.sh rcon "mhr balance"
```

What takes effect immediately, and what does not:

| Value | After `/mhr reload` |
| --- | --- |
| Unlock prices, advancement rewards | Immediately — the shop reads the price when it sells |
| Mob damage multiplier | Immediately, for damage dealt after the reload |
| World border sizes | **Next run.** A world already running keeps the border it was given; resizing it under a player mid-run is not something a balance edit should do |

The rule for anything added later: if a value is read each time it is used, a reload reaches it for
free. Cache it at world load and it will not, so don't — read `BalanceManager.get()` at the point of
use instead.

## Using it from feature code

```java
int price = BalanceManager.get().unlockPrice("world.ore.diamond").orElse(0);
double multiplier = BalanceManager.get().mobDamageMultiplier();
int reward = BalanceManager.get().advancementReward("minecraft:end/kill_dragon");
```

`BalanceManager.get()` is cheap and thread-safe — the snapshot is immutable and is swapped whole on
reload, so worldgen threads can call it. Features must not keep a number of their own; that is the
one rule this layer exists to enforce.

## Adding values

A new tuning value with no typed home yet can go straight into a new top-level section and be read
by path:

```java
double stepPercent = BalanceManager.get().number("vanillaPlus.speed.stepPercent", 10);
```

When the system settles down, give it a record and an accessor in `Balance` and validation in
`BalanceManager.bind`. The path lookup splits on `.`, so it cannot reach into `unlocks` — those keys
contain dots themselves. Use `unlockPrice` for those.

## Where this sits in the mod

```
core/        shared state and config loading — Balance, BalanceManager
progression/ currency and rewards (not built yet; reads currency.advancements)
shop/        the shop (not built yet; reads unlocks and worldBorder)
features/    gameplay mechanics only — no balance numbers of their own
```

Features depend on `core`, never on each other, and never on `shop` or `progression`. The existing
feature code still sits directly under `fi.vilpponen.mhr`; it moves under `features/` as each
feature is next touched, rather than in one sweep that would collide with everything in flight.

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
There is one exception, a starter item's `item`: that is one value, not a little tree of them, and
an override naming it replaces the whole stack. See [Starter items](#starter-items).

The override changes things that exist; it does not add them. The bundled file is the catalogue, so
it is also the schema: every key the override names, at every depth, has to be in the bundled file
already. A misspelt one is refused, with the nearest real name when the typo is small enough to
guess at:

```
The balance override ./config/hardcore-roguelite-balance.json sets 'unlocks.world.ore.diamod',
which the bundled balance does not have. Did you mean 'world.ore.diamond'?

The balance override ./config/hardcore-roguelite-balance.json sets 'dificulty',
which the bundled balance does not have. Did you mean 'difficulty'?
```

Without that rule both of those merge perfectly and do nothing: the first makes an entry nothing
reads while the real diamond keeps its price, the second makes a section nothing reads while mobs go
on hitting exactly as hard as before. Neither would say a word.

Shape counts as well as names. The override has to look like the bundled file all the way down, so
only the values differ — replacing an object with a number, or a number with a string, is refused
before anything is merged, and the game stays on the balance it had.

There is no exception to any of it, which is the point. A new value — a new unlock, a new tuning knob
for a vanilla+ system — goes in `default-balance.json` first, where the catalogue already lives, and
the override tunes it afterwards. See [Adding values](#adding-values).

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
	},

	"vanillaPlus": {
		"craftEnchant": {
			"maxUnlockLevel": 4,
			"strengthPerLevel": 0.25
		}
	}
}
```

- **`currency.advancements`** — currency for completing a vanilla advancement, by its full id. An
  advancement that is not listed pays nothing.
- **`unlocks`** — what the shop sells, keyed by stable unlock id. `price` is in currency. A
  repeatable unlock is priced once and costs that much each time it is bought. `item` is only for
  starter items — see [Starter items](#starter-items).
- **`worldBorder`** — one entry per tier. `size` is the edge-to-edge width in blocks; leaving it out
  means the border is never in the way. A tier's unlock id is `world.border.` plus its key, but its
  price sits here so the size and the price stay next to each other.
- **`endBorder.minimumSize`** — the smallest the end's own border may be, whatever tier the run is
  on. Centered on the origin it has to hold both the main island and the obsidian arrival platform
  100 blocks east of it, or an end portal would drop you outside the border. Read by path, as
  [Adding values](#adding-values) describes.
- **`difficulty.mobDamageMultiplier`** — how much harder than vanilla mobs hit. `1.0` is vanilla.
- **`vanillaPlus.craftEnchant`** — the crafted-tool enchant, which is the first repeatable unlock.
  `maxUnlockLevel` is how many times `player.craft.enchant` can be bought; `strengthPerLevel` is what
  each of those levels is worth as a fraction of an enchantment's own maximum, so the two multiply to
  `1.0` when the top level is meant to reach it. Two levels worth `0.5` each is the same curve in
  half the purchases.

`default-balance.json` may hold sections nothing reads yet, which is how a new vanilla+ system gets
balanced from data before it has a typed accessor — see [Adding values](#adding-values). Inside the
four sections above, an unknown key is an error either way, so `mobDamageMultipler` is caught rather
than silently ignored.

## Starter items

A starter item is an ordinary unlock that happens to hand over an item stack rather than switch on
a piece of code, so it lives in the same catalogue under the same kind of id:

```json
"unlocks": {
	"starter.bread": { "price": 3, "item": { "id": "minecraft:bread", "count": 16 } },

	"starter.efficient_pickaxe": {
		"price": 100,
		"item": {
			"id": "minecraft:diamond_pickaxe",
			"count": 1,
			"components": { "minecraft:enchantments": { "minecraft:efficiency": 3 } }
		}
	}
}
```

`item` is a vanilla item stack, written the way `/give` and loot tables write one, and read by the
game's own item codec. That is the whole reason there is no code per item: enchantments, custom
names, dyed armor and anything Mojang adds to the component system work the day they are added.
Adding a starter item is adding a key here.

Two things are not quite vanilla's format. `count` may be more than one stack — 128 bread means two
slots of bread, which the chest splits — where vanilla caps it at 99. And an unlock without `item`
is not a starter item at all; nothing goes in the chest for it.

`count` is a balance number like any other, so it is checked with the rest of them: a whole number
of at least 1, with no upper bound. `0`, `-4` and `16.5` stop the file loading rather than being
rounded into some other amount, for the same reason a misspelt key does — an edit that grants
something different from what it says is exactly as invisible as one that grants nothing.

A starter item is bought once, not levelled: there is no constant behind it, so nothing knows what
a second level of it would mean. Its level in the save file is always 1.

### The stack is one value

`item` is the only thing in the balance file that is not walked into. An override naming it says
what that item now **is**, whole:

```json
{
	"unlocks": {
		"starter.bread": {
			"item": {
				"id": "minecraft:bread",
				"count": 16,
				"components": { "minecraft:custom_name": "Packed Lunch" }
			}
		}
	}
}
```

That is what makes the stack retunable in both directions. Merging key by key would have let you
add a component and change one but never remove one, since the old components object stayed
underneath; and the key check, applied inside the stack, would have refused the example above
outright — the bundled bread has no `components` key for it to match, so it reads a perfectly good
edit as a typo. Neither is this layer's business: what may go inside an item stack is the game's.

The price of that is no typo hint inside the stack. Instead the whole stack is put through the
game's own item codec against the server's registries, and a candidate that fails never becomes the
balance in effect:

```
Balance value 'unlocks.starter.bread.item' is not an item the game has: Unknown registry key ...
```

At startup that stops the game, and on reload the running game keeps the balance it already had —
the same treatment a misspelt price gets. It happens as soon as the server is up, which is the
earliest anything can tell `minecraft:bread` from `minecraft:braed`; the balance file itself is
loaded before that, so the check is registered and run then rather than during mod init. Features
with a value only they can validate can do the same through `BalanceManager.addCheck`.

Everything the player owns goes in one chest at the start of a run, or a double chest if 27 slots
is not enough. 54 filled slots is the ceiling: past that the chest is still placed full and the
leftovers are named in chat and in the log, because a purchase that vanished without a word is
exactly the kind of silent failure this layer exists to prevent. Trimming the catalogue brings them
back — the purchases behind them are permanent.

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
written to `config/hardcore-roguelite-progress.json`, the argument the dev command takes, and what
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

A value the override leaves out is not an error: it keeps its bundled value through the merge. That
is the only fallback in the system. A value missing from the bundled file is an error, because
nothing is behind it.

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
| Unlock prices, advancement rewards | Immediately — the shop reads the price when it sells, and an open shop screen is re-sent |
| Mob damage multiplier | Immediately, for damage dealt after the reload |
| World border sizes | **Next run.** A world already running keeps the border it was given; resizing it under a player mid-run is not something a balance edit should do |

The rule for anything added later: if a value is read each time it is used, a reload reaches it for
free. Cache it at world load and it will not, so don't — read `BalanceManager.get()` at the point of
use instead.

## Using it from feature code

```java
int price = BalanceManager.get().unlockPrice("world.ore.diamond").orElseThrow();
double multiplier = BalanceManager.get().mobDamageMultiplier();
int reward = BalanceManager.get().advancementReward("minecraft:end/kill_dragon");
```

Note the `orElseThrow`. An unlock the shop sells but the balance file does not price is a mistake in
the mod, and `orElse(0)` would turn it into a free unlock nobody ordered. The one accessor that
answers for something absent is `advancementReward`, which pays 0 for an advancement the file does
not list — that is a real answer, not a stand-in, since most advancements pay nothing.

`BalanceManager.get()` is cheap and thread-safe — the snapshot is immutable and is swapped whole on
reload, so worldgen threads can call it. Features must not keep a number of their own; that is the
one rule this layer exists to enforce.

## Adding values

A new tuning value with no typed home yet goes into a new section of `default-balance.json` and is
read by path:

```java
double stepPercent = BalanceManager.get().number("vanillaPlus.speed.stepPercent");
```

Put the value in the bundled file in the same change as the code that reads it. There is no fallback
argument, on purpose: a fallback is a balance number written in Java, and this layer exists so there
are none. A path the data does not have is a mistake in the file or in the code, and it is reported
as one — the same as a path that runs into something which is not an object on the way down, like
`vanillaPlus.speed` holding `12` when the code expects it to hold `stepPercent`.

The override keeps its own safe fallback by being merged over the bundled file: anything it leaves
out simply keeps the bundled value. That is the only fallback there is, and it is enough.

When the system settles down, give it a record and an accessor in `Balance` and validation in
`BalanceManager.bind`. The path lookup splits on `.`, so it cannot reach into `unlocks` — those keys
contain dots themselves. Use `unlockPrice` for those.

## Things that are data but not numbers

Sets belong in tags rather than in the balance file, because the game already loads and merges
those. The crafted-tool enchant ships two, and a datapack can add to either or replace it outright
without the mod being rebuilt:

| Tag | What it decides |
| --- | --- |
| `hardcore_roguelite:craft_enchantable` (item) | Which crafted items come out enchanted. Ships as the vanilla tool and sword tags. |
| `hardcore_roguelite:craft_enchant_pool` (enchantment) | What may be rolled. Ships as `#minecraft:in_enchanting_table`, which leaves out treasure enchantments and curses. |

An enchantment that cannot go on the item is never applied whatever the pool says: the game's own
answer for what fits an item is asked as well.

## Where this sits in the mod

```
core/        shared state and config loading — Balance, BalanceManager
progression/ permanent state and the one purchase operation — Progress, Wallet, Catalogue, Purchase
shop/        the shop screen, its networking and its arrangement
features/    gameplay mechanics only — no balance numbers of their own
```

`progression/Catalogue` is what turns the balance file into a product list: the `unlocks` section in
file order, plus the `worldBorder` tiers under their `world.border.*` ids. Nothing else enumerates
what is for sale.

A starter item is the exception to that last point: its icon, count and name in the shop come from
this file, because this file is what the chest will hold. Everything else is presentation.

Note what is *not* in the balance file: which row of the shop an unlock is drawn in, what icon it
has and what it is called. Those are presentation, not tuning, and they live in
`src/main/resources/shop-layout.json` and the language file. A balance edit changes what something
costs; it never changes where it appears.

Features depend on `core`, never on each other, and never on `shop` or `progression`. The existing
feature code still sits directly under `fi.vilpponen.mhr`; it moves under `features/` as each
feature is next touched, rather than in one sweep that would collide with everything in flight.

# Minecraft Hardcore Roguelite

A Minecraft mod that turns Hardcore into a roguelite.

One world is one run. You get one life. Death ends the run for good — and then opens a shop.
What you buy in the shop is permanent and carries into every future run, which starts in a
brand new world.

The twist is that a fresh save is missing things vanilla Minecraft normally gives you. No trees,
no ores, no animals, no villages, locked armor and offhand slots, a small world border. You buy
those back a piece at a time. Roughly five decent runs should be enough to get back to something
like vanilla, if that's what you spend your currency on. After that, a much more expensive
"vanilla+" tier takes you past vanilla: permanent status effects, easier hunger, a starting
chest full of gear you've unlocked.

Nothing in the shop is gated behind anything else. Everything is visible and buyable from the
first run if you can afford it, so you pick your own path.

## Status

Early. Fabric mod for Minecraft 26.3 that builds and runs, with these unlocks implemented:

- **trees** — until you buy it, the world generates without trees and saplings won't grow.
- **villages** — until you buy it, new land generates with no villages in it.
- **the ores** — coal, iron, copper, gold, redstone, lapis and diamond, each bought separately.
  A locked ore is missing from new terrain. Mining, recipes and loot are untouched.
- **passive animals** — cow, pig, sheep, chicken, horse and wolf are sold one species at a
  time. A locked species never spawns by itself: not in new terrain, not on the spawn tick
  later, and not as the chicken a baby zombie would otherwise arrive riding. Everything else
  about it is vanilla: spawn eggs, `/summon`, breeding, drops and recipes all still work.

  Locking one species leaves the others' spawning rules and their share of the draw exactly as
  vanilla has them. It does not leave the world identical, and it can't: the spawn cap is
  counted per category rather than per species, so a world with no cows in it keeps that
  category further below its cap and the animals you *have* unlocked can use the room. Vanilla
  does the same in any biome that has no cows to begin with.
- **the five equipment slots** — helmet, chestplate, leggings, boots and offhand, each bought
  separately. A locked slot shows a padlock in the inventory and refuses every item. You can still
  craft, pick up and store the gear; you just can't wear it.
- **the crafted-tool enchant** — the first vanilla+ unlock, and the first repeatable one. Every tool
  or sword you craft comes out of the crafting grid already enchanted with something that fits it.
  Buying it again raises how high that enchantment can roll; at the top level an item can come out
  at the enchantment's own maximum. Which items count, which enchantments can turn up and how the
  levels scale are all data.

There is no shop and no currency yet, so unlocks are toggled with a dev command:

```
/mhr list
/mhr unlock world.trees
/mhr unlock world.village
/mhr unlock world.ore.iron
/mhr unlock world.animal.cow
/mhr unlock player.slot.boots
/mhr unlock player.craft.enchant      # buys the next level
/mhr unlock player.craft.enchant 4    # or jump straight to one
```

Prices, rewards, border sizes and the mob damage multiplier are data, not code: they live in
`default-balance.json`, a config file can override any of them, and `/mhr reload` picks up an edit
without restarting. See [`docs/balance.md`](docs/balance.md).

- [`docs/design-v0.2.fi.md`](docs/design-v0.2.fi.md) — full design document (v0.2, in Finnish)
- [`docs/balance.md`](docs/balance.md) — how balance numbers are configured and reloaded
- [`docs/open-questions.md`](docs/open-questions.md) — things the design deliberately hasn't settled
- [`docs/dev-environment.md`](docs/dev-environment.md) — how to build and test it

## Building

Builds and the test server run in a kubernetes cluster, not on your machine:

```sh
scripts/dev.sh up   # once
scripts/dev.sh go   # build, install, restart the server
```

See [`docs/dev-environment.md`](docs/dev-environment.md). A plain `gradle build` with JDK 25 also
works if you'd rather build locally.

## Design rules of thumb

- A good unlock is **obvious**. You should notice it's missing within a minute, not after half an hour.
- Binary on/off, not a hidden percentage nerf.
- Difficulty comes from the world being incomplete, not from making normal actions slower.
  No nerfed mining speed, no weaker food, no blocked recipes.
- Mobs hitting harder is fair. Grinding is not.
- A fully maxed build should make the Ender Dragon comfortable — but never a god mode.

## License

MIT, see [LICENSE](LICENSE).

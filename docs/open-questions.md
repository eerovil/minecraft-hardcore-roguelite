# Open questions

Things the design document does not answer yet. Each of these needs a decision before the
matching part of the mod can be built.

## Currency — how is it earned? (blocking)

**Still open.** The spending half is built — there is a permanent purse in
`fi.vilpponen.mhr.progression.Wallet`, and the shop takes currency out of it through one purchase
operation — but nothing in gameplay puts any in. `/mhr currency give` is a development stand-in, not
an answer, and `currency.advancements` in the balance file is a price list waiting for the rule
rather than the rule itself.

The whole economy in section 13 of the design doc assumes a currency, but nothing says where it
comes from. This is the biggest gap: prices, the "about five runs to get back to vanilla" target,
and the 10–50× vanilla+ multiplier are all meaningless until the earning rate exists.

Things to decide:

- What the payout is based on — time survived, depth reached, biomes visited, bosses killed,
  items crafted, milestones hit, or some mix.
- Whether a run that ends badly still pays something.
- Whether the payout is shown live during the run or only on the death screen.
- Whether there is any way to bank currency without dying.

## Balance numbers

- Exact world border sizes for Tiny / Medium / Large. Current guesses are 128, 512 and 2048
  blocks, to be settled by playtesting.
- How much extra damage mobs do in the base difficulty.
- Actual prices for everything.

## Mechanics not yet specified

- Natural regeneration: which vanilla+ tiers exist and what each one does.
- Hunger easing: which of the four ideas in section 10 is actually used.
- Start chest: exact item list and pricing, including the enchanted tiers.
- Which positive status effects are safe to sell permanently, and which break the game.

Settled since:

- Armor slots are four separate unlocks, not one. Offhand is a fifth. Implemented in
  `fi.vilpponen.mhr.equipment`.
- How the shop is presented: one scrolling page with no category navigation, vanilla restoration
  above Vanilla+, an icon and a price per entry and the wordier explanation on hover. Implemented in
  `fi.vilpponen.mhr.shop`, arranged by `src/main/resources/shop-layout.json`.
- Where border progression lives: the tier is whichever `world.border.*` unlock the player owns
  furthest along, read at the start of every run. `/mhr border` still overrides it by hand for the
  world it is run in.

## Technical, once coding starts

- How permanent unlocks are stored across worlds, and where that file lives.
- What opens the shop in normal play. It is `/mhr shop` today; the design's own entry point is the
  death screen, which waits on run/death handling.
- How removed content (trees, ores, animals, villages) is actually suppressed in worldgen
  and spawning, per unlock.
- Whether unlock state is meant to be shareable or resettable by the player.

Settled since:

- A run is no longer a whole Minecraft save. One save holds a persistent lobby dimension plus three
  disposable run dimensions that are deleted and regenerated between runs, so starting the next run
  never means restarting the game. The between-runs state the shop opens into is
  `RunPhase.LOBBY`. Implemented in `fi.vilpponen.mhr.run`; see
  [run lifecycle](codebase/run-lifecycle.md).

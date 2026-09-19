# Open questions

Things the design document does not answer yet. Each of these needs a decision before the
matching part of the mod can be built.

## Currency — how is it earned? (blocking)

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
- Armor slots: one unlock for all four, or four separate unlocks.
- Start chest: exact item list and pricing, including the enchanted tiers.
- Which positive status effects are safe to sell permanently, and which break the game.

## Technical, once coding starts

- How permanent unlocks are stored across worlds, and where that file lives.
- How the shop UI is presented after death.
- How removed content (trees, ores, animals, villages) is actually suppressed in worldgen
  and spawning, per unlock.
- Whether unlock state is meant to be shareable or resettable by the player.

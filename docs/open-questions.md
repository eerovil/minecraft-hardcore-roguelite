# Open questions

Things the design document does not answer yet. Each of these needs a decision before the
matching part of the mod can be built.

## Currency — how is it earned? (settled)

**Advancements pay.** Finishing a vanilla advancement inside a run pays whatever
`currency.advancements` in the balance file prices it at, and most advancements are not listed and
pay nothing. Implemented in `fi.vilpponen.mhr.earn`.

What that settles, question by question:

- **What the payout is based on** — milestones, which Minecraft already has a good list of. They are
  visible, they are the player's own goals rather than the mod's, and their price list was already
  in the balance file waiting for this.
- **Whether a bad run still pays** — yes. An advancement pays the moment it is finished, so a run
  keeps everything it banked before it went wrong. Dying ends the run, not the reward.
- **Whether the payout is shown live** — yes, twice: a chat line as each one pays, and the purse
  drawn in the corner of the screen the whole time.
- **Whether currency can be banked without dying** — the question stops applying. There is nothing
  to bank; it is already permanent the moment it is paid.

One consequence is worth knowing before changing anything near it: **a player's advancements are
cleared as they cross into a run**, because a run is meant to be a fresh world and a fresh world has
none. That is what makes the same advancement earnable in every run — and it is also why nothing has
to remember which advancements have already been paid for. Vanilla's own record is the ledger.

Still open, and now worth playtesting rather than deciding on paper:

- Whether the shipped payouts actually land on "about five reasonable runs restores vanilla".
- Whether anything besides advancements should pay — the design's own list mentioned depth, biomes
  and time survived, and none of them is ruled out by this.

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
- How removed content (trees, ores, animals, villages) is actually suppressed in worldgen
  and spawning, per unlock.
- Whether unlock state is meant to be shareable or resettable by the player.

Settled since:

- A run is no longer a whole Minecraft save. One save holds a persistent lobby dimension plus three
  disposable run dimensions that are deleted and regenerated between runs, so starting the next run
  never means restarting the game. The between-runs state the shop opens into is
  `RunPhase.LOBBY`. Implemented in `fi.vilpponen.mhr.run`; see
  [run lifecycle](codebase/run-lifecycle.md).
- What opens the shop in normal play: **a block in the lobby**. The lobby is a small island in the
  void with an emerald block on it, and right-clicking that block opens the shop. The death screen
  the design originally named is not available to be the entry point — the roguelite cancels
  vanilla's death handling outright, so there is no game-over screen at all; death puts the player
  back in the lobby, standing in front of the block. `/mhr shop` stays as the operator's and the
  tests' spare key, and both doors end in `ShopServer.open`. Implemented in
  `fi.vilpponen.mhr.shop.ShopBlock` and `fi.vilpponen.mhr.run.LobbyIsland`; see
  [run lifecycle](codebase/run-lifecycle.md#the-lobby-is-a-room-not-a-floor).

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

Design only. No code yet.

- [`docs/design-v0.2.fi.md`](docs/design-v0.2.fi.md) — full design document (v0.2, in Finnish)
- [`docs/open-questions.md`](docs/open-questions.md) — things the design deliberately hasn't settled

## Planned target

Fabric, Minecraft 1.21.x.

## Design rules of thumb

- A good unlock is **obvious**. You should notice it's missing within a minute, not after half an hour.
- Binary on/off, not a hidden percentage nerf.
- Difficulty comes from the world being incomplete, not from making normal actions slower.
  No nerfed mining speed, no weaker food, no blocked recipes.
- Mobs hitting harder is fair. Grinding is not.
- A fully maxed build should make the Ender Dragon comfortable — but never a god mode.

## License

MIT, see [LICENSE](LICENSE).

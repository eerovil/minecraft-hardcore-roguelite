# Manual smoke test

Fifteen minutes with a real client, to feel the loop rather than to prove it. The automated version
of this same cycle is `ProgressionCycleClientTest`, and it is the thing that proves the loop; this
document is for when you want to *play* it.

If a step here does not match what the game does, the game is right and this file is stale — say so
in an issue.

## What you need

| | Version | Where it is written down |
| --- | --- | --- |
| Minecraft | 26.3 | `gradle.properties` |
| Fabric Loader | 0.19.5 or newer | `gradle.properties`, `fabric.mod.json` |
| Fabric API | 0.161.0+26.3 | `gradle.properties` — **required**, the mod does not run without it |
| Java to play on | 21 or newer | `build.gradle` |
| JDK to build with | 25 | Loom refuses to run on less — `docs/dev-environment.md` |

There is no gradle wrapper in this repository, so building locally needs a system Gradle running on
JDK 25.

## 1. Get the jar

The normal path is the cluster, because that is where everything else about this project builds:

```sh
scripts/dev.sh go          # sync + build + deploy + restart the dev server
```

That also puts the jar and Fabric API into the dev server's `mods/` for you, which is the quickest
way to *play* it — see [option A](#2a-play-on-the-dev-server) below.

To get a jar in your hand instead:

```sh
gradle build               # needs JDK 25
ls build/libs/
```

The one you want is `build/libs/hardcore-roguelite-0.1.0.jar`. Ignore `-sources`.

## 2a. Play on the dev server

This is the path the repository actually supports, and the one `scripts/dev.sh` is built around.

On the Mac that runs the cluster:

```sh
kubectl -n mhr-dev port-forward svc/mhr-server 25565:25565
```

Then, in a Fabric 26.3 client with Fabric API **and this mod's jar** in its `mods/` folder, add a
server at `localhost:25565` and join. The client needs the mod too: the shop screen and the
inventory padlocks are client-side.

Op yourself, because every `/mhr` command needs permission level 2:

```sh
scripts/dev.sh rcon "op <your-minecraft-name>"
```

> The dev server runs `online-mode=false`. Do not put it on a real network.

The cluster is shared. Somebody else's `scripts/dev.sh go` will restart the server under you with
their jar.

## 2b. Play in singleplayer

Not a path the repository verifies, but there is nothing in the mod that needs a dedicated server.
Make an ordinary Fabric 26.3 instance, put `hardcore-roguelite-0.1.0.jar` and
`fabric-api-0.161.0+26.3.jar` in its `mods/`, and create a **new world with cheats allowed** —
without cheats you cannot run `/mhr` at all, and there is no other way to start a run yet.

Hardcore is not required. The mod cancels vanilla's death handling itself, so the roguelite rules
apply either way.

## 3. Start from a clean profile

Permanent progression lives **outside every world**, so making a new world does not reset it. Delete
these from the instance you are playing on:

```
config/hardcore-roguelite-progress.json     purchases and currency
config/hardcore-roguelite-balance.json      your price overrides, if you made any
config/hardcore-roguelite-unlocks.json      only on a profile from an older build
config/hardcore-roguelite-currency.json     only on a profile from an older build
```

A missing progress file is a new player with nothing. **A file that is there and cannot be read
stops the game on purpose**, naming the path — that is not a crash to work around, it is the mod
refusing to write an empty profile over a repairable one.

Then delete the world, or delete just `<save>/hardcore-roguelite-run.json` to forget which run the
save was on. On the dev server: `scripts/dev.sh newworld`.

## 4. First join: the lobby

You arrive on a bedrock plane under an empty sky, at about `y=-63`, in the dimension
`hardcore_roguelite:lobby`. Chat says:

> No run in progress. Start one with /mhr run start.

That is the between-runs room. Nothing spawns there, there is no weather and there is nothing to
mine. It is never deleted, so anything you leave in it stays for good.

Check where you stand at any time with:

```
/mhr run                  the phase, the run id, the seed, how many runs are done
/mhr list                 every unlock, owned or locked, plus your currency
```

## 5. Start run 1 and see what is missing

```
/mhr run start
```

Chat answers `Run 1 started on seed <seed>`. The overworld, nether and end are deleted and
regenerated from that seed and you are dropped into the new overworld.

Look around. With nothing bought:

- **No trees.** Not sparse — none at all. This is the most striking one and it is why `world.trees`
  is the unlock to buy first.
- **No starter chest** at your feet. With something bought there would be one within three blocks.
- **A 128-block world.** Walk in any direction and the border stops you about 64 blocks out. That is
  the `tiny` tier, the one you get for having bought no tier at all.
- **Padlocks in the inventory.** Open it: the armour and offhand squares you have not bought are
  locked, and an item put in one is handed straight back.
- **No iron, gold, redstone, lapis or diamond** in the stone, and no villages.

Nether and end portals still work and still lead to *this* run's nether and end.

## 6. End the run

Either play until something kills you, or:

```
/mhr run end
```

Dying is the real path and worth doing once. There is no game-over screen, no spectator mode and no
"delete world" button: you are revived and put back in the lobby, and the run is over.

You arrive with **nothing**. Inventory, ender chest, experience, hunger, the respawn point you slept
at — all of it belonged to the run. Quitting the game mid-run is *not* dying; log back in and the run
is still going.

## 7. Get some currency

Nothing in gameplay pays out yet. That is deliberate — how currency is earned is still an open design
question — so for a playtest give it to yourself:

```
/mhr currency give 20
/mhr currency                 what the purse holds now
```

## 8. Buy something

```
/mhr shop
```

One scrolling page: restoring vanilla at the top, Vanilla+ below it. Click a square to buy it. An
affordable square buys and charges at once; one you cannot afford does nothing; one already owned
does nothing.

Buy these two, three currency each:

| Unlock | What it changes | When you see it |
| --- | --- | --- |
| `world.trees` | trees generate again | next run only — worldgen cannot rewrite chunks that exist |
| `starter.bread` | 16 bread in a chest at the run's spawn | next run |

If you would rather see something change *immediately*, buy `player.slot.helmet` (also 3) and watch
the padlock leave that square in your inventory.

Close the shop. `/mhr list` should now show both as `[owned]` and 14 currency left.

## 9. Start run 2 and confirm

```
/mhr run start
```

`Run 2 started on seed <a different seed>`. It is a genuinely new world — new seed, new chunks, run
1's terrain deleted, not a teleport somewhere far away.

You should see, right where you land:

- **Trees.** The thing you bought.
- **A chest within a few blocks**, holding 16 bread, with a chat line saying where it is.
- The border still 128 across, because you did not buy a tier. Buy `world.border.medium` (3) in the
  shop between runs and the next run is 512 across.

That is the whole loop: the run was disposable, the purchases were not.

## 10. Round again

Die or `/mhr run end`, spend, start. Each run is a fresh world and everything bought is still yours.

To start the *whole thing* over, go back to [step 3](#3-start-from-a-clean-profile) — delete the
progress file as well as the world. Deleting only the world keeps your purchases.

## When it goes wrong

| What you see | What it means |
| --- | --- |
| `there is no hardcore_roguelite:lobby dimension — is the mod's data pack loaded?` | the jar is not loaded, or is the wrong Minecraft version |
| `Unknown or incomplete command` on `/mhr` | you are not opped, or cheats are off |
| `This save is stopped` | the loop hit a failure it will not guess its way past. Restart the server and look at the log; it refuses rather than risk deleting a world you are standing in |
| a run refuses to start | `/mhr run` says why — one is already in progress, or the save cannot describe all three run dimensions |
| your purchases vanished | you deleted `config/hardcore-roguelite-progress.json`, or you are playing a different instance |

## What this document is and is not

Every command, message, file path and version above is taken from the code on this branch, and the
sequence itself — lobby, restricted run, death, shop purchase, better second run — is the one
`ProgressionCycleClientTest` drives against a real client and a real dedicated server on every
`scripts/dev.sh gametest`.

What has **not** been done is somebody sitting at a keyboard following these steps, because the
repository has no client on the machine the agents work from. So treat the *shape* as verified and
the feel as untested: if a message is worded slightly differently or a chest is four blocks away
rather than two, fix the line here.

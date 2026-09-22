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

Nothing here builds on the machine you play on. The mod is built in the cluster's build pod, the
same as every other build in this project, and the jars are copied back to you — no JDK and no
Gradle on your own machine at all.

## 1. Build it in the cluster and put it in your client

```sh
scripts/dev.sh client
```

That is the whole step **on a Mac**. It syncs the checkout to the build pod, runs the same gradle
build there that `scripts/dev.sh build` does, and copies two jars back onto this machine — the mod,
and the Fabric API version `gradle.properties` declares. The client needs both.

On Linux or Windows the same command stops and asks you where the jars go, because there is no
launcher path worth guessing outside macOS. Say it yourself and the step is the same:

```sh
MHR_CLIENT_MODS_DIR=/path/to/instance/mods scripts/dev.sh client
```

On a Mac it installs into `$HOME/Library/Application Support/minecraft/mods`, and **that directory
has to exist already**: start the Fabric 26.3 profile once and the launcher makes it. A missing
one is an error rather than something the script creates, because an invented mods directory is one
no launcher reads — which looks exactly like a mod that does not work. For Prism, MultiMC or any
second instance, name it yourself the same way:

```sh
MHR_CLIENT_MODS_DIR="$HOME/Library/Application Support/PrismLauncher/instances/mhr/.minecraft/mods" \
  scripts/dev.sh client
```

Run it again after every code change. It replaces `hardcore-roguelite*.jar` and `fabric-api-*.jar`
and leaves your other mods alone, so old builds cannot pile up and win load order over the one you
just made.

`client` is the client install and `go` is the server loop; neither touches the other's
destination. If you also want the dev server running your build, `scripts/dev.sh go` as well.

See `docs/dev-environment.md#getting-the-mod-into-your-own-client` for the details.

## 2. Start from a clean profile

Do this **before** you join or make a world, and do it on whichever side is running the mod's
rules — the server in step 3a, your own instance in step 3b. Everything from step 5 on is written
as what an empty profile looks like, and a profile carrying an earlier playtest's purchases makes
those observations quietly wrong rather than obviously wrong.

On a profile that has never run the mod there is nothing to delete, so go straight to step 3a or 3b.

Two things to know first.

**Permanent progression is not in the world.** It sits in the Fabric config directory, so making a
new world does not reset it. These are the files:

```
config/hardcore-roguelite-progress.json     purchases and currency
config/hardcore-roguelite-balance.json      your price overrides, if you made any
config/hardcore-roguelite-unlocks.json      only on a profile from an older build
config/hardcore-roguelite-currency.json     only on a profile from an older build
```

**Deleting them under a running game does nothing.** The process holds its own copy from launch to
exit and writes it through on every change, so a file deleted while the game is up is simply written
again. Stop first.

### On the dev server

The rules run on the *server*, so the files that matter are the server's — not your client
instance's. The server has to be down while you delete them.

`MHR_CONTEXT` below is the same cluster name `scripts/dev.sh` uses, so the jars you built in step 1
and the files you delete here cannot end up on different clusters. It defaults to `eero-pc`; set it
to `mac-docker-desktop` if that is the one you are on. Setting it to the empty string is how
`scripts/dev.sh` opts out of naming a cluster at all — do the same here by dropping
`--context "$CTX"` from each line and letting your ambient kubectl context decide.

```sh
CTX="${MHR_CONTEXT-eero-pc}"      # the same default scripts/dev.sh uses
```

> **This wipes the progression of everybody who plays on the dev server**, not only yours — it is
> one shared profile. For a playtest of your own that nobody else notices, use singleplayer below.

```sh
kubectl --context "$CTX" -n mhr-dev scale deploy/mhr-server --replicas=0
kubectl --context "$CTX" -n mhr-dev wait --for=delete pod -l app=mhr-server --timeout=5m

BUILD=$(kubectl --context "$CTX" -n mhr-dev get pod -l app=mhr-build \
  -o jsonpath='{.items[0].metadata.name}')
kubectl --context "$CTX" -n mhr-dev exec "$BUILD" -- \
  rm -f /pvc/server/config/hardcore-roguelite-progress.json \
        /pvc/server/config/hardcore-roguelite-balance.json \
        /pvc/server/config/hardcore-roguelite-unlocks.json \
        /pvc/server/config/hardcore-roguelite-currency.json

scripts/dev.sh newworld      # brings the server back up on a fresh world
```

`scripts/dev.sh newworld` on its own is **not** enough: it deletes the three world directories and
nothing else, so your purchases and currency survive it. That is correct — permanent progression is
meant to outlive a world — but it means a world reset alone does not give you the fresh profile this
guide assumes.

### In singleplayer

If the game is up, quit to the title screen or close it, then delete those four files from your
instance's `config/` directory. If an earlier playtest left a save behind, delete that too — or, to
keep the world but forget which run it was on, delete `<save>/hardcore-roguelite-run.json` instead.

---

A missing progress file is a new player with nothing. **A file that is there and cannot be read
stops the game on purpose**, naming the path — that is not a crash to work around, it is the mod
refusing to write an empty profile over a repairable one.

## 3a. Play on the dev server

This is the path the repository actually supports, and the one `scripts/dev.sh` is built around.

The server is not exposed outside the cluster, so forward it to whichever machine you are playing
from:

```sh
CTX="${MHR_CONTEXT-eero-pc}"      # as in step 2, if this is a new shell
kubectl --context "$CTX" -n mhr-dev port-forward svc/mhr-server 25565:25565
```

Then, in a Fabric 26.3 client with both jars from step 1 in its `mods/` folder, add a server at
`localhost:25565` and join. The client needs the mod too: the shop screen and the inventory
padlocks are client-side.

Op yourself, because every `/mhr` command needs permission level 2:

```sh
scripts/dev.sh rcon "op <your-minecraft-name>"
```

> The dev server runs `online-mode=false`. Do not put it on a real network.

The cluster is shared. Somebody else's `scripts/dev.sh go` will restart the server under you with
their jar.

## 3b. Play in singleplayer

Not a path the repository verifies, but there is nothing in the mod that needs a dedicated server.
Make an ordinary Fabric 26.3 instance, point `scripts/dev.sh client` at its `mods/`, and create a
**new world with cheats allowed**. The lobby's own controls — the shop block and the drop off the
island — need no cheats, but every `/mhr` command in this document does.

Hardcore is not required. The mod cancels vanilla's death handling itself, so the roguelite rules
apply either way.

## 4. First join: the lobby

You arrive on a small grass island floating in empty void, at `y=65`, in the dimension
`hardcore_roguelite:lobby`. Chat says:

> No run in progress. Shop at the emerald block, then jump off the island to start one.

Look around and you should see the whole room: a 9x9 lawn, nothing above it, nothing below it, and
an **emerald block three paces south** of where you landed. That is the shop. Right-click it and the
shop screen opens — the same screen `/mhr shop` gives you.

That is the between-runs room. Nothing spawns there, there is no weather, and you cannot dig it up:
block breaking is refused in the lobby unless you are in creative. It is never deleted, so anything
you leave in it stays for good.

Check where you stand at any time with:

```
/mhr run                  the phase, the run id, the seed, how many runs are done
/mhr list                 every unlock, owned or locked, plus your currency
```

## 5. Start run 1 and see what is missing

**Walk off the edge of the island.** That is how a run starts: there is nothing down there, and
falling past `y=0` is the signal. `/mhr run start` does the same thing from the console and is what
the tests and this document use when a named seed is wanted.

Chat answers `Run 1 started on seed <seed>`. The overworld, nether and end are deleted and
regenerated from that seed and you are dropped into the new overworld.

Look around. With nothing bought:

- **No trees.** Not sparse — none at all, in any biome. This direction is reliable: the unlock
  suppresses every tree, so a treeless desert and a treeless forest look the same. (The other
  direction is not — see step 9.)
- **No starter chest** at your feet. With something bought there would be one within three blocks.
- **A 128-block world.** Walk in any direction and the border stops you about 64 blocks out. That is
  the `tiny` tier, the one you get for having bought no tier at all.
- **Padlocks in the inventory.** Open it: the armour and offhand squares you have not bought are
  locked, and an item put in one is handed straight back.
- **No ore at all** — not coal, copper, iron, gold, redstone, lapis or diamond. Every one of the
  seven is sold separately.
- **No animals.** Cows, pigs, sheep, chickens, horses and wolves are each sold separately too, so
  an empty profile means an empty countryside. And no villages.

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

Currency is earned by finishing advancements inside a run. Mine some stone with a wooden pickaxe
during step 5 and watch two things happen at once: a chat line saying what it paid, and the number
in the purse at the top left of the screen going up. Most advancements pay nothing; what each one is
worth is `currency.advancements` in the balance data.

Two things are worth checking by hand here, because they are the rule rather than the payout:

- the purse is on screen the whole time, in the lobby as well as in a run;
- an advancement you finished in the last run pays again in this one — a run is a new world, and
  your advancements are cleared as you enter it.

For a playtest that wants to get to the shop quickly, the dev command still puts currency in by
hand:

```
/mhr currency give 20
/mhr currency                 what the purse holds now
```

## 8. Buy something

Right-click the emerald block on the island, or from anywhere:

```
/mhr shop
```

One scrolling page: restoring vanilla at the top, Vanilla+ below it. Click a square to buy it. An
affordable square buys and charges at once; one you cannot afford does nothing; one already owned
does nothing.

Buy these two, three currency each. They are the two whose effect you can check without
walking anywhere:

| Unlock | What it changes | When you see it |
| --- | --- | --- |
| `starter.bread` | 16 bread in a chest at the run's spawn | next run |
| `world.border.medium` | the world goes from 128 blocks across to 512 | next run |

Buy `world.trees` as well if you like — it is the most striking change — but do not use it as
your pass/fail signal. See step 9.

If you would rather see something change *immediately*, buy `player.slot.helmet` (also 3) and watch
the padlock leave that square in your inventory.

Close the shop. `/mhr list` should now show what you bought as `[owned]`, and the currency left
should be 20 minus what you spent.

## 9. Start run 2 and confirm

```
/mhr run start
```

`Run 2 started on seed <a different seed>`. It is a genuinely new world — new seed, new chunks, run
1's terrain deleted, not a teleport somewhere far away.

Two things confirm the purchases, and both are the same every time:

- **A chest within a few blocks**, holding 16 bread, with a chat line saying where it is.
- **`/mhr border` now says `medium (512 blocks across)`** where before run 1 it said
  `tiny (128 blocks across)`. Walk west until the border stops you if you want to see it.

**Trees are not a reliable check, and this is the trap worth knowing.** The unlock restores
*vanilla* tree generation — it does not plant trees for you. So run 2 puts you wherever its seed
puts you, and a legitimate spawn in a desert, a plains or a snowy flat has few trees or none,
with the unlock working perfectly. Seeing no trees at spawn is not a failure. If you want to
check trees specifically, either walk to a forest, or start the run on a seed you have used
before and compare the same place with the unlock and without:

```
/mhr run start 45000001        the seed argument exists for exactly this
```

That is the whole loop: the run was disposable, the purchases were not.

## 10. Round again

Die or `/mhr run end`, spend, start. Each run is a fresh world and everything bought is still yours.

To start the *whole thing* over, go back to [step 2](#2-start-from-a-clean-profile) — delete the
progress file as well as the world. Deleting only the world keeps your purchases.

## When it goes wrong

| What you see | What it means |
| --- | --- |
| `there is no hardcore_roguelite:lobby dimension — is the mod's data pack loaded?` | the jar is not loaded, or is the wrong Minecraft version |
| `Unknown or incomplete command` on `/mhr` | you are not opped, or cheats are off |
| `This save is stopped` | the loop hit a failure it will not guess its way past. Restart the server and look at the log; it refuses rather than risk deleting a world you are standing in |
| a run refuses to start | `/mhr run` says why — one is already in progress, or the save cannot describe all three run dimensions |
| your purchases vanished | you deleted `config/hardcore-roguelite-progress.json`, or you are playing a different instance |

## How much of this has actually been run

Three different levels of "verified", because they are not the same and the difference matters if
a step here turns out to be wrong.

**Driven for real against the dev server**, through `scripts/dev.sh rcon` on this branch's build.
Every command below was run in this order and the output is what is quoted in the steps above:

```
/mhr list          -> everything [locked], Currency: 0
/mhr border        -> Border tier: tiny (128 blocks across)
/mhr run start     -> Run 1 started on seed -2748112778258634698
/mhr run           -> run 1 in progress (seed -2748112778258634698)
/mhr currency give 20  -> Currency: 20                     step 7's shortcut, not its main path
/mhr shop          -> Only a player can open the shop.     (from the console; it needs a player)
/mhr run end       -> Back in the lobby.
/mhr currency      -> Currency: 20                          the run ended, the money did not
/mhr unlock starter.bread                                   step 8, done the only way a console can
/mhr unlock world.border.medium
/mhr run start     -> Run 2 started on seed 7317067687666189064
/mhr border        -> Border tier: medium (512 blocks across)
```

and the server log for that run 2 reads `Starter chest at 15 67 -49`, one block from its spawn.
So the loop, the currency surviving a run, the border tier changing the next run and the starter
chest arriving are all confirmed on a real server rather than inferred.

Earning currency from an advancement is the one step above that this transcript does not cover: a
console has no advancements to finish. It is covered by `CurrencyEarningClientTest`, which plays a
real client on a real dedicated server and asserts the payout, the once-per-run rule and the number
reaching the screen.

The two `/mhr unlock` lines are the one substitution for the steps above: the console has no shop
screen, so nothing was bought by clicking here. The clicking is covered by `ShopClientTest` and by
`ProgressionCycleClientTest`, which buys both with a real mouse.

**Checked against the code**: every file path, version number, price, quoted game message and
command syntax on this page.

**Not done by anyone yet** — and this is the honest gap: nobody has sat at a keyboard and played
it. The agents working on this repository have no Minecraft client, so the parts that are only a
person with a mouse remain unexercised:

- `scripts/dev.sh client` actually landing the jars where your launcher reads them;
- the port-forward, joining, and being opped;
- the lobby, the padlocks and the shop as *drawn things* rather than as server state;
- whether fifteen minutes of this is pleasant to follow.

If you run it and a line is wrong, the game is right and this file is stale — fix the line.

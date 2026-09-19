# Dev environment

Nothing about developing this mod runs on the Linux desktop. Gradle, the Minecraft downloads and
the test server all live in the kubernetes cluster on the MacBook Air, so the desktop only holds
the source and issues commands.

## Shape of it

Namespace `mhr-dev`, one 40 GB volume, three pods:

| Pod            | Image                    | Job |
| -------------- | ------------------------ | --- |
| `mhr-build`    | `gradle:jdk25`           | Sleeps. You exec Gradle in it. Holds the source tree and the Gradle cache. |
| `mhr-gametest` | `gradle:jdk25` + Xvfb    | Sleeps. Runs the automated gameplay tests, client and all. |
| `mhr-server`   | `itzg/minecraft-server`  | A Fabric 26.3 dedicated server with the mod in its `mods/`. |

The volume is laid out as `/workspace` (source), `/gradle` (cache and the Minecraft artifacts),
`/server` (the server's game directory) and `/gametest` + `/gradle-gametest` (the test pod's own
tree and cache). The build and test pods see everything under `/pvc`; the server pod sees only
`/server`, mounted at its usual `/data`.

Both survive restarts. The expensive part — Minecraft, mappings, the Gradle cache — is downloaded
once and stays on the volume.

## Minecraft 26.3 has no mappings

Worth knowing before you touch the build file, because it trips up every older guide:

- **Minecraft is unobfuscated from 26.1 onwards.** Mojang stopped publishing obfuscation mappings,
  and Fabric stopped updating Yarn and intermediary after 1.21.11. The names in the jar are the
  real ones.
- So there is **no `mappings` dependency** in `build.gradle`, and **no `modImplementation`** —
  that configuration exists only to remap a dependency, and nothing needs remapping.
- The plugin id must be `net.fabricmc.fabric-loom`. The bare `fabric-loom` id is the legacy one
  and still insists on mappings; `net.fabricmc.fabric-loom-remap` is for 1.21.11 and older.
- Loom refuses to run on anything below **JDK 25**, which is why the build pod is `gradle:jdk25`
  even though the mod compiles to Java 21.

26.3 also reshaped a lot of the worldgen and command API. When a name is wrong, read it out of the
jar rather than guessing:

```sh
scripts/dev.sh shell
javap -cp /pvc/gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar \
  net.minecraft.world.level.levelgen.feature.TreeFeature
```

## Everyday use

```sh
scripts/dev.sh up      # first time only: create everything and wait for it
scripts/dev.sh go      # sync + build + install the jar + restart the server
scripts/dev.sh logs    # follow the server log
```

`go` is the loop. Edit here, run `go`, reconnect. A rebuild after a small change takes seconds;
the server restart is the slow part, about half a minute.

Other commands: `sync`, `build`, `deploy`, `gametest`, `console`, `rcon`, `shell`, `newworld`,
`status`, `down`, `nuke`. Run `scripts/dev.sh` with no arguments for the list.

`sync` copies exactly what a commit would see — tracked files plus untracked ones that are not
gitignored. It wipes `src/`, `k8s/` and `scripts/` in the pod first so deleted files do not
linger, and leaves `build/` and `.gradle/` alone so rebuilds stay fast.

## Automated gameplay tests

One command, no human in a Minecraft client:

```sh
scripts/dev.sh gametest
```

It syncs the tree, then runs three things in order and stops at the first that is red:

| Step | What it is |
| ---- | ---------- |
| `test` | The plain-Java unit tests — balance parsing and the like. No game. |
| `runGameTest` | Fabric's server GameTests: a dedicated server, no client. |
| `runClientGameTest` | Fabric's client GameTests: a **real Minecraft client** driving a **real dedicated server**. |

The last line is `PASS` or `FAIL`, and the exit status matches, so it can be the whole of a PR
check. There is nothing to confirm between iterations: change the code, run it again.

### Where it runs

In a third pod, `mhr-gametest`, not in the build pod and not against the dev server. It is the same
`gradle:jdk25` image plus `xvfb` and Mesa's software OpenGL driver, apt-installed at startup —
nothing in the cluster has a GPU, so the client renders with `llvmpipe` into a 1280×720 virtual
display. That works, and it is slow: a full run is minutes, most of it the client starting.

It has a Gradle cache of its own (`/pvc/gradle-gametest`) and a source tree of its own
(`/pvc/gametest/workspace`), so it neither waits for nor breaks someone else's `scripts/dev.sh go`.
The container is root only so that apt-get works; Gradle itself is dropped back to uid 1000, so
nothing root-owned lands on the volume.

Two things had to be arranged for the client to start headless at all, both in `scripts/dev.sh`:

- **`SDL_VIDEO_X11_FORCE_EGL=1`.** 26.3 asks SDL for the OpenGL context, SDL prefers GLX, and
  llvmpipe on a bare Xvfb has no GLX visual matching what the game asks for. EGL does. Without
  this the client dies on `Couldn't find matching GLX visual` before any test runs.
- **Xvfb at 24-bit colour**, started once per pod and reused.

### Test isolation

Nothing carries over between runs. Loom wipes `build/run/clientGameTest` before each one, and that
directory is the client's *and* the dedicated server's game directory, so the world, the config
directory, `hardcore-roguelite-unlocks.json` and the player's inventory all start empty. On top of
that every scenario begins by locking all five slots and emptying the player, so one scenario
cannot make the next one pass.

### Artifacts

`scripts/dev.sh gametest` copies logs, screenshots and crash reports back to `build/gametest/`
whether the run passed or failed:

```
build/gametest/run/clientGameTest/screenshots/*.png   what the client saw
build/gametest/run/clientGameTest/logs/latest.log     the client log
build/gametest/run/gameTest/logs/latest.log           the server GameTest log
build/gametest/reports/tests/...                      the unit-test HTML report
```

### Reading a failure

The client test log marks each scenario:

```
=== scenario locked-helmet-slot-refuses-a-helmet ===
=== scenario locked-helmet-slot-refuses-a-helmet: PASS ===
```

A failed one logs `FAIL` with the assertion message, writes
`screenshots/failed-<scenario-name>.png`, and carries on to the remaining scenarios — so one run
tells you everything that is broken, not just the first thing. The run ends by throwing with the
whole list, which is what turns the exit status non-zero.

An assertion message names what was expected in plain words, for example *"a locked helmet slot
must stay empty after a click that tries to fill it"*. The item-conservation ones also print the
count they found.

### What is automated now

These were manual client checks and are not any more, in
`src/gametest/java/fi/vilpponen/mhr/gametest/client/EquipmentLockClientTest.java`:

- **locked-helmet-slot-refuses-a-helmet** — real mouse clicks pick the helmet up and drop it on the
  armor square; the slot stays empty and the helmet still exists exactly once.
- **unlock-while-connected-then-equip** — `mhr unlock player.slot.helmet` with the inventory open;
  the client's copy of the unlock flips at once and the same clicks now equip it.
- **offhand-swap-hands-locked-then-unlocked** — the real swap-hands key, refused while the offhand
  is locked and vanilla once it is not.
- **locking-an-occupied-slot-empties-it** — `mhr lock` on a slot in use empties it on the spot and
  the shield comes back.
- **reconnect-keeps-a-locked-slot-empty** — wear a helmet, disconnect, lock the slot, reconnect: the
  slot is empty, the helmet is in the inventory and the client is told about the padlock again.

Every one of them ends by counting every copy of the item the player could still reach — inventory,
equipment, the cursor, and the ground — so "refused" can never quietly mean "destroyed".

The clicks are real. The cursor is moved to the middle of the square and the click is only sent once
the screen itself agrees that is the square under the pointer, so a layout change makes the test
fail rather than silently click somewhere else.

### What is still manual

- The padlock **artwork**. The tests screenshot the inventory with the helmet slot locked and again
  with it unlocked, and those are worth a look when the overlay changes, but nothing compares
  pixels. State assertions are the proof; the screenshots are for debugging.
- **Dispenser-fired armor** and **right-click-to-equip**, which need a block and an aimed
  interaction rather than an inventory screen.
- The **full-inventory fallback**, where a refused item falls at the player's feet.

## Joining the server

The server is not exposed outside the cluster. On the **Mac**:

```sh
kubectl -n mhr-dev port-forward svc/mhr-server 25565:25565
```

Then add a server in Minecraft pointing at `localhost:25565`.

The server runs with `online-mode=false`, so any username works and you do not need the port-forward
to authenticate. That is fine while it is only reachable through a forward on your own machine, and
is the reason it must not be put on a real network.

To op yourself, once you have joined at least once:

```sh
scripts/dev.sh rcon "op <your-username>"
```

## Testing the trees unlock

Trees are off until the unlock is bought. There is no shop yet, so use the dev command:

```sh
scripts/dev.sh rcon "mhr list"
scripts/dev.sh rcon "mhr unlock world.trees"
```

Worldgen only applies to chunks generated after the change, so walk into fresh land or start over
with `scripts/dev.sh newworld`. For a quick check without any of that, place the feature directly:

```sh
scripts/dev.sh rcon "forceload add 0 0 16 16"
scripts/dev.sh rcon "fill 0 100 0 8 100 8 minecraft:dirt"
scripts/dev.sh rcon "place feature minecraft:oak 4 101 4"
```

Locked, that answers "Failed to place feature". After `mhr unlock world.trees` it answers "Placed". The unlock file lives at `/server/config/hardcore-roguelite-unlocks.json`
on the volume — outside the world, because unlocks are meant to survive it.

## Testing the villages unlock

Villages are off until the unlock is bought. There is nothing to place directly here, so this one
needs a fresh world each way:

```sh
scripts/dev.sh newworld
scripts/dev.sh rcon "locate structure #minecraft:village"      # "Could not find a structure"
scripts/dev.sh rcon "locate structure minecraft:pillager_outpost"   # still found — other structures are untouched

scripts/dev.sh rcon "mhr unlock world.village"
scripts/dev.sh newworld
scripts/dev.sh rcon "locate structure #minecraft:village"      # found again
```

The second `newworld` matters: `locate` remembers that it already looked at a chunk, so a world
scanned while villages were locked keeps answering "not found" even after the unlock.

## Testing the equipment slots

All five slots start locked. On the server side you can flip them and see the state:

```sh
scripts/dev.sh rcon "mhr list"
scripts/dev.sh rcon "mhr unlock player.slot.offhand"
```

The rest of it — the padlocks, the equip attempts, the swap-hands key — used to need a human in a
real client. It does not any more:

```sh
scripts/dev.sh gametest
```

See [Automated gameplay tests](#automated-gameplay-tests). That runs a real client against a real
dedicated server and covers the locked helmet slot, unlocking while connected, the swap-hands key
both ways, locking a slot that is in use, and the reconnect.

There is one rule behind all of it: **nothing stays in a locked slot**. It is enforced in a single
place, the write barrier on `PlayerEquipment.set`, so the cases below are not five separate
features — they are five ways of asking the same question. What you are really checking each time
is that the item is refused *and* that it is still somewhere you can reach.

Three checks still want a human, because they need a block or an aimed interaction rather than an
inventory screen. Join through the port-forward and check:

- Right-click a helmet held in hand: it stays in your hand.
- Put an item in a dispenser aimed at you and fire it — armor must not go on.
- Fill your inventory completely, then have a locked slot refuse something: it falls at your feet
  rather than vanishing.

The server tells the client which slots are open when you join and again whenever `mhr unlock` or
`mhr lock` changes something, so the client's own config file is never consulted while connected.
Enforcement never reads that copy — it is for drawing only.

## Testing the world border

A run starts on the `tiny` tier, and the border is placed when the server starts, centered on the
world spawn. The sizes come from the `worldBorder` section of the balance file, not from Java, so
retuning one is editing `default-balance.json` or the config override. Change the tier with the dev
command, which applies it to the running world at once:

```sh
scripts/dev.sh rcon "mhr border"            # what tier is selected
scripts/dev.sh rcon "mhr border medium"     # tiny | medium | large | infinite
scripts/dev.sh rcon "worldborder get"       # vanilla's own read-back, in blocks
```

The tier is not stored anywhere yet, so a server restart goes back to `tiny`. Remembering it
between runs belongs to the permanent unlock state, which does not exist yet.

Each dimension gets its own center, so the server log is the quickest way to see what was applied:

```
World border tier tiny: 128 blocks across, overworld centered on 500, -700
  minecraft:overworld: 128 wide, centered on 500, -699
  minecraft:the_nether: 128 wide, centered on 62, -87
  minecraft:the_end: 512 wide, centered on 0, 0
```

The nether center is the overworld spawn through the 1:8 portal mapping, so a portal built anywhere
inside the overworld border comes out inside the nether one. To check that for real, move the spawn
somewhere far from the origin, build a portal there and send a mob through:

```sh
scripts/dev.sh rcon "setworldspawn 500 70 -700"
scripts/dev.sh rcon "mhr border tiny"
scripts/dev.sh rcon "fill 500 69 -700 503 74 -700 minecraft:obsidian"
scripts/dev.sh rcon "fill 501 70 -700 502 73 -700 minecraft:air"
scripts/dev.sh rcon "setblock 501 70 -700 minecraft:nether_portal[axis=x]"   # and 502 70, 501 71, 502 71
scripts/dev.sh rcon "summon minecraft:pig 501.5 70.0 -699.5"
# a mob takes 300 ticks in the portal, then:
scripts/dev.sh rcon "execute in minecraft:the_nether run data get entity @e[type=pig,limit=1] Pos"
```

## Testing the ore unlocks

Same shape, one unlock per ore: `world.ore.coal`, `world.ore.iron`, `world.ore.copper`,
`world.ore.gold`, `world.ore.redstone`, `world.ore.lapis`, `world.ore.diamond`.
The quick check places an ore vein in a block of stone and counts what landed:

```sh
scripts/dev.sh rcon "forceload add 0 0 16 16"
scripts/dev.sh rcon "fill 0 96 0 10 106 10 minecraft:stone"
scripts/dev.sh rcon "place feature minecraft:ore_iron 5 101 5"
scripts/dev.sh rcon "fill 0 96 0 10 106 10 minecraft:stone replace minecraft:iron_ore"
```

Locked, the place fails and nothing is filled. After `mhr unlock world.ore.iron` it places and the
last command counts the vein.

For the real thing, force-load land that has never been generated and count what is in it:

```sh
scripts/dev.sh rcon "forceload add 5000 5000 5031 5031"
scripts/dev.sh rcon "fill 5000 -59 5000 5015 60 5015 minecraft:stone replace minecraft:iron_ore"
```

A `fill ... replace` is a block counter that happens to destroy what it counts, so only do it in a
throwaway world. `rcon-cli` also reads commands from stdin, which is much faster than one
`scripts/dev.sh rcon` per command when you are counting fourteen ore blocks across several chunks.

Note that the cluster is shared: if someone else runs `scripts/dev.sh go` while you are testing,
the server restarts under you with their jar. The build pod's `/pvc/workspace` is shared too, so a
deploy can ship someone else's build. For a test that has to be left alone, copy your tree to a
directory of your own under `/pvc`, build there, and run a second server deployment against its own
`subPath` — then delete it when you are done.

### The large iron and copper veins

The deep veins do not come from an ore feature, so `place feature` cannot reach them and a scan of a
few chunks will usually miss them: they are rare, and only about one block in fifty of a vein is a
raw ore block. Two things make them testable:

- `raw_iron_block` and `raw_copper_block` only ever come from a vein, so counting them counts veins
  and nothing else.
- With a fixed `SEED` on the server, deleting the world regenerates exactly the same terrain. Scan
  the same coordinates once with the ore unlocked and once with it locked and the two runs are
  directly comparable. 16×16 chunks is enough to contain a few veins.

Something that is unlocked in both runs — coal is a good choice — should come out at roughly the
same count, which is how you know you really did regenerate the same world.
## Testing the crafted-tool enchant

The unlock is repeatable, so `mhr list` shows a level next to it and `mhr unlock` buys the next one:

```sh
scripts/dev.sh rcon "mhr list"
scripts/dev.sh rcon "mhr unlock player.craft.enchant"     # level 1
scripts/dev.sh rcon "mhr unlock player.craft.enchant 4"   # straight to the top level
scripts/dev.sh rcon "mhr lock player.craft.enchant"
```

The levels are kept in the same file as everything else, `/server/config/hardcore-roguelite-unlocks.json`,
which is now a map of unlock id to level rather than a list of ids. A file in the old format still
reads, with everything in it counting as level one, and is rewritten in the new shape on the spot.

How many levels there are and what each is worth are balance numbers, so a curve change needs no
rebuild:

```sh
scripts/dev.sh rcon "mhr balance"   # shows vanillaPlus.craftEnchant
scripts/dev.sh rcon "mhr reload"    # after editing config/hardcore-roguelite-balance.json
```

The crafting itself needs a real client, because there is no way to craft from the server console.
Join through the port-forward and check:

- With the unlock locked, craft a wooden pickaxe: it comes out plain.
- `mhr unlock player.craft.enchant`, then put the ingredients back in the grid. The output slot already
  shows the enchantment before you take it — that is the point, it is the crafting result that is
  enchanted, not the item in your hand afterwards.
- Take it out by clicking, by shift-clicking and through the recipe book. All three give the same
  enchanted item.
- Leave the ingredients sitting in the grid and pull the last one out and back a few times. The
  enchantment stays the same, so jiggling the grid is not a way to reroll. Craft one and set up the
  next: that one is a new roll.
- `mhr unlock player.craft.enchant 4` and craft a few more. Efficiency now turns up at V rather than I.
- Craft something that is not a tool — planks, a chest, a bow — and it stays plain.
- Nothing impossible ever lands: no Sharpness on a pickaxe, no Mending, no curses.

## Resource use

The Mac node has 8 CPUs and 24 GB. The build pod is capped at 6 CPU / 8 GB and the server at
4 CPU / 5 GB, both well inside that with room for whatever else the Mac is doing. `scripts/dev.sh down`
removes both pods but keeps the volume, so bringing it back is fast.

## Known gaps

- Client-side behaviour is tested by the client GameTests in the `mhr-gametest` pod, not by hand —
  see [Automated gameplay tests](#automated-gameplay-tests). There is still no client you can *look*
  at: for exploring by eye you run the real Minecraft client on the Mac through the port-forward.
- The gametest pod apt-gets its virtual display on every start, so the first `scripts/dev.sh
  gametest` after a pod restart waits a minute for that, and a run with a cold Gradle cache waits
  rather longer while it downloads Minecraft again.
- The Gradle `runServer`/`runClient` dev tasks from Loom are not used here; the mod is tested as a
  built jar against a real server, which is closer to how it will ship but slower to iterate.
- The cluster is a `kind` cluster with no port mappings, hence the port-forward. Exposing 25565 on
  the Mac's Tailscale address would mean recreating the cluster.

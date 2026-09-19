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

That source tree is shared between people, though, the same way the build pod's is. Two `gametest`
runs at once overwrite each other's `src/` halfway through, which shows up as *somebody else's*
tests failing in your output — a scenario name you have never heard of is the giveaway. Give
yourself a tree of your own to stay out of the way:

```sh
MHR_GAMETEST_WORKSPACE=/pvc/gametest/workspace-mine scripts/dev.sh gametest
```

The Gradle cache stays shared either way, which is the part worth sharing.

Two things had to be arranged for the client to start headless at all, both in `scripts/dev.sh`:

- **`SDL_VIDEO_FORCE_EGL=1`.** 26.3 asks SDL for the OpenGL context, SDL prefers GLX, and llvmpipe
  on a bare Xvfb has no GLX visual matching what the game asks for. EGL does. Without this the
  client dies on `Couldn't find matching GLX visual` before any test runs. Note the name: the SDL2
  spelling was `SDL_VIDEO_X11_FORCE_EGL` and SDL3 ignores it silently, which looks exactly like the
  variable not working.
- **Xvfb at 24-bit colour**, started once per pod and reused.

### Test isolation

Nothing carries over between runs. Loom wipes `build/run/clientGameTest` before each one, and that
directory is the client's *and* the dedicated server's game directory, so the world, the config
directory, `hardcore-roguelite-unlocks.json` and the player's inventory all start empty. On top of
that every scenario sets up the state it depends on rather than inheriting it — the equipment ones
lock all five slots and empty the player, the tree ones set `world.trees` to what they need and
clear their own patch of ground — so one scenario cannot make the next one pass, and the order they
run in does not matter.

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
`screenshots/<n>_failed-<scenario-name>.png`, and carries on to the remaining scenarios — so one run
tells you everything that is broken, not just the first thing. The run ends by throwing with the
whole list, which is what turns the exit status non-zero.

An assertion message names what was expected in plain words, for example *"a locked helmet slot
must stay empty after a click that tries to fill it"*. The item-conservation ones also print the
count they found.

### What is automated now

#### The equipment slots

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

The two inventory screenshots are worth a look, because they are the padlock check the docs used
to ask a human for:

| Helmet slot locked | ...and the moment after `mhr unlock player.slot.helmet` |
| ------------------ | ------------------------------------------------------ |
| ![five padlocks](images/gametest-helmet-slot-locked.png) | ![four padlocks](images/gametest-helmet-slot-unlocked.png) |

Five padlocks become four, with the helmet square back to its vanilla empty icon, without the
inventory being closed and reopened.

The clicks are real. The cursor is moved to the middle of the square and the click is only sent once
the screen itself agrees that is the square under the pointer, so a layout change makes the test
fail rather than silently click somewhere else.

#### The trees unlock

Trees need no client, so most of them are **server GameTests** — the fast step, seconds rather than
minutes. They live in `src/gametest/java/fi/vilpponen/mhr/gametest/TreeUnlockGameTest.java` and each
one lays its own patch of dirt and asks a vanilla feature to place itself on it, which is the same
call worldgen, bonemeal and a sapling all come down to:

- **locked-world-refuses-a-tree-feature** / **unlocked-world-places-a-tree-feature** — the
  `place feature minecraft:oak` check the section below used to ask a human to type.
- **locked-world-refuses-a-fallen-tree** / **unlocked-world-places-a-fallen-tree** — the same for
  fallen trees, which are a free pile of logs and so have to go too.
- **sapling-will-not-grow-while-locked** / **sapling-grows-once-unlocked** — a sapling pushed along
  the way bonemeal pushes it. Locked, it is still standing afterwards and no logs exist; unlocked,
  it is a tree.
- **unrelated-vegetation-still-places-while-locked** — a flower patch still places while trees are
  locked. This is the control: a mixin that quietly stopped *every* feature would pass all of the
  above and be caught only here.

The real worldgen path cannot be checked there, because Fabric's server GameTests run on a superflat
world with no trees in it to suppress. So it gets a client GameTest of its own, in
`src/gametest/java/fi/vilpponen/mhr/gametest/client/TreeWorldgenClientTest.java`, which builds a
dedicated server on an *ordinary* overworld instead of the harness's flat one:

- **the-world-a-run-starts-in-has-no-trees** — the land the server made around spawn by itself, with
  nothing force-loaded. No logs, and ground blocks in the thousands so that "no logs" means
  something.
- **fresh-land-has-no-trees-while-locked** — a plain `minecraft:forest` about six thousand blocks
  out, force-loaded into existence on the spot. Still no logs.
- **fresh-land-has-trees-once-unlocked** — `mhr unlock world.trees`, then a *different* forest six
  thousand blocks the other way. Logs in the hundreds.

Two different forests on purpose: worldgen only applies to chunks made after the change, so scanning
the same patch twice would answer "no trees" both times and look exactly like the feature working.
Plain `minecraft:forest` on purpose too — a biome *tag* also matches places like a mushroom island,
which has no trees in vanilla either, and "no logs where there never were any" proves nothing.

| Fresh forest, trees locked | ...and a fresh forest after `mhr unlock world.trees` |
| -------------------------- | ---------------------------------------------------- |
| ![grass and flowers, no trees](images/gametest-fresh-forest-trees-locked.png) | ![the same kind of land, full of oaks](images/gametest-fresh-forest-trees-unlocked.png) |

### What is still manual

- The padlock **artwork**. The tests screenshot the inventory with the helmet slot locked and again
  with it unlocked, and those are worth a look when the overlay changes, but nothing compares
  pixels. State assertions are the proof; the screenshots are for debugging.
- **Dispenser-fired armor** and **right-click-to-equip**, which need a block and an aimed
  interaction rather than an inventory screen.
- The **full-inventory fallback**, where a refused item falls at the player's feet.
- Nothing about trees, beyond looking at a world by eye if you want to.

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

Nothing here needs doing by hand any more:

```sh
scripts/dev.sh gametest
```

covers the whole unlock — direct feature placement both ways, fallen trees, saplings, and real
terrain generated from scratch with and without the unlock. See
[Automated gameplay tests](#automated-gameplay-tests). What follows is how to poke at it on the dev
server when you want to *see* it rather than prove it.

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
## Testing the animal unlocks

Unlike trees, an animal unlock takes effect at once — natural spawning asks every time, so land you
have already visited starts or stops producing that species straight away. That half needs a player
online to test, though, because the spawn tick only runs near one. What rcon alone can test is the
other half: force-load a patch of land nobody has been to yet, then count what is standing in it.

**Widen the border first.** New land only gets its animals if it is inside the world border, and
the border starts 128 blocks wide, so a patch out at x=8000 generates perfectly empty and every
count reads zero whether the species is locked or not. That looks exactly like the feature working
and is not.

```sh
scripts/dev.sh rcon "mhr border infinite"
scripts/dev.sh rcon "mhr lock world.animal.cow"
scripts/dev.sh rcon "execute positioned 8000 100 8000 run locate biome minecraft:plains"
scripts/dev.sh rcon "forceload add 7904 7904 8159 8159"   # 256 chunks, the per-command maximum
# wait a minute or so for the chunks to generate
scripts/dev.sh rcon "execute if entity @e[type=minecraft:cow,x=7904,y=-64,z=7904,dx=256,dy=384,dz=256]"
```

That answers "Test failed" while the species is locked and "Test passed. Count: N" once it is
unlocked and a *different* fresh patch has been generated. Plains is the biome to pick: cows,
sheep, pigs and chickens all populate it, so one patch tests four species at once — but do not
reach for rabbits or foxes as a control there, because plains has neither. Pick the control out of
the biome you are actually standing in.

Force-loaded chunks stay loaded and cost memory, so `forceload remove all` between rounds, or
the server eventually gets killed.

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

## Testing the starter chest

Starter items are ordinary unlocks: they sit in `default-balance.json` under `unlocks` with every
other price, and carry the stack they hand over. See [`balance.md`](balance.md#starter-items).

```json
"starter.bread": { "price": 3, "item": { "id": "minecraft:bread", "count": 16 } }
```

Because it is balance data, the local override on the volume —
`/server/config/hardcore-roguelite-balance.json` — can retune the contents as well as the price,
and `mhr reload` picks the edit up without a restart:

```sh
scripts/dev.sh rcon "mhr reload"
```

What the player *owns* is separate and lives with the other unlocks, outside the world, so it
survives `newworld`.

The chest normally appears when a player first joins a fresh world. That needs a real client, so
for a quick check from the console place it by hand instead:

```sh
scripts/dev.sh rcon "mhr list"                      # unlocks, then the starter items
scripts/dev.sh rcon "mhr unlock starter.bread"
scripts/dev.sh rcon "mhr starterchest 0 64 0"       # position needed from the console
scripts/dev.sh rcon "data get block -1 63 -1"       # it lands next to the spot you named
```

`mhr starterchest` with no position puts it next to you, and only works in game.

Things worth checking:

- Nothing owned: the command refuses and no block is placed.
- Up to 27 filled slots is one chest; past that it is a double chest, and `data get block` on each
  half shows the load split 27 and the rest.
- Items stack the way the game would: two catalogue entries of the same thing become one stack, and
  a count over a stack splits across slots.
- Over 54 slots is a broken catalogue. The chest is still placed and still full, and everything left
  out is named both in chat and in the server log, as one tally per item rather than one line per
  slot — a `count` in the millions is legal balance data, and has to report rather than hang.
- The chest lands next to you, at your level — not on the surface above you. Worth checking from
  underground, since that is where the two used to differ: `mhr starterchest 0 20 0` inside solid
  stone should refuse to find a clear spot and say so, while a spot in a cave at y 20 should get a
  chest at y 20 rather than one on the hillside overhead.
- `mhr list` ends with whether this run has had its chest yet. It is a flag in the world's own save,
  so `newworld` clears it and logging out and back in does not give a second chest.

## Resource use

The Mac node has 8 CPUs and 24 GB. The build pod is capped at 6 CPU / 8 GB and the server at
4 CPU / 5 GB, both well inside that with room for whatever else the Mac is doing. `scripts/dev.sh down`
removes both pods but keeps the volume, so bringing it back is fast.

## Known gaps

- Client-side behaviour is tested by the client GameTests in the `mhr-gametest` pod, not by hand —
  see [Automated gameplay tests](#automated-gameplay-tests). There is still no client you can *look*
  at: for exploring by eye you run the real Minecraft client on the Mac through the port-forward.
- The mouse button is `InputConstants.MOUSE_BUTTON_LEFT`, which is **1** in 26.3, not 0. 26.3
  takes its input from SDL and SDL numbers buttons from one. Pressing 0 presses nothing at all and
  the test then fails somewhere much later, so it is worth knowing before writing the next one.
- The gametest pod apt-gets its virtual display on every start, so the first `scripts/dev.sh
  gametest` after a pod restart waits a minute for that, and a run with a cold Gradle cache waits
  rather longer while it downloads Minecraft again.
- The Gradle `runServer`/`runClient` dev tasks from Loom are not used here; the mod is tested as a
  built jar against a real server, which is closer to how it will ship but slower to iterate.
- The cluster is a `kind` cluster with no port mappings, hence the port-forward. Exposing 25565 on
  the Mac's Tailscale address would mean recreating the cluster.

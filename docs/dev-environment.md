# Dev environment

Nothing about developing this mod runs on the Linux desktop. Gradle, the Minecraft downloads and
the test server all live in the kubernetes cluster on the MacBook Air, so the desktop only holds
the source and issues commands.

## Shape of it

Namespace `mhr-dev`, one 40 GB volume, two pods:

| Pod          | Image                    | Job |
| ------------ | ------------------------ | --- |
| `mhr-build`  | `gradle:jdk25`           | Sleeps. You exec Gradle in it. Holds the source tree and the Gradle cache. |
| `mhr-server` | `itzg/minecraft-server`  | A Fabric 26.3 dedicated server with the mod in its `mods/`. |

The volume is laid out as `/workspace` (source), `/gradle` (cache and the Minecraft artifacts) and
`/server` (the server's game directory). The build pod sees all three under `/pvc`; the server pod
sees only `/server`, mounted at its usual `/data`.

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

Other commands: `sync`, `build`, `deploy`, `console`, `rcon`, `shell`, `newworld`, `status`,
`down`, `nuke`. Run `scripts/dev.sh` with no arguments for the list.

`sync` copies exactly what a commit would see — tracked files plus untracked ones that are not
gitignored. It wipes `src/`, `k8s/` and `scripts/` in the pod first so deleted files do not
linger, and leaves `build/` and `.gradle/` alone so rebuilds stay fast.

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

The rest needs a real client, because the lock marker is drawn client-side and the equip attempts
have to come from a player.

There is one rule behind all of it: **nothing stays in a locked slot**. It is enforced in a single
place, the write barrier on `PlayerEquipment.set`, so the cases below are not five separate
features — they are five ways of asking the same question. What you are really checking each time
is that the item is refused *and* that it is still somewhere you can reach.

Join through the port-forward and check:

- Open the inventory: the four armor squares and the offhand square carry a padlock.
- Click, shift-click or number-key an armor piece into a locked slot — nothing moves.
- Right-click a helmet held in hand: it stays in your hand.
- Hold something in your main hand and press the swap-hands key (**F** by default). The offhand
  stays empty and the item ends up back in your inventory — check it is *there*, in a free slot,
  not destroyed and not duplicated. Then `mhr unlock player.slot.offhand` and press F again: now it swaps
  normally.
- Put an item in a dispenser aimed at you and fire it — armor must not go on.
- `mhr unlock player.slot.helmet` while the inventory is open: the helmet padlock disappears at once, the
  other four stay. Equipping a helmet then works and nothing else changed.
- Locking a slot that is in use: `mhr unlock player.slot.offhand`, raise a shield, then
  `mhr lock player.slot.offhand`. The shield goes back to your inventory immediately and right-clicking
  must not raise it. Same for a worn helmet and `mhr lock player.slot.helmet`.
- The same with a full inventory: the item falls at your feet rather than vanishing.
- Log out with a locked slot occupied — set it up with `/item replace entity <you> weapon.offhand
  with minecraft:shield` — then log back in. The slot must be empty and the shield in your
  inventory.

The server tells the client which slots are open when you join and again whenever `mhr unlock` or
`mhr lock` changes something, so the client's own config file is never consulted while connected.
Enforcement never reads that copy — it is for drawing only.
## Testing the animal unlocks

Animals only appear in terrain generated after the species was unlocked, so the test is always
"force-load a patch of land nobody has been to yet, then count what is standing in it". No client
needed — rcon does all of it.

```sh
scripts/dev.sh rcon "mhr lock world.animal.cow"
scripts/dev.sh rcon "execute positioned 8000 100 8000 run locate biome minecraft:plains"
scripts/dev.sh rcon "forceload add 7904 7904 8159 8159"   # 256 chunks, the per-command maximum
# wait a minute or so for the chunks to generate
scripts/dev.sh rcon "execute if entity @e[type=minecraft:cow,x=7904,y=-64,z=7904,dx=256,dy=384,dz=256]"
```

That answers "Test failed" while the species is locked and "Test passed. Count: N" once it is
unlocked and a *different* fresh patch has been generated. Plains is the biome to pick: cows,
sheep, pigs and chickens all populate it, so one patch tests four species at once. Rabbits and
foxes make a good control — they are not part of this unlock and should keep showing up.

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

## Resource use

The Mac node has 8 CPUs and 24 GB. The build pod is capped at 6 CPU / 8 GB and the server at
4 CPU / 5 GB, both well inside that with room for whatever else the Mac is doing. `scripts/dev.sh down`
removes both pods but keeps the volume, so bringing it back is fast.

## Known gaps

- No client in the cluster. You run the real Minecraft client on the Mac. Testing client-side
  behaviour (the locked inventory slots from the design doc) will need a different arrangement.
- The Gradle `runServer`/`runClient` dev tasks from Loom are not used here; the mod is tested as a
  built jar against a real server, which is closer to how it will ship but slower to iterate.
- The cluster is a `kind` cluster with no port mappings, hence the port-forward. Exposing 25565 on
  the Mac's Tailscale address would mean recreating the cluster.

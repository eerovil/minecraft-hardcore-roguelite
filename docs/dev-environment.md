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

package fi.vilpponen.mhr.gametest.server;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-species passive-animal unlocks, checked on a plain dedicated server with no client.
 *
 * <p>Two seams carry the whole feature and both are real vanilla entry points, so this test calls
 * them the way the game does rather than reaching into the mod:
 *
 * <ul>
 * <li>{@code SpawnPlacements.checkSpawnRules} is the question both halves of natural spawning ask
 * before they place anything — the chunk-generation pass and the spawn tick afterwards.
 * <li>{@code EntityType.create(Level, EntitySpawnReason)} is how a mob is built once something has
 * decided to make one, and is the only door the chicken jockey comes through.
 * </ul>
 *
 * <p>Nothing here is probabilistic except the chicken-jockey scenario, which rolls vanilla's own
 * 5% jockey chance enough times that missing it is a one-in-thirty-thousand event.
 * {@link fi.vilpponen.mhr.gametest.client.AnimalWorldgenClientTest} covers the other end — real
 * terrain, generated from scratch, counted animal by animal.
 *
 * <p>Everything is one test method on purpose. The unlock state is one file shared by the whole
 * server and GameTest runs a batch's tests side by side in the same world, so two methods flipping
 * unlocks would step on each other. Inside the method the scenarios are strictly sequential and
 * every one of them starts by locking all six species, so the order does not matter either.
 */
public class AnimalSpawnGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** The test structure is 8x8x8. The floor is laid at y=0 and the animals are asked about y=1. */
	private static final int SIZE = 8;
	private static final BlockPos STANDING_ON = new BlockPos(4, 1, 4);

	/** Fixed, so two runs ask the spawn rules exactly the same questions. */
	private static final long SPAWN_RULE_SEED = 0xA417A1L;

	/**
	 * How many baby zombies the jockey scenario finalizes. Vanilla gives a baby zombie a 5% chance
	 * of arriving on a chicken, so 200 tries miss altogether about three times in a hundred
	 * thousand runs — well past the point where a red test means a broken feature.
	 */
	private static final int JOCKEY_ATTEMPTS = 200;

	/** One of the six species: the unlock that sells it and the entity type it spawns as. */
	private record Species(String name, String unlock, EntityType<?> type) {}

	private static final List<Species> SPECIES = List.of(
			new Species("cow", "world.animal.cow", EntityTypes.COW),
			new Species("pig", "world.animal.pig", EntityTypes.PIG),
			new Species("sheep", "world.animal.sheep", EntityTypes.SHEEP),
			new Species("chicken", "world.animal.chicken", EntityTypes.CHICKEN),
			new Species("horse", "world.animal.horse", EntityTypes.HORSE),
			new Species("wolf", "world.animal.wolf", EntityTypes.WOLF));

	/**
	 * The mobs that must not notice any of this. Three shapes of bystander, because the feature
	 * could plausibly damage any of them: a passive animal that is simply not sold, a near miss
	 * that shares a family with something that is, and a hostile mob.
	 */
	private static final List<EntityType<?>> BYSTANDERS = List.of(
			EntityTypes.RABBIT,
			EntityTypes.FOX,
			EntityTypes.SQUID,
			EntityTypes.MOOSHROOM,
			EntityTypes.DONKEY,
			EntityTypes.LLAMA,
			EntityTypes.CAT,
			EntityTypes.ZOMBIE,
			EntityTypes.SKELETON,
			EntityTypes.CREEPER,
			EntityTypes.SPIDER);

	/** The three reasons the world makes an animal nobody asked for. */
	private static final List<EntitySpawnReason> THE_WORLDS_OWN_DOING = List.of(
			EntitySpawnReason.NATURAL,
			EntitySpawnReason.CHUNK_GENERATION,
			EntitySpawnReason.JOCKEY);

	/** Reasons that mean a player, a command or a rule of the game deliberately made one. */
	private static final List<EntitySpawnReason> DELIBERATE = List.of(
			EntitySpawnReason.COMMAND,
			EntitySpawnReason.SPAWN_ITEM_USE,
			EntitySpawnReason.BREEDING,
			EntitySpawnReason.SPAWNER,
			EntitySpawnReason.STRUCTURE,
			EntitySpawnReason.DISPENSER,
			EntitySpawnReason.CONVERSION,
			EntitySpawnReason.EVENT);

	@GameTest(maxTicks = 2000)
	public void animalUnlocksDecideWhatTheWorldSpawns(GameTestHelper helper) {
		layTheGround(helper);
		daylight(helper);

		List<String> failures = new ArrayList<>();

		for (Species species : SPECIES) {
			scenario(failures, "unlocked-" + species.name() + "-spawns-the-vanilla-way",
					() -> unlockedSpeciesSpawns(helper, species));
			scenario(failures, "locked-" + species.name() + "-never-spawns-by-itself",
					() -> lockedSpeciesNeverSpawns(helper, species));
			scenario(failures, "locked-" + species.name() + "-can-still-be-made-on-purpose",
					() -> lockedSpeciesCanStillBeMade(helper, species));
			scenario(failures, "unlocking-" + species.name() + "-leaves-the-other-five-locked",
					() -> mixedLocksAreIndependent(helper, species));
		}

		scenario(failures, "summon-still-works-with-every-species-locked",
				() -> summonStillWorks(helper));
		scenario(failures, "locking-every-species-leaves-other-mobs-alone",
				() -> bystandersAreUntouched(helper));
		scenario(failures, "locked-chicken-gets-no-jockey-chicken",
				() -> lockedChickenGetsNoJockey(helper));
		scenario(failures, "unlocked-chicken-gets-its-jockey-back",
				() -> unlockedChickenGetsItsJockeyBack(helper));
		scenario(failures, "a-locked-chicken-does-not-stop-the-other-jockeys",
				() -> otherJockeysAreUntouched(helper));

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " animal-spawn scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All animal-spawn server scenarios passed.");
		helper.succeed();
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * The baseline, and the reason none of the "must not spawn" scenarios can pass vacuously: with
	 * the species bought, vanilla's own spawn rules say yes on this patch of grass, and the game
	 * hands back a real animal for each of the three reasons the world uses.
	 */
	private void unlockedSpeciesSpawns(GameTestHelper helper, Species species) {
		unlockEverySpecies(helper);

		for (EntitySpawnReason reason : THE_WORLDS_OWN_DOING) {
			if (reason == EntitySpawnReason.JOCKEY && species.type() != EntityTypes.CHICKEN) {
				// Only the chicken is ever built as a jockey; asking about the rest would be
				// asserting something vanilla never does.
				continue;
			}
			check(created(helper, species.type(), reason) != null,
					"an unlocked " + species.name() + " must be buildable for " + reason
							+ ", and the game returned nothing");
		}
		check(spawnRulesSayYes(helper, species.type(), EntitySpawnReason.CHUNK_GENERATION),
				"an unlocked " + species.name() + " must pass vanilla's chunk-generation spawn rules"
						+ " on grass, and it does not — the locked half of this test would then"
						+ " prove nothing");
		check(spawnRulesSayYes(helper, species.type(), EntitySpawnReason.NATURAL),
				"an unlocked " + species.name() + " must pass vanilla's natural spawn rules on lit"
						+ " grass, and it does not — the locked half of this test would then prove"
						+ " nothing");
	}

	/** Locked, the world's own two spawn paths both refuse it, and so does building one. */
	private void lockedSpeciesNeverSpawns(GameTestHelper helper, Species species) {
		lockEverySpecies(helper);

		check(!spawnRulesSayYes(helper, species.type(), EntitySpawnReason.NATURAL),
				"a locked " + species.name() + " must fail the spawn-tick rules, and it passed");
		check(!spawnRulesSayYes(helper, species.type(), EntitySpawnReason.CHUNK_GENERATION),
				"a locked " + species.name() + " must fail the chunk-generation rules, and it passed");
		for (EntitySpawnReason reason : THE_WORLDS_OWN_DOING) {
			check(created(helper, species.type(), reason) == null,
					"a locked " + species.name() + " must not be built for " + reason
							+ ", and the game handed one over");
		}
	}

	/**
	 * Everything that is somebody deliberately making an animal stays vanilla while the species is
	 * locked: spawn eggs, commands, breeding, spawners, structures, dispensers and conversions.
	 */
	private void lockedSpeciesCanStillBeMade(GameTestHelper helper, Species species) {
		lockEverySpecies(helper);

		for (EntitySpawnReason reason : DELIBERATE) {
			Entity made = created(helper, species.type(), reason);
			check(made != null, "a locked " + species.name() + " must still be made for " + reason
					+ ", because that is somebody deliberately making one, and the game returned"
					+ " nothing");
			discard(made);
		}
	}

	/**
	 * The unlocks are six switches and not one. With exactly one species bought, that species is
	 * back and the other five are still missing — which is the mixed state a real save is almost
	 * always in.
	 */
	private void mixedLocksAreIndependent(GameTestHelper helper, Species species) {
		lockEverySpecies(helper);
		command(helper, "mhr unlock " + species.unlock());

		check(spawnRulesSayYes(helper, species.type(), EntitySpawnReason.CHUNK_GENERATION),
				"buying " + species.name() + " must bring it back, and the spawn rules still say no");

		for (Species other : SPECIES) {
			if (other.name().equals(species.name())) {
				continue;
			}
			check(!spawnRulesSayYes(helper, other.type(), EntitySpawnReason.CHUNK_GENERATION),
					"buying " + species.name() + " must not also unlock " + other.name()
							+ ", and " + other.name() + " passed the chunk-generation rules");
			check(created(helper, other.type(), EntitySpawnReason.NATURAL) == null,
					"buying " + species.name() + " must not also unlock " + other.name()
							+ ", and a natural " + other.name() + " was built");
		}
	}

	/**
	 * {@code /summon} through the real command dispatcher, for every species, with every species
	 * locked. This is the one the feature contract is explicit about: a locked animal is still an
	 * ordinary animal that an admin can place.
	 */
	private void summonStillWorks(GameTestHelper helper) {
		lockEverySpecies(helper);

		for (Species species : SPECIES) {
			helper.killAllEntities();
			BlockPos where = helper.absolutePos(STANDING_ON);
			command(helper, "summon " + typeId(species.type()) + " "
					+ where.getX() + " " + where.getY() + " " + where.getZ());

			int standing = helper.getEntities(species.type()).size();
			check(standing == 1, "`/summon` must still work on a locked " + species.name()
					+ ", and the region holds " + standing + " of them instead of one");
		}
		helper.killAllEntities();
	}

	/**
	 * The control. Every species locked, and every mob the feature does not own must answer exactly
	 * what it answered with all six bought — same spawn rules, same mob built.
	 *
	 * <p>Comparing the two states rather than demanding "yes" is deliberate: some of these cannot
	 * spawn on a lit patch of grass in the first place, and a test that quietly asserted nothing
	 * for them would be worse than no test at all.
	 */
	private void bystandersAreUntouched(GameTestHelper helper) {
		unlockEverySpecies(helper);
		List<Boolean> vanilla = new ArrayList<>();
		for (EntityType<?> bystander : BYSTANDERS) {
			vanilla.add(spawnRulesSayYes(helper, bystander, EntitySpawnReason.CHUNK_GENERATION));
		}

		lockEverySpecies(helper);
		for (int i = 0; i < BYSTANDERS.size(); i++) {
			EntityType<?> bystander = BYSTANDERS.get(i);
			boolean now = spawnRulesSayYes(helper, bystander, EntitySpawnReason.CHUNK_GENERATION);
			check(now == vanilla.get(i), "locking all six species must not change whether "
					+ typeId(bystander) + " passes the chunk-generation spawn rules, and it went from "
					+ vanilla.get(i) + " to " + now);

			for (EntitySpawnReason reason : THE_WORLDS_OWN_DOING) {
				Entity made = created(helper, bystander, reason);
				check(made != null, "locking all six species must leave " + typeId(bystander)
						+ " buildable for " + reason + ", and the game returned nothing");
				discard(made);
			}
		}
	}

	/**
	 * The path that goes around natural spawning entirely: a naturally spawned baby zombie builds
	 * its own chicken inside {@code finalizeSpawn}, so the spawn rules never see it.
	 *
	 * <p>Real baby zombies, finalized the way the spawner finalizes them, {@value #JOCKEY_ATTEMPTS}
	 * times. With the chicken locked not one of them may arrive mounted.
	 */
	private void lockedChickenGetsNoJockey(GameTestHelper helper) {
		lockEverySpecies(helper);

		check(created(helper, EntityTypes.CHICKEN, EntitySpawnReason.JOCKEY) == null,
				"a locked chicken must not be buildable as a jockey mount");

		int mounted = finalizeBabyZombies(helper);
		check(mounted == 0, "a locked chicken must never arrive under a baby zombie, and "
				+ mounted + " of " + JOCKEY_ATTEMPTS + " zombies came in riding one");
	}

	/** And with the chicken bought, the jockeys come back — so the check above is not vacuous. */
	private void unlockedChickenGetsItsJockeyBack(GameTestHelper helper) {
		lockEverySpecies(helper);
		command(helper, "mhr unlock world.animal.chicken");

		check(created(helper, EntityTypes.CHICKEN, EntitySpawnReason.JOCKEY) != null,
				"an unlocked chicken must be buildable as a jockey mount again");

		int mounted = finalizeBabyZombies(helper);
		check(mounted > 0, "an unlocked chicken must ride out from under baby zombies again, and"
				+ " none of " + JOCKEY_ATTEMPTS + " zombies brought one");
	}

	/**
	 * The other jockeys are somebody else's mobs. A locked chicken must not stop a spider from
	 * carrying a skeleton, or a strider from carrying a zombified piglin.
	 */
	private void otherJockeysAreUntouched(GameTestHelper helper) {
		lockEverySpecies(helper);

		for (EntityType<?> mount : List.of(EntityTypes.SPIDER, EntityTypes.STRIDER,
				EntityTypes.SKELETON, EntityTypes.ZOMBIFIED_PIGLIN)) {
			Entity made = created(helper, mount, EntitySpawnReason.JOCKEY);
			check(made != null, "a locked chicken must leave " + typeId(mount)
					+ " buildable as part of a jockey, and the game returned nothing");
			discard(made);
		}
	}

	// --- asking the game -------------------------------------------------------------------

	/** Vanilla's own spawn-rule check, at the middle of the floor, on a fixed seed. */
	private static boolean spawnRulesSayYes(GameTestHelper helper, EntityType<?> type,
			EntitySpawnReason reason) {
		ServerLevel level = helper.getLevel();
		return SpawnPlacements.checkSpawnRules(type, level, reason,
				helper.absolutePos(STANDING_ON), RandomSource.create(SPAWN_RULE_SEED));
	}

	/**
	 * Builds a mob the way everything in the game builds one, and throws it away again.
	 *
	 * <p>{@code EntityType.create(Level, EntitySpawnReason)} is the call {@code Zombie} makes for
	 * its jockey chicken, so this is the real path and not a stand-in for it.
	 */
	private static Entity created(GameTestHelper helper, EntityType<?> type,
			EntitySpawnReason reason) {
		Entity made = type.create(helper.getLevel(), reason);
		discard(made);
		return made;
	}

	private static void discard(Entity entity) {
		if (entity != null) {
			entity.discard();
		}
	}

	/**
	 * Spawns {@value #JOCKEY_ATTEMPTS} baby zombies and finalizes each one the way the natural
	 * spawner does, counting how many ended up on a chicken.
	 *
	 * <p>The zombies are forced to be babies through {@code ZombieGroupData} — that is the same
	 * object the spawner hands to a group's first zombie — so the only roll left is vanilla's own
	 * 5% jockey chance. Everything is cleared out afterwards.
	 */
	private static int finalizeBabyZombies(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos where = helper.absolutePos(STANDING_ON);
		int mounted = 0;

		for (int attempt = 0; attempt < JOCKEY_ATTEMPTS; attempt++) {
			Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.NATURAL);
			if (zombie == null) {
				throw new AssertionError("The game refused to build a plain zombie, which this test"
						+ " has no business affecting");
			}
			zombie.snapTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0.0f, 0.0f);
			level.addFreshEntity(zombie);
			zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(where),
					EntitySpawnReason.NATURAL, new Zombie.ZombieGroupData(true, true));

			if (zombie.getVehicle() != null
					&& zombie.getVehicle().getType() == EntityTypes.CHICKEN) {
				mounted++;
			}
			helper.killAllEntities();
		}
		helper.killAllEntities();
		return mounted;
	}

	// --- the box ---------------------------------------------------------------------------

	/**
	 * A floor of grass with clear air above it: what the spawn rules for every one of the six
	 * species want underfoot.
	 */
	private static void layTheGround(GameTestHelper helper) {
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				helper.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
				for (int y = 1; y < SIZE; y++) {
					helper.setBlock(x, y, z, Blocks.AIR);
				}
			}
		}
	}

	/**
	 * Noon and clear skies, because the spawn-tick rules for a passive animal ask how bright it is
	 * and a test world left at whatever time it happened to be would answer differently run to run.
	 */
	private static void daylight(GameTestHelper helper) {
		command(helper, "time set noon");
		command(helper, "weather clear");
	}

	// --- plumbing --------------------------------------------------------------------------

	private static void lockEverySpecies(GameTestHelper helper) {
		for (Species species : SPECIES) {
			command(helper, "mhr lock " + species.unlock());
		}
	}

	private static void unlockEverySpecies(GameTestHelper helper) {
		for (Species species : SPECIES) {
			command(helper, "mhr unlock " + species.unlock());
		}
	}

	private static String typeId(EntityType<?> type) {
		return EntityType.getKey(type).toString();
	}

	/** The dev command, through the real command dispatcher, as the console would run it. */
	private static void command(GameTestHelper helper, String command) {
		MinecraftServer server = helper.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	/**
	 * Runs one scenario. A failure is recorded rather than ending the run, so one command shows
	 * every criterion that is red instead of only the first.
	 */
	private static void scenario(List<String> failures, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}

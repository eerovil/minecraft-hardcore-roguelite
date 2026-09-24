package fi.vilpponen.mhr.mixin;

import java.util.Map;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The private things a server keeps that building a world out of band needs.
 *
 * <p>Minecraft creates every level once, in {@code MinecraftServer.createLevels}, and never offers
 * to do it again. This mod's runs are worlds that come and go inside one running server, so
 * {@code fi.vilpponen.mhr.run.RunWorlds} has to do what that method does — which means reaching the
 * level map it writes into, the executor and save directory a level is built from, and the
 * world-generation settings that hold the seed.
 *
 * <p>The seed is the reason for {@link Mutable}. {@code ServerLevel.getSeed()} reads it back off
 * the server rather than out of the level, so a new run generating different terrain means
 * replacing that object before the new levels are built. Nothing else here is written to.
 *
 * <p>This is an accessor and an invoker and nothing else: no behaviour is injected, and the decisions all live in
 * the run package. See {@code docs/codebase/minecraft-hooks.md}.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
	@Accessor("levels")
	Map<ResourceKey<Level>, ServerLevel> mhr$levels();

	@Accessor("executor")
	Executor mhr$executor();

	@Accessor("storageSource")
	LevelStorageSource.LevelStorageAccess mhr$storageSource();

	@Accessor("worldGenSettings")
	@Mutable
	void mhr$setWorldGenSettings(WorldGenSettings settings);

	/**
	 * Work out the spawn vanilla hands out again, now rather than on the next tick. A run moves its
	 * border and its spawn inside one call, and whatever asks for the spawn in that same call has to
	 * get the new answer. See {@code fi.vilpponen.mhr.border.WorldBorders}.
	 */
	@Invoker("updateEffectiveRespawnData")
	void mhr$updateEffectiveRespawnData();
}

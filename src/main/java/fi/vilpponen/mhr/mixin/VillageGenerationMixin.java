package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.village.Villages;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No villages until the villages unlock is bought.
 *
 * <p>Refusing to start the structure is the earliest point where a village is still one object:
 * the chunk simply records that nothing starts here, so no piece of it is ever placed. Which
 * structures count as villages comes from the vanilla {@code minecraft:village} tag, so the five
 * vanilla variants are covered and nothing else is touched.
 *
 * <p>Villagers, trades and zombie villagers are deliberately left alone — this only removes the
 * buildings.
 */
@Mixin(Structure.class)
public class VillageGenerationMixin {
	@Inject(method = "generate", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$blockVillages(Holder<Structure> structure, ResourceKey<Level> level,
			RegistryAccess registryAccess, ChunkGenerator generator, BiomeSource biomeSource,
			Climate.Sampler sampler, RandomState randomState, StructureTemplateManager templateManager,
			long seed, ChunkPos chunkPos, int references, LevelHeightAccessor heightAccessor,
			Predicate<Holder<Biome>> validBiome, CallbackInfoReturnable<StructureStart> cir) {
		if (structure.is(StructureTags.VILLAGE) && !Villages.generationEnabled()) {
			cir.setReturnValue(StructureStart.INVALID_START);
		}
	}
}

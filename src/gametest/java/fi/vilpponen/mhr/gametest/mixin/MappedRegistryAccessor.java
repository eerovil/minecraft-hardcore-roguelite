package fi.vilpponen.mhr.gametest.mixin;

import java.util.Map;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A save that is missing one of the dimensions a run is made of.
 *
 * <p>There is no supported way to take a dimension out of a loaded world: the registry is frozen
 * once the save is open, and the thing being tested — refusing a run start before anything is
 * deleted — only happens on a save that is already open. Building a world preset without a nether
 * would mean a second harness for one scenario, and the scenario is precisely about a save that
 * was fine and is not any more.
 *
 * <p>So the test borrows the registry's own lookup map for the length of one call and puts the
 * entry straight back. {@code getValue} is a plain {@code byKey.get}, so an absent entry reads as
 * absent, which is exactly the state a removed data pack leaves behind.
 *
 * <p>Test-only, and in the gametest source set for that reason. Nothing in the shipped mod can
 * reach it.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor {
	@Accessor("byKey")
	Map<Object, Object> mhr$byKey();
}

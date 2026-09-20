package fi.vilpponen.mhr.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The two halves of hunger that vanilla keeps to itself.
 *
 * <p>Food level and saturation have setters; exhaustion and the tick timer do not, because nothing
 * in vanilla ever wants to put a player's hunger back to how it started — eating and starving are
 * the only ways it moves. A run boundary does want exactly that: the next run has to begin from the
 * same hunger a new player begins from, and a half-full exhaustion bar carried over from the last
 * run is a head start on starving that nobody chose.
 *
 * <p>An accessor and nothing else; what the fresh-run baseline actually is lives in
 * {@code RunLifecycle}. See {@code docs/codebase/minecraft-hooks.md}.
 */
@Mixin(FoodData.class)
public interface FoodDataAccessor {
	@Accessor("exhaustionLevel")
	void mhr$setExhaustionLevel(float exhaustion);

	@Accessor("tickTimer")
	void mhr$setTickTimer(int ticks);
}

package fi.vilpponen.mhr.gametest.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where the inventory screen actually drew its slots, so a test can put the mouse on one.
 *
 * <p>The tests click armor slots the way a player does — move the cursor, press the left button —
 * and that needs the screen's own idea of where the window starts. Vanilla keeps it protected.
 * Reading {@code hoveredSlot} back afterwards is what makes the click self-checking: if the
 * arithmetic ever lands on the wrong square, the test says so instead of quietly passing.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
	@Accessor("leftPos")
	int mhr$leftPos();

	@Accessor("topPos")
	int mhr$topPos();

	@Accessor("hoveredSlot")
	Slot mhr$hoveredSlot();
}

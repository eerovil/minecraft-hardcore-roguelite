package fi.vilpponen.mhr.mixin.client;

import fi.vilpponen.mhr.equipment.EquipmentLocks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a padlock over every locked equipment slot in the inventory screen.
 *
 * <p>Drawn at the end of the vanilla slot draw, so it sits on top of the empty-slot icon. The
 * padlock is four rectangles rather than a texture: it needs no resource pack entry and no font
 * character that might be missing.
 *
 * <p>Slot coordinates are already relative to the screen's top left here — the container screen
 * translates by {@code leftPos}/{@code topPos} before drawing any slot.
 */
@Mixin(AbstractContainerScreen.class)
public class LockedSlotOverlayMixin {
	private static final int SHADE = 0xC0200000;
	private static final int METAL = 0xFFD8D8D8;
	private static final int KEYHOLE = 0xFF303030;

	@Inject(method = "extractSlot", at = @At("RETURN"))
	private void hardcoreRoguelite$markLocked(GuiGraphicsExtractor extractor, Slot slot,
			int mouseX, int mouseY, CallbackInfo ci) {
		if (!(slot.container instanceof Inventory)) {
			return;
		}
		EquipmentSlot equipmentSlot = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot.getContainerSlot());
		if (equipmentSlot == null || EquipmentLocks.isUnlocked(equipmentSlot)) {
			return;
		}

		int x = slot.x;
		int y = slot.y;
		extractor.fill(x, y, x + 16, y + 16, SHADE);
		// Shackle, then body, then the keyhole punched out of it.
		extractor.fill(x + 6, y + 3, x + 10, y + 4, METAL);
		extractor.fill(x + 5, y + 4, x + 6, y + 8, METAL);
		extractor.fill(x + 10, y + 4, x + 11, y + 8, METAL);
		extractor.fill(x + 4, y + 8, x + 12, y + 13, METAL);
		extractor.fill(x + 7, y + 9, x + 9, y + 12, KEYHOLE);
	}
}

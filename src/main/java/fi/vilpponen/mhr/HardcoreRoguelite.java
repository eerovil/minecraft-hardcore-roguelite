package fi.vilpponen.mhr;

import fi.vilpponen.mhr.command.UnlockCommand;
import fi.vilpponen.mhr.equipment.EquipmentSlots;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HardcoreRoguelite implements ModInitializer {
	public static final String MOD_ID = "hardcore_roguelite";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		UnlockState state = UnlockState.get();
		LOGGER.info("Hardcore Roguelite loaded. Unlocked: {}", state.describe());

		EquipmentSlots.register();

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> UnlockCommand.register(dispatcher));
	}
}

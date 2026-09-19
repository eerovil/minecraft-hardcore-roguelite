package fi.vilpponen.mhr;

import fi.vilpponen.mhr.command.BalanceCommand;
import fi.vilpponen.mhr.command.UnlockCommand;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceManager;
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
		// Deliberately before anything else, and deliberately not caught: a broken balance override
		// should stop the game here with a readable message rather than quietly play at the wrong
		// numbers. See BalanceManager.
		Balance balance = BalanceManager.load();

		UnlockState state = UnlockState.get();
		LOGGER.info("Hardcore Roguelite loaded. Unlocked: {}. Balance: {} unlocks priced, mob damage x{}",
				state.describe(), balance.unlocks().size(), balance.mobDamageMultiplier());

		EquipmentSlots.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			UnlockCommand.register(dispatcher);
			BalanceCommand.register(dispatcher);
		});
	}
}

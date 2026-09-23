package fi.vilpponen.mhr;

import fi.vilpponen.mhr.border.BorderCommand;
import fi.vilpponen.mhr.border.WorldBorders;
import fi.vilpponen.mhr.command.BalanceCommand;
import fi.vilpponen.mhr.command.ShopCommand;
import fi.vilpponen.mhr.command.UnlockCommand;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.earn.AdvancementPayouts;
import fi.vilpponen.mhr.equipment.EquipmentSlots;
import fi.vilpponen.mhr.progression.Progress;
import fi.vilpponen.mhr.run.LobbyIsland;
import fi.vilpponen.mhr.run.RunAdmission;
import fi.vilpponen.mhr.run.RunCommand;
import fi.vilpponen.mhr.run.RunLifecycle;
import fi.vilpponen.mhr.shop.ShopBlock;
import fi.vilpponen.mhr.shop.ShopServer;
import fi.vilpponen.mhr.starter.RunStart;
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

		LOGGER.info("Hardcore Roguelite loaded. Balance: {} unlocks priced, mob damage x{}",
				balance.unlocks().size(), balance.mobDamageMultiplier());

		// Before every other server hook: progression belongs to the save, and anything that asks
		// what is owned — worldgen, the border, the run lifecycle — has to be asking this save.
		Progress.register();

		// First, and before anything can read a player's save data: a persistent attachment that is
		// not registered by the time an entity is loaded is dropped with a log line and no other
		// trace. See RunAdmission.
		RunAdmission.register();

		EquipmentSlots.register();
		RunLifecycle.register();
		WorldBorders.init();
		RunStart.register();
		AdvancementPayouts.register();
		ShopServer.register();
		LobbyIsland.register();
		ShopBlock.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			UnlockCommand.register(dispatcher);
			BalanceCommand.register(dispatcher);
			BorderCommand.register(dispatcher);
			ShopCommand.register(dispatcher);
			RunCommand.register(dispatcher);
		});
	}
}

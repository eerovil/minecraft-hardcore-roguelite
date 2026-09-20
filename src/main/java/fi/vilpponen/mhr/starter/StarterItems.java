package fi.vilpponen.mhr.starter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceException;
import fi.vilpponen.mhr.core.BalanceManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/**
 * The starter items the shop can sell, read out of the balance catalogue.
 *
 * <p>There is no catalogue of its own here on purpose. A starter item is an ordinary unlock with
 * an ordinary dotted id, sitting in {@code default-balance.json} next to every other price, and
 * carrying one extra key — the stack it puts in the chest:
 *
 * <pre>
 * "starter.bread":   { "price": 3, "item": { "id": "minecraft:bread", "count": 16 } },
 * "starter.pickaxe": { "price": 100, "item": {
 *                        "id": "minecraft:diamond_pickaxe", "count": 1,
 *                        "components": { "minecraft:enchantments": { "minecraft:efficiency": 3 } } } }
 * </pre>
 *
 * <p>That {@code item} value is a vanilla item stack in the same shape {@code /give} and loot
 * tables use, decoded with {@link ItemStack#CODEC}. It is why there is no special case per item:
 * enchantments, custom names, dyed armor and anything Mojang adds to the component system work the
 * day they are added, without a line of code here. And because it is balance data, the override
 * file can retune the contents as freely as the price, and a misspelt key is caught at startup by
 * the same check that catches a misspelt price.
 *
 * <p>Nothing is cached. The catalogue is read at the point of use, as {@code docs/balance.md}
 * requires, so {@code /mhr reload} reaches a changed item stack the same way it reaches a changed
 * price. The chest is built once a run, so the decoding cost is not worth a cache.
 *
 * <p>Decoding cannot fail by the time the chest is built, because {@link #installCheck} has
 * already put every item in the catalogue through the same codec — that is the point of it. So
 * {@link #ownedStacks} throws rather than skipping, and the throw is a bug in this file rather
 * than a mistake in someone's config.
 */
public final class StarterItems {
	/**
	 * The registries to check item stacks against: the running server's, or none.
	 *
	 * <p>Whose they are matters. Some of what an item stack can name lives in a world's own data —
	 * enchantments among them, which is most of the point of selling an enchanted pickaxe — so the
	 * answer to "is this a real item?" is a different answer per world, and the registries stop
	 * being valid when that world closes. Hence one field the lifecycle owns rather than a
	 * registry provider captured in a closure: open a second world in the same client and the new
	 * one replaces the old, instead of both being consulted and the old world's graph being held
	 * alive by a validator nobody can remove.
	 *
	 * <p>Null between worlds, and during mod init before there has ever been one. There is nothing
	 * to check against then, and nothing asks.
	 */
	private static volatile HolderLookup.Provider registries;

	private StarterItems() {
	}

	/**
	 * Make every starter item's stack part of what it means for a balance file to be valid.
	 *
	 * <p>Registered once, at mod init. It can only say anything once a server is up, which is the
	 * earliest anything can tell {@code minecraft:bread} from {@code minecraft:braed} — see
	 * {@link #useRegistriesOf}. From then on a candidate balance whose item names something the
	 * game does not have never becomes the balance in effect: at startup the game stops with the
	 * id in the message, and {@code /mhr reload} refuses and leaves the running game on the
	 * balance it had.
	 *
	 * <p>Without this the mistake surfaced a whole run later — the chest would be built, the bad
	 * item quietly left out of it, and the run already marked as having had its chest, so the
	 * purchase was simply gone.
	 */
	public static void installCheck() {
		BalanceManager.addCheck(StarterItems::checkItems);
	}

	private static void checkItems(Balance candidate) {
		HolderLookup.Provider current = registries;
		if (current == null) {
			return;
		}
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, current);
		for (Balance.UnlockBalance unlock : candidate.unlocks().values()) {
			unlock.item().ifPresent(json -> decodeOrThrow(ops, unlock.id(), json));
		}
	}

	/**
	 * Point the check at this server's registries and apply it to the balance already loaded.
	 *
	 * <p>That balance was read before any world existed, so nothing has looked at its items yet.
	 * A throw here stops the server; the log line is so the reason is one readable sentence rather
	 * than only the top of a crash report.
	 *
	 * @throws BalanceException if the balance in effect has an item this world does not have
	 */
	public static void useRegistriesOf(MinecraftServer server) {
		registries = server.registryAccess();
		try {
			BalanceManager.recheck();
		} catch (BalanceException e) {
			HardcoreRoguelite.LOGGER.error("Refusing to run on this balance. {}", e.getMessage());
			throw e;
		}
	}

	/** The world is closing, so its registries are about to stop meaning anything. Let them go. */
	public static void forgetRegistries() {
		registries = null;
	}

	/** @throws fi.vilpponen.mhr.core.BalanceException naming the unlock and what the codec said. */
	private static ItemStack decodeOrThrow(RegistryOps<JsonElement> ops, String id, JsonObject json) {
		return ItemStack.CODEC.parse(ops, withoutCount(json))
				.getOrThrow(error -> new BalanceException(
						"Balance value 'unlocks." + id + ".item' is not an item the game has: " + error))
				.copyWithCount(countOf(json));
	}

	/** Every unlock id in the catalogue that puts something in the chest, in file order. */
	public static List<String> ids() {
		List<String> ids = new ArrayList<>();
		for (Balance.UnlockBalance unlock : BalanceManager.get().unlocks().values()) {
			if (unlock.item().isPresent()) {
				ids.add(unlock.id());
			}
		}
		return ids;
	}

	/** @return true if this unlock is a starter item, i.e. it puts something in the chest. */
	public static boolean isStarterItem(String id) {
		return BalanceManager.get().unlock(id).flatMap(Balance.UnlockBalance::item).isPresent();
	}

	/**
	 * The stack one unlock puts in the chest, as the balance in effect says right now.
	 *
	 * <p>The shop asks this so that what it shows is what {@link #ownedStacks} will hand over.
	 * Describing the reward from anywhere else — an icon file, a line in the language file — is a
	 * second copy of a number the override file is allowed to change.
	 *
	 * @return empty for an unlock that is not a starter item
	 */
	public static ItemStack stackFor(String id, HolderLookup.Provider registries) {
		return BalanceManager.get().unlock(id)
				.flatMap(Balance.UnlockBalance::item)
				.map(json -> decodeOrThrow(RegistryOps.create(JsonOps.INSTANCE, registries), id, json))
				.orElse(ItemStack.EMPTY);
	}

	/**
	 * The stacks the player has actually bought, in catalogue order.
	 *
	 * @param registries the server's, needed to decode an item's data components
	 */
	public static List<ItemStack> ownedStacks(HolderLookup.Provider registries) {
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		UnlockState state = UnlockState.get();
		List<ItemStack> owned = new ArrayList<>();

		for (Balance.UnlockBalance unlock : BalanceManager.get().unlocks().values()) {
			if (!state.isOwned(unlock.id())) {
				continue;
			}
			unlock.item().ifPresent(json -> owned.add(decodeOrThrow(ops, unlock.id(), json)));
		}
		return owned;
	}

	/**
	 * The count the catalogue asked for, which is deliberately not the codec's business.
	 *
	 * <p>{@link ItemStack#CODEC} refuses a count above 99, because everywhere else in the game a
	 * stack is a stack. Here it is a shopping list: "128 bread" means two slots of bread, and
	 * {@link ChestLoad} is what splits it. So the count is taken out before the codec sees it
	 * — otherwise a perfectly good line in the catalogue is rejected — and put back afterwards.
	 *
	 * <p>Nothing is corrected here. A count that is not a whole number of at least one has already
	 * stopped the balance file loading, so by the time this runs the only question left is whether
	 * the entry named one at all.
	 */
	private static int countOf(JsonObject json) {
		JsonElement count = json.get("count");
		return count == null ? 1 : count.getAsInt();
	}

	private static JsonElement withoutCount(JsonObject json) {
		if (!json.has("count")) {
			return json;
		}
		JsonObject copy = json.deepCopy();
		copy.remove("count");
		return copy;
	}
}

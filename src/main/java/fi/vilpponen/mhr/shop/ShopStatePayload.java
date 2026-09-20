package fi.vilpponen.mhr.shop;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.progression.Offer;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The whole shop, as the server currently sees it, sent to one client.
 *
 * <p>Everything a purchase depends on travels: the catalogue, the prices out of the server's
 * balance file, the levels out of the server's unlock file, and the currency. The client is not
 * asked to work any of that out from its own config, because on a dedicated server its config is
 * not the one in force — and a screen drawing one price while the server charges another is the
 * kind of disagreement that looks like a bug in the shop.
 *
 * <p>The reward travels too, where there is one. A starter item's whole effect is a stack in the
 * balance catalogue, and that stack is data an override may replace, so the only honest place for
 * the screen to get the icon, the count and the name is from the server that will hand it over.
 *
 * <p>What does not travel is how any of it looks. Grouping, ordering, and the icon and wording of
 * everything whose effect is code rather than an item are the client's, out of
 * {@code shop-layout.json} and the language file.
 *
 * <p>This is presentation state and nothing more. The server still decides every purchase; a
 * client that lies about what it can afford is simply refused.
 *
 * @param open true when this is the packet that should put the screen in front of the player, false
 *     when it is only bringing an already-open screen up to date
 */
public record ShopStatePayload(boolean open, int currency, List<Offer> offers,
		Map<String, Reward> rewards) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ShopStatePayload> TYPE =
			new CustomPacketPayload.Type<>(
					Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "shop_state"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShopStatePayload> STREAM_CODEC =
			CustomPacketPayload.codec(ShopStatePayload::write, ShopStatePayload::read);

	public ShopStatePayload {
		offers = List.copyOf(offers);
		rewards = Map.copyOf(rewards);
	}

	/**
	 * Read the whole thing before building one.
	 *
	 * <p>Not a constructor taking the buffer: the compact constructor above copies both collections
	 * as it runs, so anything added to them afterwards lands in a copy that is already immutable —
	 * which is a decoder that throws on every packet rather than one that quietly loses entries.
	 * Reading here also puts the order of the fields somewhere it can be read, instead of leaving it
	 * to the order arguments happen to be evaluated in.
	 */
	private static ShopStatePayload read(RegistryFriendlyByteBuf buf) {
		boolean open = buf.readBoolean();
		int currency = buf.readVarInt();

		int count = buf.readVarInt();
		List<Offer> offers = new ArrayList<>(count);
		Map<String, Reward> rewards = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			Offer offer = new Offer(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
			offers.add(offer);
			Reward reward = readReward(buf);
			if (reward.isSomething()) {
				rewards.put(offer.id(), reward);
			}
		}
		return new ShopStatePayload(open, currency, offers, rewards);
	}

	/** Never the whole count in the stack: a starter item may ask for more than a stack holds. */
	private static Reward readReward(RegistryFriendlyByteBuf buf) {
		ItemStack item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
		return item.isEmpty() ? Reward.NONE : new Reward(item, buf.readVarInt());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeBoolean(open);
		buf.writeVarInt(currency);
		buf.writeVarInt(offers.size());
		for (Offer offer : offers) {
			buf.writeUtf(offer.id());
			buf.writeVarInt(offer.price());
			buf.writeVarInt(offer.level());
			buf.writeVarInt(offer.maxLevel());

			Reward reward = rewards.getOrDefault(offer.id(), Reward.NONE);
			ItemStack one = reward.isSomething() ? reward.item().copyWithCount(1) : ItemStack.EMPTY;
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, one);
			if (reward.isSomething()) {
				buf.writeVarInt(reward.count());
			}
		}
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

package fi.vilpponen.mhr.shop;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.progression.Offer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The whole shop, as the server currently sees it, sent to one client.
 *
 * <p>Everything a purchase depends on travels: the catalogue, the prices out of the server's
 * balance file, the levels out of the server's unlock file, and the currency. The client is not
 * asked to work any of that out from its own config, because on a dedicated server its config is
 * not the one in force — and a screen drawing one price while the server charges another is the
 * kind of disagreement that looks like a bug in the shop.
 *
 * <p>What does not travel is how any of it looks. Grouping, ordering, icons and wording are the
 * client's, out of {@code shop-layout.json} and the language file.
 *
 * <p>This is presentation state and nothing more. The server still decides every purchase; a
 * client that lies about what it can afford is simply refused.
 *
 * @param open true when this is the packet that should put the screen in front of the player, false
 *     when it is only bringing an already-open screen up to date
 */
public record ShopStatePayload(boolean open, int currency, List<Offer> offers) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ShopStatePayload> TYPE =
			new CustomPacketPayload.Type<>(
					Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "shop_state"));

	public static final StreamCodec<FriendlyByteBuf, ShopStatePayload> STREAM_CODEC =
			CustomPacketPayload.codec(ShopStatePayload::write, ShopStatePayload::new);

	public ShopStatePayload {
		offers = List.copyOf(offers);
	}

	private ShopStatePayload(FriendlyByteBuf buf) {
		this(buf.readBoolean(), buf.readVarInt(), readOffers(buf));
	}

	private static List<Offer> readOffers(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		List<Offer> offers = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			offers.add(new Offer(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
		}
		return offers;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeBoolean(open);
		buf.writeVarInt(currency);
		buf.writeVarInt(offers.size());
		for (Offer offer : offers) {
			buf.writeUtf(offer.id());
			buf.writeVarInt(offer.price());
			buf.writeVarInt(offer.level());
			buf.writeVarInt(offer.maxLevel());
		}
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

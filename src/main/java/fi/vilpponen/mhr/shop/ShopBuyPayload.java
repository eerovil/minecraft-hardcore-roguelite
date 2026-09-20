package fi.vilpponen.mhr.shop;

import fi.vilpponen.mhr.HardcoreRoguelite;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * "I clicked this." One unlock id, and nothing else.
 *
 * <p>Deliberately not a price, a level or a confirmation that the client thought it could afford
 * it. Every one of those is something the server already knows and is the authority on, and a
 * client that sent them would only be offering the server a chance to believe the wrong one.
 *
 * <p>The id is length-capped because it arrives from a client: an id longer than any the catalogue
 * has cannot match anything anyway, so there is no reason to read it.
 */
public record ShopBuyPayload(String id) implements CustomPacketPayload {
	/** Comfortably longer than any dotted id the catalogue has, and short enough to be harmless. */
	private static final int MAX_ID_LENGTH = 128;

	public static final CustomPacketPayload.Type<ShopBuyPayload> TYPE =
			new CustomPacketPayload.Type<>(
					Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "shop_buy"));

	public static final StreamCodec<FriendlyByteBuf, ShopBuyPayload> STREAM_CODEC =
			CustomPacketPayload.codec(ShopBuyPayload::write, ShopBuyPayload::new);

	private ShopBuyPayload(FriendlyByteBuf buf) {
		this(buf.readUtf(MAX_ID_LENGTH));
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(id, MAX_ID_LENGTH);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

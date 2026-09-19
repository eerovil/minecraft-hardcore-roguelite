package fi.vilpponen.mhr.equipment;

import fi.vilpponen.mhr.HardcoreRoguelite;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Tells a client which equipment slots are unlocked, as one bit per slot.
 *
 * <p>Only the five slots matter here, so a bit set is enough and there is nothing to keep in sync
 * beyond it. This is not persistence — the server still owns the real state.
 */
public record EquipmentUnlockPayload(int unlockedBits) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<EquipmentUnlockPayload> TYPE =
			new CustomPacketPayload.Type<>(
					Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "equipment_unlocks"));

	public static final StreamCodec<FriendlyByteBuf, EquipmentUnlockPayload> STREAM_CODEC =
			CustomPacketPayload.codec(EquipmentUnlockPayload::write, EquipmentUnlockPayload::new);

	private EquipmentUnlockPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(unlockedBits);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

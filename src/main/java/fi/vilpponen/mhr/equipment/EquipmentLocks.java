package fi.vilpponen.mhr.equipment;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Which of the five lockable equipment slots the player may use.
 *
 * <p>This is the whole public API of the feature. Anything that wants to know whether a slot is
 * available — the shop, later — asks {@link #isUnlocked(EquipmentSlot)} or goes through
 * {@link #unlockFor(EquipmentSlot)} to find the matching {@link Unlock}. Everything else in this
 * package is the enforcement and the UI marker.
 *
 * <p>The five slots are independent: each has its own {@link Unlock}, and buying one says nothing
 * about the others. Slots that are not lockable — the main hand, a horse's saddle, a mob's body
 * armor — are always unlocked and never touched.
 *
 * <p><b>Sides.</b> {@link #isUnlocked} is the server's answer and only ever reads
 * {@link UnlockState}; it is what {@link EquipmentSlotRule} enforces. A connected client cannot
 * read that file, so the server sends it the unlocked set (see {@link EquipmentUnlockPayload}) and
 * the padlocks are drawn from {@link #isUnlockedForDisplay}, which consults that copy only for an
 * entity on the logical client. Keeping the two apart matters in single player, where the client
 * and the integrated server are one process.
 */
public final class EquipmentLocks {
	/**
	 * The lockable slots, in the order their bits are sent over the network. Order is ours, not
	 * vanilla's, so it does not shift when Mojang adds an equipment slot.
	 */
	private static final EquipmentSlot[] LOCKABLE = {
			EquipmentSlot.HEAD,
			EquipmentSlot.CHEST,
			EquipmentSlot.LEGS,
			EquipmentSlot.FEET,
			EquipmentSlot.OFFHAND,
	};

	private static final List<EquipmentSlot> LOCKABLE_VIEW = List.of(LOCKABLE);

	private static final Map<EquipmentSlot, Unlock> UNLOCKS = new EnumMap<>(EquipmentSlot.class);

	static {
		UNLOCKS.put(EquipmentSlot.HEAD, Unlock.SLOT_HELMET);
		UNLOCKS.put(EquipmentSlot.CHEST, Unlock.SLOT_CHESTPLATE);
		UNLOCKS.put(EquipmentSlot.LEGS, Unlock.SLOT_LEGGINGS);
		UNLOCKS.put(EquipmentSlot.FEET, Unlock.SLOT_BOOTS);
		UNLOCKS.put(EquipmentSlot.OFFHAND, Unlock.SLOT_OFFHAND);
	}

	private EquipmentLocks() {
	}

	/** @return the five slots that can be locked, in no meaningful order. */
	public static List<EquipmentSlot> lockableSlots() {
		return LOCKABLE_VIEW;
	}

	/** @return the unlock that opens this slot, or null for a slot that is never locked. */
	public static Unlock unlockFor(EquipmentSlot slot) {
		return UNLOCKS.get(slot);
	}

	/** @return true if this unlock is one of the five equipment slots. */
	public static boolean isSlotUnlock(Unlock unlock) {
		return UNLOCKS.containsValue(unlock);
	}

	/** @return true if this slot is one of the five that can be locked at all. */
	public static boolean isLockable(EquipmentSlot slot) {
		return UNLOCKS.containsKey(slot);
	}

	/**
	 * The server's answer, and the only one enforcement ever asks for.
	 *
	 * @return true if the player may use this slot. Always true for a slot that is not lockable.
	 */
	public static boolean isUnlocked(EquipmentSlot slot) {
		Unlock unlock = UNLOCKS.get(slot);
		return unlock == null || UnlockState.get().isOwned(unlock);
	}

	/**
	 * The same question asked for drawing and for letting vanilla decline politely, which has to
	 * work on a client that has no progression snapshot. For anything on the logical client that is the set
	 * the server last sent; everywhere else it is {@link #isUnlocked}.
	 */
	public static boolean isUnlockedForDisplay(Entity viewer, EquipmentSlot slot) {
		Unlock unlock = UNLOCKS.get(slot);
		if (unlock == null) {
			return true;
		}
		if (viewer != null && viewer.level().isClientSide()) {
			Integer bits = SyncedSlotUnlocks.bits();
			if (bits != null) {
				return (bits & (1 << bitIndex(slot))) != 0;
			}
		}
		return UnlockState.get().isOwned(unlock);
	}

	/** The five unlocked slots as a bit set, for sending to a client. */
	static int packUnlockedBits() {
		int bits = 0;
		for (int i = 0; i < LOCKABLE.length; i++) {
			if (UnlockState.get().isOwned(UNLOCKS.get(LOCKABLE[i]))) {
				bits |= 1 << i;
			}
		}
		return bits;
	}

	private static int bitIndex(EquipmentSlot slot) {
		for (int i = 0; i < LOCKABLE.length; i++) {
			if (LOCKABLE[i] == slot) {
				return i;
			}
		}
		throw new IllegalArgumentException("Not a lockable slot: " + slot);
	}
}

package fi.vilpponen.mhr.equipment;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import java.util.EnumMap;
import java.util.Map;
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
 * <p><b>Sides.</b> The truth lives on the server in {@link UnlockState}. A connected client has no
 * access to that file, so the server sends it the set of unlocked slots (see
 * {@link EquipmentUnlockPayload}) and the client answers from that copy instead. In single player
 * the two are the same process and agree by construction.
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

	private static final Map<EquipmentSlot, Unlock> UNLOCKS = new EnumMap<>(EquipmentSlot.class);

	static {
		UNLOCKS.put(EquipmentSlot.HEAD, Unlock.SLOT_HELMET);
		UNLOCKS.put(EquipmentSlot.CHEST, Unlock.SLOT_CHESTPLATE);
		UNLOCKS.put(EquipmentSlot.LEGS, Unlock.SLOT_LEGGINGS);
		UNLOCKS.put(EquipmentSlot.FEET, Unlock.SLOT_BOOTS);
		UNLOCKS.put(EquipmentSlot.OFFHAND, Unlock.SLOT_OFFHAND);
	}

	/**
	 * What the server last told this client, as a bit per {@link EquipmentSlot#getId()}, or
	 * {@code null} while this process has not been told anything — which is the normal state on a
	 * dedicated server, where {@link UnlockState} is the answer.
	 */
	private static volatile Integer clientUnlockedBits;

	private EquipmentLocks() {
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

	/** @return true if the player may use this slot. Always true for a slot that is not lockable. */
	public static boolean isUnlocked(EquipmentSlot slot) {
		Unlock unlock = UNLOCKS.get(slot);
		if (unlock == null) {
			return true;
		}
		Integer bits = clientUnlockedBits;
		if (bits != null) {
			return (bits & (1 << bitIndex(slot))) != 0;
		}
		return UnlockState.get().isOwned(unlock);
	}

	public static boolean isLocked(EquipmentSlot slot) {
		return !isUnlocked(slot);
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

	/** Called on the client when the server tells it which slots are open. */
	public static void acceptFromServer(int bits) {
		clientUnlockedBits = bits;
	}

	/** Called on the client when it leaves a server, so the next one starts from its own answer. */
	public static void forgetServerAnswer() {
		clientUnlockedBits = null;
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

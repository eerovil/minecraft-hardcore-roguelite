package fi.vilpponen.mhr.border;

import net.minecraft.world.level.border.WorldBorder;

/**
 * How big the world is for one run.
 *
 * <p>A run gets one of these and keeps it. There are deliberately only a few big steps rather than
 * many small ones, so moving up a tier is something you notice immediately.
 *
 * <p>The finite diameters are balance values. They are expected to move around during playtesting,
 * which is the whole reason they live here as plain constants: changing one is a one-line edit and
 * nothing else has to know.
 */
public enum BorderTier {
	TINY("tiny", 128.0),
	MEDIUM("medium", 512.0),
	LARGE("large", 2048.0),
	/**
	 * Vanilla's own maximum, which is also the size a normal world starts with. Not literally
	 * infinite, but the border stops being a restriction you can ever reach.
	 */
	INFINITE("infinite", WorldBorder.MAX_SIZE);

	/** The tier a run has when nothing has been unlocked yet. */
	public static final BorderTier DEFAULT = TINY;

	private final String id;
	private final double diameter;

	BorderTier(String id, double diameter) {
		this.id = id;
		this.diameter = diameter;
	}

	public String id() {
		return id;
	}

	/** Border width in blocks, edge to edge. */
	public double diameter() {
		return diameter;
	}

	public boolean isInfinite() {
		return this == INFINITE;
	}

	public static BorderTier byId(String id) {
		for (BorderTier tier : values()) {
			if (tier.id.equals(id)) {
				return tier;
			}
		}
		return null;
	}
}

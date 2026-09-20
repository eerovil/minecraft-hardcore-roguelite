package fi.vilpponen.mhr.progression;

/**
 * One thing the shop can sell, as it stands right now.
 *
 * <p>A snapshot, not a handle: {@link #level} is what the player owned at the moment
 * {@link Catalogue} was asked, and {@link #price} is what the balance in effect said then. Nothing
 * here is cached anywhere — ask the catalogue again after a purchase or a {@code /mhr reload}.
 *
 * <p>The item stack a starter item puts in the chest is deliberately absent. The shop screen shows
 * it, and it is a presentation detail; what a purchase needs to decide is only the three numbers.
 *
 * @param id the stable dotted id, the one string that is also the balance key and the save key
 * @param price what the next level costs, from balance data
 * @param level how many times it has been bought, zero for not owned
 * @param maxLevel how far it can be taken, one for the ordinary on/off unlock
 */
public record Offer(String id, int price, int level, int maxLevel) {
	public boolean isOwned() {
		return level > 0;
	}

	public boolean isMaxed() {
		return level >= maxLevel;
	}

	/** Whether buying it again does anything, i.e. whether the level is worth showing at all. */
	public boolean isRepeatable() {
		return maxLevel > 1;
	}
}

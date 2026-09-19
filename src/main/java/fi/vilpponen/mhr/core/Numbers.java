package fi.vilpponen.mhr.core;

/**
 * Turning a JSON number into a number the rest of the mod can use, or saying why it cannot.
 *
 * <p>JSON has one numeric type and no range, so {@code 1e20} and {@code 1e400} are both valid JSON
 * and neither is a valid price. Narrowing them quietly would produce {@link Integer#MAX_VALUE} and
 * infinity, which is exactly the silent nonsense this layer exists to prevent — a balance mistake
 * has to be reported, not rounded off.
 */
final class Numbers {
	private Numbers() {
	}

	/** @throws BalanceException if the value is infinite or not a number at all */
	static double finite(double value, String path) {
		if (!Double.isFinite(value)) {
			throw new BalanceException("Balance value '" + path + "' is out of range: " + value);
		}
		return value;
	}

	/** @throws BalanceException if the value is not a whole number that fits in an {@code int} */
	static int toInt(double value, String path) {
		finite(value, path);
		if (value != Math.rint(value)) {
			throw new BalanceException("Balance value '" + path + "' should be a whole number, not " + value);
		}
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			throw new BalanceException("Balance value '" + path + "' is too big to be a whole number: " + value);
		}
		return (int) value;
	}
}

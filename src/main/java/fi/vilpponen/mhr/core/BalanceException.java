package fi.vilpponen.mhr.core;

/**
 * A balance file could not be read.
 *
 * <p>Always thrown with a message a person can act on: which file, which key, what was expected.
 * Bad balance data is never quietly replaced with a default, because a run played at the wrong
 * prices looks exactly like a run played at the right ones.
 */
public class BalanceException extends RuntimeException {
	public BalanceException(String message) {
		super(message);
	}

	public BalanceException(String message, Throwable cause) {
		super(message, cause);
	}
}

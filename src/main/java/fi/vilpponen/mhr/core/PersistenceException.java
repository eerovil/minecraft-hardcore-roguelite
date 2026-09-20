package fi.vilpponen.mhr.core;

/**
 * Permanent state did not reach the disk.
 *
 * <p>Unchecked on purpose. Everything that writes permanent state is deep inside gameplay code that
 * has no sensible way to carry on without it, and the one caller that does have something to say
 * about it — a purchase — catches it deliberately. The alternative, which this replaces, was
 * logging the failure and returning as though it had worked.
 */
public class PersistenceException extends RuntimeException {
	public PersistenceException(String message, Throwable cause) {
		super(message, cause);
	}
}

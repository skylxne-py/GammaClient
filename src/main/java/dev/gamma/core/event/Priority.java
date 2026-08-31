package dev.gamma.core.event;

/**
 * Named priority bands for {@link EventBus#subscribe}. Higher values run first.
 * Plain ints are accepted too — these are just the common cases.
 */
public final class Priority {

	public static final int HIGHEST = 1000;
	public static final int HIGH = 500;
	public static final int NORMAL = 0;
	public static final int LOW = -500;
	public static final int LOWEST = -1000;

	private Priority() {
	}
}

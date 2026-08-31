package dev.gamma.core;

import dev.gamma.Gamma;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The only place blocking I/O is allowed to happen — chunk DB writes, config saves, file
 * scans. Never touch this from the render or client thread's own code path; post to it instead.
 */
public final class GammaExecutor {

	private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(2, new GammaThreadFactory());

	private GammaExecutor() {
	}

	public static void execute(Runnable task) {
		EXECUTOR.execute(wrap(task));
	}

	/** For debounced saves: schedule work after {@code delay}, cancelling any prior pending run via the returned future. */
	public static ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
		return EXECUTOR.schedule(wrap(task), delay, unit);
	}

	public static void shutdown() {
		EXECUTOR.shutdown();
	}

	/**
	 * Only for the client-stopping shutdown hook: blocks until in-flight work (e.g. a final
	 * config save) drains, so the JVM doesn't exit out from under a daemon worker thread.
	 */
	public static void shutdownAndAwait(long timeout, TimeUnit unit) {
		EXECUTOR.shutdown();
		try {
			EXECUTOR.awaitTermination(timeout, unit);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static Runnable wrap(Runnable task) {
		return () -> {
			try {
				task.run();
			} catch (Exception e) {
				Gamma.LOGGER.error("Uncaught exception on a Gamma worker thread", e);
			}
		};
	}

	private static final class GammaThreadFactory implements ThreadFactory {
		private final AtomicInteger count = new AtomicInteger(1);

		@Override
		public Thread newThread(Runnable r) {
			Thread thread = new Thread(r, "Gamma-Worker-" + count.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		}
	}
}

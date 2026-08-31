package dev.gamma.chunks.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.gamma.Gamma;
import dev.gamma.chunks.model.ChunkObservation;
import dev.gamma.chunks.model.ChunkQuery;
import dev.gamma.chunks.model.ChunkRecord;
import dev.gamma.chunks.model.ChunkStats;
import dev.gamma.chunks.model.Classification;
import dev.gamma.chunks.model.ClassificationResult;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One SQLite file under {@code .minecraft/gamma/chunks/}, one per server (per the project's
 * "No blocking I/O on the render or client thread" and the roadmap's storage spec). Every
 * operation is enqueued and drained through {@link GammaExecutor} — never called directly from
 * the client thread — but the queue+flag pair in {@link #drain}/{@link #drainLoop} guarantees
 * only one {@link GammaExecutor} worker ever touches the single JDBC {@link Connection} at a
 * time, and batches whatever piled up into one transaction, satisfying "all writes batched and
 * off-thread" without needing a dedicated single-thread executor of our own.
 */
public final class ChunkDatabase {

	private final Path databaseFile;
	private final Gson gson = new Gson();
	private final ConcurrentLinkedQueue<QueueEntry> queue = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean draining = new AtomicBoolean(false);
	private volatile Connection connection;

	public ChunkDatabase(String server) {
		this.databaseFile = GammaPaths.dir("chunks").resolve(GammaPaths.sanitizeFileName(server) + ".db");
	}

	public void upsert(ChunkObservation observation, ClassificationResult result) {
		enqueue(connection -> doUpsert(connection, observation, result));
	}

	public void query(ChunkQuery query, Consumer<List<ChunkRecord>> callback) {
		enqueue(connection -> callback.accept(doQuery(connection, query)));
	}

	public void stats(Consumer<ChunkStats> callback) {
		enqueue(connection -> callback.accept(doStats(connection)));
	}

	/**
	 * {@code VACUUM} + {@code ANALYZE} (Phase 7 hardening — ".chunks compact" in
	 * {@code GammaCommands}, {@code attended()}-gated since it's long-running on a big DB).
	 * SQLite refuses {@code VACUUM} inside an explicit transaction, so unlike every other
	 * operation here it can't be folded into {@link #drainLoop}'s batched commit — it's queued
	 * as an exclusive entry instead, which forces the batch to flush and commit first and runs
	 * this alone in autocommit mode, still through the same single-connection queue so it never
	 * races a concurrent write.
	 */
	public void compact(Runnable onDone) {
		enqueueExclusive(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("VACUUM");
				statement.execute("ANALYZE");
			} finally {
				if (onDone != null) {
					onDone.run();
				}
			}
		});
	}

	/**
	 * Exclusive, like {@link #compact}: closing must flush and commit whatever's already queued
	 * first, then happen alone — not as one more statement inside a batch that immediately tries
	 * to commit against a connection it just closed.
	 */
	public void close() {
		enqueueExclusive(connection -> connection.close());
	}

	private void enqueue(SqlTask task) {
		queue.add(new QueueEntry(task, false));
		drain();
	}

	private void enqueueExclusive(SqlTask task) {
		queue.add(new QueueEntry(task, true));
		drain();
	}

	private void drain() {
		if (draining.compareAndSet(false, true)) {
			GammaExecutor.execute(this::drainLoop);
		}
	}

	private void drainLoop() {
		try {
			Connection connection = openConnection();
			if (connection == null) {
				queue.clear();
				return;
			}
			List<QueueEntry> drained = new ArrayList<>();
			QueueEntry entry;
			while ((entry = queue.poll()) != null) {
				drained.add(entry);
			}
			if (drained.isEmpty()) {
				return;
			}
			List<SqlTask> batch = new ArrayList<>();
			for (QueueEntry e : drained) {
				if (e.exclusive()) {
					runBatch(connection, batch);
					batch.clear();
					runExclusive(connection, e.task());
				} else {
					batch.add(e.task());
				}
			}
			runBatch(connection, batch);
		} finally {
			draining.set(false);
			if (!queue.isEmpty()) {
				drain();
			}
		}
	}

	private void runBatch(Connection connection, List<SqlTask> batch) {
		if (batch.isEmpty()) {
			return;
		}
		try {
			connection.setAutoCommit(false);
			for (SqlTask t : batch) {
				t.run(connection);
			}
			connection.commit();
		} catch (SQLException e) {
			Gamma.LOGGER.error("Chunk DB batch failed against {}, rolling back", databaseFile, e);
			rollbackQuietly(connection);
		} finally {
			restoreAutoCommitQuietly(connection);
		}
	}

	private void runExclusive(Connection connection, SqlTask task) {
		try {
			task.run(connection);
		} catch (SQLException e) {
			Gamma.LOGGER.error("Chunk DB exclusive task failed against {}", databaseFile, e);
		}
	}

	private Connection openConnection() {
		if (connection != null) {
			return connection;
		}
		try {
			Connection opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
			// WAL + synchronous=NORMAL: standard SQLite hardening for a DB expected to grow into
			// the hundreds of thousands of rows (Phase 7 — "verify query performance at 500k+
			// logged chunks"). WAL lets readers (e.g. `.chunks query` while a batch is mid-write)
			// proceed without blocking on writers; NORMAL trades the fully-durable fsync-per-commit
			// guarantee for far fewer fsyncs, which is fine here since a lost chunk observation on
			// an unclean shutdown just gets relogged the next time that chunk loads.
			try (Statement pragma = opened.createStatement()) {
				pragma.execute("PRAGMA journal_mode=WAL");
				pragma.execute("PRAGMA synchronous=NORMAL");
			}
			ChunkSchema.migrate(opened);
			connection = opened;
			return opened;
		} catch (SQLException e) {
			Gamma.LOGGER.error("Failed to open chunk DB {}", databaseFile, e);
			return null;
		}
	}

	private void doUpsert(Connection connection, ChunkObservation observation, ClassificationResult result) throws SQLException {
		long previousHash = 0;
		int previousRevisits = 0;
		boolean existed = false;
		try (PreparedStatement select = connection.prepareStatement(
				"SELECT content_hash, revisit_count FROM chunks WHERE dimension = ? AND x = ? AND z = ?")) {
			select.setString(1, observation.dimension());
			select.setInt(2, observation.chunkX());
			select.setInt(3, observation.chunkZ());
			try (ResultSet resultSet = select.executeQuery()) {
				if (resultSet.next()) {
					existed = true;
					previousHash = resultSet.getLong("content_hash");
					previousRevisits = resultSet.getInt("revisit_count");
				}
			}
		}
		if (existed && previousHash != observation.contentHash()) {
			Gamma.LOGGER.debug("Chunk ({}, {}, {}) content changed since last visit", observation.dimension(), observation.chunkX(), observation.chunkZ());
		}
		int revisitCount = existed ? previousRevisits + 1 : 1;

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO chunks (dimension, x, z, first_seen, last_seen, revisit_count, classification, confidence, content_hash, storage_count, block_entity_counts, notable_block_counts)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(dimension, x, z) DO UPDATE SET
					last_seen = excluded.last_seen,
					revisit_count = excluded.revisit_count,
					classification = excluded.classification,
					confidence = excluded.confidence,
					content_hash = excluded.content_hash,
					storage_count = excluded.storage_count,
					block_entity_counts = excluded.block_entity_counts,
					notable_block_counts = excluded.notable_block_counts
				""")) {
			statement.setString(1, observation.dimension());
			statement.setInt(2, observation.chunkX());
			statement.setInt(3, observation.chunkZ());
			statement.setLong(4, observation.loadTimeMillis());
			statement.setLong(5, observation.loadTimeMillis());
			statement.setInt(6, revisitCount);
			statement.setString(7, result.classification().name());
			statement.setDouble(8, result.confidence());
			statement.setLong(9, observation.contentHash());
			statement.setInt(10, observation.storageCount());
			statement.setString(11, gson.toJson(observation.blockEntityCounts()));
			statement.setString(12, gson.toJson(observation.notableBlockCounts()));
			statement.executeUpdate();
		}
	}

	private List<ChunkRecord> doQuery(Connection connection, ChunkQuery query) throws SQLException {
		StringBuilder sql = new StringBuilder("SELECT * FROM chunks WHERE 1 = 1");
		List<Object> params = new ArrayList<>();
		if (query.dimension() != null) {
			sql.append(" AND dimension = ?");
			params.add(query.dimension());
		}
		if (query.minX() != null) {
			sql.append(" AND x >= ?");
			params.add(query.minX());
		}
		if (query.maxX() != null) {
			sql.append(" AND x <= ?");
			params.add(query.maxX());
		}
		if (query.minZ() != null) {
			sql.append(" AND z >= ?");
			params.add(query.minZ());
		}
		if (query.maxZ() != null) {
			sql.append(" AND z <= ?");
			params.add(query.maxZ());
		}
		if (query.minStorageCount() != null) {
			sql.append(" AND storage_count >= ?");
			params.add(query.minStorageCount());
		}
		if (query.classification() != null) {
			sql.append(" AND classification = ?");
			params.add(query.classification().name());
		}
		if (query.maxAgeMillis() != null) {
			sql.append(" AND last_seen >= ?");
			params.add(System.currentTimeMillis() - query.maxAgeMillis());
		}
		sql.append(" ORDER BY storage_count DESC LIMIT ?");
		params.add(query.limit());

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
			for (int i = 0; i < params.size(); i++) {
				statement.setObject(i + 1, params.get(i));
			}
			try (ResultSet resultSet = statement.executeQuery()) {
				List<ChunkRecord> results = new ArrayList<>();
				while (resultSet.next()) {
					results.add(readRecord(resultSet));
				}
				return results;
			}
		}
	}

	private ChunkStats doStats(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						SELECT
							COUNT(*) AS total,
							SUM(CASE WHEN classification = 'LIKELY_NEW' THEN 1 ELSE 0 END) AS likely_new,
							SUM(CASE WHEN classification = 'LIKELY_EXISTING' THEN 1 ELSE 0 END) AS likely_existing,
							SUM(CASE WHEN classification = 'UNKNOWN' THEN 1 ELSE 0 END) AS unknown_count,
							COALESCE(SUM(storage_count), 0) AS storage_total,
							COALESCE(AVG(confidence), 0) AS avg_confidence
						FROM chunks
						""")) {
			if (!resultSet.next()) {
				return ChunkStats.EMPTY;
			}
			return new ChunkStats(
					resultSet.getInt("total"),
					resultSet.getInt("likely_new"),
					resultSet.getInt("likely_existing"),
					resultSet.getInt("unknown_count"),
					resultSet.getLong("storage_total"),
					resultSet.getDouble("avg_confidence"));
		}
	}

	private ChunkRecord readRecord(ResultSet resultSet) throws SQLException {
		return new ChunkRecord(
				resultSet.getString("dimension"),
				resultSet.getInt("x"),
				resultSet.getInt("z"),
				resultSet.getLong("first_seen"),
				resultSet.getLong("last_seen"),
				resultSet.getInt("revisit_count"),
				Classification.valueOf(resultSet.getString("classification")),
				resultSet.getDouble("confidence"),
				resultSet.getLong("content_hash"),
				resultSet.getInt("storage_count"),
				parseCounts(resultSet.getString("block_entity_counts")),
				parseCounts(resultSet.getString("notable_block_counts")));
	}

	private Map<String, Integer> parseCounts(String json) {
		Type type = new TypeToken<Map<String, Integer>>() {
		}.getType();
		Map<String, Integer> map = gson.fromJson(json, type);
		return map == null ? Map.of() : map;
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException e) {
			Gamma.LOGGER.error("Chunk DB rollback also failed", e);
		}
	}

	private static void restoreAutoCommitQuietly(Connection connection) {
		try {
			connection.setAutoCommit(true);
		} catch (SQLException e) {
			Gamma.LOGGER.error("Failed to restore chunk DB auto-commit", e);
		}
	}

	@FunctionalInterface
	private interface SqlTask {
		void run(Connection connection) throws SQLException;
	}

	/** {@code exclusive} tasks (currently just {@link #compact}) flush and commit the batch before running alone in autocommit mode. */
	private record QueueEntry(SqlTask task, boolean exclusive) {
	}
}

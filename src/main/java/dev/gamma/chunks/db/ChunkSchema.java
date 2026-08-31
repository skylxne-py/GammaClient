package dev.gamma.chunks.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Versioned schema, migrated via SQLite's own {@code PRAGMA user_version} as the cursor — no
 * separate migrations table needed for a single-table schema this size. Add future migrations
 * as another {@code if (version < N)} step in {@link #migrate}, never by editing {@link #applyV1}.
 */
final class ChunkSchema {

	private ChunkSchema() {
	}

	static void migrate(Connection connection) throws SQLException {
		int version = userVersion(connection);
		if (version < 1) {
			applyV1(connection);
			version = 1;
		}
		if (version < 2) {
			applyV2(connection);
			version = 2;
		}
		setUserVersion(connection, version);
	}

	private static int userVersion(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
			return resultSet.next() ? resultSet.getInt(1) : 0;
		}
	}

	private static void setUserVersion(Connection connection, int version) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			// PRAGMA doesn't accept bound parameters; `version` is always our own int constant, never external input.
			statement.execute("PRAGMA user_version = " + version);
		}
	}

	private static void applyV1(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE IF NOT EXISTS chunks (
						dimension TEXT NOT NULL,
						x INTEGER NOT NULL,
						z INTEGER NOT NULL,
						first_seen INTEGER NOT NULL,
						last_seen INTEGER NOT NULL,
						revisit_count INTEGER NOT NULL DEFAULT 1,
						classification TEXT NOT NULL,
						confidence REAL NOT NULL,
						content_hash INTEGER NOT NULL,
						storage_count INTEGER NOT NULL DEFAULT 0,
						block_entity_counts TEXT NOT NULL DEFAULT '{}',
						notable_block_counts TEXT NOT NULL DEFAULT '{}',
						PRIMARY KEY (dimension, x, z)
					)
					""");
			// The (dimension, x, z) primary key is already indexed implicitly; storage_count and
			// classification are indexed separately since `.chunks query`/stash-style filtering
			// hits those directly rather than the key columns.
			statement.execute("CREATE INDEX IF NOT EXISTS idx_chunks_storage_count ON chunks(storage_count)");
			statement.execute("CREATE INDEX IF NOT EXISTS idx_chunks_classification ON chunks(classification)");
		}
	}

	/**
	 * Hardening: added after auditing {@code ChunkQuery} usage at scale
	 * (the design notes): {@code .chunks query}/{@code stashes} always filter by dimension
	 * together with either {@code storage_count} or {@code last_seen}, which the v1 single-column
	 * indices can't serve as one index scan. {@code last_seen} itself (age filtering) had no
	 * index at all.
	 */
	private static void applyV2(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("CREATE INDEX IF NOT EXISTS idx_chunks_last_seen ON chunks(last_seen)");
			statement.execute("CREATE INDEX IF NOT EXISTS idx_chunks_dimension_storage_count ON chunks(dimension, storage_count DESC)");
		}
	}
}

package dev.gamma.gui.map;

import dev.gamma.chunks.ChunkObservationCollector;
import dev.gamma.chunks.db.ChunkDatabase;
import dev.gamma.chunks.model.ChunkQuery;
import dev.gamma.chunks.model.ChunkRecord;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-viewport cache of {@link ChunkRecord}s backing the map overlay/minimap. The goal is to render from a
 * cached tile, rebuilt incrementally, and never re-query the DB per frame. There is
 * no real GPU texture here (see the design notes) — each visible chunk cell is
 * still one {@code Renderer2D.fill()} per frame, same as every other Renderer2D-drawn shape in
 * this codebase. What this class actually caches is the *data* driving those fills: a bounding-box
 * query only re-runs when the visible region moves outside the last-queried area (padded, so
 * panning a little doesn't immediately re-trigger one), which is the real per-frame cost
 * being guarded against — hitting SQLite every frame, not the fill() calls themselves.
 */
public final class MapTileCache {

	private static final int PADDING_CHUNKS = 24;
	private static final int MAX_ROWS = 20_000;

	private final Map<Long, ChunkRecord> chunks = new ConcurrentHashMap<>();
	private volatile String cachedDimension;
	private volatile int cachedMinX;
	private volatile int cachedMaxX;
	private volatile int cachedMinZ;
	private volatile int cachedMaxZ;
	private volatile boolean refreshing;

	public ChunkRecord get(String dimension, int chunkX, int chunkZ) {
		if (!dimension.equals(cachedDimension)) {
			return null;
		}
		return chunks.get(ChunkPos.pack(chunkX, chunkZ));
	}

	/** Kicks off an async re-query if {@code [minX,maxX] x [minZ,maxZ]} (in chunk coordinates) isn't already covered by the last query. Safe to call every frame — it no-ops until the viewport actually moves out of bounds. */
	public void ensureCovers(String dimension, int minX, int maxX, int minZ, int maxZ) {
		boolean dimensionChanged = !dimension.equals(cachedDimension);
		boolean outOfBounds = dimensionChanged || minX < cachedMinX || maxX > cachedMaxX || minZ < cachedMinZ || maxZ > cachedMaxZ;
		if (!outOfBounds || refreshing) {
			return;
		}
		ChunkObservationCollector collector = ChunkObservationCollector.instance;
		ChunkDatabase database = collector == null ? null : collector.database();
		if (database == null) {
			return;
		}
		refreshing = true;
		int queryMinX = minX - PADDING_CHUNKS;
		int queryMaxX = maxX + PADDING_CHUNKS;
		int queryMinZ = minZ - PADDING_CHUNKS;
		int queryMaxZ = maxZ + PADDING_CHUNKS;
		ChunkQuery query = new ChunkQuery(dimension, queryMinX, queryMaxX, queryMinZ, queryMaxZ, null, null, null, MAX_ROWS);
		database.query(query, results -> {
			Map<Long, ChunkRecord> fresh = new ConcurrentHashMap<>();
			for (ChunkRecord record : results) {
				fresh.put(ChunkPos.pack(record.x(), record.z()), record);
			}
			chunks.clear();
			chunks.putAll(fresh);
			cachedDimension = dimension;
			cachedMinX = queryMinX;
			cachedMaxX = queryMaxX;
			cachedMinZ = queryMinZ;
			cachedMaxZ = queryMaxZ;
			refreshing = false;
		});
	}
}

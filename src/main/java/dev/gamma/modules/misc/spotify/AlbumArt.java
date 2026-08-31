package dev.gamma.modules.misc.spotify;

import com.mojang.blaze3d.platform.NativeImage;
import dev.gamma.Gamma;
import dev.gamma.core.GammaExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * Downloads album covers and hands the overlay a texture it can blit.
 *
 * <h2>Why the decode is by hand</h2>
 *
 * <p>{@code NativeImage.read} looks like the obvious way in and cannot be used here: it calls
 * {@code PngInfo.validateHeader} first and throws {@code IOException("PNG header missing")} on
 * anything else. Spotify's covers come off {@code i.scdn.co} as JPEG, so that path fails for every
 * single one — which is exactly what "images don't display" was. The image is therefore decoded
 * with {@code ImageIO} (which does know JPEG) and copied into a {@link NativeImage} pixel by pixel.
 * {@code NativeImage.setPixel} takes ARGB and converts internally, which is the same layout
 * {@code BufferedImage.getRGB} hands back, so no channel swizzling is involved.
 *
 * <h2>The two-thread hand-off</h2>
 *
 * <p>Fetching and decoding are pure CPU and I/O, so they happen on {@link GammaExecutor}. Creating
 * the {@link DynamicTexture} is not — that allocates a GPU texture and uploads to it, which has to
 * happen on the client thread. So the worker produces a {@link NativeImage} and hops to the client
 * thread only for the upload, which is the smallest piece that genuinely has to be there.
 *
 * <p>{@link #textureFor} never blocks and never triggers work: it answers with what is already
 * resident or {@code null}. Kicking a download off is {@link #request}'s job, called from the
 * module's tick when the track changes — a render path must not start network requests, and one
 * called per frame would start dozens before the first finished.
 *
 * <h2>Eviction</h2>
 *
 * <p>Textures are GPU memory and are not garbage collected by the JVM, so they are released
 * explicitly, oldest first, past {@link #MAX_CACHED}. That cap is generous for the actual access
 * pattern — you look at one cover at a time and go back to the previous one at most — while still
 * bounding a session that leaves the game running through a long playlist.
 */
public final class AlbumArt {

	private static final int MAX_CACHED = 8;
	private static final Duration TIMEOUT = Duration.ofSeconds(20);
	/** Refuse anything implausible for a cover, rather than decoding whatever a redirect landed on. */
	private static final int MAX_BYTES = 4 * 1024 * 1024;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private static final Map<String, Loaded> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
	private static final Deque<String> INSERTION_ORDER = new ArrayDeque<>();
	private static final AtomicInteger COUNTER = new AtomicInteger();

	private AlbumArt() {
	}

	/** A cover already resident on the GPU, or {@code null}. Safe and cheap from the render thread. */
	public static Loaded textureFor(String url) {
		return url == null ? null : CACHE.get(url);
	}

	/** Starts a download if this cover isn't cached and isn't already being fetched. */
	public static void request(String url) {
		if (url == null || url.isEmpty() || CACHE.containsKey(url) || !IN_FLIGHT.add(url)) {
			return;
		}
		GammaExecutor.execute(() -> {
			NativeImage image = null;
			try {
				byte[] bytes = download(url);
				if (bytes == null) {
					return;
				}
				image = decode(bytes, url);
				if (image == null) {
					return;
				}
				NativeImage decoded = image;
				Minecraft.getInstance().execute(() -> upload(url, decoded));
				image = null;
			} catch (IOException e) {
				Gamma.LOGGER.warn("Could not load album art from {}", url, e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				if (image != null) {
					// Only reached when the hand-off never happened; otherwise the client thread
					// owns it, and closing here would free memory the GPU upload is about to read.
					image.close();
				}
				// Cleared here rather than after the upload: this is what guards against two
				// downloads of the same cover, and the upload's own cache check covers the rest.
				IN_FLIGHT.remove(url);
			}
		});
	}

	/** JPEG (and anything else ImageIO knows) into a {@link NativeImage}. See the class doc. */
	private static NativeImage decode(byte[] bytes, String url) throws IOException {
		BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
		if (source == null) {
			Gamma.LOGGER.warn("Album art from {} is in a format this build can't decode", url);
			return null;
		}
		int width = source.getWidth();
		int height = source.getHeight();
		NativeImage image = new NativeImage(width, height, false);
		int[] row = new int[width];
		for (int y = 0; y < height; y++) {
			source.getRGB(0, y, width, 1, row, 0, width);
			for (int x = 0; x < width; x++) {
				image.setPixel(x, y, row[x]);
			}
		}
		return image;
	}

	private static byte[] download(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
		HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			Gamma.LOGGER.warn("Album art request returned HTTP {} for {}", response.statusCode(), url);
			return null;
		}
		byte[] body = response.body();
		if (body.length > MAX_BYTES) {
			Gamma.LOGGER.warn("Ignoring an implausibly large album art response ({} bytes) from {}", body.length, url);
			return null;
		}
		return body;
	}

	private static void upload(String url, NativeImage image) {
		if (CACHE.containsKey(url)) {
			image.close();
			return;
		}
		int width = image.getWidth();
		int height = image.getHeight();
		Identifier id = Identifier.fromNamespaceAndPath(Gamma.MOD_ID, "album_art/" + COUNTER.getAndIncrement());
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Gamma album art", image));
		CACHE.put(url, new Loaded(id, width, height));
		synchronized (INSERTION_ORDER) {
			INSERTION_ORDER.addLast(url);
			while (INSERTION_ORDER.size() > MAX_CACHED) {
				Loaded evicted = CACHE.remove(INSERTION_ORDER.removeFirst());
				if (evicted != null) {
					Minecraft.getInstance().getTextureManager().release(evicted.id());
				}
			}
		}
	}

	/** Drops every cached cover. Called when the overlay is switched off, so nothing sits on the GPU unused. */
	public static void clear() {
		Minecraft client = Minecraft.getInstance();
		synchronized (INSERTION_ORDER) {
			for (String url : INSERTION_ORDER) {
				Loaded loaded = CACHE.remove(url);
				if (loaded != null) {
					client.getTextureManager().release(loaded.id());
				}
			}
			INSERTION_ORDER.clear();
		}
		CACHE.clear();
	}

	/** A resident cover: where to find it, and how big it is so the blit can map the whole image. */
	public record Loaded(Identifier id, int width, int height) {
	}
}

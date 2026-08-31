package dev.gamma.modules.misc.discord;

import com.google.gson.JsonObject;
import dev.gamma.Gamma;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A from-scratch client for Discord's local IPC protocol (used by every Rich Presence
 * integration): a Unix domain socket on Linux/macOS, a named pipe on Windows, both named
 * {@code discord-ipc-<n>} for {@code n} in 0..9 (one per running Discord install/instance). No
 * existing dependency covers this, and pulling in a full Discord SDK for one optional,
 * off-by-default module felt disproportionate — the wire protocol itself is small: a 4-byte
 * opcode + 4-byte little-endian length header, then a JSON payload.
 */
public final class DiscordIpcClient implements Closeable {

	private static final int OP_HANDSHAKE = 0;
	private static final int OP_FRAME = 1;
	private static final int OP_CLOSE = 2;

	private ByteChannel channel;
	private RandomAccessFile windowsPipe;

	public boolean connect(String clientId) {
		for (int i = 0; i < 10; i++) {
			try {
				channel = openChannel(i);
				if (channel == null) {
					continue;
				}
				JsonObject handshake = new JsonObject();
				handshake.addProperty("v", 1);
				handshake.addProperty("client_id", clientId);
				write(OP_HANDSHAKE, handshake);
				readFrame(); // Discord's READY event — contents unused, just drains the response.
				return true;
			} catch (IOException e) {
				closeQuietly();
			}
		}
		return false;
	}

	public void setActivity(String details, String state, long startEpochSeconds) {
		if (channel == null) {
			return;
		}
		JsonObject activity = new JsonObject();
		activity.addProperty("details", details);
		activity.addProperty("state", state);
		JsonObject timestamps = new JsonObject();
		timestamps.addProperty("start", startEpochSeconds);
		activity.add("timestamps", timestamps);

		JsonObject args = new JsonObject();
		args.addProperty("pid", ProcessHandle.current().pid());
		args.add("activity", activity);

		JsonObject frame = new JsonObject();
		frame.addProperty("cmd", "SET_ACTIVITY");
		frame.addProperty("nonce", UUID.randomUUID().toString());
		frame.add("args", args);

		try {
			write(OP_FRAME, frame);
		} catch (IOException e) {
			Gamma.LOGGER.debug("DiscordRPC: lost connection while updating presence", e);
			closeQuietly();
		}
	}

	@Override
	public void close() {
		if (channel != null) {
			try {
				write(OP_CLOSE, new JsonObject());
			} catch (IOException ignored) {
			}
		}
		closeQuietly();
	}

	public boolean isConnected() {
		return channel != null;
	}

	private ByteChannel openChannel(int index) throws IOException {
		if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
			try {
				windowsPipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + index, "rw");
				return windowsPipe.getChannel();
			} catch (IOException e) {
				windowsPipe = null;
				return null;
			}
		}
		String base = System.getenv("XDG_RUNTIME_DIR");
		if (base == null) {
			base = System.getenv("TMPDIR");
		}
		if (base == null) {
			base = "/tmp";
		}
		Path socketPath = Path.of(base, "discord-ipc-" + index);
		if (!socketPath.toFile().exists()) {
			return null;
		}
		SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
		socket.connect(UnixDomainSocketAddress.of(socketPath));
		return socket;
	}

	private void write(int opcode, JsonObject payload) throws IOException {
		byte[] json = payload.toString().getBytes(StandardCharsets.UTF_8);
		ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		header.putInt(opcode).putInt(json.length).flip();
		channel.write(header);
		channel.write(ByteBuffer.wrap(json));
	}

	private void readFrame() throws IOException {
		ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		while (header.hasRemaining()) {
			if (channel.read(header) < 0) {
				throw new IOException("Discord IPC closed during handshake");
			}
		}
		header.flip();
		header.getInt(); // opcode — expected to be the READY event, unused.
		int length = header.getInt();
		ByteBuffer body = ByteBuffer.allocate(length);
		while (body.hasRemaining()) {
			if (channel.read(body) < 0) {
				throw new IOException("Discord IPC closed mid-frame");
			}
		}
	}

	private void closeQuietly() {
		try {
			if (channel != null) {
				channel.close();
			}
		} catch (IOException ignored) {
		}
		channel = null;
		windowsPipe = null;
	}
}

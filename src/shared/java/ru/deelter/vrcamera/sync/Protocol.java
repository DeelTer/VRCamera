package ru.deelter.vrcamera.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the mod and the server plugin say to each other about pinned photo sheets. Compiled into both, so they can't
 * disagree about it.
 * <p>
 * One plugin channel, every message starts with a byte that says what it is. The numbers of clientbound and
 * serverbound messages are counted separately.
 */
public final class Protocol {
	public static final String CHANNEL = "vrcamera:sync";
	public static final int VERSION = 1;

	// A message to the server can't be larger than 32767 bytes, picture included. And a channel that is shared
	// with every other mod should not be filled with pictures
	public static final int MAX_IMAGE_BYTES = 24_000;
	public static final int MAX_IMAGE_SIDE = 320;
	public static final int MAX_SHEETS_PER_MESSAGE = 48;

	/** to the server */
	public static final byte C_HELLO = 1;
	public static final byte C_PIN = 2;
	public static final byte C_UNPIN = 3;
	public static final byte C_IMAGE = 4;
	public static final byte C_CAMERA = 5;

	/** to the client */
	public static final byte S_HELLO = 1;
	public static final byte S_SHEETS = 2;
	public static final byte S_REMOVE = 3;
	public static final byte S_IMAGE = 4;
	public static final byte S_PIN_RESULT = 5;
	public static final byte S_FORGET = 6;
	public static final byte S_RESET = 7;
	public static final byte S_CAMERA = 8;

	/** why a sheet is gone: someone took it off, or what it was pinned to is gone and it falls */
	public static final byte REMOVED_TAKEN = 0;
	public static final byte REMOVED_FELL = 1;

	public static final byte PIN_OK = 0;
	public static final byte PIN_TOO_MANY = 1;
	public static final byte PIN_CHUNK_FULL = 2;
	public static final byte PIN_TOO_FAR = 3;
	public static final byte PIN_BAD_IMAGE = 4;
	public static final byte PIN_TOO_FAST = 5;
	public static final byte PIN_NOT_ALLOWED = 6;
	public static final byte PIN_SERVER_FULL = 7;

	/**
	 * a sheet as the server tells clients about it
	 *
	 * @param removable if the player it is sent to may take it off
	 */
	public record Sheet(
			long id, UUID owner, String ownerName, double x, double y, double z, float qx, float qy, float qz,
			float qw, float aspect, long imageHash, boolean removable) {}

	/**
	 * a sheet a player pinned
	 *
	 * @param reference picked by the client, the answer names it again
	 * @param blockX    the block it is pinned to, the sheet falls when that goes
	 */
	public record Pin(
			long reference, int blockX, int blockY, int blockZ, double x, double y, double z, float qx, float qy,
			float qz, float qw, float aspect, byte[] image) {}

	public record PinResult(long reference, byte result, long id, long imageHash) {}

	public record Limits(int version, int maxOwn, int maxPerChunk, int maxImageBytes) {}

	/**
	 * where the camera of a player is. Sent a few times per second while it is on, nothing says that it is off:
	 * a camera that is not heard of for a moment is gone
	 */
	public record Camera(UUID owner, String ownerName, double x, double y, double z, float qx, float qy, float qz,
	                     float qw) {}

	private Protocol() {
	}

	@FunctionalInterface
	private interface Body {
		void write(DataOutputStream out) throws IOException;
	}

	private static byte[] message(byte type, Body body) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(type);
			body.write(out);
		} catch (IOException e) {
			// not from writing into memory
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}

	/**
	 * @return what is left of the message after its first byte, the one that says what it is
	 */
	public static DataInputStream body(byte[] message) {
		return new DataInputStream(new ByteArrayInputStream(message, 1, message.length - 1));
	}

	public static byte[] clientHello() {
		return message(C_HELLO, out -> out.writeInt(VERSION));
	}

	public static byte[] pin(Pin pin) {
		return message(C_PIN, out -> {
			out.writeLong(pin.reference);
			out.writeInt(pin.blockX);
			out.writeInt(pin.blockY);
			out.writeInt(pin.blockZ);
			out.writeDouble(pin.x);
			out.writeDouble(pin.y);
			out.writeDouble(pin.z);
			out.writeFloat(pin.qx);
			out.writeFloat(pin.qy);
			out.writeFloat(pin.qz);
			out.writeFloat(pin.qw);
			out.writeFloat(pin.aspect);
			out.writeShort(pin.image.length);
			out.write(pin.image);
		});
	}

	public static Pin readPin(DataInputStream in) throws IOException {
		long reference = in.readLong();
		int blockX = in.readInt();
		int blockY = in.readInt();
		int blockZ = in.readInt();
		double x = in.readDouble();
		double y = in.readDouble();
		double z = in.readDouble();
		float qx = in.readFloat();
		float qy = in.readFloat();
		float qz = in.readFloat();
		float qw = in.readFloat();
		float aspect = in.readFloat();
		int length = in.readUnsignedShort();
		if (length > MAX_IMAGE_BYTES) {
			throw new IOException("picture of " + length + " bytes");
		}
		byte[] image = new byte[length];
		in.readFully(image);
		return new Pin(reference, blockX, blockY, blockZ, x, y, z, qx, qy, qz, qw, aspect, image);
	}

	public static byte[] unpin(long id) {
		return message(C_UNPIN, out -> out.writeLong(id));
	}

	public static byte[] imageRequest(long imageHash) {
		return message(C_IMAGE, out -> out.writeLong(imageHash));
	}

	public static byte[] serverHello(Limits limits) {
		return message(S_HELLO, out -> {
			out.writeInt(limits.version);
			out.writeInt(limits.maxOwn);
			out.writeInt(limits.maxPerChunk);
			out.writeInt(limits.maxImageBytes);
		});
	}

	public static Limits readLimits(DataInputStream in) throws IOException {
		return new Limits(in.readInt(), in.readInt(), in.readInt(), in.readInt());
	}

	public static byte[] sheets(List<Sheet> sheets) {
		return message(S_SHEETS, out -> {
			out.writeShort(sheets.size());
			for (Sheet sheet : sheets) {
				out.writeLong(sheet.id);
				out.writeLong(sheet.owner.getMostSignificantBits());
				out.writeLong(sheet.owner.getLeastSignificantBits());
				out.writeUTF(sheet.ownerName);
				out.writeDouble(sheet.x);
				out.writeDouble(sheet.y);
				out.writeDouble(sheet.z);
				out.writeFloat(sheet.qx);
				out.writeFloat(sheet.qy);
				out.writeFloat(sheet.qz);
				out.writeFloat(sheet.qw);
				out.writeFloat(sheet.aspect);
				out.writeLong(sheet.imageHash);
				out.writeBoolean(sheet.removable);
			}
		});
	}

	public static List<Sheet> readSheets(DataInputStream in) throws IOException {
		int count = in.readUnsignedShort();
		if (count > MAX_SHEETS_PER_MESSAGE) {
			throw new IOException(count + " sheets in one message");
		}
		List<Sheet> sheets = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			sheets.add(new Sheet(in.readLong(), new UUID(in.readLong(), in.readLong()), in.readUTF(),
					in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(),
					in.readFloat(), in.readFloat(), in.readFloat(), in.readLong(), in.readBoolean()));
		}
		return sheets;
	}

	public static byte[] remove(long id, byte reason) {
		return message(S_REMOVE, out -> {
			out.writeLong(id);
			out.writeByte(reason);
		});
	}

	public static byte[] image(long imageHash, byte[] image) {
		return message(S_IMAGE, out -> {
			out.writeLong(imageHash);
			out.writeShort(image.length);
			out.write(image);
		});
	}

	public static byte[] readImage(DataInputStream in) throws IOException {
		int length = in.readUnsignedShort();
		if (length > MAX_IMAGE_BYTES) {
			throw new IOException("picture of " + length + " bytes");
		}
		byte[] image = new byte[length];
		in.readFully(image);
		return image;
	}

	public static byte[] pinResult(PinResult result) {
		return message(S_PIN_RESULT, out -> {
			out.writeLong(result.reference);
			out.writeByte(result.result);
			out.writeLong(result.id);
			out.writeLong(result.imageHash);
		});
	}

	public static PinResult readPinResult(DataInputStream in) throws IOException {
		return new PinResult(in.readLong(), in.readByte(), in.readLong(), in.readLong());
	}

	public static byte[] forget(List<Long> ids) {
		return message(S_FORGET, out -> {
			out.writeShort(ids.size());
			for (long id : ids) {
				out.writeLong(id);
			}
		});
	}

	public static List<Long> readForget(DataInputStream in) throws IOException {
		int count = in.readUnsignedShort();
		List<Long> ids = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ids.add(in.readLong());
		}
		return ids;
	}

	/**
	 * to the server, which knows who it is from
	 */
	public static byte[] camera(double x, double y, double z, float qx, float qy, float qz, float qw) {
		return message(C_CAMERA, out -> writePose(out, x, y, z, qx, qy, qz, qw));
	}

	public static byte[] camera(Camera camera) {
		return message(S_CAMERA, out -> {
			out.writeLong(camera.owner.getMostSignificantBits());
			out.writeLong(camera.owner.getLeastSignificantBits());
			out.writeUTF(camera.ownerName);
			writePose(out, camera.x, camera.y, camera.z, camera.qx, camera.qy, camera.qz, camera.qw);
		});
	}

	private static void writePose(
			DataOutputStream out, double x, double y, double z, float qx, float qy, float qz, float qw)
			throws IOException {
		out.writeDouble(x);
		out.writeDouble(y);
		out.writeDouble(z);
		out.writeFloat(qx);
		out.writeFloat(qy);
		out.writeFloat(qz);
		out.writeFloat(qw);
	}

	/**
	 * @param owner who the server says it is from, null for one read from a client: {@code ownerName} is not
	 *              in the message then either
	 */
	public static Camera readCamera(DataInputStream in, boolean fromServer) throws IOException {
		UUID owner = fromServer ? new UUID(in.readLong(), in.readLong()) : null;
		String name = fromServer ? in.readUTF() : "";
		return new Camera(owner, name, in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(),
				in.readFloat(), in.readFloat(), in.readFloat());
	}

	public static byte[] reset() {
		return message(S_RESET, out -> {});
	}
}

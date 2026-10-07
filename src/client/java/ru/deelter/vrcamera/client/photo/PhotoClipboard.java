package ru.deelter.vrcamera.client.photo;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import ru.deelter.vrcamera.Vrcamera;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Puts a photo on the clipboard, to paste it somewhere right away. The game has no way to do that with a picture,
 * and Java has none while the game runs: this asks Windows itself. On other systems it does nothing.
 */
final class PhotoClipboard {
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	private static final int CF_DIB = 8;
	private static final int GMEM_MOVEABLE = 2;
	private static final int HEADER = 40;
	private static boolean broken;

	private interface User32 extends StdCallLibrary {
		boolean OpenClipboard(Pointer window);

		boolean EmptyClipboard();

		Pointer SetClipboardData(int format, Pointer memory);

		boolean CloseClipboard();
	}

	private interface Kernel32 extends StdCallLibrary {
		Pointer GlobalAlloc(int flags, long bytes);

		Pointer GlobalLock(Pointer memory);

		boolean GlobalUnlock(Pointer memory);

		Pointer GlobalFree(Pointer memory);
	}

	private PhotoClipboard() {
	}

	/**
	 * @param pixels the picture as ARGB, its first line at the top. Not used by anyone else afterwards
	 */
	static void copy(int[] pixels, int width, int height) {
		if (!WINDOWS || broken) {
			return;
		}
		// a full picture is a lot to copy, not on the thread that draws
		CompletableFuture.runAsync(() -> {
			try {
				write(pixels, width, height);
			} catch (RuntimeException | LinkageError e) {
				broken = true;
				Vrcamera.LOGGER.warn("VRCamera: photos can't be put on the clipboard here", e);
			}
		});
	}

	private static void write(int[] pixels, int width, int height) {
		User32 user = Native.load("user32", User32.class);
		Kernel32 kernel = Native.load("kernel32", Kernel32.class);
		Pointer memory = kernel.GlobalAlloc(GMEM_MOVEABLE, HEADER + pixels.length * 4L);
		if (memory == null) {
			return;
		}
		Pointer bitmap = kernel.GlobalLock(memory);
		bitmap.setInt(0, HEADER);
		bitmap.setInt(4, width);
		// its first line at the bottom, the way Windows keeps a bitmap
		bitmap.setInt(8, height);
		bitmap.setShort(12, (short) 1);
		bitmap.setShort(14, (short) 32);
		bitmap.setInt(16, 0);
		bitmap.setInt(20, pixels.length * 4);
		bitmap.setLong(24, 0);
		bitmap.setLong(32, 0);
		for (int line = 0; line < height; line++) {
			int from = (height - 1 - line) * width;
			// what was filmed through glass is not see-through in a picture
			for (int i = from; i < from + width; i++) {
				pixels[i] |= 0xFF000000;
			}
			bitmap.write(HEADER + (long) line * width * 4, pixels, from, width);
		}
		kernel.GlobalUnlock(memory);
		// The clipboard owns the memory once it has it. Until then it is this code's to give back
		if (!user.OpenClipboard(null)) {
			kernel.GlobalFree(memory);
			return;
		}
		try {
			user.EmptyClipboard();
			if (user.SetClipboardData(CF_DIB, memory) == null) {
				kernel.GlobalFree(memory);
			}
		} finally {
			user.CloseClipboard();
		}
	}
}

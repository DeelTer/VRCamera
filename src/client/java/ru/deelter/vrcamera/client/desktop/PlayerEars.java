package ru.deelter.vrcamera.client.desktop;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Where the player looks from, kept while the camera of the game is somewhere else for a picture of the mod.
 * <p>
 * Voice chat mods ask the camera of the game where the player hears from: some of them from a thread of their
 * own at any moment, some whenever the game begins to draw. In the middle of a picture of the mod they would get
 * the place of that picture, and voices would jump between the two. They are told what is kept here instead.
 */
public final class PlayerEars {
	private static volatile Kept kept;
	private static Thread drawing;
	private static boolean forListeners;
	private static boolean listenersEnd;

	private PlayerEars() {
	}

	/**
	 * before the camera of the game is moved for a picture: on the thread that draws
	 */
	static void keep(Vec3 position, Vector3fc forward, Vector3fc up, Vector3fc left, Quaternionf rotation, float yRot,
	                 float xRot) {
		drawing = Thread.currentThread();
		kept = new Kept(position, new Vector3f(forward), new Vector3f(up), new Vector3f(left),
				new Quaternionf(rotation), yRot, xRot);
	}

	/**
	 * the camera of the game is back with the player
	 */
	static void letGo() {
		kept = null;
		forListeners = false;
	}

	/**
	 * The game is about to begin to draw the picture, and whoever listens for that is called first. Only where
	 * the end of those calls was seen before: without it the picture itself would be drawn from the player
	 */
	static void listenersNext() {
		forListeners = listenersEnd;
	}

	/**
	 * the game is past the calls of who listens for it to begin to draw
	 */
	public static void listenersDone() {
		listenersEnd = true;
		forListeners = false;
	}

	/**
	 * @return where the player looks from, for whoever asks the camera of the game right now. Null if the camera
	 * of the game is to answer for itself
	 */
	public static Kept now() {
		final Kept ears = kept;
		return ears != null && (forListeners || Thread.currentThread() != drawing) ? ears : null;
	}

	public record Kept(Vec3 position, Vector3f forward, Vector3f up, Vector3f left, Quaternionf rotation, float yRot,
	                   float xRot) {
	}
}

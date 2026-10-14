package ru.deelter.vrcamera.client.voice;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.openal.AL10;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

/**
 * Voice chat mods play the voices of the others from where they are, heard by the player. While the camera films
 * and the player wants that, they are heard turned the way the camera looks: a voice on the left of the picture
 * comes from the left. How loud a voice is stays as the player hears it, the camera may be far from both.
 * <p>
 * Those mods set up a voice on their own thread, in their own sound context. Where the camera looks is taken on
 * the thread of the game, every tick, and only handed over.
 */
public final class VoiceEars {
	private static volatile float @Nullable [] orientation;

	private VoiceEars() {
	}

	/**
	 * on the thread of the game: where the camera looks now, if the voices are to be heard that way
	 */
	public static void refresh() {
		final DesktopCamera.Pose pose = CameraConfig.current().voiceFromCamera ? DesktopCamera.INSTANCE.pose() : null;
		if (pose == null) {
			orientation = null;
			return;
		}
		final Vector3f forward = pose.rotation().transform(new Vector3f(0, 0, -1));
		final Vector3f up = pose.rotation().transform(new Vector3f(0, 1, 0));
		orientation = new float[]{forward.x, forward.y, forward.z, up.x, up.y, up.z};
	}

	/**
	 * on the thread of a voice chat mod: turns the ears of its sound context to the camera, if they are to be
	 */
	public static void turn() {
		final float[] now = orientation;
		if (now != null) {
			AL10.alListenerfv(AL10.AL_ORIENTATION, now);
		}
	}
}

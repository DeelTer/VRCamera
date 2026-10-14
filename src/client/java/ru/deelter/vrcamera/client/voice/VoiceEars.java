package ru.deelter.vrcamera.client.voice;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.openal.AL10;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

/**
 * Voice chat mods play the voices of the others from where they are, heard by the player. While the camera films
 * and the player wants that, they are heard from the camera: a voice on the left of the picture comes from the
 * left. How loud a voice is stays as the player hears it, the camera may be far from both.
 * <p>
 * Those mods set up a voice on their own thread, in their own sound context. Where the camera and the player are is
 * taken on the thread of the game, every tick, and only handed over.
 */
public final class VoiceEars {
	private static volatile @Nullable Ears ears;

	private VoiceEars() {
	}

	/**
	 * on the thread of the game: where the camera is and looks now, if the voices are to be heard from it
	 */
	public static void refresh() {
		final DesktopCamera.Pose pose = CameraConfig.current().voiceFromCamera ? DesktopCamera.INSTANCE.pose() : null;
		final LocalPlayer player = Minecraft.getInstance().player;
		if (pose == null || player == null) {
			ears = null;
			return;
		}
		final Vector3f forward = pose.rotation().transform(new Vector3f(0, 0, -1));
		final Vector3f up = pose.rotation().transform(new Vector3f(0, 1, 0));
		ears = new Ears(pose.position(), player.getEyePosition(),
				new float[]{forward.x, forward.y, forward.z, up.x, up.y, up.z});
	}

	/**
	 * On the thread of a voice chat mod that works out how loud a voice is by itself, and only leaves where it comes
	 * from to OpenAL: the ears of its sound context go to the camera.
	 */
	public static void turn() {
		final Ears now = ears;
		if (now != null) {
			listen(now);
		}
	}

	/**
	 * On the thread of a voice chat mod that lets OpenAL work out how loud a voice is, from how far it is: the ears
	 * go to the camera, and the voice the way it is from the camera, as far away as it is from the player.
	 *
	 * @param source   the voice, as OpenAL knows it
	 * @param position where the one speaking is, null for a voice that comes from nowhere in the world
	 */
	public static void turn(int source, @Nullable Vec3 position) {
		final Ears now = ears;
		if (now == null) {
			return;
		}
		listen(now);
		if (position == null) {
			return;
		}
		final Vec3 way = position.subtract(now.camera);
		if (way.lengthSqr() < 1.0E-6) {
			return;
		}
		final Vec3 heard = now.camera.add(way.normalize().scale(position.distanceTo(now.player)));
		AL10.alSource3f(source, AL10.AL_POSITION, (float) heard.x, (float) heard.y, (float) heard.z);
	}

	private static void listen(Ears now) {
		AL10.alListener3f(AL10.AL_POSITION, (float) now.camera.x, (float) now.camera.y, (float) now.camera.z);
		AL10.alListenerfv(AL10.AL_ORIENTATION, now.orientation);
	}

	/**
	 * @param orientation where the camera looks and which way is up for it, as OpenAL wants it
	 */
	private record Ears(Vec3 camera, Vec3 player, float[] orientation) {
	}
}

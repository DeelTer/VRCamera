package ru.deelter.vrcamera.client.figura;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

import java.util.Random;

/**
 * When the player looks into the lens that films them, the way someone who is filmed does: for a moment after a cut
 * to another of their cameras, and now and then while the camera is close.
 * <p>
 * Only says where to look. The eyes are those of an avatar, which asks for this and moves them itself.
 */
public final class EyeContact {
	public static final EyeContact INSTANCE = new EyeContact();

	private static final double NEAR = 8.0;

	private static final double CUT_LOOK = 2.5;
	private static final double GLANCE_SHORTEST = 1.2;
	private static final double GLANCE_LONGEST = 2.5;
	private static final double PAUSE_SHORTEST = 5.0;
	private static final double PAUSE_LONGEST = 12.0;

	private static final double REACH = Math.cos(Math.toRadians(60.0));
	private static final double NANOS = 1.0E9;

	private final Random random = new Random();
	private int filmedBy = -1;
	private long lookUntil;
	private long nextGlance;

	/**
	 * @return where the lens is that the player looks into right now, null if they look at none
	 */
	@Nullable
	public Vec3 target() {
		final Minecraft mc = Minecraft.getInstance();
		final LocalPlayer player = mc.player;
		final DesktopCamera camera = DesktopCamera.INSTANCE;
		final DesktopCamera.Pose lens = camera.lens();
		if (player == null || lens == null || !camera.filmsSelf()) {
			filmedBy = -1;
			return null;
		}
		final long now = System.nanoTime();
		final int active = camera.activeFreeCamera();
		if (active != filmedBy) {
			if (active != -1 && filmedBy != -1) {
				lookUntil = now + (long) (CUT_LOOK * NANOS);
				nextGlance = lookUntil + pause();
			}
			filmedBy = active;
		}
		final float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		final Vec3 to = lens.position().subtract(player.getEyePosition(partialTick));
		final double away = to.length();
		if (away < 1.0E-3 || to.dot(player.getViewVector(partialTick)) / away < REACH) {
			return null;
		}
		if (now < lookUntil) {
			return lens.position();
		}
		if (away > NEAR || now < nextGlance) {
			return null;
		}
		lookUntil = now + between(GLANCE_SHORTEST, GLANCE_LONGEST);
		nextGlance = lookUntil + pause();
		return lens.position();
	}

	/**
	 * @return how far the eyes turn for {@link #target}, in degrees from where the head faces: to the right of
	 * the player and up. Null if they look at no lens
	 */
	@Nullable
	public double[] offset() {
		final Vec3 target = target();
		final LocalPlayer player = Minecraft.getInstance().player;
		if (target == null || player == null) {
			return null;
		}
		final float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
		final Vec3 to = target.subtract(player.getEyePosition(partialTick));
		final double yaw = Math.toDegrees(Math.atan2(-to.x, to.z));
		final double pitch = -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
		return new double[]{Mth.wrapDegrees(yaw - player.getViewYRot(partialTick)),
				player.getViewXRot(partialTick) - pitch};
	}

	private EyeContact() {
	}

	private long pause() {
		return between(PAUSE_SHORTEST, PAUSE_LONGEST);
	}

	private long between(double shortest, double longest) {
		return (long) ((shortest + random.nextDouble() * (longest - shortest)) * NANOS);
	}
}

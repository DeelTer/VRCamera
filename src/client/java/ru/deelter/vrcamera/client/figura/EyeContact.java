package ru.deelter.vrcamera.client.figura;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
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

	// blocks a camera may be away for a look at it now and then
	private static final double NEAR = 8.0;
	// seconds the look after a cut lasts, one now and then, and the time between two of those
	private static final double CUT_LOOK = 2.5;
	private static final double GLANCE_SHORTEST = 1.2;
	private static final double GLANCE_LONGEST = 2.5;
	private static final double PAUSE_SHORTEST = 5.0;
	private static final double PAUSE_LONGEST = 12.0;
	// eyes only turn so far: a camera further to the side than this is not looked at
	private static final double REACH = Math.cos(Math.toRadians(60.0));
	private static final double NANOS = 1.0E9;

	private final Random random = new Random();
	private int filmedBy = -1;
	private long lookUntil;
	private long nextGlance;

	private EyeContact() {
	}

	/**
	 * @return where the lens is that the player looks into right now, null if they look at none
	 */
	public Vec3 target() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		DesktopCamera camera = DesktopCamera.INSTANCE;
		DesktopCamera.Pose lens = camera.lens();
		if (player == null || lens == null || !camera.filmsSelf()) {
			this.filmedBy = -1;
			return null;
		}
		long now = System.nanoTime();
		int active = camera.activeFreeCamera();
		if (active != this.filmedBy) {
			if (active != -1 && this.filmedBy != -1) {
				this.lookUntil = now + (long) (CUT_LOOK * NANOS);
				this.nextGlance = this.lookUntil + pause();
			}
			this.filmedBy = active;
		}
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 to = lens.position().subtract(player.getEyePosition(partialTick));
		double away = to.length();
		if (away < 1.0E-3 || to.dot(player.getViewVector(partialTick)) / away < REACH) {
			return null;
		}
		if (now < this.lookUntil) {
			return lens.position();
		}
		if (away > NEAR || now < this.nextGlance) {
			return null;
		}
		this.lookUntil = now + between(GLANCE_SHORTEST, GLANCE_LONGEST);
		this.nextGlance = this.lookUntil + pause();
		return lens.position();
	}

	/**
	 * @return how far the eyes turn for {@link #target}, in degrees from where the head faces: to the right of
	 * the player and up. Null if they look at no lens
	 */
	public double[] offset() {
		Vec3 target = target();
		LocalPlayer player = Minecraft.getInstance().player;
		if (target == null || player == null) {
			return null;
		}
		float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 to = target.subtract(player.getEyePosition(partialTick));
		double yaw = Math.toDegrees(Math.atan2(-to.x, to.z));
		double pitch = -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
		return new double[]{Mth.wrapDegrees(yaw - player.getViewYRot(partialTick)),
				player.getViewXRot(partialTick) - pitch};
	}

	private long pause() {
		return between(PAUSE_SHORTEST, PAUSE_LONGEST);
	}

	private long between(double shortest, double longest) {
		return (long) ((shortest + this.random.nextDouble() * (longest - shortest)) * NANOS);
	}
}

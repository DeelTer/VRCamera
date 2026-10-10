package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.photo.PhotoSheet;

/**
 * The hand of a player at a screen, for photos: where they look. A photo they point at and press the use key on
 * is taken. It hangs in front of them with its picture to them, the wheel moves it away and back, and a key turns
 * it. Held against a block it lies flat on it, and is pinned there when it is let go of. Anywhere else it falls.
 */
public final class SheetReach implements PhotoAlbum.Hands {
	public static final SheetReach INSTANCE = new SheetReach();
	/**
	 * the one hand a player at a screen has
	 */
	public static final int HAND = 0;

	private static final double REACH = 6.0;
	private static final double AIM = 0.14;
	private static final double NEAR = 0.45;
	private static final double FAR = 5.0;
	private static final double WHEEL = 0.12;
	private static final double EASE = 12.0;
	private static final double OFF_SURFACE = 0.04;
	private static final float QUARTER_TURN = (float) (Math.PI / 2.0);

	private final Quaternionf rotation = new Quaternionf();
	private PhotoSheet aimedAt;
	private boolean holding;
	private boolean useWasDown;
	private double wanted;
	private double distance;
	private int quarterTurns;
	private Vec3 position = Vec3.ZERO;

	private SheetReach() {
	}

	/**
	 * one frame of a player who is not in VR, before the photos are moved on
	 */
	public void frame(Minecraft mc, double dt) {
		final LocalPlayer player = mc.player;
		final boolean useDown = mc.options.keyUse.isDown();
		final boolean pressed = useDown && !useWasDown;
		useWasDown = useDown;
		final DesktopCamera camera = DesktopCamera.INSTANCE;
		if (player == null || mc.screen != null || camera.isSteered() || camera.reachesForCamera()) {
			letGo();
			aimedAt = null;
			return;
		}
		final float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		final Vec3 eyes = player.getEyePosition(partialTick);
		final Vec3 look = player.getViewVector(partialTick);
		if (holding) {
			if (useDown && PhotoAlbum.INSTANCE.isHolding(HAND)) {
				carry(player, eyes, look, dt);
			} else {
				letGo();
			}
			return;
		}
		aimedAt = PhotoAlbum.INSTANCE.pointedAt(eyes, look, REACH, AIM, HAND);
		if (aimedAt != null && pressed) {
			take(aimedAt, eyes);
		}
	}

	public boolean isHolding() {
		return holding;
	}

	/**
	 * @return if the use key belongs to a photo right now, and not to the game
	 */
	public boolean wantsUseKey() {
		return holding || aimedAt != null;
	}

	/**
	 * @return false if no photo is held, and the wheel is for something else
	 */
	public boolean scroll(double notches) {
		if (!holding) {
			return false;
		}
		wanted = CamMath.clamp(wanted * Math.exp(notches * WHEEL), NEAR, FAR);
		return true;
	}

	/**
	 * turns the held photo a quarter, the way the hands of a clock go
	 *
	 * @return false if no photo is held
	 */
	public boolean turn() {
		if (holding) {
			quarterTurns++;
		}
		return holding;
	}

	/**
	 * throws the held photo away, if the player made it
	 *
	 * @return null if no photo is held, otherwise if it is gone
	 */
	@Nullable
	public Boolean discard() {
		if (!holding) {
			return null;
		}
		final Boolean gone = PhotoAlbum.INSTANCE.discard(HAND);
		if (!Boolean.FALSE.equals(gone)) {
			holding = false;
		}
		return gone;
	}

	/**
	 * @return what the player is doing with a photo right now, for the hint on what to press. Null for nothing
	 */
	@Nullable
	CameraHints.Hint hint() {
		if (holding) {
			return CameraHints.Hint.SHEET_HOLD;
		}
		return aimedAt != null ? CameraHints.Hint.SHEET_AIM : null;
	}

	@Override
	public Vec3 position(int hand) {
		return position;
	}

	@Override
	public Quaternionf rotation(int hand) {
		return new Quaternionf(rotation);
	}

	private void take(PhotoSheet sheet, Vec3 eyes) {
		holding = true;
		quarterTurns = 0;
		position = sheet.center();
		rotation.set(sheet.rotation());
		distance = position.distanceTo(eyes);
		wanted = CamMath.clamp(distance, NEAR, FAR);
		PhotoAlbum.INSTANCE.take(sheet, HAND);
		aimedAt = null;
	}

	private void letGo() {
		if (holding) {
			holding = false;
			PhotoAlbum.INSTANCE.release(HAND);
		}
	}

	private void carry(LocalPlayer player, Vec3 eyes, Vec3 look, double dt) {
		final double ease = 1.0 - Math.exp(-EASE * dt);
		distance += (wanted - distance) * ease;
		final BlockHitResult wall = player.level().clip(new ClipContext(eyes, eyes.add(look.scale(distance)),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		final Vec3 target;
		final Vector3f out;
		if (wall.getType() == HitResult.Type.MISS) {
			target = eyes.add(look.scale(distance));
			out = eyes.subtract(target).normalize().toVector3f();
		} else {
			final Direction face = wall.getDirection();
			out = new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ());
			target = wall.getLocation().add(out.x * OFF_SURFACE, out.y * OFF_SURFACE, out.z * OFF_SURFACE);
		}
		position = position.lerp(target, ease);
		rotation.slerp(facing(out, look), (float) ease);
	}

	/**
	 * @param out  where the picture of the photo faces
	 * @param look where the player looks, for which way is up on a photo that lies flat
	 * @return how a photo is turned that faces that way, upright and then turned as often as the player asked
	 */
	private Quaternionf facing(Vector3f out, Vec3 look) {
		final Vector3f upright = new Vector3f(0, 1, 0);
		upright.sub(new Vector3f(out).mul(upright.dot(out)));
		if (upright.lengthSquared() < 1.0E-4F) {
			upright.set((float) look.x, 0, (float) look.z);
			upright.sub(new Vector3f(out).mul(upright.dot(out)));
		}
		if (upright.lengthSquared() < 1.0E-4F) {
			upright.set(0, 0, 1);
		}
		upright.normalize();
		final Vector3f right = upright.cross(out, new Vector3f());
		final float angle = quarterTurns * QUARTER_TURN;
		final Vector3f up = new Vector3f(upright).mul((float) Math.cos(angle))
				.add(new Vector3f(right).mul((float) Math.sin(angle)));
		return new Quaternionf().setFromNormalized(new Matrix3f(up.cross(out, new Vector3f()), up, out));
	}
}

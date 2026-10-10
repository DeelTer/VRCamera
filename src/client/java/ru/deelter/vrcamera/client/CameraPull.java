package ru.deelter.vrcamera.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.vivecraft.api.client.HeldInteractModule;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRData;

import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.config.PullStyle;
import ru.deelter.vrcamera.client.math.CamMath;

/**
 * Pulls a camera that is out of reach into the hand: point at it, look at it, and hold the interact button until it
 * comes flying. Let go of, it falls in the physics mode, and is placed there like any camera put down by hand in the
 * other modes.
 * <p>
 * As an interact module of Vivecraft. That gives the short buzz when the hand finds the camera, and keeps the button
 * from attacking or using the held item meanwhile.
 */
public final class CameraPull implements HeldInteractModule {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "camera_pull");

	private static final double HAND_ANGLE = Math.toRadians(14);
	private static final double HEAD_ANGLE = Math.toRadians(35);
	private static final double AIM_SLACK = 1.4;

	private static final double MIN_DISTANCE = 1.5;
	private static final double MAX_DISTANCE = 64.0;

	private final CameraController controller;

	private final int[] heldTicks = new int[2];
	private final boolean[] pulled = new boolean[2];

	private final boolean[] aimed = new boolean[2];

	public CameraPull(CameraController controller) {
		this.controller = controller;
	}

	private static double angle(Vec3 one, Vec3 other) {
		return Math.acos(CamMath.clamp(one.normalize().dot(other.normalize()), -1.0, 1.0));
	}

	@Override
	public Identifier getId() {
		return ID;
	}

	@Override
	public int getPriority() {
		return 760;
	}

	@Override
	public boolean swingsArm() {
		return false;
	}

	@Override
	public void reset(LocalPlayer player, InteractionHand hand) {
		heldTicks[hand.ordinal()] = 0;
		pulled[hand.ordinal()] = false;
	}

	@Override
	public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 handPosition) {
		final boolean found = isAimedAt(hand, handPosition, aimed[hand.ordinal()] ? AIM_SLACK : 1.0);
		aimed[hand.ordinal()] = found;
		return found;
	}

	@Override
	public boolean onPress(LocalPlayer player, InteractionHand hand) {
		heldTicks[hand.ordinal()] = 0;
		pulled[hand.ordinal()] = false;
		return true;
	}

	@Override
	public boolean onHoldTick(LocalPlayer player, InteractionHand hand) {
		final int index = hand.ordinal();
		if (pulled[index]) {
			final double drawn = controller.pullProgress(hand);
			if (drawn >= 0) {
				VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(hand), 0.05F,
						(float) CamMath.lerp(120.0, 320.0, drawn), (float) CamMath.lerp(0.15, 1.0, drawn), 0.0F);
			}
			return true;
		}
		if (!controller.canPull()) {
			return false;
		}
		if (controller.config().pullStyle == PullStyle.TELEKINESIS) {
			pulled[index] = true;
			controller.startPull(hand);
			return true;
		}
		final double progress = ++heldTicks[index] / (controller.config().pullSeconds * 20.0);

		VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(hand), 0.05F,
				(float) CamMath.lerp(120.0, 320.0, progress), (float) CamMath.lerp(0.15, 1.0, progress), 0.0F);
		if (progress >= 1.0) {
			pulled[index] = true;
			controller.startPull(hand);
		}
		return true;
	}

	@Override
	public void onRelease(LocalPlayer player, InteractionHand hand) {
		if (pulled[hand.ordinal()]) {
			controller.endPull(hand);
		}
		reset(player, hand);
	}

	/**
	 * @param slack multiplier for how far off hand and head may point
	 */
	private boolean isAimedAt(InteractionHand hand, Vec3 handPosition, double slack) {
		if (controller.config().pullSeconds <= 0 || !controller.canPull()) {
			return false;
		}
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		final VRData vrData = dataHolder.vrPlayer.vrdata_world_pre;
		final Vec3 camera = dataHolder.cameraTracker.getPosition();

		final Vec3 fromHand = camera.subtract(handPosition);
		final double distance = fromHand.length();
		if (distance < MIN_DISTANCE * vrData.worldScale || distance > MAX_DISTANCE) {
			return false;
		}
		final Vec3 fromHead = camera.subtract(vrData.hmd.getPosition());
		return angle(fromHand, new Vec3(vrData.getController(hand.ordinal()).getDirection())) < HAND_ANGLE * slack &&
				angle(fromHead, new Vec3(vrData.hmd.getDirection())) < HEAD_ANGLE * slack;
	}
}

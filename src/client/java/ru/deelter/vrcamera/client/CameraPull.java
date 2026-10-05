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

	// The hand has to point at the camera this well, and the head has to look about its way. Pointing alone would
	// catch the camera all the time while doing something else
	private static final double HAND_ANGLE = Math.toRadians(14);
	private static final double HEAD_ANGLE = Math.toRadians(35);
	private static final double AIM_SLACK = 1.4;
	// closer than this the camera can just be grabbed, further away it is not seen well enough to mean it
	private static final double MIN_DISTANCE = 1.5;
	private static final double MAX_DISTANCE = 64.0;

	private final CameraController controller;
	// per hand: ticks the button is held for, and if the camera was sent on its way already
	private final int[] heldTicks = new int[2];
	private final boolean[] pulled = new boolean[2];
	// If the hand found the camera the last time. It may then point a bit worse and keep it, or a hand right at the
	// edge would buzz over and over
	private final boolean[] aimed = new boolean[2];

	public CameraPull(CameraController controller) {
		this.controller = controller;
	}

	@Override
	public Identifier getId() {
		return ID;
	}

	@Override
	public int getPriority() {
		// right after grabbing the camera, which goes first when it is in reach
		return 760;
	}

	@Override
	public boolean swingsArm() {
		return false;
	}

	@Override
	public void reset(LocalPlayer player, InteractionHand hand) {
		this.heldTicks[hand.ordinal()] = 0;
		this.pulled[hand.ordinal()] = false;
	}

	@Override
	public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 handPosition) {
		boolean found = isAimedAt(hand, handPosition, this.aimed[hand.ordinal()] ? AIM_SLACK : 1.0);
		this.aimed[hand.ordinal()] = found;
		return found;
	}

	/**
	 * @param slack multiplier for how far off hand and head may point
	 */
	private boolean isAimedAt(InteractionHand hand, Vec3 handPosition, double slack) {
		if (this.controller.config().pullSeconds <= 0 || !this.controller.canPull()) {
			return false;
		}
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		VRData vr = dh.vrPlayer.vrdata_world_pre;
		Vec3 camera = dh.cameraTracker.getPosition();

		Vec3 fromHand = camera.subtract(handPosition);
		double distance = fromHand.length();
		if (distance < MIN_DISTANCE * vr.worldScale || distance > MAX_DISTANCE) {
			return false;
		}
		Vec3 fromHead = camera.subtract(vr.hmd.getPosition());
		return angle(fromHand, new Vec3(vr.getController(hand.ordinal()).getDirection())) < HAND_ANGLE * slack &&
				angle(fromHead, new Vec3(vr.hmd.getDirection())) < HEAD_ANGLE * slack;
	}

	private static double angle(Vec3 a, Vec3 b) {
		return Math.acos(CamMath.clamp(a.normalize().dot(b.normalize()), -1.0, 1.0));
	}

	@Override
	public boolean onPress(LocalPlayer player, InteractionHand hand) {
		this.heldTicks[hand.ordinal()] = 0;
		this.pulled[hand.ordinal()] = false;
		return true;
	}

	@Override
	public boolean onHoldTick(LocalPlayer player, InteractionHand hand) {
		int index = hand.ordinal();
		if (this.pulled[index]) {
			// the camera is on its way or in the hand, it stays there for as long as the button is held
			return true;
		}
		if (!this.controller.canPull()) {
			return false;
		}
		double progress = ++this.heldTicks[index] / (this.controller.config().pullSeconds * 20.0);
		// gets stronger and higher until the camera comes
		VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(hand), 0.05F,
				(float) CamMath.lerp(120.0, 320.0, progress), (float) CamMath.lerp(0.15, 1.0, progress), 0.0F);
		if (progress >= 1.0) {
			this.pulled[index] = true;
			this.controller.startPull(hand);
		}
		return true;
	}

	@Override
	public void onRelease(LocalPlayer player, InteractionHand hand) {
		if (this.pulled[hand.ordinal()]) {
			this.controller.endPull(hand);
		}
		reset(player, hand);
	}
}

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
import org.vivecraft.client_vr.gameplay.trackers.CameraTracker;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.math.CamMath;

/**
 * The second hand at a camera the first one holds. Holding its interact button takes a photo. Letting go with the
 * first hand meanwhile hands the camera over to the second.
 * <p>
 * Vivecraft would give the camera to the second hand the moment it presses. That has to wait until the first hand
 * lets go, or there would be no telling a photo from taking the camera over.
 */
public final class CameraShutter implements HeldInteractModule {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "camera_shutter");
	// blocks between both hands
	private static final double REACH = 0.35;

	private final CameraController controller;
	private final int[] heldTicks = new int[2];
	private final boolean[] taken = new boolean[2];

	public CameraShutter(CameraController controller) {
		this.controller = controller;
	}

	@Override
	public Identifier getId() {
		return ID;
	}

	@Override
	public int getPriority() {
		// before Vivecraft grabs the camera at 750
		return 740;
	}

	@Override
	public boolean swingsArm() {
		return false;
	}

	@Override
	public void reset(LocalPlayer player, InteractionHand hand) {
		this.heldTicks[hand.ordinal()] = 0;
		this.taken[hand.ordinal()] = false;
	}

	@Override
	public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 handPosition) {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		CameraTracker camera = dh.cameraTracker;
		if (!this.controller.isEngaged() || !camera.isMoving() || camera.isQuickMode() ||
				camera.getMovingController() == hand.ordinal())
		{
			return false;
		}
		// Hand to hand, both from the same tick. The camera itself is placed per frame, and is a step ahead of
		// the hands of a walking player
		VRData vr = dh.vrPlayer.vrdata_world_pre;
		Vec3 holding = vr.getController(camera.getMovingController()).getPosition();
		return handPosition.distanceTo(holding) < REACH * vr.worldScale;
	}

	@Override
	public boolean onPress(LocalPlayer player, InteractionHand hand) {
		reset(player, hand);
		this.controller.offerHand(hand.ordinal());
		return true;
	}

	@Override
	public boolean onHoldTick(LocalPlayer player, InteractionHand hand) {
		int index = hand.ordinal();
		CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		if (!camera.isMoving()) {
			if (!this.controller.isEngaged() || !camera.isVisible()) {
				return false;
			}
			// The first hand let go, earlier in this same tick. Take the camera before the next frame finds it
			// in no hand and drops it
			camera.startMoving(index);
			return true;
		}
		if (camera.getMovingController() == index) {
			// this hand has the camera now, for as long as it holds the button
			return true;
		}
		double seconds = this.controller.config().photoHoldSeconds;
		if (this.taken[index] || seconds <= 0) {
			return true;
		}
		double progress = Math.min(1.0, ++this.heldTicks[index] / (seconds * 20.0));
		// gets stronger and higher until the shutter clicks, so it does not come as a surprise
		VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(hand), 0.05F,
				(float) CamMath.lerp(120.0, 320.0, progress), (float) CamMath.lerp(0.15, 1.0, progress), 0.0F);
		// may be refused while the last sheet is still coming out, then it is taken as soon as that is over
		if (progress >= 1.0 && this.controller.takePhoto()) {
			this.taken[index] = true;
		}
		return true;
	}

	@Override
	public void onRelease(LocalPlayer player, InteractionHand hand) {
		this.controller.withdrawHand(hand.ordinal());
		CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		if (camera.isMoving() && camera.getMovingController() == hand.ordinal()) {
			camera.stopMoving();
		}
		reset(player, hand);
	}
}

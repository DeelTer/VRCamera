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
import ru.deelter.vrcamera.client.config.PhotoGesture;
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
		return 740;
	}

	@Override
	public boolean swingsArm() {
		return false;
	}

	@Override
	public void reset(LocalPlayer player, InteractionHand hand) {
		heldTicks[hand.ordinal()] = 0;
		taken[hand.ordinal()] = false;
	}

	@Override
	public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 handPosition) {
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		final CameraTracker camera = dataHolder.cameraTracker;

		if (controller.config().photoGesture != PhotoGesture.OTHER_HAND || !controller.isEngaged() ||
				!camera.isMoving() || camera.isQuickMode() || camera.getMovingController() == hand.ordinal()) {
			return false;
		}

		final VRData vrData = dataHolder.vrPlayer.vrdata_world_pre;
		final Vec3 holding = vrData.getController(camera.getMovingController()).getPosition();
		return handPosition.distanceTo(holding) < REACH * vrData.worldScale;
	}

	@Override
	public boolean onPress(LocalPlayer player, InteractionHand hand) {
		reset(player, hand);
		controller.offerHand(hand.ordinal());
		return true;
	}

	@Override
	public boolean onHoldTick(LocalPlayer player, InteractionHand hand) {
		final int index = hand.ordinal();
		final CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		if (!camera.isMoving()) {
			if (!controller.isEngaged() || !camera.isVisible()) {
				return false;
			}

			camera.startMoving(index);
			return true;
		}
		if (camera.getMovingController() == index) {
			return true;
		}
		final double seconds = controller.config().photoHoldSeconds;
		if (taken[index]) {
			return true;
		}
		final double progress = seconds <= 0 ? 1.0 : Math.min(1.0, ++heldTicks[index] / (seconds * 20.0));

		VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(hand), 0.05F,
				(float) CamMath.lerp(120.0, 320.0, progress), (float) CamMath.lerp(0.15, 1.0, progress), 0.0F);

		if (progress >= 1.0 && controller.takePhoto()) {
			taken[index] = true;
		}
		return true;
	}

	@Override
	public void onRelease(LocalPlayer player, InteractionHand hand) {
		controller.withdrawHand(hand.ordinal());
		final CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		if (camera.isMoving() && camera.getMovingController() == hand.ordinal()) {
			camera.stopMoving();
		}
		reset(player, hand);
	}
}

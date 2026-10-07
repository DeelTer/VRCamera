package ru.deelter.vrcamera.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.vivecraft.api.client.HeldInteractModule;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRData;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.photo.PhotoSheet;

/**
 * Picks up a photo sheet with the interact button, like the camera is picked up. Let go of next to a block it is
 * pinned there, anywhere else it is thrown.
 */
public final class SheetGrab implements HeldInteractModule {
	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Vrcamera.MOD_ID, "sheet_grab");
	// blocks from the hand to the middle of a sheet
	private static final double REACH = 0.22;

	@Override
	public ResourceLocation getId() {
		return ID;
	}

	@Override
	public int getPriority() {
		// Before everything about the camera, the shutter at 740 and grabbing it at 750. A sheet is the smaller
		// thing to hit, a hand right at one means the sheet
		return 735;
	}

	@Override
	public boolean swingsArm() {
		return false;
	}

	@Override
	public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 handPosition) {
		return nearest(handPosition, hand) != null;
	}

	private static PhotoSheet nearest(Vec3 handPosition, InteractionHand hand) {
		VRData vr = ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_pre;
		return PhotoAlbum.INSTANCE.nearest(handPosition, hand.ordinal(), REACH * vr.worldScale);
	}

	@Override
	public boolean onPress(LocalPlayer player, InteractionHand hand) {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		PhotoSheet sheet = nearest(dh.vrPlayer.vrdata_world_pre.getController(hand.ordinal()).getPosition(), hand);
		if (sheet == null) {
			return false;
		}
		// by the pose of the frame, that is what the sheet is moved with from here on
		PhotoAlbum.INSTANCE.grab(sheet, hand.ordinal(), Vive.hands(dh.vrPlayer.vrdata_world_render));
		return true;
	}

	@Override
	public boolean onHoldTick(LocalPlayer player, InteractionHand hand) {
		return PhotoAlbum.INSTANCE.isHolding(hand.ordinal());
	}

	@Override
	public void onRelease(LocalPlayer player, InteractionHand hand) {
		PhotoAlbum.INSTANCE.release(hand.ordinal());
	}
}

package ru.deelter.vrcamera.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
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
	private static final Identifier ID = Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "sheet_grab");

	private static final double REACH = 0.22;

	private static PhotoSheet nearest(Vec3 handPosition, InteractionHand hand) {
		final VRData vrData = ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_pre;
		return PhotoAlbum.INSTANCE.nearest(handPosition, hand.ordinal(), REACH * vrData.worldScale);
	}

	@Override
	public Identifier getId() {
		return ID;
	}

	@Override
	public int getPriority() {
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

	@Override
	public boolean onPress(LocalPlayer player, InteractionHand hand) {
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		final Vec3 reach = dataHolder.vrPlayer.vrdata_world_pre.getController(hand.ordinal()).getPosition();
		final PhotoSheet sheet = nearest(reach, hand);
		if (sheet == null) {
			return false;
		}

		PhotoAlbum.INSTANCE.grab(sheet, hand.ordinal(), Vive.hands(dataHolder.vrPlayer.vrdata_world_render));
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

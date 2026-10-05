package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import ru.deelter.vrcamera.client.CameraController;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

	// Gizmos have to be added right before the game collects them for the pass that is being set up. Added any later
	// in the same pass they are only drawn in the next one: what was meant for the right eye ends up in the recording.
	// Optional, without this only the marker and the camera icon are missing
	@Inject(method = "extractGizmos", at = @At("HEAD"), require = 0)
	private void vrcamera$drawHeadsetAids(CallbackInfo ci) {
		CameraController controller = CameraController.INSTANCE;
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		if (!CameraController.isVRRunning()) {
			return;
		}
		// only for the eyes of the player, none of this should show up in the recording
		if (dh.currentPass == RenderPass.LEFT || dh.currentPass == RenderPass.RIGHT) {
			controller.drawHeadsetAids(dh.vrPlayer.vrdata_world_render);
		}
	}
}

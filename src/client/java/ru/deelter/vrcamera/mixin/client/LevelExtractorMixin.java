package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.Vive;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.sync.RemoteCameras;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

	// Gizmos have to be added right before the game collects them for the pass that is being set up. Added any later
	// in the same pass they are only drawn in the next one: what was meant for the right eye ends up in the recording.
	// Optional, without this only the marker and the camera icon are missing
	@Inject(method = "extractGizmos", at = @At("HEAD"), require = 0)
	private void vrcamera$drawHeadsetAids(CallbackInfo ci) {
		// names over the cameras of other players, for whoever looks: also without VR, also in the recording
		RemoteCameras.INSTANCE.drawLabels();
		PhotoAlbum.INSTANCE.drawLabels();
		DesktopCamera.INSTANCE.drawLabel();
		if (Vr.isRunning()) {
			Vive.drawHeadsetAids();
		}
	}

	// in front of the green screen only what the player could see is filmed
	@ModifyReturnValue(method = "isEntityVisible", at = @At("RETURN"), require = 0)
	private boolean vrcamera$onlySeenEntities(boolean visible, @Local(argsOnly = true) Entity entity) {
		return visible && (!ChromaKey.applies() || ChromaKey.shows(entity));
	}
}

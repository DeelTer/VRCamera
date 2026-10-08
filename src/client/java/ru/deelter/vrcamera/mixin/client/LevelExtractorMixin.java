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

	@Inject(method = "extractGizmos", at = @At("HEAD"), require = 0)
	private void vrcamera$drawHeadsetAids(CallbackInfo ci) {

		RemoteCameras.INSTANCE.drawLabels();
		PhotoAlbum.INSTANCE.drawLabels();
		DesktopCamera.INSTANCE.drawLabel();
		if (Vr.isRunning()) {
			Vive.drawHeadsetAids();
		}
	}

	@ModifyReturnValue(method = "isEntityVisible", at = @At("RETURN"), require = 0)
	private boolean vrcamera$onlySeenEntities(boolean visible, @Local(argsOnly = true) Entity entity) {
		return visible && (!ChromaKey.applies() || ChromaKey.shows(entity));
	}
}

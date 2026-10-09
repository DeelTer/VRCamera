package ru.deelter.vrcamera.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.Vive;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DesktopGui;
import ru.deelter.vrcamera.client.desktop.DirectorPass;
import ru.deelter.vrcamera.client.photo.CameraFlashes;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.sync.RemoteCameras;

/**
 * Where the mod draws into a picture of the world: its things with the entities, its texts with what the game
 * writes into the world itself. The green screen is two clears around the entities, see {@link ChromaKey}.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	@Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
	private void vrcamera$drawTexts(CallbackInfo ci) {
		RemoteCameras.INSTANCE.drawLabels();
		PhotoAlbum.INSTANCE.drawLabels();
		DesktopCamera.INSTANCE.drawLabel();
		if (Vr.isRunning()) {
			Vive.drawHeadsetAids();
		}
	}

	@Inject(method = "submitEntities", at = @At("HEAD"), require = 0)
	private void vrcamera$greenBehindEntities(CallbackInfo ci) {
		ChromaKey.paintOver();
	}

	@Inject(method = "submitEntities", at = @At("TAIL"), require = 0)
	private void vrcamera$drawThings(
			PoseStack poseStack, LevelRenderState state, SubmitNodeCollector output, CallbackInfo ci) {
		final Vec3 from = state.cameraRenderState.pos;
		PhotoAlbum.INSTANCE.render(output, from, poseStack);
		RemoteCameras.INSTANCE.render(output, from, poseStack);
		CameraFlashes.INSTANCE.render(output, from, poseStack);
		if (Vr.isVanillaPass()) {
			DesktopCamera.INSTANCE.renderModel(output, from, poseStack);
			if (DirectorPass.isActive()) {
				DesktopGui.render(output, from, poseStack);
			}
		}
	}

	@Inject(method = "submitBlockEntities", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noBlockEntities(CallbackInfo ci) {
		if (ChromaKey.applies()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noBlockOutline(CallbackInfo ci) {
		if (ChromaKey.applies()) {
			ci.cancel();
		}
	}

	@Inject(method = "finalizeGizmoCollection", at = @At("HEAD"), require = 0)
	private void vrcamera$nothingAfterEntities(CallbackInfo ci) {
		ChromaKey.shutOut();
	}
}

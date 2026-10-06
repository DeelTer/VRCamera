package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client_xr.render_pass.RenderPassType;
import ru.deelter.vrcamera.client.photo.CameraFlashes;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.sync.RemoteCameras;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	// For players that have the mod but are not in VR: photo sheets and the cameras of others are drawn into the
	// regular picture. The same place Vivecraft draws its VR things from; in a VR pass those call into the mod
	// themselves, and this stays out of it to not draw everything twice
	@Inject(method = "submitFeatures*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;finalizeGizmoCollection()V"), require = 0)
	private void vrcamera$drawWithoutVR(
			CallbackInfo ci, @Local(argsOnly = true) LevelRenderState levelRenderState,
			@Local(argsOnly = true) SubmitNodeCollector output, @Local PoseStack poseStack) {
		if (!RenderPassType.isVanilla()) {
			return;
		}
		PhotoAlbum.INSTANCE.render(output, levelRenderState.cameraRenderState.pos, poseStack);
		RemoteCameras.INSTANCE.render(output, levelRenderState.cameraRenderState.pos, poseStack);
		CameraFlashes.INSTANCE.render(output, levelRenderState.cameraRenderState.pos, poseStack);
	}
}

package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DesktopGui;
import ru.deelter.vrcamera.client.desktop.DirectorPass;
import ru.deelter.vrcamera.client.photo.CameraFlashes;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.sync.RemoteCameras;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	@Inject(method = "submitFeatures*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;finalizeGizmoCollection()V"), require = 0)
	private void vrcamera$drawWithoutVR(
			CallbackInfo ci, @Local(argsOnly = true) LevelRenderState levelRenderState,
			@Local(argsOnly = true) SubmitNodeCollector output, @Local PoseStack poseStack) {
		if (!Vr.isVanillaPass()) {
			return;
		}
		PhotoAlbum.INSTANCE.render(output, levelRenderState.cameraRenderState.pos, poseStack);
		RemoteCameras.INSTANCE.render(output, levelRenderState.cameraRenderState.pos, poseStack);
		CameraFlashes.INSTANCE.render(output, levelRenderState.cameraRenderState.pos, poseStack);
		DesktopCamera.INSTANCE.renderModel(output, levelRenderState.cameraRenderState.pos, poseStack);
		if (DirectorPass.isActive()) {
			DesktopGui.render(output, levelRenderState.cameraRenderState.pos, poseStack);
		}
	}

	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V"), require = 0)
	private void vrcamera$greenBehindEntities(CallbackInfo ci) {
		ChromaKey.paintOver();
	}

	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent()V", shift = At.Shift.AFTER), require = 0)
	private void vrcamera$nothingAfterEntities(CallbackInfo ci) {
		ChromaKey.shutOut();
	}

	@Inject(method = "executeSolid", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"), require = 0)
	private void vrcamera$greenBehindEntitiesWhileDrawing(CallbackInfo ci) {
		ChromaKey.paintOverWhileDrawing();
	}

	@Inject(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V", shift = At.Shift.AFTER), require = 0)
	private void vrcamera$nothingAfterEntitiesWhileDrawing(CallbackInfo ci) {
		ChromaKey.shutOutWhileDrawing();
	}

	@Inject(method = "executeOit", at = @At("HEAD"), require = 0)
	private void vrcamera$nothingAfterSolidEntities(CallbackInfo ci) {
		ChromaKey.shutOut();
	}

	@WrapWithCondition(method = "submitFeatures*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;submitBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"), require = 0)
	private boolean vrcamera$noBlockEntities(
			LevelRenderer self, PoseStack poseStack, LevelRenderState state, SubmitNodeCollector output) {
		return !ChromaKey.applies();
	}

	@WrapWithCondition(method = "submitFeatures*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"), require = 0)
	private boolean vrcamera$noParticles(ParticlesRenderState particles, SubmitNodeCollector output, CameraRenderState camera) {
		return !ChromaKey.applies();
	}

	@WrapWithCondition(method = "submitFeatures*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;submitBlockOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"), require = 0)
	private boolean vrcamera$noBlockOutline(
			LevelRenderer self, PoseStack poseStack, SubmitNodeCollector output, LevelRenderState state) {
		return !ChromaKey.applies();
	}
}

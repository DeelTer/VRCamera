package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DirectorPass;
import ru.deelter.vrcamera.client.desktop.OutputWindow;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noBobForCamera(CallbackInfo ci) {
		if (DirectorPass.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noHurtBobForCamera(CallbackInfo ci) {
		if (DirectorPass.isActive()) {
			ci.cancel();
		}
	}

	@ModifyReturnValue(method = "getFov", at = @At("RETURN"), require = 0)
	private float vrcamera$fovOfDirector(float fov) {
		DesktopCamera.Pose pose = DesktopCamera.INSTANCE.pose();
		return pose == null ? fov : pose.fov();
	}

	@ModifyExpressionValue(method = "getProjectionMatrix", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getWidth()I"), require = 0)
	private int vrcamera$widthOfPicture(int width) {
		return DirectorPass.width(width);
	}

	@ModifyExpressionValue(method = "getProjectionMatrix", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getHeight()I"), require = 0)
	private int vrcamera$heightOfPicture(int height) {
		return DirectorPass.height(height);
	}

	@ModifyConstant(method = "getProjectionMatrix", constant = @Constant(floatValue = 0.05F), require = 0)
	private float vrcamera$nearOfPicture(float near) {
		return DirectorPass.near(near);
	}

	@WrapWithCondition(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"), require = 0)
	private boolean vrcamera$noPauseForCameraWindow(Minecraft mc, boolean pauseOnly) {
		return !OutputWindow.isFocused();
	}
}

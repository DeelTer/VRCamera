package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import ru.deelter.vrcamera.client.desktop.DitheredBlocks;

@Pseudo
@Mixin(targets = "me.zipestudio.blockdithering.dithering.DitheringDataConfig", remap = false)
public class BlockDitheringConfigMixin {

	@ModifyReturnValue(method = "getFarDistance", at = @At("RETURN"), require = 0)
	private double vrcamera$asFarAsThePlayer(double far) {
		return DitheredBlocks.far(far);
	}

	@ModifyReturnValue(method = "getNearDistance", at = @At("RETURN"), require = 0)
	private double vrcamera$nearlyAsFarAsThePlayer(double near) {
		return DitheredBlocks.near(near);
	}
}

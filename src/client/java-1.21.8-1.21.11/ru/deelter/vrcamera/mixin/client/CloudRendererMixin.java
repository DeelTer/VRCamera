package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.CloudSlots;

/**
 * The clouds keep the shape they were built in for the camera they were last drawn for. The camera of the mod is
 * somewhere else than the player: it gets its own, or both would be built anew every frame, and the game waits
 * for the graphics card each time.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin implements CloudSlots {
	@Shadow
	private boolean needsRebuild;
	@Shadow
	private int prevCellX;
	@Shadow
	private int prevCellZ;
	@Shadow
	private CloudStatus prevType;
	@Shadow
	private int quadCount;
	@Shadow
	private MappableRingBuffer utb;

	@Unique
	private boolean vrcamera$needsRebuild = true;
	@Unique
	private int vrcamera$cellX = Integer.MIN_VALUE;
	@Unique
	private int vrcamera$cellZ = Integer.MIN_VALUE;
	@Unique
	private CloudStatus vrcamera$status;
	@Unique
	private int vrcamera$quads;
	@Unique
	private MappableRingBuffer vrcamera$faces;

	@Override
	public void vrcamera$swap() {
		final boolean rebuild = needsRebuild;
		needsRebuild = vrcamera$needsRebuild;
		vrcamera$needsRebuild = rebuild;
		final int cellX = prevCellX;
		prevCellX = vrcamera$cellX;
		vrcamera$cellX = cellX;
		final int cellZ = prevCellZ;
		prevCellZ = vrcamera$cellZ;
		vrcamera$cellZ = cellZ;
		final CloudStatus status = prevType;
		prevType = vrcamera$status;
		vrcamera$status = status;
		final int quads = quadCount;
		quadCount = vrcamera$quads;
		vrcamera$quads = quads;
		final MappableRingBuffer faces = utb;
		utb = vrcamera$faces;
		vrcamera$faces = faces;
	}

	@Inject(method = "markForRebuild", at = @At("TAIL"), require = 0)
	private void vrcamera$rebuildBoth(CallbackInfo ci) {
		vrcamera$needsRebuild = true;
	}

	@Inject(method = "close", at = @At("TAIL"), require = 0)
	private void vrcamera$closeOwn(CallbackInfo ci) {
		if (vrcamera$faces != null) {
			vrcamera$faces.close();
			vrcamera$faces = null;
		}
	}
}

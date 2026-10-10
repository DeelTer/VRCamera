package ru.deelter.vrcamera.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.desktop.DirectorPass;

import java.lang.reflect.Field;

/**
 * Sodium works out what of the world is seen for one view, the one of the player, and keeps at that from frame to
 * frame. The picture of the camera is a second view in the same frame. It is drawn with what Sodium finds for it on
 * the spot, and leaves everything Sodium keeps for the player the way it was.
 */
@Pseudo
@Mixin(targets = {
		"net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
		"me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager"
}, remap = false)
public class SodiumSectionManagerMixin {
	@Unique
	private static final String[] vrcamera$FOUND = {"renderLists", "taskLists", "renderTree"};
	@Unique
	private static boolean vrcamera$broken;
	@Unique
	private Field[] vrcamera$fields;
	@Unique
	private Object[] vrcamera$ofPlayer;

	@Unique
	private static void vrcamera$failed(Exception cause) {
		vrcamera$broken = true;
		Vrcamera.LOGGER.warn("VRCamera: can't keep the view of the player apart from the one of the camera for Sodium, "
				+ "chunks may be missing for a moment while the camera films", cause);
	}

	@Inject(method = "updateChunks", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$chunksOncePerFrame(CallbackInfo ci) {
		if (DirectorPass.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "prepareRenderTrees", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$seenForThePlayerOnly(CallbackInfo ci) {
		if (DirectorPass.isActive()) {
			ci.cancel();
		} else {
			vrcamera$giveBack();
		}
	}

	@Inject(method = "finalizeRenderLists", at = @At("HEAD"), require = 0)
	private void vrcamera$keepWhatThePlayerSees(CallbackInfo ci) {
		if (!DirectorPass.isActive() || vrcamera$ofPlayer != null || vrcamera$broken) {
			return;
		}
		try {
			if (vrcamera$fields == null) {
				vrcamera$fields = new Field[vrcamera$FOUND.length];
				for (int i = 0; i < vrcamera$FOUND.length; i++) {
					try {
						vrcamera$fields[i] = getClass().getDeclaredField(vrcamera$FOUND[i]);
						vrcamera$fields[i].setAccessible(true);
					} catch (NoSuchFieldException e) {
					}
				}
			}
			final Object[] kept = new Object[vrcamera$fields.length];
			for (int i = 0; i < kept.length; i++) {
				kept[i] = vrcamera$fields[i] == null ? null : vrcamera$fields[i].get(this);
			}
			vrcamera$ofPlayer = kept;
		} catch (ReflectiveOperationException | RuntimeException e) {
			vrcamera$failed(e);
		}
	}

	/**
	 * before Sodium goes on with the view of the player: what it had for it, in place of what it found for the camera
	 */
	@Unique
	private void vrcamera$giveBack() {
		final Object[] kept = vrcamera$ofPlayer;
		if (kept == null) {
			return;
		}
		vrcamera$ofPlayer = null;
		try {
			for (int i = 0; i < kept.length; i++) {
				if (vrcamera$fields[i] != null) {
					vrcamera$fields[i].set(this, kept[i]);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			vrcamera$failed(e);
		}
	}
}

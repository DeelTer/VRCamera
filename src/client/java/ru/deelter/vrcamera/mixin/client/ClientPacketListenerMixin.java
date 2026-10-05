package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

	// The server tells every client near an explosion about it, also a server that knows nothing of this mod.
	// At the tail: the head is also reached off the game thread, where the packet only gets passed on
	@Inject(method = "handleExplosion", at = @At("TAIL"), require = 0)
	private void vrcamera$blowSheetsAway(ClientboundExplodePacket packet, CallbackInfo ci) {
		PhotoAlbum.INSTANCE.explosion(packet.center(), packet.radius());
	}
}

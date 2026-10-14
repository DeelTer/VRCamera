package ru.deelter.vrcamera.client.voice;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.OpenALSoundEvent;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.Vrcamera;

/**
 * Simple Voice Chat finds this through the entry point "voicechat", and only while it is installed: every voice it
 * plays is heard from the camera, see {@link VoiceEars}
 */
public final class SimpleVoiceChatEars implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return Vrcamera.MOD_ID;
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(OpenALSoundEvent.class, event -> {
			final Position at = event.getPosition();
			VoiceEars.turn(event.getSource(), at == null ? null : new Vec3(at.getX(), at.getY(), at.getZ()));
		});
	}
}

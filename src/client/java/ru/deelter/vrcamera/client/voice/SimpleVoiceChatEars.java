package ru.deelter.vrcamera.client.voice;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.OpenALSoundEvent;
import ru.deelter.vrcamera.Vrcamera;

/**
 * Simple Voice Chat finds this through the entry point "voicechat", and only while it is installed: every voice it
 * plays is heard the way the camera looks, see {@link VoiceEars}
 */
public final class SimpleVoiceChatEars implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return Vrcamera.MOD_ID;
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(OpenALSoundEvent.class, event -> VoiceEars.turn());
	}
}

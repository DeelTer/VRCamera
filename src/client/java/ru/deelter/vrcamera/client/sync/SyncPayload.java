package ru.deelter.vrcamera.client.sync;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.deelter.vrcamera.sync.Protocol;

/**
 * A message of {@link Protocol} on its plugin channel. The bytes go over the wire as they are, with nothing around
 * them: that is what a Paper plugin gets and sends.
 */
public record SyncPayload(byte[] data) implements CustomPacketPayload {
	public static final Type<SyncPayload> TYPE = new Type<>(Identifier.parse(Protocol.CHANNEL));
	public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(
			(buffer, payload) -> buffer.writeBytes(payload.data),
			buffer -> {
				byte[] data = new byte[buffer.readableBytes()];
				buffer.readBytes(data);
				return new SyncPayload(data);
			});

	@Override
	public Type<SyncPayload> type() {
		return TYPE;
	}
}

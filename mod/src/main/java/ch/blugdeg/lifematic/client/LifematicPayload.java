package ch.blugdeg.lifematic.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Rohe Bytes auf dem Kanal lifematic:main (gleiches Format wie das Paper-Plugin). */
public record LifematicPayload(byte[] data) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LifematicPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("lifematic", "main"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LifematicPayload> CODEC =
            CustomPacketPayload.codec(LifematicPayload::write, LifematicPayload::read);

    private static LifematicPayload read(FriendlyByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        return new LifematicPayload(b);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBytes(data);
    }

    @Override
    public CustomPacketPayload.Type<LifematicPayload> type() {
        return TYPE;
    }
}

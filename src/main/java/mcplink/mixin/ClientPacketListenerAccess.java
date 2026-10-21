package mcplink.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccess {
    /** The server's view distance in chunks, as sent at login. */
    @Accessor("serverChunkRadius")
    int mcplink$serverChunkRadius();
}

package mcplink.mixin;

import mcplink.script.Scripts;
import mcplink.state.PacketLog;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every packet the server sends this client, on the network thread before the game handles it, and every packet the client
 * sends. The integrated server's own connections and server-list pings are skipped. Scripts may drop or replace packets either way.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow
    private volatile PacketListener packetListener;

    @Shadow
    protected abstract void channelRead0(ChannelHandlerContext ctx, Packet<?> packet);

    @Shadow
    public abstract void send(Packet<?> packet, ChannelFutureListener listener, boolean flush);

    @Shadow
    public abstract PacketFlow getSending();

    /** Set while a script's replacement packet is handed back to the game, so it is not logged or filtered again. */
    @Unique
    private static final ThreadLocal<Boolean> mcplink$passing = ThreadLocal.withInitial(() -> false);

    // the descriptor picks the real method over the compiler's Object bridge
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void mcplink$received(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (mcplink$passing.get()) return;
        PacketListener listener = this.packetListener;
        if (listener == null || listener.flow() != PacketFlow.CLIENTBOUND || listener.protocol() == ConnectionProtocol.STATUS) return;
        PacketLog.received(this, packet);
        if (!Scripts.wantsPacketsIn()) return;
        Object r = Scripts.packetIn(packet);
        if (r == null) return;
        ci.cancel();
        if (r instanceof Packet<?> replacement) {
            mcplink$passing.set(true);
            try {
                this.channelRead0(ctx, replacement);
            } finally {
                mcplink$passing.set(false);
            }
        }
    }

    /** All of Connection.send's overloads end here. */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void mcplink$sending(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (mcplink$passing.get() || !Scripts.wantsPacketsOut() || this.getSending() != PacketFlow.SERVERBOUND) return;
        Object r;
        mcplink$passing.set(true);   // a handler that sends a packet itself doesn't run the handlers again
        try {
            r = Scripts.packetOut(packet);
        } finally {
            mcplink$passing.set(false);
        }
        if (r == null) return;
        ci.cancel();
        if (r instanceof Packet<?> replacement) {
            mcplink$passing.set(true);
            try {
                this.send(replacement, listener, flush);
            } finally {
                mcplink$passing.set(false);
            }
        }
    }
}

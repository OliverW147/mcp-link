package mcplink.mixin;

import mcplink.state.Perf;
import mcplink.tools.DataTools;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void mcplink$time(ClientboundSetTimePacket packet, CallbackInfo ci) {
        Perf.serverTime(packet.gameTime());
    }

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void mcplink$login(ClientboundLoginPacket packet, CallbackInfo ci) {
        Perf.resetServerTime();
    }

    @Inject(method = "handleMerchantOffers", at = @At("TAIL"))
    private void mcplink$offers(ClientboundMerchantOffersPacket packet, CallbackInfo ci) {
        DataTools.tradesReceived(packet);
    }
}

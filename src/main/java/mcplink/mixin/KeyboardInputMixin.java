package mcplink.mixin;

import mcplink.control.Control;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While an agent task steers, its keys replace the keyboard's; any movement key from the player hands control back. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mcplink$steer(CallbackInfo ci) {
        if (!Control.steering()) return;
        Input user = this.keyPresses;
        if (user.forward() || user.backward() || user.left() || user.right() || user.jump()) {
            Control.userMoved();
            return;
        }
        Input in = Control.input();
        this.keyPresses = in;
        float f = in.forward() == in.backward() ? 0.0F : in.forward() ? 1.0F : -1.0F;
        float s = in.left() == in.right() ? 0.0F : in.left() ? 1.0F : -1.0F;
        this.moveVector = new Vec2(s, f).normalized();
    }
}

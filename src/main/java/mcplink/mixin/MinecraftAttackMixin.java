package mcplink.mixin;

import mcplink.control.Control;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vanilla calls continueAttack every tick with "attack key down and mouse grabbed", and stops breaking the block when that is
 * false. The window is usually unfocused while the player talks to the agent, so a mining task forces it on instead.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
    @ModifyVariable(method = "continueAttack", at = @At("HEAD"), argsOnly = true)
    private boolean mcplink$holdAttack(boolean down) {
        return down || Control.holdAttack();
    }
}

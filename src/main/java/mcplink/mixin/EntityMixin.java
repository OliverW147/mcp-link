package mcplink.mixin;

import mcplink.script.Scripts;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The outline colour of an entity a script made glow (the game takes it from the team colour). */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void mcplink$glowColor(CallbackInfoReturnable<Integer> cir) {
        if (Scripts.GLOWING.isEmpty()) return;
        int c = Scripts.glowColor((Entity) (Object) this);
        if (c >= 0) cir.setReturnValue(c);
    }
}

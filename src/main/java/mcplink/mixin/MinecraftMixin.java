package mcplink.mixin;

import mcplink.control.Control;
import mcplink.script.Scripts;
import mcplink.state.Perf;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /**
     * With "pause on lost focus" (the default) the game reopens the pause menu every frame once the window has been unfocused
     * for half a second, which is always the case while the player types to the agent. That pauses singleplayer and stops the
     * game's own key handling (mining, held right-click), so it is skipped while control is on.
     */
    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void mcplink$keepRunning(CallbackInfo ci) {
        if (Control.keepRunning()) ci.cancel();
    }

    /** Entities a script made glow get the outline (its colour comes from EntityMixin). */
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void mcplink$glow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!Scripts.GLOWING.isEmpty() && Scripts.glowColor(entity) >= 0) cir.setReturnValue(true);
    }

    /** Frame timing: one call per frame in every screen, so menus and loading leave no gaps in the record. */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void mcplink$frame(boolean advanceGameTime, CallbackInfo ci) {
        Perf.frame();
    }
}

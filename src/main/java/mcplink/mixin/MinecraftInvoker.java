package mcplink.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The game's own left/right-click handling, so actions behave exactly like a mouse click on the current crosshair target. */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {
    @Invoker("startAttack")
    boolean mcplink$startAttack();

    @Invoker("continueAttack")
    void mcplink$continueAttack(boolean down);

    @Invoker("startUseItem")
    void mcplink$startUseItem();

    /** Recomputes hitResult for the current view direction. */
    @Invoker("pick")
    void mcplink$pick(float partialTicks);
}

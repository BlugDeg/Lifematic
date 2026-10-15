package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostThread;
import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Einfaerbung ueber die Vertexfarbe (wirkt auch mit Shadern):
 * blau = fehlt noch, rot = falscher Block, orange = falsch ausgerichtet, violett = gehoert nicht hin.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {
    @Unique private int lifematic$tint;

    @Inject(method = "renderModel", at = @At("HEAD"))
    private void lifematic$pickTint(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        this.lifematic$tint = GhostThread.tintFor(pos);
    }

    @Inject(method = "tintQuad", at = @At("TAIL"))
    private void lifematic$applyTint(MutableQuadViewImpl quad, CallbackInfo ci) {
        int tint = this.lifematic$tint;
        if (tint == 0) return;
        for (int i = 0; i < 4; i++) {
            quad.setColor(i, ColorMixer.mulComponentWise(quad.baseColor(i), tint));
        }
    }
}

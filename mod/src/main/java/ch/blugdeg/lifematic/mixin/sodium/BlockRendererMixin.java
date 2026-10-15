package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostManager;
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

/** Geisterbloecke blau einfaerben, damit man sie von echten Bloecken unterscheidet. Wirkt auch mit Shadern. */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {
    @Unique private boolean lifematic$ghost;

    @Inject(method = "renderModel", at = @At("HEAD"))
    private void lifematic$checkGhost(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        this.lifematic$ghost = GhostThread.isGhost(pos);
    }

    @Inject(method = "tintQuad", at = @At("TAIL"))
    private void lifematic$tintGhost(MutableQuadViewImpl quad, CallbackInfo ci) {
        if (!this.lifematic$ghost) return;
        for (int i = 0; i < 4; i++) {
            quad.setColor(i, ColorMixer.mulComponentWise(quad.baseColor(i), GhostManager.TINT));
        }
    }
}

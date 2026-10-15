package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostThread;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Einfaerbung ueber die Vertexfarbe (wirkt auch mit Shadern):
<<<<<<< HEAD
 * blau = fehlt noch, rot = falscher Block, orange = falsch ausgerichtet, violett = gehoert nicht hin.
=======
 * blau = fehlt noch (halbtransparent), rot = falscher Block, orange = falsch ausgerichtet, violett = gehoert nicht hin.
>>>>>>> 19e18d1 (Geisterbloecke halbtransparent darstellen)
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {
    @Unique private int lifematic$tint;
<<<<<<< HEAD

    @Inject(method = "renderModel", at = @At("HEAD"))
    private void lifematic$pickTint(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        this.lifematic$tint = GhostThread.tintFor(pos);
=======
    @Unique private boolean lifematic$ghost;

    @Inject(method = "renderModel", at = @At("HEAD"))
    private void lifematic$pickTint(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        this.lifematic$ghost = GhostThread.currentIsGhost();
        this.lifematic$tint = GhostThread.tintFor(pos);
    }

    /** Geisterbloecke kommen in Sodiums durchsichtigen Durchgang (sortiert, mit Alpha aus der Vertexfarbe). */
    @WrapOperation(method = "processQuad", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/DefaultMaterials;forChunkLayer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;"))
    private Material lifematic$ghostsTranslucent(ChunkSectionLayer layer, Operation<Material> original) {
        return this.lifematic$ghost ? DefaultMaterials.TRANSLUCENT : original.call(layer);
>>>>>>> 19e18d1 (Geisterbloecke halbtransparent darstellen)
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

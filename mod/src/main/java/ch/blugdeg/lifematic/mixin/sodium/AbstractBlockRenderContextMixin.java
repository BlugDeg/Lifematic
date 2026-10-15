package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostThread;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Flaechen zwischen zwei Geisterbloecken weglassen; echte Bloecke sehen nur die echte Welt. */
@Mixin(value = AbstractBlockRenderContext.class, remap = false)
public abstract class AbstractBlockRenderContextMixin {
    @WrapOperation(method = "shouldDrawSide", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/BlockAndTintGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState lifematic$ghostNeighbors(BlockAndTintGetter level, BlockPos pos, Operation<BlockState> original) {
        return GhostThread.neighborForCulling(pos, original.call(level, pos));
    }
}

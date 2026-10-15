package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostThread;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zeichnet an leeren Stellen den Geisterblock, ohne ihn in die Weltkopie zu schreiben. */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState lifematic$ghostInsteadOfAir(LevelSlice slice, int x, int y, int z, Operation<BlockState> original) {
        return GhostThread.substitute(x, y, z, original.call(slice, x, y, z));
    }

    /** Geisterbloecke verdecken fuer Sodiums Sichtbarkeitsberechnung nichts. */
    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z"))
    private boolean lifematic$ghostsDoNotOcclude(BlockState state, Operation<Boolean> original) {
        return !GhostThread.currentIsGhost() && original.call(state);
    }
}

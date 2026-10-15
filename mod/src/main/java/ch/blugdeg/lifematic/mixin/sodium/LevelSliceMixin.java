package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostHolder;
import ch.blugdeg.lifematic.client.ghost.GhostManager;
import ch.blugdeg.lifematic.client.ghost.GhostSlice;
import ch.blugdeg.lifematic.client.ghost.GhostThread;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Legt die Geisterbloecke in Sodiums Weltkopie, bevor ein Abschnitt gebaut wird. */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceMixin {
    @Shadow @Final private BlockState[][] blockArrays;

    /** Abschnitte, die in der echten Welt leer sind, trotzdem bauen, wenn dort Geisterbloecke stehen. */
    @WrapOperation(method = "prepare", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private static boolean lifematic$buildGhostOnlySections(LevelChunkSection section, Operation<Boolean> original,
                                                            @Local(argsOnly = true) SectionPos pos) {
        return original.call(section) && !GhostManager.hasGhosts(pos.x(), pos.y(), pos.z());
    }

    /** Render-Thread: Momentaufnahme der Geisterbloecke an den Bauauftrag haengen. */
    @Inject(method = "prepare", at = @At("RETURN"))
    private static void lifematic$attachGhosts(Level level, SectionPos pos, ClonedChunkSectionCache cache,
                                               CallbackInfoReturnable<ChunkRenderContext> cir) {
        ChunkRenderContext ctx = cir.getReturnValue();
        if (ctx != null) ((GhostHolder) ctx).lifematic$setGhosts(GhostSlice.capture(pos));
    }

    @Inject(method = "copyData", at = @At("HEAD"))
    private void lifematic$beginCopy(ChunkRenderContext context, CallbackInfo ci) {
        GhostThread.begin();
    }

    /** Worker-Thread: Geisterbloecke dort einsetzen, wo in der echten Welt Luft ist. */
    @Inject(method = "copySectionData", at = @At("TAIL"))
    private void lifematic$overlayGhosts(ChunkRenderContext context, int sectionIndex, CallbackInfo ci) {
        GhostSlice ghosts = ((GhostHolder) context).lifematic$getGhosts();
        if (ghosts == null) return;
        BlockState[] g = ghosts.sections[sectionIndex];
        if (g == null) return;
        BlockState[] blocks = this.blockArrays[sectionIndex];
        boolean center = sectionIndex == GhostSlice.CENTER;
        for (int i = 0; i < g.length; i++) {
            BlockState ghost = g[i];
            if (ghost == null) continue;
            BlockState real = blocks[i];
            if (real.isAir() || real.canBeReplaced()) {
                blocks[i] = ghost;
                if (center) GhostThread.mark(i);
            }
        }
    }
}

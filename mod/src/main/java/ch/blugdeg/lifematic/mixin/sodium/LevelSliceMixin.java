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
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Haengt die Geisterbloecke an jeden Bauauftrag und gibt sie dem Worker-Thread mit. */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceMixin {
    /** Abschnitte, die in der echten Welt leer sind, trotzdem bauen, wenn dort Geisterbloecke stehen. */
    @WrapOperation(method = "prepare", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private static boolean lifematic$buildGhostOnlySections(LevelChunkSection section, Operation<Boolean> original,
                                                            @Local(argsOnly = true) SectionPos pos) {
        return original.call(section) && !GhostManager.hasContent(pos.x(), pos.y(), pos.z());
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
        GhostThread.begin(((GhostHolder) context).lifematic$getGhosts());
    }
}

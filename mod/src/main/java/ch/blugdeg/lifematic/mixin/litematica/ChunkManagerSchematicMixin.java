package ch.blugdeg.lifematic.mixin.litematica;

import ch.blugdeg.lifematic.client.ghost.GhostManager;
import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entladene Schematic-Chunks: Geisterbloecke dort entfernen. */
@Mixin(value = ChunkManagerSchematic.class, remap = false)
public abstract class ChunkManagerSchematicMixin {
    @Inject(method = "unloadChunk", at = @At("HEAD"))
    private void lifematic$ghostsDirty(int chunkX, int chunkZ, CallbackInfo ci) {
        GhostManager.markColumnDirty(chunkX, chunkZ);
    }
}

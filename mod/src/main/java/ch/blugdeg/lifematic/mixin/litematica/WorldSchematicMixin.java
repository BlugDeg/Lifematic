package ch.blugdeg.lifematic.mixin.litematica;

import ch.blugdeg.lifematic.client.ghost.GhostManager;
import fi.dy.masa.litematica.world.WorldSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Litematica meldet geaenderte Schematic-Chunks (Laden, Verschieben, Ebenenwechsel). */
@Mixin(value = WorldSchematic.class, remap = false)
public abstract class WorldSchematicMixin {
    @Inject(method = "scheduleChunkRenders(IIZ)V", at = @At("HEAD"))
    private void lifematic$ghostsDirty(int chunkX, int chunkZ, boolean immediate, CallbackInfo ci) {
        GhostManager.markColumnDirty(chunkX, chunkZ);
    }
}

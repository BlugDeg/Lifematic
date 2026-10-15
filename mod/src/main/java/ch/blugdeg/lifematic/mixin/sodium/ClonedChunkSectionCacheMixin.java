package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostManager;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Echte Welt hat sich geaendert: Geisterbloecke dieses Abschnitts neu bestimmen. */
@Mixin(value = ClonedChunkSectionCache.class, remap = false)
public abstract class ClonedChunkSectionCacheMixin {
    @Inject(method = "invalidate", at = @At("HEAD"))
    private void lifematic$invalidateGhosts(long key, CallbackInfo ci) {
        GhostManager.invalidateSection(key);
    }
}

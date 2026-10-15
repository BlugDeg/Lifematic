package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostHolder;
import ch.blugdeg.lifematic.client.ghost.GhostSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ChunkRenderContext.class, remap = false)
public abstract class ChunkRenderContextMixin implements GhostHolder {
    @Unique private volatile GhostSlice lifematic$ghosts;

    @Override
    public GhostSlice lifematic$getGhosts() {
        return this.lifematic$ghosts;
    }

    @Override
    public void lifematic$setGhosts(GhostSlice slice) {
        this.lifematic$ghosts = slice;
    }
}

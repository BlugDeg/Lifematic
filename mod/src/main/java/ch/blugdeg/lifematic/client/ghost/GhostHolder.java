package ch.blugdeg.lifematic.client.ghost;

/** Wird per Mixin an Sodiums ChunkRenderContext gehaengt. */
public interface GhostHolder {
    GhostSlice lifematic$getGhosts();

    void lifematic$setGhosts(GhostSlice slice);
}

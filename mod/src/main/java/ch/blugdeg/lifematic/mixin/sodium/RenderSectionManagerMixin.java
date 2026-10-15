package ch.blugdeg.lifematic.mixin.sodium;

import ch.blugdeg.lifematic.client.ghost.GhostManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Neu geladene, leere Abschnitte mit Geisterbloecken nicht als «leer» abhaken, sondern bauen. */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
    @WrapOperation(method = "onSectionAdded", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private boolean lifematic$ghostSectionsAreNotEmpty(LevelChunkSection section, Operation<Boolean> original,
                                                       @Local(argsOnly = true, ordinal = 0) int x,
                                                       @Local(argsOnly = true, ordinal = 1) int y,
                                                       @Local(argsOnly = true, ordinal = 2) int z) {
        return original.call(section) && !GhostManager.hasContent(x, y, z);
    }
}

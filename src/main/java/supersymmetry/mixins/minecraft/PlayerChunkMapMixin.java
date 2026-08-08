package supersymmetry.mixins.minecraft;

import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.base.Predicate;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;

import supersymmetry.api.subworld.SubWorldRegistry;

@Mixin(PlayerChunkMap.class)
public abstract class PlayerChunkMapMixin {

    @Shadow
    @Final
    private WorldServer world;

    @Shadow
    @Final
    private List<PlayerChunkMapEntry> entries;

    private static final Predicate<EntityPlayerMP> NOT_SPECTATOR = new Predicate<EntityPlayerMP>() {

        @Override
        public boolean apply(@Nullable EntityPlayerMP p_apply_1_) {
            return p_apply_1_ != null && !p_apply_1_.isSpectator();
        }
    };

    @Inject(method = "getChunkIterator", at = @At("HEAD"), cancellable = true)
    private void susy$includePlotChunks(CallbackInfoReturnable<Iterator<Chunk>> cir) {
        List<Chunk> plotChunks = SubWorldRegistry.collectPlotChunks(this.world);
        if (plotChunks.isEmpty()) {
            return;
        }
        cir.setReturnValue(Iterators.concat(plotChunks.iterator(), vanillaChunkIterator()));
    }

    private Iterator<Chunk> vanillaChunkIterator() {
        final Iterator<PlayerChunkMapEntry> iterator = this.entries.iterator();
        return new AbstractIterator<Chunk>() {

            @Override
            protected Chunk computeNext() {
                while (true) {
                    if (iterator.hasNext()) {
                        PlayerChunkMapEntry playerchunkmapentry = iterator.next();
                        Chunk chunk = playerchunkmapentry.getChunk();

                        if (chunk == null) {
                            continue;
                        }

                        if (!chunk.isLightPopulated() && chunk.isTerrainPopulated()) {
                            return chunk;
                        }

                        if (!chunk.wasTicked()) {
                            return chunk;
                        }

                        if (!playerchunkmapentry.hasPlayerMatchingInRange(128.0D, NOT_SPECTATOR)) {
                            continue;
                        }

                        return chunk;
                    }

                    return (Chunk) this.endOfData();
                }
            }
        };
    }
}

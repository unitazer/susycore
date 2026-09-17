package supersymmetry.mixins.minecraft;

import java.util.Iterator;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.collect.Iterators;

import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.subworld.SubWorldPlot;
import supersymmetry.api.subworld.SubWorldRegistry;

@Mixin(PlayerChunkMap.class)
public abstract class PlayerChunkMapMixin {

    @Shadow
    @Final
    private WorldServer world;

    @Inject(method = "getChunkIterator", at = @At("RETURN"), cancellable = true)
    private void susy$includePlotChunks(CallbackInfoReturnable<Iterator<Chunk>> cir) {
        List<Chunk> plotChunks = SubWorldRegistry.collectPlotChunks(this.world);
        if (plotChunks.isEmpty()) {
            return;
        }
        cir.setReturnValue(Iterators.concat(plotChunks.iterator(), cir.getReturnValue()));
    }

    @Inject(method = "markBlockForUpdate", at = @At("HEAD"))
    private void susy$deliverPlotTileUpdates(BlockPos pos, CallbackInfo ci) {
        SubWorldPlot plot = SubWorldRegistry.find(this.world, pos.getX() >> 4, pos.getZ() >> 4);
        if (plot == null) {
            return;
        }
        PhysicsWorldEntity body = PhysicsWorldEntity.forPlot(plot);
        if (body == null) {
            return;
        }
        TileEntity tileEntity = this.world.getTileEntity(pos);
        if (tileEntity == null) {
            return;
        }
        SPacketUpdateTileEntity packet = tileEntity.getUpdatePacket();
        if (packet == null) {
            return;
        }
        for (EntityPlayerMP player : body.getTrackingPlayers()) {
            player.connection.sendPacket(packet);
        }
    }
}

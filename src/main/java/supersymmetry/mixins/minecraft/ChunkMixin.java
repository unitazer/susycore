package supersymmetry.mixins.minecraft;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import supersymmetry.api.phys.ChunkSyncManager;
import supersymmetry.api.subworld.SubWorldRegistry;
import supersymmetry.client.renderer.subworld.SubWorldChunkRenderer;
import supersymmetry.common.EventHandlers;

@Mixin(Chunk.class)
public class ChunkMixin {

    @Shadow
    @Final
    private World world;

    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void onSetBlockState(BlockPos pos, IBlockState state, CallbackInfoReturnable<IBlockState> cir) {
        IBlockState oldState = cir.getReturnValue();
        if (oldState == null || oldState == state) return;

        if (this.world.isRemote) {
            SubWorldChunkRenderer.markSectionDirty(this.world, pos);
            return;
        }

        ChunkSyncManager csm = EventHandlers.chunkSyncManagers.get(this.world);
        if (csm != null) csm.handleBlockChange(pos);

        if (SubWorldRegistry.find(this.world, this.x, this.z) != null) {
            WorldServer server = (WorldServer) this.world;
            int margin = server.getMinecraftServer().getPlayerList().getViewDistance() * 16 + 32;
            SPacketBlockChange packet = new SPacketBlockChange(this.world, pos);
            for (EntityPlayer player : this.world.playerEntities) {
                if (player instanceof EntityPlayerMP) {
                    EntityPlayerMP playerMP = (EntityPlayerMP) player;
                    if (playerMP.posX >= pos.getX() - margin && playerMP.posX <= pos.getX() + margin &&
                            playerMP.posY >= pos.getY() - margin && playerMP.posY <= pos.getY() + margin &&
                            playerMP.posZ >= pos.getZ() - margin && playerMP.posZ <= pos.getZ() + margin) {
                        playerMP.connection.sendPacket(packet);
                    }
                }
            }
        }
    }

    @Inject(method = "enqueueRelightChecks", at = @At("HEAD"), cancellable = true)
    private void susy$skipRelightChecksForPlotChunks(CallbackInfo ci) {
        if (SubWorldRegistry.find(this.world, this.x, this.z) != null) {
            ci.cancel();
        }
    }
}

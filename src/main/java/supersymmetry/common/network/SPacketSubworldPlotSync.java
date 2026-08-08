package supersymmetry.common.network;

import java.io.IOException;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import gregtech.api.network.IClientExecutor;
import gregtech.api.network.IPacket;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.PhysicsWorldEntity.BlockStateInfo;
import supersymmetry.api.phys.Quaternion;

public class SPacketSubworldPlotSync implements IPacket, IClientExecutor {

    private int entityId;
    private int originChunkX;
    private int originChunkZ;
    private int sizeChunksX;
    private int sizeChunksZ;
    private Quaternion rot;
    private Vec3d rp;
    private float[] size;
    private List<BlockStateInfo> blocks;

    public SPacketSubworldPlotSync() {}

    public SPacketSubworldPlotSync(PhysicsWorldEntity entity) {
        this.entityId = entity.getEntityId();
        if (entity.getPlot() != null) {
            this.originChunkX = entity.getPlot().getOriginChunkX();
            this.originChunkZ = entity.getPlot().getOriginChunkZ();
            this.sizeChunksX = entity.getPlot().getSizeChunksX();
            this.sizeChunksZ = entity.getPlot().getSizeChunksZ();
        }
        this.rot = entity.getRotation();
        this.rp = entity.getRotationPoint();
        this.size = entity.getPlotSize();
        this.blocks = entity.collectBlocks();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void executeClient(NetHandlerPlayClient handler) {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return;
        }
        Entity entity = world.getEntityByID(this.entityId);
        if (entity instanceof PhysicsWorldEntity) {
            ((PhysicsWorldEntity) entity).applyPlotSync(this.originChunkX, this.originChunkZ, this.sizeChunksX,
                    this.sizeChunksZ, this.blocks, this.rot, this.rp, this.size);
        }
    }

    @Override
    public void encode(PacketBuffer buf) {
        buf.writeInt(this.entityId);
        buf.writeInt(this.originChunkX);
        buf.writeInt(this.originChunkZ);
        buf.writeInt(this.sizeChunksX);
        buf.writeInt(this.sizeChunksZ);
        PhysicsWorldEntity.writePose(buf, this.rot, this.rp, this.size);
        PhysicsWorldEntity.writeBlocks(buf, this.blocks);
    }

    @Override
    public void decode(PacketBuffer buf) {
        try {
            this.entityId = buf.readInt();
            this.originChunkX = buf.readInt();
            this.originChunkZ = buf.readInt();
            this.sizeChunksX = buf.readInt();
            this.sizeChunksZ = buf.readInt();
            this.rot = PhysicsWorldEntity.readRotation(buf);
            this.rp = new Vec3d(buf.readFloat(), buf.readFloat(), buf.readFloat());
            this.size = new float[] { buf.readFloat(), buf.readFloat(), buf.readFloat() };
            this.blocks = PhysicsWorldEntity.readBlocks(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

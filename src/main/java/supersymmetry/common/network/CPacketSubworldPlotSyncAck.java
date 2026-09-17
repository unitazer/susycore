package supersymmetry.common.network;

import net.minecraft.entity.Entity;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.PacketBuffer;

import gregtech.api.network.IPacket;
import gregtech.api.network.IServerExecutor;
import supersymmetry.api.phys.PhysicsWorldEntity;

public class CPacketSubworldPlotSyncAck implements IPacket, IServerExecutor {

    private int entityId;

    public CPacketSubworldPlotSyncAck() {}

    public CPacketSubworldPlotSyncAck(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void executeServer(NetHandlerPlayServer handler) {
        Entity entity = handler.player.getEntityWorld().getEntityByID(this.entityId);
        if (entity instanceof PhysicsWorldEntity) {
            ((PhysicsWorldEntity) entity).resyncFormedMultiblocks();
        }
    }

    @Override
    public void encode(PacketBuffer buf) {
        buf.writeVarInt(this.entityId);
    }

    @Override
    public void decode(PacketBuffer buf) {
        this.entityId = buf.readVarInt();
    }
}

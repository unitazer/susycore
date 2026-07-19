package supersymmetry.common.network;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import gregtech.api.block.VariantActiveBlock;
import gregtech.api.capability.GregtechDataCodes;
import gregtech.api.network.IClientExecutor;
import gregtech.api.network.IPacket;
import io.netty.buffer.Unpooled;
import supersymmetry.api.phys.PhysicsWorldEntity;

public class SPacketPocketBlockUpdate implements IPacket, IClientExecutor {

    private int entityId;
    private BlockPos pos;
    private int blockStateId;
    private NBTTagCompound tileNbt;
    private NBTTagCompound teUpdateNbt;

    public SPacketPocketBlockUpdate() {}

    public SPacketPocketBlockUpdate(int entityId, BlockPos pos, IBlockState state,
                                    NBTTagCompound tileNbt, NBTTagCompound teUpdateNbt) {
        this.entityId = entityId;
        this.pos = pos;
        this.blockStateId = Block.getStateId(state);
        this.tileNbt = tileNbt;
        this.teUpdateNbt = teUpdateNbt;
    }

    @Override
    public void encode(PacketBuffer buf) {
        buf.writeVarInt(entityId);
        buf.writeBlockPos(pos);
        buf.writeVarInt(blockStateId);
        buf.writeCompoundTag(tileNbt);
        buf.writeBoolean(teUpdateNbt != null);
        if (teUpdateNbt != null) buf.writeCompoundTag(teUpdateNbt);
    }

    @Override
    public void decode(PacketBuffer buf) {
        entityId = buf.readVarInt();
        pos = buf.readBlockPos();
        blockStateId = buf.readVarInt();
        try {
            tileNbt = buf.readCompoundTag();
        } catch (IOException e) {
            tileNbt = null;
        }
        if (buf.readBoolean()) {
            try {
                teUpdateNbt = buf.readCompoundTag();
            } catch (IOException e) {
                teUpdateNbt = null;
            }
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void executeClient(NetHandlerPlayClient handler) {
        var world = Minecraft.getMinecraft().world;
        if (world == null) return;
        Entity raw = world.getEntityByID(entityId);
        if (!(raw instanceof PhysicsWorldEntity pwe)) return;

        var list = pwe.getClientBlocks();
        if (list != null) {
            var state = Block.getStateById(blockStateId);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).pos.equals(pos)) {
                    list.set(i, new PhysicsWorldEntity.BlockStateInfo(pos, state, tileNbt));
                    break;
                }
            }
        }

        if (teUpdateNbt == null) return;
        int realDim = world.provider.getDimension();
        var entryList = teUpdateNbt.getTagList("d", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < entryList.tagCount(); i++) {
            NBTTagCompound entry = entryList.getCompoundTagAt(i);
            for (String key : entry.getKeySet()) {
                int disc = Integer.parseInt(key);
                byte[] dat = entry.getByteArray(key);
                var buf = new PacketBuffer(Unpooled.wrappedBuffer(dat));
                if (disc == GregtechDataCodes.VARIANT_RENDER_UPDATE) {
                    buf.readInt();
                    boolean isActive = buf.readBoolean();
                    int count = buf.readInt();
                    for (int j = 0; j < count; j++) {
                        VariantActiveBlock.setBlockActive(realDim, buf.readBlockPos(), isActive);
                    }
                }
            }
        }
    }
}

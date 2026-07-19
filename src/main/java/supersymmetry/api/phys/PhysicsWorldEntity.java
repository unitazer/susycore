package supersymmetry.api.phys;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;

import io.netty.buffer.ByteBuf;

public class PhysicsWorldEntity extends Entity implements IEntityAdditionalSpawnData {

    public static class BlockStateInfo {

        public final BlockPos pos;
        public final IBlockState state;
        @Nullable
        public final NBTTagCompound tileData;

        public BlockStateInfo(BlockPos pos, IBlockState state) {
            this(pos, state, null);
        }

        public BlockStateInfo(BlockPos pos, IBlockState state, @Nullable NBTTagCompound tileData) {
            this.pos = pos;
            this.state = state;
            this.tileData = tileData;
        }
    }

    private PocketWorld pocketWorld;
    private List<BlockStateInfo> clientBlocks;

    public PhysicsWorldEntity(World world) {
        super(world);
    }

    @Override
    protected void entityInit() {}

    public List<BlockStateInfo> getClientBlocks() {
        return clientBlocks;
    }

    public PocketWorld getPocketWorld() {
        return pocketWorld;
    }

    public void setPocketWorld(PocketWorld pocketWorld) {
        this.pocketWorld = pocketWorld;
    }

    private void computeBounds(List<BlockStateInfo> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockStateInfo info : blocks) {
            minX = Math.min(minX, info.pos.getX());
            minY = Math.min(minY, info.pos.getY());
            maxX = Math.max(maxX, info.pos.getX());
            maxY = Math.max(maxY, info.pos.getY());
            maxZ = Math.max(maxZ, info.pos.getZ());
            minZ = Math.min(minZ, info.pos.getZ());
        }
        double x0 = minX;
        double y0 = minY - 127;
        double z0 = minZ;
        double x1 = maxX + 1;
        double y1 = maxY - 127 + 1;
        double z1 = maxZ + 1;
        setEntityBoundingBox(new AxisAlignedBB(
                posX + x0, posY + y0, posZ + z0,
                posX + x1, posY + y1, posZ + z1));
    }

    public void updatePocketBounds() {
        if (pocketWorld == null) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean found = false;
        for (Chunk chunk : pocketWorld.getLoadedChunks().values()) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockPos pos = new BlockPos(chunk.x * 16 + x, y, chunk.z * 16 + z);
                        if (pocketWorld.getBlockState(pos).getBlock() == Blocks.AIR) continue;
                        found = true;
                        minX = Math.min(minX, pos.getX());
                        minY = Math.min(minY, pos.getY());
                        minZ = Math.min(minZ, pos.getZ());
                        maxX = Math.max(maxX, pos.getX());
                        maxY = Math.max(maxY, pos.getY());
                        maxZ = Math.max(maxZ, pos.getZ());
                    }
                }
            }
        }
        if (found) {
            double x0 = minX;
            double y0 = minY - 127;
            double z0 = minZ;
            double x1 = maxX + 1;
            double y1 = maxY - 127 + 1;
            double z1 = maxZ + 1;
            setEntityBoundingBox(new AxisAlignedBB(
                    posX + x0, posY + y0, posZ + z0,
                    posX + x1, posY + y1, posZ + z1));
        }
    }

    @Override
    public void setPosition(double x, double y, double z) {
        super.setPosition(x, y, z);
        if (clientBlocks != null) {
            computeBounds(clientBlocks);
        } else if (pocketWorld != null) {
            updatePocketBounds();
        }
    }

    @Override
    public void onEntityUpdate() {
        if (world.isRemote) return;
        if (pocketWorld != null) {
            pocketWorld.tick();
        }
    }

    @Override
    public void onAddedToWorld() {}

    @Override
    public void setDead() {
        if (pocketWorld != null) {
            pocketWorld.destroy();
            pocketWorld = null;
        }
        isDead = true;
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
    }

    @Override
    public void writeSpawnData(ByteBuf buf) {
        List<BlockStateInfo> blocks = clientBlocks != null ? clientBlocks : writeBlocksToNbt();
        PacketBuffer pb = new PacketBuffer(buf);
        pb.writeInt(blocks.size());
        for (BlockStateInfo info : blocks) {
            pb.writeInt(info.pos.getX());
            pb.writeInt(info.pos.getY());
            pb.writeInt(info.pos.getZ());
            pb.writeInt(Block.getStateId(info.state));
            pb.writeCompoundTag(info.tileData);
        }
    }

    @Override
    public void readSpawnData(ByteBuf buf) {
        try {
            PacketBuffer pb = new PacketBuffer(buf);
            int count = pb.readInt();
            ArrayList<BlockStateInfo> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int x = pb.readInt();
                int y = pb.readInt();
                int z = pb.readInt();
                int blockId = pb.readInt();
                NBTTagCompound tileData = pb.readCompoundTag();
                list.add(new BlockStateInfo(new BlockPos(x, y, z), Block.getStateById(blockId), tileData));
            }
            clientBlocks = list;
            setSize(1, 1);
            computeBounds(list);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<BlockStateInfo> writeBlocksToNbt() {
        ArrayList<BlockStateInfo> list = new ArrayList<>();
        if (pocketWorld == null) return list;
        for (Chunk chunk : pocketWorld.getLoadedChunks().values()) {
            int cx = chunk.x;
            int cz = chunk.z;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockPos pos = new BlockPos(cx * 16 + x, y, cz * 16 + z);
                        IBlockState state = pocketWorld.getBlockState(pos);
                        if (state.getBlock() == Blocks.AIR) continue;
                        TileEntity te = pocketWorld.getTileEntity(pos);
                        NBTTagCompound tileData = te != null ? te.serializeNBT() : null;
                        list.add(new BlockStateInfo(pos, state, tileData));
                    }
                }
            }
        }
        return list;
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        if (pocketWorld != null) {
            NBTTagList blockList = new NBTTagList();
            for (Chunk chunk : pocketWorld.getLoadedChunks().values()) {
                int cx = chunk.x;
                int cz = chunk.z;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 256; y++) {
                        for (int z = 0; z < 16; z++) {
                            int wx = cx * 16 + x;
                            int wz = cz * 16 + z;
                            BlockPos pos = new BlockPos(wx, y, wz);
                            IBlockState state = pocketWorld.getBlockState(pos);
                            if (state.getBlock() == Blocks.AIR) continue;

                            NBTTagCompound entry = new NBTTagCompound();
                            entry.setInteger("x", wx);
                            entry.setShort("y", (short) y);
                            entry.setInteger("z", wz);
                            entry.setInteger("b", Block.getStateId(state));

                            TileEntity te = pocketWorld.getTileEntity(pos);
                            if (te != null) {
                                entry.setTag("te", te.serializeNBT());
                            }
                            blockList.appendTag(entry);
                        }
                    }
                }
            }
            compound.setTag("pocketBlocks", blockList);
        }
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        clientBlocks = null;
        if (!compound.hasKey("pocketBlocks", Constants.NBT.TAG_LIST)) return;

        NBTTagList blockList = compound.getTagList("pocketBlocks", Constants.NBT.TAG_COMPOUND);

        if (world.isRemote) {
            ArrayList<BlockStateInfo> list = new ArrayList<>(blockList.tagCount());
            for (int i = 0; i < blockList.tagCount(); i++) {
                NBTTagCompound entry = blockList.getCompoundTagAt(i);
                int x = entry.getInteger("x");
                int y = entry.getShort("y");
                int z = entry.getInteger("z");
                int blockId = entry.getInteger("b");
                NBTTagCompound tileData = entry.hasKey("te", Constants.NBT.TAG_COMPOUND) ? entry.getCompoundTag("te") :
                        null;
                list.add(new BlockStateInfo(new BlockPos(x, y, z), Block.getStateById(blockId), tileData));
            }
            clientBlocks = list;
            setSize(1, 1);
            computeBounds(list);
        } else {
            PocketWorld pw = new PocketWorld(this);
            for (int i = 0; i < blockList.tagCount(); i++) {
                NBTTagCompound entry = blockList.getCompoundTagAt(i);
                int x = entry.getInteger("x");
                int y = entry.getShort("y");
                int z = entry.getInteger("z");
                int blockId = entry.getInteger("b");
                IBlockState state = Block.getStateById(blockId);

                BlockPos pos = new BlockPos(x, y, z);
                pw.setBlockState(pos, state, 2);

                if (entry.hasKey("te", Constants.NBT.TAG_COMPOUND)) {
                    NBTTagCompound teTag = entry.getCompoundTag("te");
                    TileEntity te = pw.getTileEntity(pos);
                    if (te != null) {
                        te.deserializeNBT(teTag);
                    }
                }
            }
            updatePocketBounds();
        }
    }
}

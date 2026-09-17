package supersymmetry.api.phys;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import org.jetbrains.annotations.Nullable;

public final class BlockStateInfo {

    public final BlockPos pos;
    public final IBlockState state;
    @Nullable public final NBTTagCompound tileData;
    @Nullable public final NBTTagCompound extra;

    public BlockStateInfo(BlockPos pos, IBlockState state) {
        this(pos, state, null, null);
    }

    public BlockStateInfo(BlockPos pos, IBlockState state, @Nullable NBTTagCompound tileData) {
        this(pos, state, tileData, null);
    }

    public BlockStateInfo(BlockPos pos, IBlockState state, @Nullable NBTTagCompound tileData,
                          @Nullable NBTTagCompound extra) {
        this.pos = pos;
        this.state = state;
        this.tileData = tileData;
        this.extra = extra;
    }

    public static void writeBlocks(PacketBuffer buf, List<BlockStateInfo> blocks) {
        buf.writeVarInt(blocks.size());
        for (BlockStateInfo info : blocks) {
            buf.writeVarInt(info.pos.getX());
            buf.writeVarInt(info.pos.getY());
            buf.writeVarInt(info.pos.getZ());
            buf.writeVarInt(Block.getStateId(info.state));
            buf.writeCompoundTag(info.tileData);
            buf.writeCompoundTag(info.extra);
        }
    }

    public static List<BlockStateInfo> readBlocks(PacketBuffer buf) throws IOException {
        int count = buf.readVarInt();
        List<BlockStateInfo> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            int blockId = buf.readVarInt();
            NBTTagCompound tileData = buf.readCompoundTag();
            NBTTagCompound extra = buf.readCompoundTag();
            blocks.add(new BlockStateInfo(new BlockPos(x, y, z), Block.getStateById(blockId), tileData, extra));
        }
        return blocks;
    }

    public static void writeBlocksToNBT(NBTTagCompound tag, List<BlockStateInfo> blocks) {
        NBTTagList blockList = new NBTTagList();
        for (BlockStateInfo info : blocks) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("x", info.pos.getX());
            entry.setShort("y", (short) info.pos.getY());
            entry.setInteger("z", info.pos.getZ());
            entry.setInteger("b", Block.getStateId(info.state));
            if (info.tileData != null) {
                entry.setTag("te", info.tileData);
            }
            if (info.extra != null) {
                entry.setTag("e", info.extra);
            }
            blockList.appendTag(entry);
        }
        tag.setTag("plotBlocks", blockList);
    }

    public static List<BlockStateInfo> readBlocksFromNBT(NBTTagCompound tag) {
        List<BlockStateInfo> blocks = new ArrayList<>();
        if (tag.hasKey("plotBlocks", Constants.NBT.TAG_LIST)) {
            NBTTagList blockList = tag.getTagList("plotBlocks", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < blockList.tagCount(); i++) {
                NBTTagCompound entry = blockList.getCompoundTagAt(i);
                int x = entry.getInteger("x");
                int y = entry.getShort("y");
                int z = entry.getInteger("z");
                int blockId = entry.getInteger("b");
                NBTTagCompound tileData = entry.hasKey("te", Constants.NBT.TAG_COMPOUND) ? entry.getCompoundTag("te") :
                        null;
                NBTTagCompound extra = entry.hasKey("e", Constants.NBT.TAG_COMPOUND) ? entry.getCompoundTag("e") : null;
                blocks.add(new BlockStateInfo(new BlockPos(x, y, z), Block.getStateById(blockId), tileData, extra));
            }
        }
        return blocks;
    }
}

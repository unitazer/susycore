package supersymmetry.api.phys;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import org.jetbrains.annotations.Nullable;

import supersymmetry.mixins.minecraft.NBTTagLongArrayAccessor;

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
        long[] positions = new long[blocks.size()];
        int[] stateIds = new int[blocks.size()];
        NBTTagList tagged = new NBTTagList();
        for (int i = 0; i < blocks.size(); i++) {
            BlockStateInfo info = blocks.get(i);
            positions[i] = info.pos.toLong();
            stateIds[i] = Block.getStateId(info.state);
            if (info.tileData == null && info.extra == null) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("i", i);
            if (info.tileData != null) {
                entry.setTag("te", info.tileData);
            }
            if (info.extra != null) {
                entry.setTag("e", info.extra);
            }
            tagged.appendTag(entry);
        }
        tag.setTag("plotBlocks", new NBTTagLongArray(positions));
        tag.setIntArray("plotBlockStates", stateIds);
        if (tagged.tagCount() > 0) {
            tag.setTag("plotBlockTags", tagged);
        }
    }

    public static List<BlockStateInfo> readBlocksFromNBT(NBTTagCompound tag) {
        int format = tag.getTagId("plotBlocks");
        if (format == Constants.NBT.TAG_LONG_ARRAY) {
            return readPackedBlocks(((NBTTagLongArrayAccessor) tag.getTag("plotBlocks")).getData(), tag);
        }
        if (format != Constants.NBT.TAG_LIST) {
            return new ArrayList<>();
        }
        NBTTagList blockList = (NBTTagList) tag.getTag("plotBlocks");
        return blockList.getTagType() == Constants.NBT.TAG_LONG ?
                readPackedBlocks(longsFromList(blockList), tag) : readLegacyBlocks(blockList);
    }

    private static long[] longsFromList(NBTTagList list) {
        long[] values = new long[list.tagCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = ((NBTTagLong) list.get(i)).getLong();
        }
        return values;
    }

    private static List<BlockStateInfo> readPackedBlocks(long[] positions, NBTTagCompound tag) {
        int count = positions.length;
        int[] stateIds = tag.getIntArray("plotBlockStates");
        List<BlockStateInfo> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            blocks.add(new BlockStateInfo(BlockPos.fromLong(positions[i]), Block.getStateById(stateIds[i])));
        }
        if (!tag.hasKey("plotBlockTags", Constants.NBT.TAG_LIST)) {
            return blocks;
        }
        NBTTagList tagged = tag.getTagList("plotBlockTags", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < tagged.tagCount(); i++) {
            NBTTagCompound entry = tagged.getCompoundTagAt(i);
            int index = entry.getInteger("i");
            if (index < 0 || index >= blocks.size()) continue;
            BlockStateInfo info = blocks.get(index);
            NBTTagCompound tileData = entry.hasKey("te", Constants.NBT.TAG_COMPOUND) ? entry.getCompoundTag("te") :
                    null;
            NBTTagCompound extra = entry.hasKey("e", Constants.NBT.TAG_COMPOUND) ? entry.getCompoundTag("e") : null;
            blocks.set(index, new BlockStateInfo(info.pos, info.state, tileData, extra));
        }
        return blocks;
    }

    private static List<BlockStateInfo> readLegacyBlocks(NBTTagList blockList) {
        List<BlockStateInfo> blocks = new ArrayList<>();
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
        return blocks;
    }
}

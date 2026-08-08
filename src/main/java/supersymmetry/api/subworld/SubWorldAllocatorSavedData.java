package supersymmetry.api.subworld;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

public class SubWorldAllocatorSavedData extends WorldSavedData {

    public static final String DATA_NAME = "susy_subworld_allocator";

    private final List<SubWorldAllocator.Rect> allocated = new ArrayList<>();

    public SubWorldAllocatorSavedData() {
        super(DATA_NAME);
    }

    public SubWorldAllocatorSavedData(String name) {
        super(name);
    }

    public static SubWorldAllocatorSavedData get(WorldServer world) {
        MapStorage storage = world.getMapStorage();
        SubWorldAllocatorSavedData data = (SubWorldAllocatorSavedData) storage
                .getOrLoadData(SubWorldAllocatorSavedData.class, DATA_NAME);
        if (data == null) {
            data = new SubWorldAllocatorSavedData();
            storage.setData(DATA_NAME, data);
            data.markDirty();
        }
        return data;
    }

    public List<SubWorldAllocator.Rect> getAllocated() {
        return this.allocated;
    }

    public void setAllocated(List<SubWorldAllocator.Rect> rects) {
        this.allocated.clear();
        for (SubWorldAllocator.Rect rect : rects) {
            this.allocated.add(rect.copy());
        }
        this.markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        this.allocated.clear();
        if (nbt.hasKey("rects", Constants.NBT.TAG_LIST)) {
            NBTTagList list = nbt.getTagList("rects", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound entry = list.getCompoundTagAt(i);
                this.allocated.add(new SubWorldAllocator.Rect(entry.getInteger("x"), entry.getInteger("z"),
                        entry.getInteger("w"), entry.getInteger("h")));
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        NBTTagList list = new NBTTagList();
        for (SubWorldAllocator.Rect rect : this.allocated) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("x", rect.x);
            entry.setInteger("z", rect.z);
            entry.setInteger("w", rect.w);
            entry.setInteger("h", rect.h);
            list.appendTag(entry);
        }
        compound.setTag("rects", list);
        return compound;
    }
}

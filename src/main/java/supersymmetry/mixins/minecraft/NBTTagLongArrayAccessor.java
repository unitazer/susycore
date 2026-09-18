package supersymmetry.mixins.minecraft;

import net.minecraft.nbt.NBTTagLongArray;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NBTTagLongArray.class)
public interface NBTTagLongArrayAccessor {

    @Accessor
    long[] getData();
}

package supersymmetry.api.phys;

import java.io.IOException;

import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializer;

public final class QuaternionDataSerializer implements DataSerializer<Quaternion> {

    public static final QuaternionDataSerializer INSTANCE = new QuaternionDataSerializer();

    private QuaternionDataSerializer() {}

    @Override
    public void write(PacketBuffer buf, Quaternion value) {
        buf.writeFloat((float) value.getX());
        buf.writeFloat((float) value.getY());
        buf.writeFloat((float) value.getZ());
        buf.writeFloat((float) value.getW());
    }

    @Override
    public Quaternion read(PacketBuffer buf) throws IOException {
        float x = buf.readFloat();
        float y = buf.readFloat();
        float z = buf.readFloat();
        float w = buf.readFloat();
        return new Quaternion(w, x, y, z);
    }

    @Override
    public DataParameter<Quaternion> createKey(int id) {
        return new DataParameter<>(id, this);
    }

    @Override
    public Quaternion copyValue(Quaternion value) {
        return value;
    }
}

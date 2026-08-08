package supersymmetry.mixins.minecraft;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import supersymmetry.api.subworld.SubWorldAllocatorSavedData;
import supersymmetry.api.subworld.SubWorldContainer;
import supersymmetry.api.subworld.SubWorldContainerHolder;

@Mixin(World.class)
public abstract class WorldMixin implements SubWorldContainerHolder {

    @Unique
    private volatile SubWorldContainer susy$subWorldContainer;

    @Override
    public SubWorldContainer susy$getSubWorldContainer() {
        SubWorldContainer container = this.susy$subWorldContainer;
        if (container == null) {
            synchronized (this) {
                container = this.susy$subWorldContainer;
                if (container == null) {
                    container = new SubWorldContainer((World) (Object) this);
                    if (!((World) (Object) this).isRemote) {
                        container.attachSavedData(
                                SubWorldAllocatorSavedData.get((WorldServer) (World) (Object) this));
                    }
                    this.susy$subWorldContainer = container;
                }
            }
        }
        return container;
    }

    @Inject(method = "updateEntities", at = @At("HEAD"))
    private void susy$tickContainer(CallbackInfo ci) {
        SubWorldContainer container = this.susy$subWorldContainer;
        if (container != null) {
            container.tick();
        }
    }
}

package supersymmetry.client.renderer.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import supersymmetry.Supersymmetry;

@Mod.EventBusSubscriber(modid = Supersymmetry.MODID)
public class PhysicsDebugRenderer {

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) return;

        double dx = view.lastTickPosX + (view.posX - view.lastTickPosX) * event.getPartialTicks();
        double dy = view.lastTickPosY + (view.posY - view.lastTickPosY) * event.getPartialTicks();
        double dz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * event.getPartialTicks();

        GlStateManager.pushMatrix();
        GlStateManager.depthMask(false);
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.translate(-dx, -dy, -dz);
        supersymmetry.common.Native.frametest();
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(true);
        GlStateManager.popMatrix();
    }
}

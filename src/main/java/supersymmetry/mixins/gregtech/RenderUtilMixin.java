package supersymmetry.mixins.gregtech;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gregtech.client.utils.RenderUtil;

@Mixin(value = RenderUtil.class, remap = false)
public class RenderUtilMixin {

    @Inject(method = "hookDepthBuffer", at = @At("HEAD"), cancellable = true)
    private static void susy$hookDepthAttachment(Framebuffer fbo, int depthBuffer, CallbackInfo ci) {
        if (depthBuffer >= 0) return;
        ci.cancel();

        if (fbo == null || !GLContext.getCapabilities().GL_ARB_framebuffer_object) return;

        Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
        if (main == null) return;

        int last = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, main.framebufferObject);

        int type = ARBFramebufferObject.glGetFramebufferAttachmentParameteri(OpenGlHelper.GL_FRAMEBUFFER,
                ARBFramebufferObject.GL_DEPTH_ATTACHMENT,
                ARBFramebufferObject.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name = ARBFramebufferObject.glGetFramebufferAttachmentParameteri(OpenGlHelper.GL_FRAMEBUFFER,
                ARBFramebufferObject.GL_DEPTH_ATTACHMENT,
                ARBFramebufferObject.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, last);

        if (name <= 0) return;
        if (type == GL11.GL_TEXTURE) {
            RenderUtil.hookDepthTexture(fbo, name);
        } else if (type == OpenGlHelper.GL_RENDERBUFFER) {
            RenderUtil.hookDepthBuffer(fbo, name);
        }
    }
}

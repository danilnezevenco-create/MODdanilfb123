package com.danifb123.danilfb123mod.client.snow;

import com.danifb123.danilfb123mod.Danilfb123Mod;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/** Регистрация шейдера снега (assets/danilfb123mod/shaders/core/snow.*). */
@EventBusSubscriber(modid = Danilfb123Mod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SnowShaders {
    private static ShaderInstance snow;

    private SnowShaders() {}

    public static ShaderInstance snow() { return snow; }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(Danilfb123Mod.MODID, "snow"),
                        DefaultVertexFormat.POSITION),
                shader -> snow = shader);
    }
}

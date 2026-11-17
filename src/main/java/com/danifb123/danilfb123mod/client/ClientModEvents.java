package com.danifb123.danilfb123mod.client;

import com.danifb123.danilfb123mod.Danilfb123Mod;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** События мод-шины (только клиент). */
@EventBusSubscriber(modid = Danilfb123Mod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // F3+T перезагружает ресурсы: сбрасываем кэш модели, она перечитается из bbmodel
        event.registerReloadListener((ResourceManagerReloadListener) manager -> PlayerModelHolder.reset());
    }
}

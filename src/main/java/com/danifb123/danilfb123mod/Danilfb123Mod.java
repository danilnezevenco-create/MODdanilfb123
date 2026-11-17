package com.danifb123.danilfb123mod;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Главный класс мода. Автор: danifb123.
 * Пока здесь только заглушка: вся работа этапа 1 происходит на клиенте (пакет client).
 */
@Mod(Danilfb123Mod.MODID)
public class Danilfb123Mod {
    public static final String MODID = "danilfb123mod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Danilfb123Mod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("danilfb123mod загружен");
    }
}

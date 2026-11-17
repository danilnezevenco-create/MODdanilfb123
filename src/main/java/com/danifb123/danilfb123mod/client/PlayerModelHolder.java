package com.danifb123.danilfb123mod.client;

import com.danifb123.danilfb123mod.Danilfb123Mod;
import com.danifb123.danilfb123mod.client.bb.BbModel;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;

/** Лениво загружает и кэширует модель игрока из .bbmodel. */
public final class PlayerModelHolder {
    public static final ResourceLocation MODEL_FILE =
            ResourceLocation.fromNamespaceAndPath(Danilfb123Mod.MODID, "bbmodels/player.bbmodel");
    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Danilfb123Mod.MODID, "textures/entity/player.png");

    private static BbModel cached;
    private static boolean failed;

    private PlayerModelHolder() {}

    public static synchronized void reset() {
        cached = null;
        failed = false;
        PlayerPoseController.clear();
    }

    /** Возвращает модель или null, если загрузка не удалась (тогда рисуется ванильный игрок). */
    public static synchronized BbModel get() {
        if (cached != null) return cached;
        if (failed) return null;
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(MODEL_FILE)) {
            cached = BbModel.load(in);
            Danilfb123Mod.LOGGER.info("Модель игрока загружена: {} костей, анимации: {}",
                    cached.bones.size(), cached.animations.keySet());
        } catch (Exception e) {
            failed = true;
            Danilfb123Mod.LOGGER.error("Не удалось загрузить {}", MODEL_FILE, e);
        }
        return cached;
    }
}

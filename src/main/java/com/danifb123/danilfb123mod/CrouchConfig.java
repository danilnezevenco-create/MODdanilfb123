package com.danifb123.danilfb123mod;

import net.minecraft.world.entity.EntityDimensions;

/** Размеры присяда мода. Используются и клиентом, и сервером (хитбокс должен совпадать). */
public final class CrouchConfig {
    /** Ширина хитбокса. */
    public static final float WIDTH = 0.6f;
    /** Высота хитбокса в присяде (в ванили 1.5). */
    public static final float HEIGHT = 1.3f;
    /** Высота глаз (камеры) в присяде (в ванили 1.27). */
    public static final float EYE_HEIGHT = 1.15f;

    public static final EntityDimensions DIMENSIONS = EntityDimensions.scalable(WIDTH, HEIGHT).withEyeHeight(EYE_HEIGHT);

    private CrouchConfig() {}
}
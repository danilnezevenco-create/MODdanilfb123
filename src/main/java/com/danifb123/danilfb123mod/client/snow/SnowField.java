package com.danifb123.danilfb123mod.client.snow;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;

/**
 * Карта деформаций снега вокруг камеры. Хранится в текстуре RGBA:
 *   R = насколько снег вдавлен (0..255 -> доля толщины слоя), G = валик выдавленного снега по краям.
 * Карта зациклена (тороидальная): окно SIZE текселей едет за камерой, вошедшие строки очищаются.
 * 1 тексель = 1/16 блока (около 6 см).
 */
public final class SnowField {
    public static final int SIZE = 1024;
    public static final int CELLS_PER_BLOCK = 16;
    public static final float WINDOW_BLOCKS = (float) SIZE / CELLS_PER_BLOCK; // 64
    public static final float SNOW_DEPTH = 0.25f;     // толщина слоя снега над землёй, блоков
    public static final float RIM_MAX = 0.04f;        // максимальная высота валика, блоков
    public static final float MAX_COMPRESSION = 0.92f; // не до самой земли, чтобы не было z-fighting

    private final DynamicTexture texture;
    private final NativeImage image;
    private int centerX, centerZ;
    private boolean initialized;
    private boolean dirty = true;

    public SnowField() {
        texture = new DynamicTexture(SIZE, SIZE, true); // true = обнулить память
        image = texture.getPixels();
        upload();
    }

    public int textureId() { return texture.getId(); }

    public void close() { texture.close(); }

    public void clearAll() {
        image.fillRect(0, 0, SIZE, SIZE, 0);
        dirty = true;
    }

    private void upload() {
        texture.upload();
        texture.setFilter(true, false); // upload сбрасывает фильтр на NEAREST, возвращаем линейный
        dirty = false;
    }

    public void uploadIfDirty() {
        if (dirty) upload();
    }

    /** Сдвигает окно карты за камерой и очищает ячейки, которые вошли в окно с другой стороны. */
    public void recenter(double wx, double wz) {
        int cx = Mth.floor(wx * CELLS_PER_BLOCK);
        int cz = Mth.floor(wz * CELLS_PER_BLOCK);
        if (!initialized) {
            centerX = cx; centerZ = cz; initialized = true;
            return;
        }
        int dx = cx - centerX, dz = cz - centerZ;
        if (dx == 0 && dz == 0) return;

        if (Math.abs(dx) >= SIZE || Math.abs(dz) >= SIZE) {
            image.fillRect(0, 0, SIZE, SIZE, 0);
        } else {
            if (dx > 0) for (int c = centerX + SIZE / 2; c < cx + SIZE / 2; c++) clearColumn(c);
            else if (dx < 0) for (int c = cx - SIZE / 2; c < centerX - SIZE / 2; c++) clearColumn(c);
            if (dz > 0) for (int c = centerZ + SIZE / 2; c < cz + SIZE / 2; c++) clearRow(c);
            else if (dz < 0) for (int c = cz - SIZE / 2; c < centerZ - SIZE / 2; c++) clearRow(c);
        }
        centerX = cx; centerZ = cz;
        dirty = true;
    }

    private void clearColumn(int worldCell) { image.fillRect(Math.floorMod(worldCell, SIZE), 0, 1, SIZE, 0); }
    private void clearRow(int worldCell) { image.fillRect(0, Math.floorMod(worldCell, SIZE), SIZE, 1, 0); }

    /**
     * Вдавливает отпечаток ступни. yaw в радианах, "вперёд" = (-sin yaw, cos yaw).
     * pressure 0..1 (доля слоя, которую продавили), scale масштабирует размер отпечатка.
     */
    public void stampFoot(double wx, double wz, float yaw, float pressure, float scale) {
        float a = 0.12f * scale; // половина ширины (с запасом: снег вокруг ноги осыпается)
        float b = 0.24f * scale; // половина длины
        float reach = Math.max(a, b) * 1.9f;
        float p = Math.min(pressure, MAX_COMPRESSION);
        float cos = Mth.cos(yaw), sin = Mth.sin(yaw);

        int minX = Mth.floor((wx - reach) * CELLS_PER_BLOCK), maxX = Mth.floor((wx + reach) * CELLS_PER_BLOCK);
        int minZ = Mth.floor((wz - reach) * CELLS_PER_BLOCK), maxZ = Mth.floor((wz + reach) * CELLS_PER_BLOCK);

        for (int cz = minZ; cz <= maxZ; cz++) {
            for (int cx = minX; cx <= maxX; cx++) {
                if (Math.abs(cx - centerX) > SIZE / 2 - 8 || Math.abs(cz - centerZ) > SIZE / 2 - 8) continue;

                double dx = (cx + 0.5) / CELLS_PER_BLOCK - wx;
                double dz = (cz + 0.5) / CELLS_PER_BLOCK - wz;
                float u = (float) (dx * cos + dz * sin);   // поперёк стопы
                float v = (float) (-dx * sin + dz * cos);  // вдоль стопы
                float e = Mth.sqrt((u / a) * (u / a) + (v / b) * (v / b));
                if (e > 1.9f) continue;

                int tx = Math.floorMod(cx, SIZE), tz = Math.floorMod(cz, SIZE);
                int px = image.getPixelRGBA(tx, tz);
                int r = px & 0xFF, g = (px >> 8) & 0xFF;

                if (e <= 1f) {
                    float t = Mth.clamp((1f - e) / 0.35f, 0f, 1f);
                    t = t * t * (3f - 2f * t);
                    int nr = Math.round(p * t * 255f);
                    if (nr > 0) {
                        if (nr > r) r = nr;
                        g = 0; // валик внутри нового следа сминается
                    }
                } else {
                    float k = 1f - (e - 1f) / 0.9f;
                    k *= k;
                    int ng = Math.round(p * k * 255f * 0.8f);
                    if (r < 40 && ng > g) g = ng;
                }
                image.setPixelRGBA(tx, tz, 0xFF000000 | (g << 8) | r);
                dirty = true;
            }
        }
    }

    /** Центр окна, блоков (для проверок снаружи). */
    public double windowCenterX() { return (centerX + 0.5) / CELLS_PER_BLOCK; }
    public double windowCenterZ() { return (centerZ + 0.5) / CELLS_PER_BLOCK; }
}

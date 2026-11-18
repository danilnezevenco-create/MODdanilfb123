package com.danifb123.danilfb123mod.client.snow;

import com.danifb123.danilfb123mod.Danilfb123Mod;
import com.danifb123.danilfb123mod.client.PlayerPoseController;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Клиентский прототип деформируемого снега для плоского мира.
 *
 * Идея: слой снега толщиной SNOW_DEPTH лежит НАД землёй (ground = baseY), игрок стоит на земле,
 * а снег по умолчанию доходит ему до голени. Шаги вдавливают снег до земли.
 * Включение: команда /danilsnow (базовая высота берётся из положения игрока).
 */
@EventBusSubscriber(modid = Danilfb123Mod.MODID, value = Dist.CLIENT)
public final class SnowSystem {
    private static final float CELL_FINE = 1f / SnowField.CELLS_PER_BLOCK;
    private static final float CELL_COARSE = 0.5f;
    private static final int FINE_HALF = 192;   // 12 блоков
    private static final int COARSE_HALF = 192; // 96 блоков
    private static final int COARSE_HOLE = 24;  // 12 блоков (там мелкая сетка)

    private static boolean enabled;
    private static double baseY;
    private static SnowField field;
    private static VertexBuffer fineMesh, coarseMesh;
    private static final Map<UUID, Foot> FEET = new HashMap<>();

    private static final class Foot {
        double lx, lz;
        boolean init, wasGround;
    }

    private SnowSystem() {}

    // ---------- команда ----------
    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("danilsnow")
                .executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return 0;
                    baseY = Math.round(mc.player.getY() * 16.0) / 16.0;
                    enable();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Снег включён. Уровень земли Y=" + baseY + ". /danilsnow off — выключить, /danilsnow clear — стереть следы"), false);
                    return 1;
                })
                .then(Commands.literal("off").executes(ctx -> {
                    disable();
                    ctx.getSource().sendSuccess(() -> Component.literal("Снег выключен"), false);
                    return 1;
                }))
                .then(Commands.literal("clear").executes(ctx -> {
                    if (field != null) field.clearAll();
                    ctx.getSource().sendSuccess(() -> Component.literal("Следы стёрты"), false);
                    return 1;
                })));
    }

    private static void enable() {
        if (field == null) field = new SnowField();
        field.clearAll();
        FEET.clear();
        enabled = true;
    }

    private static void disable() {
        enabled = false;
        FEET.clear();
    }

    private static void ensureResources() {
        if (field == null) field = new SnowField();
        if (fineMesh == null) fineMesh = SnowMesh.build(FINE_HALF, 0);
        if (coarseMesh == null) coarseMesh = SnowMesh.build(COARSE_HALF, COARSE_HOLE);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        disable();
    }

    // ---------- следы ----------
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled || field == null) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        field.recenter(cam.x, cam.z);
        for (AbstractClientPlayer p : level.players()) step(p);
    }

    private static void step(AbstractClientPlayer p) {
        Foot f = FEET.computeIfAbsent(p.getUUID(), k -> new Foot());
        double x = p.getX(), z = p.getZ();
        boolean onSnow = p.onGround() && Math.abs(p.getY() - baseY) < 0.5;
        if (!f.init) {
            f.lx = x; f.lz = z; f.init = true; f.wasGround = onSnow;
            return;
        }
        double dx = x - f.lx, dz = z - f.lz;
        f.lx = x; f.lz = z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 4.0) { f.wasGround = onSnow; return; } // телепорт

        float yaw = dist > 0.01 ? (float) Math.atan2(-dx, dz) : p.yBodyRot * Mth.DEG_TO_RAD;

        if (onSnow && !f.wasGround) { // приземление: оба следа, глубже и шире
            stampPair(x, z, yaw, 0.92f, 1.25f);
        }
        // Шаги берём из анимации: контроллер сообщает, какая нога коснулась земли в этот тик (первой всегда левая).
        int foot = PlayerPoseController.pollStep(p.getUUID());
        if (onSnow && foot >= 0) {
            boolean sprint = p.isSprinting();
            boolean sneak = p.isCrouching();
            float pressure = sprint ? 0.92f : sneak ? 0.6f : 0.82f;
            double reach = sprint ? 0.27 : sneak ? 0.13 : 0.2; // нога в момент касания вынесена вперёд
            float side = foot == PlayerPoseController.LEFT ? 1f : -1f; // +1 = левая нога (слева от направления движения)
            field.stampFoot(x + Mth.cos(yaw) * 0.11 * side - Mth.sin(yaw) * reach,
                    z + Mth.sin(yaw) * 0.11 * side + Mth.cos(yaw) * reach, yaw, pressure, 1f);
        }
        f.wasGround = onSnow;
    }

    private static void stampPair(double x, double z, float yaw, float pressure, float scale) {
        for (int s = -1; s <= 1; s += 2) {
            field.stampFoot(x + Mth.cos(yaw) * 0.11 * s, z + Mth.sin(yaw) * 0.11 * s, yaw, pressure, scale);
        }
    }

    // ---------- рендер ----------
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!enabled || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        ShaderInstance shader = SnowShaders.snow();
        Minecraft mc = Minecraft.getInstance();
        if (shader == null || mc.level == null) return;

        ensureResources();
        Camera cam = event.getCamera();
        Vec3 c = cam.getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        field.recenter(c.x, c.z);
        field.uploadIfDirty();

        // сетка привязана к миру с шагом 0.5 блока, чтобы вершины не "плыли" при движении
        double gx = Math.floor(c.x * 2.0) / 2.0;
        double gz = Math.floor(c.z * 2.0) / 2.0;
        double win = SnowField.WINDOW_BLOCKS;
        float u0 = (float) (gx / win - Math.floor(gx / win));
        float v0 = (float) (gz / win - Math.floor(gz / win));

        // свет по времени суток
        float a = mc.level.getTimeOfDay(pt) * Mth.TWO_PI;
        float sunUp = Mth.cos(a);
        float day = Mth.clamp((sunUp + 0.1f) / 0.4f, 0f, 1f);
        day = day * day * (3f - 2f * day);
        float ambient = 0.10f + 0.50f * day;
        float sunI = 0.95f * day;
        Vector3f sunDir = new Vector3f(Mth.sin(a) * 0.8f, sunUp, 0.35f).normalize();

        shader.safeGetUniform("GridOffset").set((float) (gx - c.x), (float) (baseY - c.y), (float) (gz - c.z));
        shader.safeGetUniform("UvOrigin").set(u0, v0);
        shader.safeGetUniform("Consts").set(SnowField.SNOW_DEPTH, SnowField.RIM_MAX, SnowField.WINDOW_BLOCKS, 29f);
        shader.safeGetUniform("SunDir").set(sunDir.x, sunDir.y, sunDir.z);
        shader.safeGetUniform("Params").set(ambient, sunI, 0f, 0f);

        RenderSystem.setShaderTexture(0, field.textureId());
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.disableBlend();

        draw(fineMesh, CELL_FINE, shader, event);
        draw(coarseMesh, CELL_COARSE, shader, event);

        RenderSystem.enableCull();
    }

    private static void draw(VertexBuffer mesh, float cell, ShaderInstance shader, RenderLevelStageEvent event) {
        shader.safeGetUniform("Cell").set(cell);
        mesh.bind();
        mesh.drawWithShader(event.getModelViewMatrix(), event.getProjectionMatrix(), shader);
        VertexBuffer.unbind();
    }
}
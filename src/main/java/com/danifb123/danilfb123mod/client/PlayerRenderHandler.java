package com.danifb123.danilfb123mod.client;

import com.danifb123.danilfb123mod.Danilfb123Mod;
import com.danifb123.danilfb123mod.client.bb.BbModel;
import com.danifb123.danilfb123mod.client.bb.BbModelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/**
 * Подмена ванильного игрока своей моделью:
 *  - вид от третьего лица (и другие игроки): RenderPlayerEvent.Pre отменяет ванильный рендер;
 *  - вид от первого лица: ванилла игрока не рисует, поэтому рисуем тело сами после сущностей.
 */
@EventBusSubscriber(modid = Danilfb123Mod.MODID, value = Dist.CLIENT)
public final class PlayerRenderHandler {

    /** Масштаб модели. Когда добавишь голову и рост станет ~28.8 ед. (1.8 блока), оставь 1.0. */
    public static final float MODEL_SCALE = 1.0f;

    /** Насколько блоков отодвинуть тело назад в первом лице, чтобы камера не упиралась в шею/плечи. */
    public static final float FIRST_PERSON_BACK_OFFSET = 0.2f;

    private PlayerRenderHandler() {}

    // ---------- третье лицо и другие игроки ----------
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        BbModel model = PlayerModelHolder.get();
        if (model == null) return; // модель не загрузилась: рисуем ванильную
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) return;

        event.setCanceled(true);
        VertexConsumer vc = event.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(PlayerModelHolder.TEXTURE));
        draw(player, model, event.getPoseStack(), vc, event.getPackedLight(), event.getPartialTick());
    }

    // ---------- первое лицо ----------
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Camera camera = event.getCamera();
        if (camera.isDetached() || !(camera.getEntity() instanceof AbstractClientPlayer player)) return;
        if (player.isSleeping() || player.isSpectator()) return;
        BbModel model = PlayerModelHolder.get();
        if (model == null) return;

        Minecraft mc = Minecraft.getInstance();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = camera.getPosition();

        double x = Mth.lerp(pt, player.xo, player.getX()) - cam.x;
        double y = Mth.lerp(pt, player.yo, player.getY()) - cam.y;
        double z = Mth.lerp(pt, player.zo, player.getZ()) - cam.z;

        // сдвиг назад относительно направления тела (вперёд = (-sin yaw, +cos yaw))
        float yaw = Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
        double rad = Math.toRadians(yaw);
        x += Math.sin(rad) * FIRST_PERSON_BACK_OFFSET;
        z -= Math.cos(rad) * FIRST_PERSON_BACK_OFFSET;

        // Матрица вида камеры уже лежит в RenderSystem (как при рисовании сущностей), поэтому свой PoseStack с нуля
        PoseStack ps = new PoseStack();
        ps.translate(x, y, z);

        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(player, pt);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityCutoutNoCull(PlayerModelHolder.TEXTURE);
        draw(player, model, ps, buffers.getBuffer(type), light, pt);
        buffers.endBatch(type);
    }

    /** Пустая рука в первом лице не нужна: у нас есть целое тело. Предметы в руках остаются. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (PlayerModelHolder.get() != null && event.getItemStack().isEmpty()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        PlayerPoseController.clear();
    }

    // ---------- общее ----------
    private static void draw(AbstractClientPlayer player, BbModel model, PoseStack ps,
                             VertexConsumer vc, int light, float partial) {
        float[] pose = PlayerPoseController.compute(player, model, partial);
        float yaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);

        ps.pushPose();
        // модель в Blockbench смотрит в -Z, а игрок с yaw=0 смотрит в +Z
        ps.mulPose(Axis.YP.rotationDegrees(180f - yaw));
        ps.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        BbModelRenderer.render(model, pose, ps, vc, light, LivingEntityRenderer.getOverlayCoords(player, 0f));
        ps.popPose();
    }
}

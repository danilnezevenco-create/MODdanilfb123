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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import java.util.HashMap;
import java.util.Map;

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

    /** Кость головы: от первого лица её не рисуем, чтобы она не лезла в камеру. */
    private static final String HEAD_BONE = "head";

    /** Кости кистей: к ним крепятся предметы. */
    private static final String PALM_RIGHT = "Palm_R", PALM_LEFT = "Palm_L";

    /**
     * Дополнительный сдвиг предмета в руке в единицах модели (1/16 блока), в системе координат предмета
     * (после ванильного разворота): X вбок, Y вдоль предмета, Z вперёд/назад. Подгоняй, если предмет сидит не в ладони.
     */
    private static final float ITEM_OFFSET_X = 0f, ITEM_OFFSET_Y = 0f, ITEM_OFFSET_Z = 0f;

    private static BbModel centerModel;
    private static final Map<Integer, float[]> PALM_CENTERS = new HashMap<>();

    private PlayerRenderHandler() {}

    // ---------- третье лицо и другие игроки ----------
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        BbModel model = PlayerModelHolder.get();
        if (model == null) return; // модель не загрузилась: рисуем ванильную
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) return;

        event.setCanceled(true);
        VertexConsumer vc = event.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(PlayerModelHolder.TEXTURE));
        // своя камера от первого лица рисуется отдельно (см. ниже), поэтому здесь голова нужна всегда
        draw(player, model, event.getPoseStack(), vc, event.getMultiBufferSource(), event.getPackedLight(), event.getPartialTick(), false);
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
        draw(player, model, ps, buffers.getBuffer(type), buffers, light, pt, true);
        buffers.endBatch(type);
        buffers.endBatch(); // предметы в руках модели
    }

    /** Ванильная рука и предмет в углу экрана не нужны: предметы рисуются в руках модели. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (PlayerModelHolder.get() != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        PlayerPoseController.clear();
    }

    // ---------- общее ----------
    private static void draw(AbstractClientPlayer player, BbModel model, PoseStack ps,
                             VertexConsumer vc, MultiBufferSource buffers, int light, float partial, boolean firstPerson) {
        float[] pose = PlayerPoseController.compute(player, model, partial);
        float yaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);

        int palmR = model.boneIndex.getOrDefault(PALM_RIGHT, -1);
        int palmL = model.boneIndex.getOrDefault(PALM_LEFT, -1);
        PoseStack[] hands = new PoseStack[2]; // [0] правая кисть, [1] левая

        ps.pushPose();
        // модель в Blockbench смотрит в -Z, а игрок с yaw=0 смотрит в +Z
        ps.mulPose(Axis.YP.rotationDegrees(180f - yaw));
        ps.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        BbModelRenderer.render(model, pose, ps, vc, light, LivingEntityRenderer.getOverlayCoords(player, 0f),
                firstPerson ? HEAD_BONE : null,
                (idx, stack) -> {
                    if (idx == palmR) hands[0] = snapshot(stack);
                    else if (idx == palmL) hands[1] = snapshot(stack);
                });
        ps.popPose();

        // Предметы рисуем ПОСЛЕ модели: другой RenderType закрыл бы буфер, в который пишет модель.
        renderHeldItems(player, model, hands, palmR, palmL, buffers, light);
    }

    private static PoseStack snapshot(PoseStack src) {
        PoseStack c = new PoseStack();
        c.last().pose().set(src.last().pose());
        c.last().normal().set(src.last().normal());
        return c;
    }

    private static void renderHeldItems(AbstractClientPlayer player, BbModel model, PoseStack[] hands,
                                        int palmR, int palmL, MultiBufferSource buffers, int light) {
        if (player.isInvisible()) return;
        Minecraft mc = Minecraft.getInstance();
        for (int side = 0; side < 2; side++) {
            PoseStack hs = hands[side];
            if (hs == null) continue;
            boolean left = side == 1;
            HumanoidArm arm = left ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
            ItemStack stack = arm == player.getMainArm() ? player.getMainHandItem() : player.getOffhandItem();
            if (stack.isEmpty()) continue;

            // Матрица кисти действует на координаты позы покоя, поэтому сначала идём в центр ладони в этих координатах.
            float[] c = palmCenter(model, left ? palmL : palmR);
            hs.translate(c[0] / 16f, c[1] / 16f, c[2] / 16f);
            // Дальше ванильное положение предмета в руке. Ванильное пространство модели = наше, повёрнутое на 180° вокруг Z.
            hs.mulPose(Axis.ZP.rotationDegrees(180f));
            hs.mulPose(Axis.XP.rotationDegrees(-90f));
            hs.mulPose(Axis.YP.rotationDegrees(180f));
            hs.translate(ITEM_OFFSET_X / 16f, ITEM_OFFSET_Y / 16f, ITEM_OFFSET_Z / 16f);

            ItemDisplayContext ctx = left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            mc.getItemRenderer().renderStatic(player, stack, ctx, left, hs, buffers, player.level(),
                    light, OverlayTexture.NO_OVERLAY, player.getId() + ctx.ordinal());
        }
    }

    /** Центр меша кисти в координатах позы покоя (единицы модели). */
    private static float[] palmCenter(BbModel model, int bone) {
        if (centerModel != model) { PALM_CENTERS.clear(); centerModel = model; }
        return PALM_CENTERS.computeIfAbsent(bone, b -> {
            float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            boolean any = false;
            if (b >= 0) {
                for (BbModel.Poly poly : model.bones.get(b).polys) {
                    for (int i = 0; i < poly.n; i++) {
                        for (int k = 0; k < 3; k++) {
                            float v = poly.pos[i * 3 + k];
                            mn[k] = Math.min(mn[k], v);
                            mx[k] = Math.max(mx[k], v);
                        }
                        any = true;
                    }
                }
            }
            if (!any) { // нет меша: берём точку привязки кости
                if (b < 0) return new float[3];
                BbModel.Bone boneData = model.bones.get(b);
                return new float[]{boneData.ox, boneData.oy, boneData.oz};
            }
            return new float[]{(mn[0] + mx[0]) / 2f, (mn[1] + mx[1]) / 2f, (mn[2] + mx[2]) / 2f};
        });
    }
}
package com.danifb123.danilfb123mod.client.bb;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

/** Рисует BbModel в заданной позе через PoseStack. Единицы модели: 16 = 1 блок. */
public final class BbModelRenderer {
    private BbModelRenderer() {}

    /** Вызывается для каждой кости сразу после применения её трансформации (ps.last() = матрица кости). */
    public interface BoneHook {
        void onBone(int boneIndex, PoseStack ps);
    }

    public static void render(BbModel model, float[] pose, PoseStack ps, VertexConsumer vc, int light, int overlay) {
        render(model, pose, ps, vc, light, overlay, null);
    }

    /** hiddenBone: имя кости, которую не рисуем вместе со всеми её дочерними (null = рисуем всё). */
    public static void render(BbModel model, float[] pose, PoseStack ps, VertexConsumer vc, int light, int overlay,
                              String hiddenBone) {
        render(model, pose, ps, vc, light, overlay, hiddenBone, null);
    }

    public static void render(BbModel model, float[] pose, PoseStack ps, VertexConsumer vc, int light, int overlay,
                              String hiddenBone, BoneHook hook) {
        int hidden = hiddenBone == null ? -1 : model.boneIndex.getOrDefault(hiddenBone, -1);
        for (int root : model.roots) renderBone(model, root, pose, ps, vc, light, overlay, hidden, hook);
    }

    private static void renderBone(BbModel model, int idx, float[] pose, PoseStack ps,
                                   VertexConsumer vc, int light, int overlay, int hidden, BoneHook hook) {
        if (idx == hidden) return;
        BbModel.Bone b = model.bones.get(idx);
        int o = idx * 6;

        ps.pushPose();
        // T(pivot + position) * Rz * Ry * Rx * T(-pivot)
        ps.translate((b.ox + pose[o]) / 16f, (b.oy + pose[o + 1]) / 16f, (b.oz + pose[o + 2]) / 16f);
        float rx = b.rx + pose[o + 3], ry = b.ry + pose[o + 4], rz = b.rz + pose[o + 5];
        if (rz != 0f) ps.mulPose(Axis.ZP.rotationDegrees(rz));
        if (ry != 0f) ps.mulPose(Axis.YP.rotationDegrees(ry));
        if (rx != 0f) ps.mulPose(Axis.XP.rotationDegrees(rx));
        ps.translate(-b.ox / 16f, -b.oy / 16f, -b.oz / 16f);
        if (hook != null) hook.onBone(idx, ps);

        PoseStack.Pose last = ps.last();
        for (BbModel.Poly p : b.polys) {
            for (int i = 0; i < 4; i++) {
                int k = i < p.n ? i : p.n - 1; // треугольник = квад с повторённой последней вершиной
                vc.addVertex(last, p.pos[k * 3] / 16f, p.pos[k * 3 + 1] / 16f, p.pos[k * 3 + 2] / 16f)
                        .setColor(255, 255, 255, 255)
                        .setUv(p.uv[k * 2], p.uv[k * 2 + 1])
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(last, p.nx, p.ny, p.nz);
            }
        }
        for (int child : b.children) renderBone(model, child, pose, ps, vc, light, overlay, hidden, hook);
        ps.popPose();
    }
}
package com.danifb123.danilfb123mod.client.bb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Загруженная модель Blockbench (.bbmodel, формат "Free Model" с мешами).
 *
 * Все вершины хранятся в АБСОЛЮТНЫХ координатах позы покоя (единицы Blockbench, 16 = 1 блок),
 * поворот элемента уже применён. Кости задают только pivot и поворот;
 * матрица кости = parent * T(pivot + position) * Rz*Ry*Rx(rotation) * T(-pivot).
 */
public final class BbModel {

    /** Грань: треугольник (n=3) или четырёхугольник (n=4). */
    public static final class Poly {
        public final int n;
        public final float[] pos = new float[12];
        public final float[] uv = new float[8];
        public float nx, ny, nz;

        Poly(int n) { this.n = n; }
    }

    public static final class Bone {
        public final String name;
        public final int parent;
        public final float ox, oy, oz; // pivot
        public final float rx, ry, rz; // базовый поворот группы, градусы
        public final List<Poly> polys = new ArrayList<>();
        public final List<Integer> children = new ArrayList<>();

        Bone(String name, int parent, float[] origin, float[] rot) {
            this.name = name;
            this.parent = parent;
            this.ox = origin[0]; this.oy = origin[1]; this.oz = origin[2];
            this.rx = rot[0]; this.ry = rot[1]; this.rz = rot[2];
        }
    }

    public final List<Bone> bones = new ArrayList<>();
    public final List<Integer> roots = new ArrayList<>();
    public final Map<String, Integer> boneIndex = new HashMap<>();
    public final Map<String, BbAnimation> animations = new HashMap<>();

    private final Map<String, Integer> groupUuidToBone = new HashMap<>();
    private final Map<String, JsonObject> groupsByUuid = new HashMap<>();
    private final Map<String, JsonObject> elementsByUuid = new HashMap<>();
    private float uvW = 64f, uvH = 64f;
    private int syntheticRoot = -1;
    public int skippedElements; // кубы и прочее, что пока не поддерживается

    private BbModel() {}

    public static BbModel load(InputStream in) throws IOException {
        JsonObject root;
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        BbModel m = new BbModel();
        m.parse(root);
        return m;
    }

    int boneByGroupUuid(String uuid) { return groupUuidToBone.getOrDefault(uuid, -1); }
    int boneByName(String name) { return boneIndex.getOrDefault(name, -1); }

    private void parse(JsonObject root) {
        if (root.has("resolution")) {
            JsonObject res = root.getAsJsonObject("resolution");
            uvW = res.get("width").getAsFloat();
            uvH = res.get("height").getAsFloat();
        }
        for (JsonElement e : root.getAsJsonArray("elements")) {
            JsonObject o = e.getAsJsonObject();
            elementsByUuid.put(o.get("uuid").getAsString(), o);
        }
        if (root.has("groups")) {
            for (JsonElement g : root.getAsJsonArray("groups")) {
                if (g.isJsonObject()) {
                    JsonObject o = g.getAsJsonObject();
                    groupsByUuid.put(o.get("uuid").getAsString(), o);
                }
            }
        }
        for (JsonElement n : root.getAsJsonArray("outliner")) buildNode(n, -1);

        if (root.has("animations")) {
            for (JsonElement a : root.getAsJsonArray("animations")) {
                BbAnimation anim = BbAnimation.parse(a.getAsJsonObject(), this);
                animations.put(anim.name, anim);
            }
        }
    }

    private void buildNode(JsonElement node, int parent) {
        if (node.isJsonPrimitive()) { // uuid элемента (меша)
            JsonObject e = elementsByUuid.get(node.getAsString());
            if (e == null) return;
            int target = parent;
            if (target < 0) {
                if (syntheticRoot < 0) {
                    syntheticRoot = bones.size();
                    bones.add(new Bone("__root", -1, new float[3], new float[3]));
                    roots.add(syntheticRoot);
                }
                target = syntheticRoot;
            }
            addMesh(bones.get(target), e);
            return;
        }
        JsonObject o = node.getAsJsonObject();
        String uuid = o.get("uuid").getAsString();
        JsonObject g = groupsByUuid.getOrDefault(uuid, o); // в старых файлах группа лежит прямо в outliner
        String name = g.has("name") ? g.get("name").getAsString() : uuid;

        int idx = bones.size();
        bones.add(new Bone(name, parent, floats(g, "origin"), floats(g, "rotation")));
        boneIndex.putIfAbsent(name, idx);
        groupUuidToBone.put(uuid, idx);
        if (parent >= 0) bones.get(parent).children.add(idx); else roots.add(idx);

        if (o.has("children")) {
            for (JsonElement c : o.getAsJsonArray("children")) buildNode(c, idx);
        }
    }

    private void addMesh(Bone bone, JsonObject e) {
        if (!e.has("type") || !"mesh".equals(e.get("type").getAsString())) {
            skippedElements++;
            return;
        }
        float[] origin = floats(e, "origin");
        float[] rot = floats(e, "rotation");
        double[][] r = rotMatrix(rot[0], rot[1], rot[2]);

        Map<String, float[]> abs = new HashMap<>();
        double cx = 0, cy = 0, cz = 0;
        for (Map.Entry<String, JsonElement> en : e.getAsJsonObject("vertices").entrySet()) {
            JsonArray v = en.getValue().getAsJsonArray();
            double x = v.get(0).getAsDouble(), y = v.get(1).getAsDouble(), z = v.get(2).getAsDouble();
            float ax = (float) (origin[0] + r[0][0] * x + r[0][1] * y + r[0][2] * z);
            float ay = (float) (origin[1] + r[1][0] * x + r[1][1] * y + r[1][2] * z);
            float az = (float) (origin[2] + r[2][0] * x + r[2][1] * y + r[2][2] * z);
            abs.put(en.getKey(), new float[]{ax, ay, az});
            cx += ax; cy += ay; cz += az;
        }
        if (abs.isEmpty()) return;
        cx /= abs.size(); cy /= abs.size(); cz /= abs.size();

        for (Map.Entry<String, JsonElement> fe : e.getAsJsonObject("faces").entrySet()) {
            JsonObject f = fe.getValue().getAsJsonObject();
            JsonArray vk = f.getAsJsonArray("vertices");
            int n = vk.size();
            if (n < 3 || n > 4) continue; // рёбра и точки пропускаем
            JsonObject fuv = f.has("uv") ? f.getAsJsonObject("uv") : null;

            float[][] p = new float[n][];
            float[][] t = new float[n][2];
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                String key = vk.get(i).getAsString();
                p[i] = abs.get(key);
                if (p[i] == null) { ok = false; break; }
                if (fuv != null && fuv.has(key)) {
                    JsonArray uv = fuv.getAsJsonArray(key);
                    t[i][0] = uv.get(0).getAsFloat() / uvW;
                    t[i][1] = uv.get(1).getAsFloat() / uvH;
                }
            }
            if (!ok) continue;

            float ux = p[1][0] - p[0][0], uy = p[1][1] - p[0][1], uz = p[1][2] - p[0][2];
            float vx = p[2][0] - p[0][0], vy = p[2][1] - p[0][1], vz = p[2][2] - p[0][2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-9f) continue;
            nx /= len; ny /= len; nz /= len;

            // нормаль должна смотреть наружу от центра меша; иначе разворачиваем порядок вершин
            float fx = 0, fy = 0, fz = 0;
            for (float[] q : p) { fx += q[0]; fy += q[1]; fz += q[2]; }
            fx /= n; fy /= n; fz /= n;
            if ((fx - cx) * nx + (fy - cy) * ny + (fz - cz) * nz < 0) {
                for (int i = 0; i < n / 2; i++) {
                    float[] tp = p[i]; p[i] = p[n - 1 - i]; p[n - 1 - i] = tp;
                    float[] tt = t[i]; t[i] = t[n - 1 - i]; t[n - 1 - i] = tt;
                }
                nx = -nx; ny = -ny; nz = -nz;
            }

            Poly poly = new Poly(n);
            for (int i = 0; i < n; i++) {
                poly.pos[i * 3] = p[i][0]; poly.pos[i * 3 + 1] = p[i][1]; poly.pos[i * 3 + 2] = p[i][2];
                poly.uv[i * 2] = t[i][0]; poly.uv[i * 2 + 1] = t[i][1];
            }
            poly.nx = nx; poly.ny = ny; poly.nz = nz;
            bone.polys.add(poly);
        }
    }

    /** Rz * Ry * Rx (порядок ZYX, как в Blockbench). */
    private static double[][] rotMatrix(float rxDeg, float ryDeg, float rzDeg) {
        double a = Math.toRadians(rxDeg), b = Math.toRadians(ryDeg), c = Math.toRadians(rzDeg);
        double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cc = Math.cos(c), sc = Math.sin(c);
        double[][] X = {{1, 0, 0}, {0, ca, -sa}, {0, sa, ca}};
        double[][] Y = {{cb, 0, sb}, {0, 1, 0}, {-sb, 0, cb}};
        double[][] Z = {{cc, -sc, 0}, {sc, cc, 0}, {0, 0, 1}};
        return mul(Z, mul(Y, X));
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    private static float[] floats(JsonObject o, String key) {
        float[] r = new float[3];
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) r[i] = a.get(i).getAsFloat();
        }
        return r;
    }
}

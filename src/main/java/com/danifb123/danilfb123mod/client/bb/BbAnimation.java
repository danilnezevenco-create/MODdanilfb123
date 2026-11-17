package com.danifb123.danilfb123mod.client.bb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Анимация из .bbmodel. Поза хранится в массиве float[кости * 6]:
 * для кости i: [i*6+0..2] = position (единицы Blockbench), [i*6+3..5] = rotation (градусы).
 * Значения аддитивны к позе покоя (как в Blockbench): итоговый поворот = базовый поворот группы + значение анимации.
 */
public final class BbAnimation {
    public final String name;
    public final float length;
    public final boolean loops;
    private final List<Track> tracks = new ArrayList<>();

    private BbAnimation(String name, float length, boolean loops) {
        this.name = name;
        this.length = length;
        this.loops = loops;
    }

    private static final class Key {
        float time;
        float[] v = new float[3];
        boolean step;
    }

    private static final class Track {
        final int bone, slot;
        final float[] times;
        final float[][] values;
        final boolean[] step;

        Track(int bone, int slot, List<Key> keys) {
            this.bone = bone;
            this.slot = slot;
            keys.sort((a, b) -> Float.compare(a.time, b.time));
            int n = keys.size();
            times = new float[n];
            values = new float[n][];
            step = new boolean[n];
            for (int i = 0; i < n; i++) {
                times[i] = keys.get(i).time;
                values[i] = keys.get(i).v;
                step[i] = keys.get(i).step;
            }
        }

        void sample(float t, float[] out) {
            int n = times.length;
            if (n == 0) return;
            float[] r;
            if (t <= times[0]) {
                r = values[0];
            } else if (t >= times[n - 1]) {
                r = values[n - 1];
            } else {
                int i = 0;
                while (i < n - 2 && t >= times[i + 1]) i++;
                float[] a = values[i], b = values[i + 1];
                float k = step[i] ? 0f : (t - times[i]) / (times[i + 1] - times[i]);
                r = new float[]{a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k};
            }
            int o = bone * 6 + slot;
            out[o] = r[0]; out[o + 1] = r[1]; out[o + 2] = r[2];
        }
    }

    static BbAnimation parse(JsonObject o, BbModel model) {
        String name = o.has("name") ? o.get("name").getAsString() : "unnamed";
        float length = o.has("length") ? o.get("length").getAsFloat() : 0f;
        String loop = o.has("loop") ? o.get("loop").getAsString() : "once";
        BbAnimation anim = new BbAnimation(name, length, "loop".equals(loop));

        if (o.has("animators")) {
            for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("animators").entrySet()) {
                JsonObject an = en.getValue().getAsJsonObject();
                if (an.has("type") && !"bone".equals(an.get("type").getAsString())) continue;
                int bone = model.boneByGroupUuid(en.getKey());
                if (bone < 0 && an.has("name")) bone = model.boneByName(an.get("name").getAsString());
                if (bone < 0 || !an.has("keyframes")) continue;

                List<Key> pos = new ArrayList<>(), rot = new ArrayList<>();
                for (JsonElement ke : an.getAsJsonArray("keyframes")) {
                    JsonObject k = ke.getAsJsonObject();
                    String ch = k.get("channel").getAsString();
                    List<Key> target = "position".equals(ch) ? pos : "rotation".equals(ch) ? rot : null;
                    if (target == null) continue; // scale пока не поддерживается
                    JsonArray dps = k.getAsJsonArray("data_points");
                    if (dps == null || dps.size() == 0) continue;
                    JsonObject dp = dps.get(0).getAsJsonObject();
                    Key key = new Key();
                    key.time = k.get("time").getAsFloat();
                    key.v[0] = num(dp.get("x")); key.v[1] = num(dp.get("y")); key.v[2] = num(dp.get("z"));
                    key.step = k.has("interpolation") && "step".equals(k.get("interpolation").getAsString());
                    target.add(key);
                    if (key.time > anim.maxTime) anim.maxTime = key.time;
                }
                if (!pos.isEmpty()) anim.tracks.add(new Track(bone, 0, pos));
                if (!rot.isEmpty()) anim.tracks.add(new Track(bone, 3, rot));
            }
        }
        return anim;
    }

    private float maxTime;

    private float effectiveLength() { return length > 0 ? length : maxTime; }

    /** Записывает позу в момент time (секунды) в out. Массив предварительно обнуляется. */
    public void sample(float time, float[] out) {
        Arrays.fill(out, 0f);
        float len = effectiveLength();
        float t = time;
        if (loops && len > 0) t = t % len;
        else if (t > len) t = len;
        for (Track tr : tracks) tr.sample(t, out);
    }

    public static void blend(float[] a, float[] b, float t, float[] out) {
        for (int i = 0; i < out.length; i++) out[i] = a[i] + (b[i] - a[i]) * t;
    }

    /** Значения в bbmodel бывают строками и молангом; нечисловое трактуем как 0. */
    private static float num(JsonElement e) {
        if (e == null || e.isJsonNull()) return 0f;
        try {
            return e.getAsFloat();
        } catch (Exception ex) {
            try { return Float.parseFloat(e.getAsString().trim()); } catch (Exception ex2) { return 0f; }
        }
    }
}

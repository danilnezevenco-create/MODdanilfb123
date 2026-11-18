package com.danifb123.danilfb123mod.client;

import com.danifb123.danilfb123mod.Danilfb123Mod;
import com.danifb123.danilfb123mod.client.bb.BbAnimation;
import com.danifb123.danilfb123mod.client.bb.BbModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Pose;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Выбирает анимацию игрока и плавно переключает между ними.
 *
 * Логика анимации считается в КЛИЕНТСКОМ ТИКЕ (а не в кадре рендера): так моменты касания ногой земли
 * известны ровно в тот тик, когда они происходят, и следы на снегу синхронны с шагами в анимации.
 * Рендер только интерполирует время анимации между тиками.
 *
 * Состояния: idle / walk / sprint / jump / down (приседание) / crouch_idle / crouch (ходьба в приседе) / up (вставание).
 * Приседание определяется по позе игрока (Pose.CROUCHING), которая синхронизируется сервером, поэтому
 * чужой присед виден всем, у кого стоит мод.
 */
@EventBusSubscriber(modid = Danilfb123Mod.MODID, value = Dist.CLIENT)
public final class PlayerPoseController {
    private static final float TICK = 0.05f;          // секунд в тике
    private static final float BLEND_TICKS = 3f;      // 0.15 с
    private static final double MOVE_THRESHOLD = 0.01; // блоков/тик
    private static final float START_LEAD = 0.02f;    // старт цикла сразу после касания ногой, стоявшей "сзади"
    private static final double WALK_REF = 0.2155, SPRINT_REF = 0.2806, CROUCH_REF = 0.0648; // блоков/тик при скорости 1.0 анимации
    private static final String LEG_LEFT = "Leg_L", LEG_RIGHT = "Leg_R";
    public static final int LEFT = 0, RIGHT = 1;

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static BbModel contactModel;
    private static final Map<String, float[]> CONTACTS = new HashMap<>();

    private PlayerPoseController() {}

    private static final class State {
        final float[] out, a, b;
        String cur = "", prev = "";
        float t0, t1;      // время текущей анимации в прошлом и последнем тике
        float pt0, pt1;    // то же для предыдущей анимации (для смешивания)
        float b0, b1 = 1f; // коэффициент смешивания
        Boolean crouching; // null до первого тика
        String trans;      // "down" / "up" / null
        float transT;
        int nextFoot = LEFT; // нога, которая шагнёт следующей
        int pendingStep = -1;

        State(int n) { out = new float[n]; a = new float[n]; b = new float[n]; }
    }

    public static void clear() { STATES.clear(); CONTACTS.clear(); contactModel = null; }

    /** Нога (LEFT/RIGHT), коснувшаяся земли с прошлого опроса, либо -1. Сбрасывает флаг. */
    public static int pollStep(UUID id) {
        State s = STATES.get(id);
        if (s == null) return -1;
        int v = s.pendingStep;
        s.pendingStep = -1;
        return v;
    }

    // ---------- тик ----------
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        BbModel model = PlayerModelHolder.get();
        if (level == null || model == null) { STATES.clear(); return; }
        STATES.keySet().removeIf(id -> level.getPlayerByUUID(id) == null);
        for (AbstractClientPlayer p : level.players()) tickPlayer(p, model);
    }

    private static State stateFor(AbstractClientPlayer p, BbModel model) {
        int n = model.bones.size() * 6;
        State s = STATES.get(p.getUUID());
        if (s == null || s.out.length != n) {
            s = new State(n);
            STATES.put(p.getUUID(), s);
        }
        return s;
    }

    private static void tickPlayer(AbstractClientPlayer p, BbModel model) {
        State s = stateFor(p, model);
        boolean crouching = p.getPose() == Pose.CROUCHING;
        boolean airborne = !p.onGround() && !p.isInWater() && !p.onClimbable()
                && !p.getAbilities().flying && !p.isFallFlying();
        double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
        double speed = Math.sqrt(dx * dx + dz * dz);

        s.t0 = s.t1; s.pt0 = s.pt1; s.b0 = s.b1;

        // ---- переходы присед / вставание ----
        if (s.crouching == null) {
            s.crouching = crouching;
        } else if (crouching != s.crouching) {
            s.crouching = crouching;
            String next = crouching ? "down" : "up";
            float start = 0f;
            if (s.trans != null && !s.trans.equals(next)) {
                // переключили посреди анимации: играем её в обратную сторону с того же места
                start = Math.max(0f, Math.min(animLen(model, next), animLen(model, s.trans) - s.transT));
            }
            s.trans = next;
            s.transT = start;
        }
        if (s.trans != null) {
            s.transT += TICK;
            if (s.transT >= animLen(model, s.trans)) s.trans = null;
        }

        // ---- выбор анимации ----
        String want;
        if (airborne) want = "jump";
        else if (s.trans != null) want = s.trans;
        else if (crouching) want = speed > MOVE_THRESHOLD ? "crouch" : "crouch_idle";
        else if (speed > MOVE_THRESHOLD) want = p.isSprinting() ? "sprint" : "walk";
        else want = "idle";

        boolean switched = false;
        if (!want.equals(s.cur)) {
            boolean fromLoco = isLoco(s.cur);
            s.prev = s.cur;
            s.pt0 = s.pt1 = s.t1;
            s.b0 = s.b1 = 0f;
            s.cur = want;
            float start = 0f;
            if (isLoco(want)) {
                if (!fromLoco) s.nextFoot = LEFT; // начинаем движение всегда с левой ноги
                start = startTime(model, want, s.nextFoot);
            }
            s.t0 = s.t1 = start;
            switched = true;
        }

        // ---- ход времени ----
        float mul = 1f;
        switch (want) {
            case "walk" -> mul = clamp((float) (speed / WALK_REF), 0.5f, 1.6f);
            case "sprint" -> mul = clamp((float) (speed / SPRINT_REF), 0.5f, 1.6f);
            case "crouch" -> mul = clamp((float) (speed / CROUCH_REF), 0.5f, 1.6f);
            default -> { }
        }
        float adv = TICK * mul;
        if (want.equals("down") || want.equals("up")) {
            s.t1 = s.transT;
        } else {
            float before = s.t1;
            s.t1 += adv;
            if (isLoco(want) && !switched) detectSteps(model, s, want, before, s.t1);
        }
        s.pt1 += adv;
        s.b1 = Math.min(1f, s.b1 + 1f / BLEND_TICKS);
    }

    // ---------- шаги ----------
    private static boolean isLoco(String a) { return a.equals("walk") || a.equals("sprint") || a.equals("crouch"); }

    private static float animLen(BbModel model, String name) {
        BbAnimation a = model.animations.get(name);
        return a != null ? Math.max(0.05f, a.lengthSeconds()) : 0.5f;
    }

    /** Время касания землёй для каждой ноги: момент, когда нога вынесена дальше всего вперёд (поворот X максимален). */
    private static float[] contacts(BbModel model, String anim) {
        if (contactModel != model) { CONTACTS.clear(); contactModel = model; }
        return CONTACTS.computeIfAbsent(anim, k -> {
            float len = animLen(model, k);
            float[] r = {0.25f * len, 0.75f * len}; // запасной вариант, если у анимации нет дорожек ног
            BbAnimation a = model.animations.get(k);
            Integer l = model.boneIndex.get(LEG_LEFT), rt = model.boneIndex.get(LEG_RIGHT);
            if (a != null && l != null && rt != null) {
                float tl = a.peakRotationTime(l, 0);
                if (tl >= 0f) { r[LEFT] = tl; r[RIGHT] = (tl + len / 2f) % len; }
            }
            return r;
        });
    }

    /** Стартовая фаза цикла так, чтобы первой коснулась земли нога first: сразу после касания другой ноги. */
    private static float startTime(BbModel model, String anim, int first) {
        float len = animLen(model, anim);
        float t = contacts(model, anim)[1 - first] + START_LEAD;
        return t % len;
    }

    private static void detectSteps(BbModel model, State s, String anim, float a, float b) {
        float len = animLen(model, anim);
        float[] c = contacts(model, anim);
        for (int foot = 0; foot < 2; foot++) {
            double k0 = Math.floor((a - c[foot]) / len), k1 = Math.floor((b - c[foot]) / len);
            if (k1 > k0) {
                s.pendingStep = foot;
                s.nextFoot = 1 - foot;
            }
        }

        // Если шаги в цикле стоят неровно (в беге: пара следов, потом длинный пробел),
        // ставим дополнительный левый след ровно в середине длинного пробела.
        float first = Math.min(c[0], c[1]), second = Math.max(c[0], c[1]);
        float gapIn = second - first, gapOut = len - gapIn;
        if (Math.max(gapIn, gapOut) > 2f * Math.min(gapIn, gapOut)) {
            float mid = gapOut > gapIn ? second + gapOut / 2f : first + gapIn / 2f;
            double k0 = Math.floor((a - mid) / len), k1 = Math.floor((b - mid) / len);
            if (k1 > k0) s.pendingStep = LEFT; // nextFoot не трогаем, чтобы очередь ног не сбилась
        }
    }

    // ---------- рендер ----------
    /** Возвращает позу; массив действителен до следующего вызова для того же игрока. */
    public static float[] compute(AbstractClientPlayer p, BbModel model, float partial) {
        State s = stateFor(p, model);
        if (s.cur.isEmpty()) tickPlayer(p, model); // игрок ещё не успел попасть в тик

        float tc = s.t0 + (s.t1 - s.t0) * partial;
        BbAnimation cur = model.animations.get(s.cur);
        if (cur != null) cur.sample(tc, s.a);
        else java.util.Arrays.fill(s.a, 0f);

        float blend = s.b0 + (s.b1 - s.b0) * partial;
        BbAnimation prev = blend < 1f ? model.animations.get(s.prev) : null;
        if (prev != null) {
            float tp = s.pt0 + (s.pt1 - s.pt0) * partial;
            prev.sample(tp, s.b);
            BbAnimation.blend(s.b, s.a, blend, s.out);
        } else {
            System.arraycopy(s.a, 0, s.out, 0, s.out.length);
        }
        return s.out;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}

package com.danifb123.danilfb123mod.client;

import com.danifb123.danilfb123mod.client.bb.BbAnimation;
import com.danifb123.danilfb123mod.client.bb.BbModel;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Выбирает анимацию игрока (idle / walk / sprint / jump) и плавно переключает между ними.
 * Работает целиком на клиенте: другие игроки видны с твоей моделью без сетевого кода.
 */
public final class PlayerPoseController {
    private static final float BLEND_SECONDS = 0.15f;
    private static final double MOVE_THRESHOLD = 0.01; // блоков/тик
    private static final Map<UUID, State> STATES = new HashMap<>();

    private PlayerPoseController() {}

    private static final class State {
        final float[] out, a, b;
        String current = "", previous = "";
        float time, previousTime, blend = 1f, lastClock = -1f;

        State(int n) { out = new float[n]; a = new float[n]; b = new float[n]; }
    }

    public static void clear() { STATES.clear(); }

    /** Возвращает позу; массив действителен до следующего вызова для того же игрока. */
    public static float[] compute(AbstractClientPlayer p, BbModel model, float partial) {
        int n = model.bones.size() * 6;
        State s = STATES.get(p.getUUID());
        if (s == null || s.out.length != n) {
            s = new State(n);
            STATES.put(p.getUUID(), s);
        }

        float clock = (p.tickCount + partial) / 20f;
        float dt = clock - s.lastClock;
        if (dt < 0f || dt > 0.5f) dt = 0f; // первый кадр, телепорт, пауза
        s.lastClock = clock;

        double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
        double speed = Math.sqrt(dx * dx + dz * dz);

        String want = choose(p, speed);
        if (!want.equals(s.current)) {
            s.previous = s.current;
            s.previousTime = s.time;
            s.current = want;
            s.time = 0f;
            s.blend = 0f;
        }

        float mul = 1f;
        if (want.equals("walk")) mul = clamp((float) (speed / 0.2155), 0.5f, 1.6f);
        else if (want.equals("sprint")) mul = clamp((float) (speed / 0.2806), 0.5f, 1.6f);
        s.time += dt * mul;
        s.previousTime += dt * mul;
        s.blend = Math.min(1f, s.blend + dt / BLEND_SECONDS);

        BbAnimation cur = model.animations.get(s.current);
        if (cur != null) cur.sample(s.time, s.a);
        else java.util.Arrays.fill(s.a, 0f);

        BbAnimation prev = s.blend < 1f ? model.animations.get(s.previous) : null;
        if (prev != null) {
            prev.sample(s.previousTime, s.b);
            BbAnimation.blend(s.b, s.a, s.blend, s.out);
        } else {
            System.arraycopy(s.a, 0, s.out, 0, n);
        }
        return s.out;
    }

    private static String choose(AbstractClientPlayer p, double speed) {
        boolean airborne = !p.onGround() && !p.isInWater() && !p.onClimbable()
                && !p.getAbilities().flying && !p.isFallFlying();
        if (airborne) return "jump";
        if (speed > MOVE_THRESHOLD) return p.isSprinting() ? "sprint" : "walk";
        return "idle";
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}

package com.danifb123.danilfb123mod.client;

import com.danifb123.danilfb123mod.Danilfb123Mod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/**
 * Управление движением локального игрока:
 *  - присед ВСЕГДА по переключению (кнопка берётся из ванильных настроек), независимо от настройки "переключать присед";
 *  - бег ВСЕГДА по удержанию кнопки бега, независимо от настройки "переключать бег";
 *  - в присяде бегать нельзя: нажатие кнопки бега встаёт и (пока кнопка зажата) переходит в бег;
 *  - нажатие присяда на бегу переводит на шаг, дальше присед;
 *  - скорость меняется плавно (разгон и торможение), а не рывком.
 *
 * Состояние присяда передаётся серверу ванильным флагом "shift" (поэтому поза CROUCHING видна всем игрокам,
 * а хитбокс и камеру опускает PlayerMixin).
 */
@EventBusSubscriber(modid = Danilfb123Mod.MODID, value = Dist.CLIENT)
public final class CrouchController {
    /** Множитель скорости ходьбы. */
    private static final float WALK = 1.0f;
    /** Ванильный бег даёт +30% скорости (атрибутом). */
    private static final float SPRINT_MUL = 1.3f;
    /** Скорость разгона и торможения: на сколько единиц множителя меняется скорость за тик (20 тиков = 1 с). */
    private static final float ACCEL = 0.10f;
    private static final float DECEL = 0.12f;

    private static LocalPlayer lastPlayer;
    private static boolean crouched;
    private static float speed = 1f; // текущий (сглаженный) множитель скорости

    private CrouchController() {}

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer p)) return;
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        Input in = event.getInput();

        if (p != lastPlayer) { // новый мир / возрождение
            lastPlayer = p;
            crouched = false;
            speed = 1f;
        }

        // Бег всегда по удержанию: выключаем настройку "переключать бег" (иначе ваниль продолжит бежать после отпускания).
        if (o.toggleSprint().get()) {
            o.toggleSprint().set(false);
            o.keySprint.setDown(false);
        }

        // Нажатия (учитываются даже очень короткие, меньше тика)
        int crouchClicks = drain(o.keyShift);
        int sprintClicks = drain(o.keySprint);
        if (mc.screen != null) { crouchClicks = 0; sprintClicks = 0; }

        // Там, где shift в ваниле значит другое (полёт, плавание, транспорт), отдаём управление ванили.
        boolean vanillaControl = p.isPassenger() || p.getAbilities().flying || p.isFallFlying() || p.isSleeping()
                || p.isSpectator() || p.isInWater() || p.isInLava() || p.isSwimming();
        if (vanillaControl) {
            crouched = false;
            speed = 1f;
            return;
        }

        if ((crouchClicks & 1) == 1) {
            if (!crouched) crouched = true;
            else if (canStandUp(p)) crouched = false; // под низким потолком встать нельзя
        }
        if (sprintClicks > 0 && crouched && canStandUp(p)) crouched = false;

        boolean slow = crouched || p.isCrouching();
        float rawF = axis(in.up, in.down);
        float rawL = axis(in.left, in.right);
        boolean moving = rawF != 0f || rawL != 0f;

        boolean foodOk = p.getFoodData().getFoodLevel() > 6 || p.getAbilities().mayfly;
        boolean wantSprint = o.keySprint.isDown() && !slow && rawF > 0f && foodOk
                && !p.isUsingItem() && !p.hasEffect(MobEffects.BLINDNESS);
        // Если бег закончился, а скорость ещё выше шага, бег-флаг держим до конца плавного торможения.
        boolean carry = p.isSprinting() && speed > WALK + 0.001f && rawF > 0f;
        boolean sprint = wantSprint || carry;

        float target;
        if (!moving) target = 0f;
        else if (wantSprint) target = WALK * SPRINT_MUL;
        else if (slow) target = (float) p.getAttributeValue(Attributes.SNEAKING_SPEED);
        else target = WALK;
        speed = approach(speed, target, target > speed ? ACCEL : DECEL);

        if (moving) {
            float len = Mth.sqrt(rawF * rawF + rawL * rawL);
            float m = Math.min(1f, sprint ? speed / SPRINT_MUL : speed); // ваниль сама умножит на 1.3 при беге
            in.forwardImpulse = rawF / len * m;
            in.leftImpulse = rawL / len * m;
        } else {
            in.forwardImpulse = 0f;
            in.leftImpulse = 0f;
        }
        if (p.isSprinting() != sprint) p.setSprinting(sprint);
        in.shiftKeyDown = crouched; // ваниль отправит это серверу, поза CROUCHING синхронизируется всем
    }

    /** Хватает ли места над головой, чтобы встать в полный рост. */
    private static boolean canStandUp(LocalPlayer p) {
        AABB box = p.getDimensions(Pose.STANDING).makeBoundingBox(p.position()).deflate(1.0E-7);
        return p.level().noBlockCollision(p, box);
    }

    private static int drain(KeyMapping key) {
        int n = 0;
        while (key.consumeClick()) n++;
        return n;
    }

    private static float axis(boolean positive, boolean negative) {
        return positive == negative ? 0f : positive ? 1f : -1f;
    }

    private static float approach(float v, float target, float step) {
        if (v < target) return Math.min(target, v + step);
        return Math.max(target, v - step);
    }
}
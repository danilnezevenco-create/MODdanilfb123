package com.danifb123.danilfb123mod.mixin;

import com.danifb123.danilfb123mod.CrouchConfig;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Общий (клиент + сервер) миксин: низкий хитбокс/камера присяда и падение с края блока в присяде. */
@Mixin(Player.class)
public abstract class PlayerMixin {

    /** Присед мода ниже ванильного: меньше хитбокс и ниже глаза. */
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true, require = 0)
    private void danilfb123$crouchDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (pose == Pose.CROUCHING) cir.setReturnValue(CrouchConfig.DIMENSIONS);
    }

    /** В ваниле присед не даёт упасть с края; здесь падать можно. */
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true, require = 0)
    private void danilfb123$allowFallingOffEdge(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(movement);
    }
}

package mindless.transformer.impl.entity;

import mindless.event.PostMotionEvent;
import mindless.event.PostUpdateEvent;
import mindless.event.PreMotionEvent;
import mindless.event.PreUpdateEvent;
import mindless.utility.RotationUtils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.COverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovementInput;
import net.minecraftforge.common.MinecraftForge;

@CTransformer(EntityPlayerSP.class)
public abstract class TransformerEntityPlayerSP {
    @CShadow public int sprintingTicksLeft;
    @CShadow protected int sprintToggleTimer;
    @CShadow public float prevTimeInPortal;
    @CShadow public float timeInPortal;
    @CShadow public MovementInput movementInput;
    @CShadow private int horseJumpPowerCounter;
    @CShadow private float horseJumpPower;
    @CShadow private boolean serverSprintState;
    @CShadow public NetHandlerPlayClient sendQueue;
    @CShadow private boolean serverSneakState;
    @CShadow private double lastReportedPosX;
    @CShadow private double lastReportedPosY;
    @CShadow private double lastReportedPosZ;
    @CShadow private float lastReportedYaw;
    @CShadow private float lastReportedPitch;
    @CShadow private int positionUpdateTicks;

    @CShadow protected abstract boolean isCurrentViewEntity();
    @CShadow public abstract boolean isRidingHorse();
    @CShadow protected abstract void sendHorseJump();

    @CInline
    @CInject(method = "onUpdate", target = @CTarget("HEAD"))
    private void onUpdatePre(InjectionCallback ci) {
        EntityPlayerSP self = (EntityPlayerSP) (Object) this;
        if (self.worldObj != null
                && self.worldObj.isBlockLoaded(new BlockPos(self.posX, 0.0, self.posZ))) {
            RotationUtils.prevRenderPitch = RotationUtils.renderPitch;
            RotationUtils.prevRenderYaw = RotationUtils.renderYaw;
            MinecraftForge.EVENT_BUS.post(new PreUpdateEvent());
        }
    }

    @CInline
    @CInject(method = "onUpdate", target = @CTarget("RETURN"))
    private void onUpdatePost(InjectionCallback ci) {
        EntityPlayerSP self = (EntityPlayerSP) (Object) this;
        if (self.worldObj != null
                && self.worldObj.isBlockLoaded(new BlockPos(self.posX, 0.0, self.posZ))) {
            MinecraftForge.EVENT_BUS.post(new PostUpdateEvent());
        }
    }

    @COverride
    public void onUpdateWalkingPlayer() {
        EntityPlayerSP self = (EntityPlayerSP) (Object) this;
        PreMotionEvent.setRenderYaw(false);
        PreMotionEvent preMotionEvent = new PreMotionEvent(
                self.posX,
                self.getEntityBoundingBox().minY,
                self.posZ,
                self.rotationYaw,
                self.rotationPitch,
                self.onGround,
                self.isSprinting(),
                self.isSneaking()
        );

        MinecraftForge.EVENT_BUS.post(preMotionEvent);

        RotationUtils.serverRotations = new float[]{preMotionEvent.getYaw(), preMotionEvent.getPitch()};

        boolean flag = preMotionEvent.isSprinting();
        if (flag != this.serverSprintState) {
            if (flag) {
                this.sendQueue.addToSendQueue(new C0BPacketEntityAction(self, C0BPacketEntityAction.Action.START_SPRINTING));
            } else {
                this.sendQueue.addToSendQueue(new C0BPacketEntityAction(self, C0BPacketEntityAction.Action.STOP_SPRINTING));
            }
            this.serverSprintState = flag;
        }

        boolean flag1 = preMotionEvent.isSneaking();
        if (flag1 != this.serverSneakState) {
            if (flag1) {
                this.sendQueue.addToSendQueue(new C0BPacketEntityAction(self, C0BPacketEntityAction.Action.START_SNEAKING));
            } else {
                this.sendQueue.addToSendQueue(new C0BPacketEntityAction(self, C0BPacketEntityAction.Action.STOP_SNEAKING));
            }
            this.serverSneakState = flag1;
        }

        if (this.isCurrentViewEntity()) {
            if (PreMotionEvent.setRenderYaw()) {
                RotationUtils.setRenderYaw(preMotionEvent.getYaw());
            }
            RotationUtils.renderPitch = preMotionEvent.getPitch();
            RotationUtils.renderYaw = preMotionEvent.getYaw();
            if (RotationUtils.setFakePitchValue) {
                RotationUtils.renderPitch = RotationUtils.fakePitch;
            }

            double d0 = preMotionEvent.getPosX() - this.lastReportedPosX;
            double d1 = preMotionEvent.getPosY() - this.lastReportedPosY;
            double d2 = preMotionEvent.getPosZ() - this.lastReportedPosZ;
            double d3 = preMotionEvent.getYaw() - this.lastReportedYaw;
            double d4 = preMotionEvent.getPitch() - this.lastReportedPitch;
            boolean flag2 = d0 * d0 + d1 * d1 + d2 * d2 > 9.0E-4 || this.positionUpdateTicks >= 20;
            boolean flag3 = d3 != 0.0 || d4 != 0.0;

            if (self.isRiding()) {
                this.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
                        preMotionEvent.getYaw(), preMotionEvent.getPitch(), self.onGround));
            } else {
                if (flag2 && flag3) {
                    this.sendQueue.addToSendQueue(new C03PacketPlayer.C06PacketPlayerPosLook(
                            preMotionEvent.getPosX(), preMotionEvent.getPosY(), preMotionEvent.getPosZ(),
                            preMotionEvent.getYaw(), preMotionEvent.getPitch(), self.onGround));
                } else if (flag2) {
                    this.sendQueue.addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(
                            preMotionEvent.getPosX(), preMotionEvent.getPosY(), preMotionEvent.getPosZ(),
                            self.onGround));
                } else if (flag3) {
                    this.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
                            preMotionEvent.getYaw(), preMotionEvent.getPitch(), self.onGround));
                } else {
                    this.sendQueue.addToSendQueue(new C03PacketPlayer(self.onGround));
                }
            }

            this.positionUpdateTicks++;

            if (flag2) {
                this.lastReportedPosX = preMotionEvent.getPosX();
                this.lastReportedPosY = preMotionEvent.getPosY();
                this.lastReportedPosZ = preMotionEvent.getPosZ();
                this.positionUpdateTicks = 0;
            }

            if (flag3) {
                this.lastReportedYaw = preMotionEvent.getYaw();
                this.lastReportedPitch = preMotionEvent.getPitch();
            }
        }
        RotationUtils.setFakePitchValue = false;
        MinecraftForge.EVENT_BUS.post(new PostMotionEvent());
    }
}

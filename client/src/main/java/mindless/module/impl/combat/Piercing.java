package mindless.module.impl.combat;

import com.google.common.base.Predicates;
import mindless.module.Module;
import mindless.module.impl.world.TargetFilter;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Piercing extends Module {

    private SliderSetting sortMode;
    private ButtonSetting ignoreBlocks;
    private ButtonSetting ignoreNonPlayer;
    private ButtonSetting weaponOnly;
    private ButtonSetting insideHitboxOnly;

    private int lastMouseOverTick = -1;

    private String[] sortModes = new String[] { "Hurt time", "Health" };

    public Piercing() {
        super("Piercing", "Hits through blocks and the players in front.", category.combat);
        this.registerSetting(sortMode = new SliderSetting("Sort mode", 0, sortModes));
        this.registerSetting(ignoreBlocks = new ButtonSetting("Ignore blocks", false));
        this.registerSetting(ignoreNonPlayer = new ButtonSetting("Ignore non-players", true));
        this.registerSetting(weaponOnly = new ButtonSetting("Weapon only", false));
        this.registerSetting(insideHitboxOnly = new ButtonSetting("Inside hitbox only", false));
    }

    @Override
    public String getInfo() {
        return sortModes[(int) sortMode.getInput()];
    }

    public boolean shouldOverrideMouseOver() {
        if (!this.isEnabled()) {
            return false;
        }
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (this.weaponOnly.isToggled() && !Utils.holdingWeapon()) {
            return false;
        }
        return ignoreBlocks.isToggled()
                || mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK;
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldOverrideMouseOver()) return;
        mindless$modifyMouseOverVanillaLook(partialTicks);
    }

    private void mindless$modifyMouseOverVanillaLook(final float partialTicks) {
        final Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity == null || mc.theWorld == null) {
            return;
        }

        double reach = mc.playerController.getBlockReachDistance();
        final Vec3 eyes = viewEntity.getPositionEyes(partialTicks);
        if (mc.playerController.extendedReach()) {
            reach = 6.0;
        }
        final Vec3 look = viewEntity.getLook(partialTicks);
        final Vec3 rayEnd = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        Entity best = null;
        Vec3 bestHit = null;
        double bestDist = Double.MAX_VALUE;
        boolean bestLiving = false;
        int bestHurt = Integer.MAX_VALUE;
        float bestHp = Float.POSITIVE_INFINITY;
        final int modeSel = (int) this.sortMode.getInput();

        for (final Entity e : mc.theWorld.getEntitiesInAABBexcluding(viewEntity,
                viewEntity.getEntityBoundingBox()
                        .addCoord(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach)
                        .expand(1.0, 1.0, 1.0), Predicates.and(EntitySelectors.NOT_SPECTATING, Entity::canBeCollidedWith)
        )) {
            if ((this.ignoreNonPlayer.isToggled() && !(e instanceof EntityPlayer)) || TargetFilter.shouldFilter(e)) {
                continue;
            }

            final float cb = e.getCollisionBorderSize();
            final AxisAlignedBB bb = e.getEntityBoundingBox().expand(cb, cb, cb);
            final MovingObjectPosition hit = bb.calculateIntercept(eyes, rayEnd);
            final boolean inside = bb.isVecInside(eyes);

            if (!inside && hit == null) continue;
            double dist = inside ? 0.0 : eyes.distanceTo(hit.hitVec);
            if (!mc.playerController.extendedReach() && dist > 3.0) continue;
            if (dist > reach) continue;
            if (dist >= bestDist) continue;
            if (this.insideHitboxOnly.isToggled() && dist > 0.10000000149011612) continue;

            if (e == viewEntity.ridingEntity && !viewEntity.canRiderInteract() && best != null) continue;

            boolean living = e instanceof EntityLivingBase;
            int hurt = living ? ((EntityLivingBase) e).hurtTime : Integer.MAX_VALUE;
            float hp = living ? ((EntityLivingBase) e).getHealth() : Float.POSITIVE_INFINITY;

            boolean take = false;
            if (best == null) {
                take = true;
            }
            else if (living && !bestLiving) {
                take = true;
            }
            else if (living == bestLiving) {
                if (!living) {
                    take = dist < bestDist;
                }
                else if (modeSel == 0) {
                    if (hurt < bestHurt) {
                        take = true;
                    }
                    else if (hurt == bestHurt && dist < bestDist) {
                        take = true;
                    }
                }
                else {
                    if (hp < bestHp) {
                        take = true;
                    }
                    else if (hp == bestHp && dist < bestDist) {
                        take = true;
                    }
                }
            }

            if (take) {
                best = e;
                bestHit = inside ? (hit == null ? eyes : hit.hitVec) : hit.hitVec;
                bestDist = dist;
                bestLiving = living;
                bestHurt = hurt;
                bestHp = hp;
            }
        }

        if (best != null && reach > 3.0 && bestDist > 3.0 && !mc.playerController.extendedReach()) {
            mc.objectMouseOver = new MovingObjectPosition(
                    MovingObjectPosition.MovingObjectType.MISS, bestHit, null, new BlockPos(bestHit)
            );
            return;
        }

        if (best != null) {
            mc.objectMouseOver = new MovingObjectPosition(best, bestHit);
            if (best instanceof EntityLivingBase || best instanceof EntityItemFrame) {
                mc.pointedEntity = best;
            }
        }
    }
}

package mindless.module.impl.combat.aura;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.util.EntitySelectors;

import java.util.Comparator;

public final class AuraTargeting {
    public static float[] rotate(float previousYaw, float previousPitch, float targetYaw, float targetPitch,
                                 float step, float smoothing, float sensitivity, java.util.Random random) {
        float yaw = MathHelper.wrapAngleTo180_float(targetYaw - previousYaw);
        float pitch = targetPitch - previousPitch;
        if (Math.abs(yaw) < .5f) yaw = 0;
        if (Math.abs(pitch) < .5f) pitch = 0;
        float yawLimit = step * (.75f + random.nextFloat() * .25f);
        float pitchLimit = step * (.75f + random.nextFloat() * .25f) * .5f;
        yaw = MathHelper.clamp_float(yaw, -yawLimit, yawLimit);
        pitch = MathHelper.clamp_float(pitch, -pitchLimit, pitchLimit);
        yaw *= 1 - .5f * MathHelper.clamp_float(smoothing / 100 + random.nextFloat() * .1f - .05f, 0, 1);
        pitch *= 1 - .5f * MathHelper.clamp_float(smoothing / 100 + random.nextFloat() * .1f - .05f, 0, 1);
        float f = sensitivity * .6f + .2f;
        float increment = f * f * f * 8 * .15f;
        if (yaw == 0 && pitch != 0) yaw = random.nextBoolean() ? increment : -increment;
        if (yaw != 0 && pitch == 0) pitch = random.nextBoolean() ? increment : -increment;
        return new float[]{previousYaw + Math.round(yaw / increment) * increment,
                MathHelper.clamp_float(previousPitch + Math.round(pitch / increment) * increment, -90, 90)};
    }
    private AuraTargeting() {
    }

    public static double distanceToBounds(AxisAlignedBB bounds, Vec3 position) {
        if (bounds.isVecInside(position)) {
            return 0.0D;
        }
        Vec3 closest = closestPoint(bounds, position);
        return position.distanceTo(closest);
    }

    public static Vec3 closestPoint(AxisAlignedBB bounds, Vec3 position) {
        return new Vec3(
                clamp(position.xCoord, bounds.minX, bounds.maxX),
                clamp(position.yCoord, bounds.minY, bounds.maxY),
                clamp(position.zCoord, bounds.minZ, bounds.maxZ));
    }

    public static float[] aimAtBounds(AxisAlignedBB bounds, Vec3 eye) {
        Vec3 center = new Vec3(
                (bounds.minX + bounds.maxX) * 0.5D,
                (bounds.minY + bounds.maxY) * 0.5D,
                (bounds.minZ + bounds.maxZ) * 0.5D);
        Vec3 closest = closestPoint(bounds, eye);
        double x = center.xCoord + (closest.xCoord - center.xCoord) * 0.875D - eye.xCoord;
        double y = closest.yCoord - eye.yCoord;
        double z = center.zCoord + (closest.zCoord - center.zCoord) * 0.875D - eye.zCoord;
        float[] rotation = rotationsFromDelta(x, y, z);
        double denominator = center.yCoord - bounds.minY;
        float bias = denominator == 0.0D ? 0.0F
                : (float) ((eye.yCoord - center.yCoord) / denominator);
        rotation[1] += 10.0F * MathHelper.clamp_float(bias, -0.5F, 0.5F);
        return rotation;
    }

    public static float[] rotationsFromDelta(double x, double y, double z) {
        double horizontal = Math.sqrt(x * x + z * z);
        return new float[]{
                (float) (Math.atan2(z, x) * 180.0D / Math.PI) - 90.0F,
                (float) (-Math.atan2(y, horizontal) * 180.0D / Math.PI)};
    }

    public static float boundsAimError(AxisAlignedBB bounds, Vec3 eye, float currentYaw,
                                       float currentPitch) {
        if (bounds.isVecInside(eye)) {
            return 0.0F;
        }
        double traceDistance = 30.0D + bounds.maxX - bounds.minX
                + bounds.maxZ - bounds.minZ + bounds.maxY - bounds.minY;
        if (intersectBounds(bounds, eye, currentYaw, currentPitch, traceDistance) != null) {
            return 0.0F;
        }
        float[] aim = aimAtBounds(bounds, eye);
        float yawError = Math.abs(MathHelper.wrapAngleTo180_float(aim[0] - currentYaw));
        float pitchError = Math.abs(aim[1] - currentPitch);
        return Math.max(yawError * 2.0F, pitchError * 2.0F);
    }

    public static MovingObjectPosition intersectBounds(AxisAlignedBB bounds, Vec3 eye,
                                                        float yaw, float pitch, double range) {
        Vec3 direction = direction(pitch, yaw);
        Vec3 end = eye.addVector(direction.xCoord * range, direction.yCoord * range,
                direction.zCoord * range);
        return bounds.calculateIntercept(eye, end);
    }

    public static Entity nearestEntity(Iterable<Entity> entities, Entity viewer, Vec3 eye,
                                       float yaw, float pitch, double range) {
        Entity nearest = null;
        double distance = range;
        for (Entity entity : entities) {
            if (entity == viewer || !entity.canBeCollidedWith() || !EntitySelectors.NOT_SPECTATING.apply(entity)) continue;
            float border = entity.getCollisionBorderSize();
            AxisAlignedBB bounds = entity.getEntityBoundingBox().expand(border, border, border);
            boolean inside = bounds.isVecInside(eye);
            if (!inside && entity == viewer.ridingEntity && !viewer.canRiderInteract()) continue;
            MovingObjectPosition hit = intersectBounds(bounds, eye, yaw, pitch, range);
            if (!inside && hit == null) continue;
            double intercept = inside ? 0 : eye.distanceTo(hit.hitVec);
            if (intercept < distance || nearest == null && intercept <= distance) {
                nearest = entity;
                distance = intercept;
            }
        }
        return nearest;
    }

    public static Comparator<AuraTarget> comparator(final int sortMode) {
        return (first, second) -> {
            int result;
            switch (sortMode) {
                case 1:
                    result = Double.compare(healthScore(first), healthScore(second));
                    break;
                case 2:
                    result = Integer.compare(first.entity.hurtResistantTime,
                            second.entity.hurtResistantTime);
                    break;
                case 3:
                    result = Float.compare(first.angularDistance, second.angularDistance);
                    break;
                default:
                    result = Double.compare(first.distance, second.distance);
                    break;
            }
            return result != 0 ? result : Double.compare(first.distance, second.distance);
        };
    }

    public static double healthScore(AuraTarget target) {
        return healthScore(target.entity.getHealth(), target.entity.getTotalArmorValue());
    }

    public static double healthScore(float health, int armor) {
        if (armor <= 0 && health > 0.0F) {
            return Double.POSITIVE_INFINITY;
        }
        return health * (20.0D / Math.max(1, armor));
    }

    public static void prefer(java.util.List<AuraTarget> candidates, double swingRange, double attackRange) {
        if (candidates.stream().anyMatch(c -> c.usableAim)) candidates.removeIf(c -> !c.usableAim);
        if (candidates.stream().anyMatch(c -> c.distance <= swingRange)) candidates.removeIf(c -> c.distance > swingRange);
        if (candidates.stream().anyMatch(c -> c.distance <= attackRange)) candidates.removeIf(c -> c.distance > attackRange);
        if (candidates.stream().anyMatch(c -> c.enemy)) candidates.removeIf(c -> !c.enemy);
    }

    private static Vec3 direction(float pitch, float yaw) {
        float yawRadians = -yaw * ((float) Math.PI / 180.0F) - (float) Math.PI;
        float pitchRadians = -pitch * ((float) Math.PI / 180.0F);
        float cosYaw = MathHelper.cos(yawRadians);
        float sinYaw = MathHelper.sin(yawRadians);
        float cosPitch = -MathHelper.cos(pitchRadians);
        float sinPitch = MathHelper.sin(pitchRadians);
        return new Vec3(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

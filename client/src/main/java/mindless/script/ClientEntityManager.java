package mindless.script;

import mindless.script.model.Entity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

public final class ClientEntityManager {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Map<net.minecraft.entity.Entity, ClientEntity> clientEntities = new IdentityHashMap<>();
    private static final Map<String, String> aliases = new HashMap<>();
    private static int nextEntityId = -1;

    static {
        aliases.put("wither", "WitherBoss");
        aliases.put("irongolem", "VillagerGolem");
        aliases.put("snowgolem", "SnowMan");
        aliases.put("mooshroom", "MushroomCow");
        aliases.put("zombiepigman", "PigZombie");
        aliases.put("magmacube", "LavaSlime");
        aliases.put("ocelot", "Ozelot");
        aliases.put("horse", "EntityHorse");
    }

    private ClientEntityManager() {
    }

    public static Entity spawnMob(final String type, final double x, final double y, final double z) {
        return callOnClientThread(() -> spawnMobNow(type, x, y, z), null);
    }

    public static boolean removeClientEntity(final Entity entity) {
        return callOnClientThread(() -> removeClientEntityNow(entity), false);
    }

    public static void clearClientEntities() {
        callOnClientThread(() -> {
            clearClientEntitiesNow();
            return true;
        }, false);
    }

    private static Entity spawnMobNow(String type, double x, double y, double z) {
        WorldClient world = mc.theWorld;
        String entityName = resolveEntityName(type);
        if (world == null || entityName == null) {
            return null;
        }

        net.minecraft.entity.Entity entity = EntityList.createEntityByName(entityName, world);
        if (!(entity instanceof EntityLivingBase)) {
            return null;
        }

        int entityId = allocateEntityId(world);
        entity.setPositionAndRotation(x, y, z, 0.0F, 0.0F);
        world.addEntityToWorld(entityId, entity);
        clientEntities.put(entity, new ClientEntity(world, entity, entityId));
        return Entity.convert(entity);
    }

    private static boolean removeClientEntityNow(Entity entity) {
        if (entity == null || entity.entity == null) {
            return false;
        }

        ClientEntity clientEntity = clientEntities.remove(entity.entity);
        if (clientEntity == null) {
            return false;
        }

        removeFromWorld(clientEntity);
        return true;
    }

    private static void clearClientEntitiesNow() {
        List<ClientEntity> entities = new ArrayList<>(clientEntities.values());
        clientEntities.clear();
        for (ClientEntity entity : entities) {
            removeFromWorld(entity);
        }
        nextEntityId = -1;
    }

    private static void removeFromWorld(ClientEntity clientEntity) {
        if (clientEntity.world.getEntityByID(clientEntity.entityId) == clientEntity.entity) {
            clientEntity.world.removeEntityFromWorld(clientEntity.entityId);
        }
        else if (clientEntity.world.loadedEntityList.contains(clientEntity.entity)) {
            clientEntity.world.removeEntity(clientEntity.entity);
        }
    }

    private static int allocateEntityId(WorldClient world) {
        while (world.getEntityByID(nextEntityId) != null) {
            decrementEntityId();
        }
        int entityId = nextEntityId;
        decrementEntityId();
        return entityId;
    }

    private static void decrementEntityId() {
        nextEntityId = nextEntityId == Integer.MIN_VALUE ? -1 : nextEntityId - 1;
    }

    private static String resolveEntityName(String type) {
        if (type == null || type.trim().isEmpty()) {
            return null;
        }

        String normalizedType = normalize(type);
        String alias = aliases.get(normalizedType);
        if (alias != null) {
            return alias;
        }

        for (String entityName : EntityList.stringToClassMapping.keySet()) {
            if (normalize(entityName).equals(normalizedType)) {
                return entityName;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized.replace("_", "").replace("-", "").replace(" ", "");
    }

    private static <T> T callOnClientThread(Callable<T> callable, T fallback) {
        try {
            if (mc.isCallingFromMinecraftThread()) {
                return callable.call();
            }
            return mc.addScheduledTask(callable).get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        }
        catch (Exception e) {
            return fallback;
        }
    }

    private static final class ClientEntity {
        private final WorldClient world;
        private final net.minecraft.entity.Entity entity;
        private final int entityId;

        private ClientEntity(WorldClient world, net.minecraft.entity.Entity entity, int entityId) {
            this.world = world;
            this.entity = entity;
            this.entityId = entityId;
        }
    }
}

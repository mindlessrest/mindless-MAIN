package mindless.module.impl.combat;

import mindless.Mindless;
import mindless.event.*;
import mindless.helper.RotationHelper;
import mindless.lag.api.*;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.*;
import mindless.module.impl.combat.aura.*;
import mindless.module.impl.world.AntiBot;
import mindless.module.impl.render.HUD;
import mindless.rotation.RotationSource;
import mindless.runtime.AccessorBridge;
import mindless.runtime.CombatPacketState;
import mindless.utility.Utils;
import mindless.utility.RenderUtils;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.*;
import net.minecraft.entity.boss.*;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.*;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.*;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import java.util.*;
import static mindless.module.impl.combat.aura.AuraAutoBlockController.*;

public class KillAura extends Module {
    public static EntityLivingBase target, attackingEntity;
    private AuraTarget currentTarget;
    private boolean hasTargetInAutoBlockRange, attackedSinceSelection, combatReady;
    private int targetIndex, lastMode = -1, lastRotation = -1, visibleSlot = -1, temporarySlot = -1;
    private long selectedAt, selectionTick = Long.MIN_VALUE, actionTick = Long.MIN_VALUE;
    private long postTick = Long.MIN_VALUE, cooldown;
    private Object world;
    private EntityLivingBase player;
    private boolean cleanupPending, performingClick;
    private int cleanupAttempts;
    private long cleanupTick = Long.MIN_VALUE;
    private EntityLivingBase lastHudTarget;
    private long lastHudAt;
    private final AuraAutoBlockController block = new AuraAutoBlockController();
    private DelayLease lease;
    private int hurtTicks, lastDamageLogTick = -1;
    private long seenBlockRevision;
    private final Map<SliderSetting, Double> defaults = new LinkedHashMap<>();
    private double previousSwing, previousAttack, previousMin, previousMax;
    private final Random random = new Random();
    public final SliderSetting mode;
    public final SliderSetting sort;
    public final SliderSetting autoBlock;
    public final ButtonSetting autoBlockRequirePress;
    public final ButtonSetting autoBlockNoSlow;
    public final SliderSetting autoBlockHold;
    public final SliderSetting autoBlockDelay;
    public final SliderSetting autoBlockHurtTime;
    public final SliderSetting autoBlockRange;
    public final SliderSetting swingRange;
    public final SliderSetting attackRange;
    public final SliderSetting fov;
    public final SliderSetting minAps;
    public final SliderSetting maxAps;
    public final SliderSetting switchDelay;
    public final SliderSetting rotationMode;
    public final SliderSetting moveFix;
    public final SliderSetting smoothing;
    public final SliderSetting angleStep;
    public final ButtonSetting throughWalls;
    public final ButtonSetting requirePress;
    public final ButtonSetting allowMining;
    public final ButtonSetting weaponsOnly;
    public final ButtonSetting allowTools;
    public final ButtonSetting inventoryCheck;
    public final ButtonSetting botCheck;
    public final ButtonSetting players;
    public final ButtonSetting bosses;
    public final ButtonSetting mobs;
    public final ButtonSetting animals;
    public final ButtonSetting golems;
    public final ButtonSetting silverfish;
    public final ButtonSetting teams;
    public final SliderSetting showTarget;
    public final SliderSetting debugLog;
    public final ButtonSetting killNotification;

    public KillAura() {
        super("Kill Aura", "Attacks targets in range.", category.combat);
        liteModule = true;
        registerSetting(mode = new SliderSetting("Mode", 1, new String[]{"Single", "Switch"}));
        registerSetting(sort = new SliderSetting("Sort mode", 1, new String[]{"Distance", "Health", "Hurt time", "FOV"}));
        registerSetting(autoBlock = new SliderSetting("Auto block mode", 7, new String[]{"None", "Vanilla", "Hypixel", "Blink", "Interact", "Spoof", "Swap", "Legit", "Fake"}));
        registerSetting(autoBlockRequirePress = new ButtonSetting("Auto block require press", true));
        registerSetting(autoBlockNoSlow = new ButtonSetting("Auto block no slow", false));
        registerSetting(autoBlockHold = new SliderSetting("Auto block hold", 1.5, 1, 20, 0.1));
        registerSetting(autoBlockDelay = new SliderSetting("Auto block delay", 0, 0, 20, 0.1));
        registerSetting(autoBlockHurtTime = new SliderSetting("Auto block hurt time", 6, 0, 10, 1));
        registerSetting(autoBlockRange = new SliderSetting("Auto block range", 3.1, 3, 8, 0.05));
        registerSetting(swingRange = new SliderSetting("Range (swing)", 3.2, 3, 6, 0.05));
        registerSetting(attackRange = new SliderSetting("Range (attack)", 3, 3, 6, 0.05));
        registerSetting(fov = new SliderSetting("FOV", 360, 30, 360, 1));
        registerSetting(minAps = new SliderSetting("Min APS", 14, 1, 20, 1));
        registerSetting(maxAps = new SliderSetting("Max APS", 14, 1, 20, 1));
        registerSetting(switchDelay = new SliderSetting("Switch delay", 70, 0, 1000, 1));
        registerSetting(rotationMode = new SliderSetting("Rotation mode", 0, new String[]{"Silent", "Lock view", "None", "Legit"}));
        registerSetting(moveFix = new SliderSetting("Move fix", 1, new String[]{"None", "Silent", "Strict"}));
        registerSetting(smoothing = new SliderSetting("Smoothing", 0, 0, 100, 1));
        registerSetting(angleStep = new SliderSetting("Angle step", 180, 30, 180, 1));
        registerSetting(throughWalls = new ButtonSetting("Hit through walls", false));
        registerSetting(requirePress = new ButtonSetting("Require mouse down", false));
        registerSetting(allowMining = new ButtonSetting("Allow mining", true));
        registerSetting(weaponsOnly = new ButtonSetting("Weapon only", true));
        registerSetting(allowTools = new ButtonSetting("Allow tools", false));
        registerSetting(inventoryCheck = new ButtonSetting("Inventory check", true));
        registerSetting(botCheck = new ButtonSetting("Bot check", true));
        registerSetting(players = new ButtonSetting("Players", true));
        registerSetting(bosses = new ButtonSetting("Bosses", false));
        registerSetting(mobs = new ButtonSetting("Mobs", false));
        registerSetting(animals = new ButtonSetting("Animals", false));
        registerSetting(golems = new ButtonSetting("Golems", true));
        registerSetting(silverfish = new ButtonSetting("Silverfish", true));
        registerSetting(teams = new ButtonSetting("Teams", true));
        registerSetting(showTarget = new SliderSetting("Show target", 2, new String[]{"None", "Default", "HUD"}));
        registerSetting(debugLog = new SliderSetting("Debug log", 0, new String[]{"None", "Health"}));
        registerSetting(killNotification = new ButtonSetting("Kill notification", true));
        for (Setting setting : settings) {
            setting.captureDefaultOnce();
            if (setting instanceof SliderSetting) defaults.put((SliderSetting) setting, ((SliderSetting) setting).getInput());
        }
        rememberConstraints();
    }

    @SubscribeEvent
    public void onPlayerKill(PlayerKillEvent event) {
        if (killNotification.isToggled()) {
            mindless.module.impl.render.Notifications.notify(event.playerName, "Target neutralized.", true);
        }
    }

    public boolean normalizeSettings() {
        boolean changed = false;
        for (Map.Entry<SliderSetting, Double> entry : defaults.entrySet()) {
            SliderSetting setting = entry.getKey();
            double old = setting.getInput();
            double value = Double.isFinite(old) ? old : entry.getValue();
            setting.setValue(value);
            changed |= old != setting.getInput();
        }
        if (swingRange.getInput() < attackRange.getInput()) { swingRange.setValue(attackRange.getInput()); changed = true; }
        if (maxAps.getInput() < minAps.getInput()) { maxAps.setValue(minAps.getInput()); changed = true; }
        rememberConstraints();
        return changed;
    }

    public void loadSettings(com.google.gson.JsonObject data) {
        for (Setting setting : settings) {
            setting.resetToDefault();
            com.google.gson.JsonElement value = data == null ? null : data.get(setting.getProfileKey());
            if (value == null || !value.isJsonPrimitive()) continue;
            com.google.gson.JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (setting instanceof SliderSetting && primitive.isNumber()) {
                double number = primitive.getAsDouble();
                if (Double.isFinite(number)) ((SliderSetting) setting).setValueRaw(number);
            } else if (setting instanceof ButtonSetting && primitive.isBoolean()) {
                ((ButtonSetting) setting).setEnabled(primitive.getAsBoolean());
            }
        }
        normalizeSettings();
    }

    private void rememberConstraints() {
        previousSwing = swingRange.getInput(); previousAttack = attackRange.getInput();
        previousMin = minAps.getInput(); previousMax = maxAps.getInput();
    }

    public void settingsEdited() {
        if (swingRange.getInput() != previousSwing && swingRange.getInput() < attackRange.getInput())
            attackRange.setValue(swingRange.getInput());
        else if (attackRange.getInput() != previousAttack && attackRange.getInput() > swingRange.getInput())
            swingRange.setValue(attackRange.getInput());
        if (minAps.getInput() != previousMin && minAps.getInput() > maxAps.getInput()) maxAps.setValue(minAps.getInput());
        else if (maxAps.getInput() != previousMax && maxAps.getInput() < minAps.getInput()) minAps.setValue(maxAps.getInput());
        normalizeSettings();
    }

    @Override public void guiUpdate() {
        settingsEdited();
        int mode = (int) autoBlock.getInput();
        autoBlockRange.setVisible(mode != NONE, this);
        autoBlockRequirePress.setVisible(mode != NONE, this);
        autoBlockNoSlow.setVisible(mode != NONE, this);
        autoBlockHold.setVisible(mode != NONE && mode != FAKE, this);
        autoBlockHurtTime.setVisible(mode != NONE && mode != FAKE, this);
        autoBlockDelay.setVisible(mode != NONE && mode != VANILLA && mode != FAKE, this);
        allowTools.setVisible(weaponsOnly.isToggled(), this);
    }

    @Override public String getInfo() { return mode.getSelectedOption(); }
    public boolean isRequireMouseDown() { return requirePress.isToggled(); }
    public boolean isSilentRotation() { return isEnabled() && rotationMode.getInput() == 0; }
    public boolean usesMovementYaw() { return combatReady && (moveFix.getInput() != 0 || rotationMode.getInput() == 1); }
    public boolean usesSilentMoveFix() { return combatReady && moveFix.getInput() == 1 && rotationMode.getInput() != 1; }
    public boolean ownsCombatInteractions() { return isEnabled() && combatReady && target != null; }
    public boolean ownsAutoBlock() { return ownsCombatInteractions() && autoBlock.getInput() != NONE && canAutoBlock(); }
    public boolean shouldSuppressClicks() { return !performingClick && ownsCombatInteractions() && (block.active || currentTarget.distance <= swingRange.getInput()); }
    public boolean shouldSuppressStopUse() { return isEnabled() && combatReady && block.active; }
    public boolean shouldRenderBlocking() { return isEnabled() && combatReady && block.render && Utils.holdingSword(); }
    public boolean allowsAuraNoSlow() { return ownsAutoBlock() && block.active && autoBlockNoSlow.isToggled(); }
    public boolean isBlockingSword() {
        if (!Utils.nullCheck()) return false;
        long revision = CombatPacketState.blockRevision();
        if (seenBlockRevision != revision) {
            seenBlockRevision = revision;
            if (block.active && !CombatPacketState.serverBlocking()) mc.thePlayer.stopUsingItem();
        }
        return Utils.holdingSword() && (mc.thePlayer.isUsingItem() || block.active && CombatPacketState.serverBlocking());
    }
    public boolean hasCombatCandidate() { return isEnabled() && currentTarget != null && basicCombatGate(); }

    public EntityLivingBase getHudTarget() {
        if (!isEnabled() || !combatReady || !Utils.nullCheck()) return null;
        if (target != null) { lastHudTarget = target; lastHudAt = System.currentTimeMillis(); return target; }
        return System.currentTimeMillis() - lastHudAt <= 150 && lastHudTarget != null
                && mc.theWorld.loadedEntityList.contains(lastHudTarget) ? lastHudTarget : null;
    }

    public static boolean keyDown(KeyBinding key) {
        if (mc == null || mc.currentScreen != null || key == null) return false;
        int code = key.getKeyCode();
        return code < 0 ? code >= -100 && Mouse.isCreated() && Mouse.isButtonDown(code + 100)
                : code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isCreated() && Keyboard.isKeyDown(code);
    }

    public boolean qualifies(ItemStack stack) {
        if (!weaponsOnly.isToggled()) return true;
        return isWeapon(stack) || allowTools.isToggled() && stack != null && stack.getItem() instanceof ItemTool;
    }

    public static boolean isWeapon(ItemStack stack) {
        if (stack == null) return false;
        if (stack.hasTagCompound()) {
            net.minecraft.nbt.NBTTagCompound tag = stack.getTagCompound();
            long id = tag.getCompoundTag("ExtraAttributes").getLong("UHCid");
            if (id == 50006L || id == 50009L || "minecraft:mace".equals(tag.getString("ItemModel"))) return true;
            if (tag.hasKey("HideFlags") && stack.getItem() instanceof ItemSpade
                    && "EMERALD".equals(((ItemSpade) stack.getItem()).getToolMaterialName())) return true;
        }
        return !(stack.getItem() instanceof ItemEnchantedBook)
                && (stack.getItem() instanceof ItemSword || EnchantmentHelper.getEnchantmentLevel(19, stack) > 0);
    }

    private boolean basicCombatGate() {
        if (!Utils.nullCheck() || mc.thePlayer.isDead || currentTarget == null
                || !mc.theWorld.loadedEntityList.contains(currentTarget.entity) || currentTarget.entity.isDead) return false;
        if (inventoryCheck.isToggled() && mc.currentScreen instanceof GuiContainer) return false;
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) return false;
        if (ModuleManager.bedAura != null && !ModuleManager.bedAura.isPrioritizingKillAura()
                && ModuleManager.bedAura.controlsInteractions()) return false;
        if (ModuleManager.displace != null && ModuleManager.displace.shouldDeferKillAuraAttack()) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null && keyDown(mc.gameSettings.keyBindUseItem)
                && (held.getItem() instanceof ItemBow || held.getItemUseAction() == EnumAction.EAT
                || held.getItemUseAction() == EnumAction.DRINK)) return false;
        boolean attack = keyDown(mc.gameSettings.keyBindAttack);
        if (allowMining.isToggled() && attack) {
            MovingObjectPosition hit = mc.thePlayer.rayTrace(mc.playerController.getBlockReachDistance(), 1.0F);
            if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) return false;
        }
        return !requirePress.isToggled() || attack;
    }

    private boolean canRunCombat() {
        if (!basicCombatGate()) return false;
        int weapon = ModuleManager.autoWeapon == null ? -1 : ModuleManager.autoWeapon.auraWeaponSlot(this);
        return qualifies(weapon < 0 ? mc.thePlayer.getHeldItem() : mc.thePlayer.inventory.getStackInSlot(weapon));
    }

    private boolean canAutoBlock() {
        if (!Utils.holdingSword()) return false;
        int mode = (int) autoBlock.getInput();
        if (mode == NONE) return true;
        if (mode == FAKE) return hasTargetInAutoBlockRange;
        return !autoBlockRequirePress.isToggled() && hasTargetInAutoBlockRange || keyDown(mc.gameSettings.keyBindUseItem);
    }

    private boolean hasTeamArmorStand(EntityLivingBase entity) {
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        if (info == null || info.getPlayerTeam() == null || info.getPlayerTeam().getColorPrefix().length() < 2) return false;
        EntityLivingBase stand = mc.theWorld.findNearestEntityWithinAABB(EntityArmorStand.class, entity.getEntityBoundingBox(), entity);
        return stand != null && stand.getName().contains(info.getPlayerTeam().getColorPrefix().substring(0, 2));
    }

    private boolean obstructed(AxisAlignedBB bounds, Vec3 eyes, float yaw, float pitch, double range) {
        MovingObjectPosition hit = AuraTargeting.intersectBounds(bounds, eyes, yaw, pitch, range);
        if (bounds.isVecInside(eyes)) return false;
        if (hit == null) return true;
        MovingObjectPosition wall = mc.theWorld.rayTraceBlocks(eyes, hit.hitVec);
        return wall != null && wall.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && eyes.squareDistanceTo(wall.hitVec) < eyes.squareDistanceTo(hit.hitVec);
    }

   private boolean isValidTargetEntity(EntityLivingBase candidate) {
      if (candidate == mc.thePlayer
         || candidate == mc.thePlayer.ridingEntity
         || candidate == mc.getRenderViewEntity()
         || candidate == mc.getRenderViewEntity().ridingEntity) {
         return false;
      }
      return this.passesEntityCategoryFilters(candidate);
   }

   private boolean passesEntityCategoryFilters(EntityLivingBase candidate) {
      if (candidate.deathTime > 0 || candidate.isDead) {
         return false;
      }

      if (candidate instanceof EntityOtherPlayerMP) {
         if (!this.players.isToggled()) {
            return false;
         }
         EntityPlayer player = (EntityPlayer)candidate;
         if (Utils.isFriended(player)) {
            return false;
         }
         if (Utils.isEnemy(player)) {
            return true;
         }
         if (this.teams.isToggled() && Utils.isTeammate(player)) {
            return false;
         }
         return !this.botCheck.isToggled() || !AntiBot.isBot(player);
      }

      if (candidate instanceof EntityDragon || candidate instanceof EntityWither) {
         return this.bosses.isToggled();
      }

      if (candidate instanceof EntityMob || candidate instanceof EntitySlime) {
         if (candidate instanceof EntitySilverfish) {
            if (!this.silverfish.isToggled()) {
               return false;
            }
            return !this.teams.isToggled() || !this.hasTeamArmorStand(candidate);
         }
         return this.mobs.isToggled();
      }

      if (candidate instanceof EntityAnimal
         || candidate instanceof EntityBat
         || candidate instanceof EntitySquid
         || candidate instanceof EntityVillager) {
         return this.animals.isToggled();
      }

      if (candidate instanceof EntityIronGolem) {
         if (!this.golems.isToggled()) {
            return false;
         }
         return !this.teams.isToggled() || !this.hasTeamArmorStand(candidate);
      }

      return false;
   }

   private boolean isWithinAnyCombatRange(double distance) {
      return this.isWithinAutoBlockRange(distance)
         || this.isWithinSwingRange(distance)
         || this.isWithinAttackRange(distance);
   }

   private boolean isWithinAutoBlockRange(double distance) {
      return this.autoBlock.getInput() != NONE
         && distance <= this.autoBlockRange.getInput();
   }

   private boolean isWithinSwingRange(double distance) {
      return distance <= this.swingRange.getInput();
   }

   private boolean isWithinAttackRange(double distance) {
      return distance <= this.attackRange.getInput();
   }

   private boolean isEnemyPlayer(EntityLivingBase entity) {
      return entity instanceof EntityPlayer && Utils.isEnemy((EntityPlayer)entity);
   }

   private int findSwapSlot(int excludedSlot) {
      for (int slot = 0; slot < 9; slot++) {
         if (slot != excludedSlot && mc.thePlayer.inventory.getStackInSlot(slot) == null) {
            return slot;
         }
      }
      for (int slot = 0; slot < 9; slot++) {
         ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
         if (slot != excludedSlot && stack != null && !stack.hasDisplayName()) {
            return slot;
         }
      }
      return Math.floorMod(excludedSlot - 1, 9);
   }

   private int findAlternateSwordSlot(int excludedSlot) {
      for (int slot = 0; slot < 9; slot++) {
         if (slot == excludedSlot) {
            continue;
         }
         ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
         if (stack != null && stack.getItem() instanceof ItemSword) {
            return slot;
         }
      }
      return -1;
   }

   private ArrayList<AuraTarget> collectTargetCandidates() {
      Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
      ArrayList<AuraTarget> candidates = new ArrayList<>();

      for (Entity entity : mc.theWorld.loadedEntityList) {
         if (!(entity instanceof EntityLivingBase)) {
            continue;
         }
         EntityLivingBase living = (EntityLivingBase)entity;
         if (!this.isValidTargetEntity(living)) {
            continue;
         }

         double border = living.getCollisionBorderSize();
         AxisAlignedBB bounds = living.getEntityBoundingBox().expand(border, border, border);
         double distance = AuraTargeting.distanceToBounds(bounds, eyes);
         if (!this.isWithinAnyCombatRange(distance)) {
            continue;
         }

         float angularDistance = AuraTargeting.boundsAimError(bounds, eyes, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
         if (angularDistance > this.fov.getInput()) {
            continue;
         }

         float[] aim = AuraTargeting.aimAtBounds(bounds, eyes);
         float yaw = aim[0];
         float pitch = aim[1];
         boolean usableAim = true;
         if (!this.throughWalls.isToggled()
            && obstructed(bounds, eyes, yaw, pitch, 8.0)) {
            if (obstructed(
               bounds, eyes, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, 8.0
            )) {
               usableAim = false;
            } else {
               yaw = mc.thePlayer.rotationYaw;
               pitch = mc.thePlayer.rotationPitch;
            }
         }

         candidates.add(new AuraTarget(
            living, bounds, living.posX, living.posY, living.posZ,
            yaw, pitch, distance, angularDistance, usableAim, isEnemyPlayer(living)
         ));
      }
      return candidates;
   }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onTick(GameTickEvent event) { prepare(); }

    private void prepare() {
        if (world != mc.theWorld || player != mc.thePlayer) { onWorldChange(); world = mc.theWorld; player = mc.thePlayer; }
        if (!isEnabled() || !Utils.nullCheck() || mc.getRenderViewEntity() == null) { clearFacade(); return; }
        long tick = Utils.getBaseClientTick();
        if (selectionTick == tick) return;
        selectionTick = tick;
        if (lastMode != (int) autoBlock.getInput() || lastRotation != (int) rotationMode.getInput()) {
            releaseOwned(true);
            lastMode = (int) autoBlock.getInput();
            lastRotation = (int) rotationMode.getInput();
        }
        int slot = mc.thePlayer.inventory.currentItem;
        if (visibleSlot != -1 && visibleSlot != slot) { temporarySlot = -1; releaseOwned(true); }
        visibleSlot = slot;
        if (hurtTicks > 0) hurtTicks--;
        if (CombatPacketState.consumeHurt()) hurtTicks = mc.thePlayer.hurtTime;
        Float delta = CombatPacketState.consumeHealthDelta();
        if (delta != null && debugLog.getInput() == 1
                && lastDamageLogTick != mc.thePlayer.ticksExisted) {
            Utils.sendMessage(String.format(Locale.ROOT, "Health: %+.1f", delta));
            lastDamageLogTick = mc.thePlayer.ticksExisted;
        }
        ArrayList<AuraTarget> candidates = collectTargetCandidates();
        selectCandidates(candidates, System.currentTimeMillis());
        combatReady = canRunCombat();
        if (combatReady) {
            target = currentTarget.entity;
            attackingEntity = isWithinAttackRange(currentTarget.distance) ? target : null;
        } else { clearFacade(); releaseOwned(true); RotationHelper.get().release(RotationSource.KILL_AURA); }
    }

    private void selectCandidates(List<AuraTarget> candidates, long now) {
        hasTargetInAutoBlockRange = candidates.stream().anyMatch(c -> isWithinAutoBlockRange(c.distance));
        if (rotationMode.getInput() == 3) {
            Entity nearest = AuraTargeting.nearestEntity(mc.theWorld.loadedEntityList, mc.thePlayer,
                    mc.thePlayer.getPositionEyes(1.0F), mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, swingRange.getInput());
            candidates.removeIf(candidate -> candidate.entity != nearest);
        }
        AuraTargeting.prefer(candidates, swingRange.getInput(), attackRange.getInput());
        AuraTarget retained = null;
        if (currentTarget != null && now - selectedAt < switchDelay.getInput()) {
            for (AuraTarget candidate : candidates) if (candidate.entity == currentTarget.entity) retained = candidate;
        }
        if (retained != null) currentTarget = retained;
        else if (candidates.isEmpty()) currentTarget = null;
        else {
            candidates.sort(AuraTargeting.comparator((int) sort.getInput()));
            if (mode.getInput() == 1 && attackedSinceSelection) { targetIndex++; attackedSinceSelection = false; }
            if (mode.getInput() == 0 || targetIndex >= candidates.size()) targetIndex = 0;
            currentTarget = candidates.get(targetIndex);
            selectedAt = now;
        }
    }

    @SubscribeEvent
    public void onRotation(ClientRotationEvent event) {
        prepare();
        if (!combatReady || !currentTarget.usableAim || !isWithinSwingRange(currentTarget.distance)
                || rotationMode.getInput() >= 2) return;
        float previousYaw = AccessorBridge.EntityPlayerSP_getLastReportedYaw(mc.thePlayer);
        float previousPitch = AccessorBridge.EntityPlayerSP_getLastReportedPitch(mc.thePlayer);
        float[] rotation = AuraTargeting.rotate(previousYaw, previousPitch, currentTarget.yaw, currentTarget.pitch,
                (float) angleStep.getInput(), (float) smoothing.getInput(), mc.gameSettings.mouseSensitivity, random);
        event.requestRotation(RotationSource.KILL_AURA, rotation[0], rotation[1]);
    }

    public void beforePlayerInteraction() {
        prepare();
        if (cleanupPending && !releaseOwned(true)) { clearFacade(); return; }
        if (!isEnabled() || !Utils.nullCheck()) return;
        long tick = Utils.getBaseClientTick();
        if (actionTick == tick) return;
        actionTick = tick;
        cooldown = AuraTiming.decrement(cooldown);
        RotationHelper rotations = RotationHelper.get();
        final float yaw = rotations.getServerYaw() == null ? mc.thePlayer.rotationYaw : rotations.getServerYaw();
        final float pitch = rotations.getServerPitch() == null ? mc.thePlayer.rotationPitch : rotations.getServerPitch();
        combatReady = canRunCombat();
        if (rotationMode.getInput() < 2 && currentTarget != null && currentTarget.usableAim
                && isWithinSwingRange(currentTarget.distance) && (rotations.getServerYawSource() != RotationSource.KILL_AURA
                || rotations.getServerPitchSource() != RotationSource.KILL_AURA)) combatReady = false;
        int mode = (int) autoBlock.getInput();
        if (mode >= HYPIXEL && mode <= INTERACT && (Mindless.packetDelayService == null
                || Mindless.packetDelayService.hasOtherOutboundOwner("KillAuraAutoBlock"))) {
            combatReady = false;
            mindless.utility.Diagnostics.log("kill-aura", "Buffered autoblock paused: outbound delay service unavailable or owned by another module");
        }
        if (!combatReady) { clearFacade(); releaseOwned(true); RotationHelper.get().release(RotationSource.KILL_AURA); return; }
        if (rotationMode.getInput() == 1) {
            mc.thePlayer.rotationYaw = yaw; mc.thePlayer.rotationPitch = pitch;
        }
        if (ModuleManager.autoBlock != null && ownsAutoBlock()) ModuleManager.autoBlock.yieldToAura();
        if (!restoreTemporarySlot()) {
            cleanupPending = true;
            cleanupTick = tick;
            cleanupAttempts++;
            releaseBuffer();
            clearFacade();
            return;
        }
            block.step(mode, true, canAutoBlock(), keyDown(mc.gameSettings.keyBindUseItem),
                    hurtTicks, (int) autoBlockHurtTime.getInput(), (long) (autoBlockHold.getInput() * 50),
                    (long) (autoBlockDelay.getInput() * 50), new AuraAutoBlockController.Actions() {
                public boolean blocking() { return isBlockingSword(); }
                public boolean clearInterval() { return !CombatPacketState.sentDigging() && !CombatPacketState.sentBlockPlacement(); }
                public boolean slotsMatch() { return mc.thePlayer.inventory.currentItem == controllerSlot(); }
                public boolean release(boolean slot) { return releaseBlocking() && (!slot || changeSlot(findSwapSlot(controllerSlot()))); }
                public boolean spoof() {
                    if (isBlockingSword() && !releaseBlocking()) return false;
                    int original = controllerSlot();
                    return changeSlot(findSwapSlot(original)) && changeSlot(original);
                }
                public boolean swapSword() {
                    int sword = findAlternateSwordSlot(controllerSlot());
                    if (sword < 0) return releaseBlocking();
                    return (!isBlockingSword() || releaseBlocking()) && changeSlot(sword)
                            && startBlocking(mc.thePlayer.inventory.getStackInSlot(sword));
                }
                public boolean attack() { return tryAttack(yaw, pitch); }
                public boolean block(boolean attacked) { return startRequestedBlock(attacked, yaw, pitch); }
                public void releaseBuffer() { KillAura.this.releaseBuffer(); }
            });
    }

    private boolean tryAttack(float yaw, float pitch) {
        if (!currentTarget.usableAim || !isWithinSwingRange(currentTarget.distance) || cooldown > 0
                || CombatPacketState.sentDigging() || CombatPacketState.sentBlockPlacement()
                || mc.thePlayer.isUsingItem()) return false;
        int restore = ModuleManager.autoWeapon == null ? -1 : ModuleManager.autoWeapon.prepareAura(this);
        try {
            return qualifies(mc.thePlayer.getHeldItem()) && executeAttack(yaw, pitch);
        } finally {
            if (restore >= 0) ModuleManager.autoWeapon.finishAura(restore);
        }
    }

    private boolean executeAttack(float yaw, float pitch) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        boolean hit = rotationMode.getInput() == 2 ? isWithinAttackRange(currentTarget.distance)
                : throughWalls.isToggled() ? AuraTargeting.intersectBounds(currentTarget.bounds, eyes, yaw, pitch, attackRange.getInput()) != null
                : !obstructed(currentTarget.bounds, eyes, yaw, pitch, attackRange.getInput());
        if (rotationMode.getInput() == 3) hit &= AuraTargeting.nearestEntity(mc.theWorld.loadedEntityList,
                mc.thePlayer, eyes, yaw, pitch, attackRange.getInput()) == currentTarget.entity;
        MovingObjectPosition planned = hit ? new MovingObjectPosition(currentTarget.entity)
                : new MovingObjectPosition(MovingObjectPosition.MovingObjectType.MISS, eyes, null, new BlockPos(eyes));
        if (!canRunCombat() || mc.thePlayer.isUsingItem()) return false;
        if (!hit) {
            PreAttackEvent event = new PreAttackEvent(planned);
            MinecraftForge.EVENT_BUS.post(event);
            if (event.isCanceled() || !canRunCombat()) return false;
            mc.thePlayer.swingItem();
            cooldown = AuraTiming.addAttackDelay(cooldown, (int) minAps.getInput(), (int) maxAps.getInput(),
                    (min, max) -> min + random.nextInt(max - min + 1));
            return false;
        }
        int id = currentTarget.entity.getEntityId();
        MovingObjectPosition previousPick = mc.objectMouseOver;
        CombatPacketState.beginAuraAttack(id);
        boolean accepted;
        performingClick = true;
        mc.objectMouseOver = planned;
        try { AccessorBridge.Minecraft_callClickMouse(mc); }
        finally {
            performingClick = false;
            mc.objectMouseOver = previousPick;
            accepted = CombatPacketState.consumeAuraAttackAccepted(id);
        }
        if (accepted) cooldown = AuraTiming.addAttackDelay(cooldown, (int) minAps.getInput(), (int) maxAps.getInput(),
                (min, max) -> min + random.nextInt(max - min + 1));
        if (accepted) attackedSinceSelection = true;
        return accepted;
    }

    private boolean send(Packet<?> packet) {
        mc.thePlayer.sendQueue.addToSendQueue(packet);
        return CombatPacketState.wasAccepted(packet);
    }

    private int controllerSlot() { return AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController); }

    private boolean changeSlot(int slot) {
        if (!send(new C09PacketHeldItemChange(slot))) return false;
        AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, slot);
        temporarySlot = slot == mc.thePlayer.inventory.currentItem ? -1 : slot;
        return true;
    }

    private boolean restoreTemporarySlot() {
        if (temporarySlot < 0) return true;
        if (controllerSlot() == temporarySlot && controllerSlot() != mc.thePlayer.inventory.currentItem)
            return changeSlot(mc.thePlayer.inventory.currentItem);
        temporarySlot = -1;
        return true;
    }

    private boolean releaseBlocking() {
        boolean accepted = send(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        if (accepted) mc.thePlayer.stopUsingItem();
        return accepted;
    }

    private boolean startBlocking(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemSword)) return false;
        if (!send(new C08PacketPlayerBlockPlacement(stack))) return false;
        mc.thePlayer.setItemInUse(stack, stack.getMaxItemUseDuration());
        return true;
    }

    private boolean startRequestedBlock(boolean attacked, float yaw, float pitch) {
        MovingObjectPosition hit = attacked ? AuraTargeting.intersectBounds(currentTarget.bounds,
                mc.thePlayer.getPositionEyes(1.0F), yaw, pitch, 8) : null;
        if (attacked && hit == null) return false;
        AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc.playerController);
        if (attacked) {
            if (!send(new C02PacketUseEntity(currentTarget.entity, new Vec3(hit.hitVec.xCoord - currentTarget.x,
                    hit.hitVec.yCoord - currentTarget.y, hit.hitVec.zCoord - currentTarget.z)))) return false;
            if (!send(new C02PacketUseEntity(currentTarget.entity, C02PacketUseEntity.Action.INTERACT))) return false;
        }
        return startBlocking(mc.thePlayer.getHeldItem());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostUpdate(PostUpdateEvent event) {
        long tick = Utils.getBaseClientTick();
        if (postTick == tick) return;
        postTick = tick;
        if (block.restartPending) {
            block.restartPending = false;
            if (ownsAutoBlock() && Mindless.packetDelayService != null
                    && !Mindless.packetDelayService.hasOtherOutboundOwner("KillAuraAutoBlock")) {
                if (lease != null && lease.isActive()) lease.releaseClaims();
                else lease = Mindless.packetDelayService.acquire(DelayRequest.auraAutoBlock());
            } else releaseBuffer();
        }
        if (Utils.nullCheck() && block.active && !CombatPacketState.serverBlocking()) mc.thePlayer.stopUsingItem();
    }

    private void releaseBuffer() { if (lease != null) { lease.release(); lease = null; } }

    private boolean releaseOwned(boolean connected) {
        releaseBuffer();
        block.render = block.restartPending = false;
        if (connected && Utils.nullCheck() && world == mc.theWorld && player == mc.thePlayer) {
            boolean release = block.active && isBlockingSword();
            boolean restore = temporarySlot >= 0 && controllerSlot() == temporarySlot
                    && controllerSlot() != mc.thePlayer.inventory.currentItem;
            if (release || restore) {
                cleanupPending = true;
                long tick = Utils.getBaseClientTick();
                if (cleanupTick == tick || cleanupAttempts >= 20
                        || CombatPacketState.sentDigging() || CombatPacketState.sentBlockPlacement()) return false;
                cleanupTick = tick;
                cleanupAttempts++;
                if (release && !releaseBlocking()) return false;
                if (!restoreTemporarySlot()) return false;
            }
        }
        block.reset();
        temporarySlot = -1;
        cleanupPending = false;
        cleanupAttempts = 0;
        cleanupTick = Long.MIN_VALUE;
        return true;
    }

    private void clearFacade() { combatReady = false; target = attackingEntity = lastHudTarget = null; lastHudAt = 0; }

    private void reset(boolean connected) {
        releaseOwned(connected);
        RotationHelper.get().release(RotationSource.KILL_AURA);
        clearFacade();
        currentTarget = null; targetIndex = 0; attackedSinceSelection = hasTargetInAutoBlockRange = false;
        cooldown = selectedAt = 0; selectionTick = actionTick = postTick = Long.MIN_VALUE;
        if (!cleanupPending) visibleSlot = -1;
        lastMode = lastRotation = -1; hurtTicks = 0;
    }

    @Override public void onEnable() { reset(true); world = mc == null ? null : mc.theWorld; player = mc == null ? null : mc.thePlayer; }
    @Override public void onDisable() { reset(true); }
    public void resetForProfileLoad() { reset(true); }
    public void onWorldChange() { reset(false); world = null; CombatPacketState.resetSession(); }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent event) {
        if (!ownsCombatInteractions() || showTarget.getInput() == 0) return;
        int color = showTarget.getInput() == 2 ? HUD.getHudColor(0) : target.hurtTime > 0 ? 16733525 : 5635925;
        double x = target.lastTickPosX + (target.posX - target.lastTickPosX) * event.partialTicks;
        double y = target.lastTickPosY + (target.posY - target.lastTickPosY) * event.partialTicks;
        double z = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * event.partialTicks;
        double cx = AccessorBridge.RenderManager_getRenderPosX(mc.getRenderManager());
        double cy = AccessorBridge.RenderManager_getRenderPosY(mc.getRenderManager());
        double cz = AccessorBridge.RenderManager_getRenderPosZ(mc.getRenderManager());
        AxisAlignedBB bounds = target.getEntityBoundingBox().expand(.1, .1, .1).offset(x - target.posX - cx, y - target.posY - cy, z - target.posZ - cz);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
            RenderUtils.drawBoundingBox(bounds, ((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, 63 / 255f);
        } finally { GL11.glPopAttrib(); }
    }
}

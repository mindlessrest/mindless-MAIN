package mindless.module.impl.player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mindless.event.ClientRotationEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.RightClickMouseEvent;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.placement.PlacementCoordinator;
import mindless.placement.PlacementLease;
import mindless.placement.PlacementRuntime;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class AutoHeadHitter extends Module {
   private static final double MAX_PLACE_REACH = 4.5;
   private static final double PLACE_RANGE_EPSILON = 0.05;
   private static final int FIRST_BLOCKS_REQUIRE_PRE_ROTATE = 2;
   private static final int ANCHOR_WAIT_TIMEOUT = 4;
   private static final int PLACE_ATTEMPT_TIMEOUT = 6;
   private static final int PLAN_RETRY_TICKS = 2;
   private static final int MIN_FORWARD_SEARCH_BLOCKS = 3;
   private static final int MAX_FORWARD_SEARCH_BLOCKS = 8;
   private static final double FORWARD_SAMPLE_STEP = 0.35;
   private static final int MAX_CAP_EXTENSION_BLOCKS_STRAIGHT = 6;
   private static final double ENEMY_EXIT_RANGE_SQ = 25.0;
   private static final double MAX_TICK_DISPLACEMENT_SQ = 0.75 * 0.75;
   private static final double MAX_LATERAL_DEVIATION = 0.9;
   private static final int COLLISION_EXIT_TICKS = 2;
   private static final int MIN_FIRST_JUMP_PLACEMENTS = 4;
   private static final int MAX_PASS_REPLANS = 1;
   private static final double PASS_MARGIN = 0.25;
   private static final double PLAYER_HALF_WIDTH = 0.3;
   private static final double MIN_PATH_SPEED_SQ = 0.0025;
   private static final double[][] LEGIT_FACE_SAMPLE_OFFSETS = new double[][]{
      {0.5, 0.5}, {0.35, 0.5}, {0.65, 0.5}, {0.5, 0.35}, {0.5, 0.65}, {0.35, 0.35}, {0.35, 0.65}, {0.65, 0.35}, {0.65, 0.65}
   };
   private static final EnumFacing[] HORIZONTAL_FACINGS = {
      EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
   };

   private static int placeDelay;
   private Phase phase;
   private final Deque<PlacementAction> actions = new ArrayDeque<>();
   private BlockPos capBlockPos;
   private boolean hasRotation;
   private float rotationYaw;
   private float rotationPitch;
   private EnumFacing lockedForward;
   private PlacementLease placementLease;
   private int ticksInPhase;
   private int jumpAirTicks;
   private int underBlockAirTicks;
   private int underBlockGroundTicks;
   private int placementStallTicks;
   private int placeAttemptStallTicks;
   private boolean waitUnderJumpStarted;
   private PlacementAction rotatedAction;
   private int rotatedActionTick = -1;
   private Vec3 rotatedHitVec;
   private int placedBlocksThisBuild;
   private int nextPlanRetryTick = Integer.MIN_VALUE;
   private long cachedGroundColumnKey = Long.MIN_VALUE;
   private int cachedGroundY = Integer.MIN_VALUE;
   private int currentClientTick = Integer.MIN_VALUE;
   private int exitRestoreRotationTick = Integer.MIN_VALUE;
   private final ButtonSetting movementFix;
   private final ButtonSetting autoJump;
   private int cachedBlockSlotTick = Integer.MIN_VALUE;
   private int cachedBlockSlot = -1;
   private double planOriginX;
   private double planOriginZ;
   private double planForwardX;
   private double planForwardZ;
   private double planLateralX;
   private double planLateralZ;
   private double capMinProgress;
   private double capMaxProgress;
   private boolean planGeometryReady;
   private boolean capProgressReady;
   private double lastObservedX;
   private double lastObservedY;
   private double lastObservedZ;
   private int lastObservedTick = Integer.MIN_VALUE;
   private int lastExitEvaluationTick = Integer.MIN_VALUE;
   private boolean lastExitEvaluationInterrupted;
   private int horizontalCollisionTicks;
   private int passReplanCount;
   private boolean firstJumpAirborne;
   private boolean firstJumpLandingValidated;
   private boolean pendingServerPositionCorrection;
   private final Map<BlockPos, Block> firstJumpPlacementCandidates = new HashMap<>();
   private ExitReason lastExitReason;

   public AutoHeadHitter() {
      super("Auto Head Hitter", "Builds a head hitter in front of you while you run.", Module.category.player);
      this.registerSetting(this.movementFix = new ButtonSetting("Movement Fix", true));
      this.registerSetting(this.autoJump = new ButtonSetting("Auto Jump", true));
   }

   @Override
   public void onEnable() {
      this.resetState(false);
      this.phase = Phase.SIMULATE_AND_PLAN;
      this.seedObservedPosition();
   }

   @Override
   public void onDisable() {
      PlacementCoordinator.get().cancel(this);
      this.resetState(true);
   }

   @SubscribeEvent
   public void onRightClick(RightClickMouseEvent event) {
      if (this.isPlacementActive() && this.phase == Phase.BUILD
         && Utils.nullCheck() && this.isUsableBlock(mc.thePlayer.getHeldItem())) {
         event.setCanceled(true);
      }
   }

   @SubscribeEvent
   public void onMouse(MouseEvent event) {
      if (this.isPlacementActive() && this.phase == Phase.BUILD
         && event.button == 1 && event.buttonstate && Utils.nullCheck()
         && this.isUsableBlock(mc.thePlayer.getHeldItem()) && event.isCancelable()) {
         event.setCanceled(true);
      }
   }

   @SubscribeEvent
   public void onReceivePacket(ReceivePacketEvent event) {
      if (event.getPacket() instanceof S08PacketPlayerPosLook) {
         this.markServerPositionCorrection();
      }
   }

   public boolean isMovementFixEnabled() {
      return this.movementFix.isToggled();
   }

   public void setMovementFixEnabled(boolean movementFixEnabled) {
      this.movementFix.setEnabled(movementFixEnabled);
   }

   public void toggleMovementFix() {
      this.movementFix.toggle();
   }

   public boolean isAutoJumpEnabled() {
      return this.autoJump.isToggled();
   }

   public void setAutoJumpEnabled(boolean autoJumpEnabled) {
      this.autoJump.setEnabled(autoJumpEnabled);
   }

   public void toggleAutoJump() {
      this.autoJump.toggle();
   }

   @SubscribeEvent
   public void onPreUpdate(PreUpdateEvent event) {
      if (this.isEnabled() && !Utils.isLocalPlayerSubUpdate()) {
          this.syncCurrentClientTick();
          if (Utils.nullCheck() && !mc.isGamePaused() && !mc.thePlayer.isDead) {
            if (this.evaluateExitConditionsOncePerTick()) {
               return;
            }

            if (this.phase == Phase.WAIT_EXIT
               && this.exitRestoreRotationTick != Integer.MIN_VALUE
               && this.currentClientTick > this.exitRestoreRotationTick) {
               this.disable();
               return;
            }

            this.handleJumpingPreUpdate();
            if (this.isEnabled() && this.phase == Phase.BUILD && !this.attemptPlaceCurrentAction()) {
               this.disable();
            }
         } else {
            this.disable();
         }
      }
   }

   @SubscribeEvent
   public void onClientRotation(ClientRotationEvent event) {
      if (this.isEnabled() && !Utils.isLocalPlayerSubUpdate()) {
         if (Utils.nullCheck() && !mc.isGamePaused() && !mc.thePlayer.isDead) {
            if (this.evaluateExitConditionsOncePerTick()) {
               return;
            }

            this.ticksInPhase++;
            switch (this.phase) {
               case SIMULATE_AND_PLAN:
                  if (!mc.thePlayer.onGround) {
                     this.disable();
                     return;
                  }

                  if (!this.prepareEarly()) {
                     this.disable();
                     return;
                  }

                  placeDelay = 0;
                  this.phase = Phase.JUMP_START;
                  this.ticksInPhase = 0;
                case JUMP_START:
                case WAIT_UNDER_AND_JUMP:
                   break;
                case WAIT_EXIT:
                  if (this.exitRestoreRotationTick == Integer.MIN_VALUE) {
                     this.exitRestoreRotationTick = mc.thePlayer.ticksExisted;
                  }
                  break;
               default:
                  break;
               case WAIT_FOR_LAST_TICK:
                  if (mc.thePlayer.onGround && this.ticksInPhase > 3) {
                     this.disable();
                     return;
                  }

                  this.releaseJumpKey();
                  placeDelay++;
                  if (placeDelay >= 0 && mc.thePlayer.ticksExisted >= this.nextPlanRetryTick) {
                     if (this.buildPlanNow()) {
                        placeDelay = 0;
                        this.phase = Phase.BUILD;
                        this.ticksInPhase = 0;
                        this.placedBlocksThisBuild = 0;
                        this.placeAttemptStallTicks = 0;
                        this.clearRotatedActionState();
                     } else {
                        this.nextPlanRetryTick = mc.thePlayer.ticksExisted + PLAN_RETRY_TICKS;
                     }
                  }
                  break;
               case BUILD:
                  if (!this.preparePlacementTick(event)) {
                     this.disable();
                     return;
                  }

                  if (this.actions.isEmpty()) {
                     this.clearRotatedActionState();
                     this.releasePlacementHotbar();
                     this.placeAttemptStallTicks = 0;
                     this.phase = Phase.WAIT_UNDER_AND_JUMP;
                     this.ticksInPhase = 0;
                     this.waitUnderJumpStarted = false;
                  }
            }

            if (this.isEnabled() && this.hasRotation && this.phase == Phase.BUILD) {
               if (this.movementFix.isToggled()) {
                  RotationHelper.get().forceMovementFix = true;
               }
               event.requestRotation(mindless.rotation.RotationSource.AUTO_HEAD_HITTER,
                     this.rotationYaw, this.rotationPitch);
            }
         } else {
            this.disable();
         }
      }
   }

   private boolean prepareEarly() {
      this.actions.clear();
      this.hasRotation = false;
      this.lockedForward = this.resolveForward();
      if (this.lockedForward == null || this.getBlockSlot() == -1) {
         return false;
      }

      this.capturePlanGeometry();

      return this.acquirePlacement();
   }

   private boolean buildPlanNow() {
      this.actions.clear();
      this.hasRotation = false;
      this.placementStallTicks = 0;
      this.placeAttemptStallTicks = 0;
      this.cachedGroundColumnKey = Long.MIN_VALUE;
      this.cachedGroundY = Integer.MIN_VALUE;
      if (this.getBlockSlot() == -1) {
         return false;
      }

      List<PlacementAction> plan = this.buildHeadHitter();
      if (plan.isEmpty()) {
         return false;
      }

      this.actions.addAll(plan);
      this.capBlockPos = plan.get(plan.size() - 1).placePos;
      this.updateCapProgress();
      return true;
   }

   private int findGroundY() {
      int x = MathHelper.floor_double(mc.thePlayer.posX);
      int z = MathHelper.floor_double(mc.thePlayer.posZ);
      long columnKey = new BlockPos(x, 0, z).toLong();
      if (this.cachedGroundColumnKey == columnKey && this.cachedGroundY != Integer.MIN_VALUE) {
         return this.cachedGroundY;
      }

      int y = MathHelper.floor_double(mc.thePlayer.posY);
      for (int check = y; check > 0; check--) {
         if (this.isSupportAvailable(new BlockPos(x, check - 1, z))) {
            this.cachedGroundColumnKey = columnKey;
            this.cachedGroundY = check;
            return check;
         }
      }

      this.cachedGroundColumnKey = columnKey;
      this.cachedGroundY = y;
      return y;
   }

   private List<PlacementAction> buildHeadHitter() {
      int groundY = this.findGroundY();
      BlockPos playerBlock = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX), groundY, MathHelper.floor_double(mc.thePlayer.posZ));
      EnumFacing left = this.lockedForward.rotateYCCW();
      EnumFacing right = this.lockedForward.rotateY();
      AxisAlignedBB projectedPath = this.getProjectedForwardPathBox();

      for (BlockPos base : this.getForwardPathCandidates(playerBlock)) {
         EnumFacing primarySide = this.chooseSaferSide(base, left, right);
         List<PlacementAction> plan = this.tryBuildOnSide(base, primarySide, projectedPath);
         if (!plan.isEmpty()) {
            return plan;
         }
      }

      return Collections.emptyList();
   }

   private List<BlockPos> getForwardPathCandidates(BlockPos playerBlock) {
      List<BlockPos> candidates = new ArrayList<>(16);
      long lastCandidateKey = Long.MIN_VALUE;
      float yawRad = (float)Math.toRadians(mc.thePlayer.rotationYaw);
      double vx = -MathHelper.sin(yawRad);
      double vz = MathHelper.cos(yawRad);
      double startX = playerBlock.getX() + 0.5;
      double startZ = playerBlock.getZ() + 0.5;

      for (double distance = MIN_FORWARD_SEARCH_BLOCKS; distance <= MAX_FORWARD_SEARCH_BLOCKS + 0.000001; distance += FORWARD_SAMPLE_STEP) {
         int blockX = MathHelper.floor_double(startX + vx * distance);
         int blockZ = MathHelper.floor_double(startZ + vz * distance);
         BlockPos candidate = new BlockPos(blockX, playerBlock.getY(), blockZ);
         long candidateKey = candidate.toLong();
         if (candidateKey != lastCandidateKey) {
            candidates.add(candidate);
            lastCandidateKey = candidateKey;
         }
      }

      return candidates;
   }

   private EnumFacing chooseSaferSide(BlockPos forwardBase, EnumFacing left, EnumFacing right) {
      int playerBlockX = MathHelper.floor_double(mc.thePlayer.posX);
      int playerBlockZ = MathHelper.floor_double(mc.thePlayer.posZ);
      double blockCenterX = playerBlockX + 0.5;
      double blockCenterZ = playerBlockZ + 0.5;
      double relX = mc.thePlayer.posX - blockCenterX;
      double relZ = mc.thePlayer.posZ - blockCenterZ;
      double lateral = relX * left.getFrontOffsetX() + relZ * left.getFrontOffsetZ();
      if (lateral > 0.0) {
         return right;
      }

      return lateral < 0.0 ? left : this.deterministicSideTieBreak(left, right);
   }

   private EnumFacing deterministicSideTieBreak(EnumFacing left, EnumFacing right) {
      return this.lockedForward != EnumFacing.NORTH && this.lockedForward != EnumFacing.SOUTH ? right : left;
   }

   private AxisAlignedBB getProjectedForwardPathBox() {
      AxisAlignedBB current = mc.thePlayer.getEntityBoundingBox();
      float yawRad = (float)Math.toRadians(mc.thePlayer.rotationYaw);
      double forwardX = -MathHelper.sin(yawRad);
      double forwardZ = MathHelper.cos(yawRad);
      double sweepDistance = 7.5;
      AxisAlignedBB swept = current.offset(forwardX * sweepDistance, 0.0, forwardZ * sweepDistance);
      return current.union(swept).expand(PLACE_RANGE_EPSILON, 0.0, PLACE_RANGE_EPSILON);
   }

   private void handleJumpingPreUpdate() {
      switch (this.phase) {
         case JUMP_START:
            if (mc.thePlayer.onGround || this.jumpAirTicks < 2) {
               this.tickJumpKey();
            }

            if (!mc.thePlayer.onGround) {
               this.jumpAirTicks++;
            }

            if (this.jumpAirTicks >= 2) {
               placeDelay = 0;
               this.phase = Phase.WAIT_FOR_LAST_TICK;
               this.ticksInPhase = 0;
            }
            break;
         case WAIT_UNDER_AND_JUMP:
            if (this.capBlockPos == null) {
               this.disable();
               return;
            }

            if (!this.waitUnderJumpStarted) {
               if (mc.thePlayer.onGround) {
                  this.waitUnderJumpStarted = true;
                  this.underBlockAirTicks = 0;
                  this.underBlockGroundTicks = 0;
               }
            } else {
               this.tickJumpKey();
               if (mc.thePlayer.onGround) {
                  this.underBlockGroundTicks++;
                  this.underBlockAirTicks = 0;
               } else {
                  this.underBlockAirTicks++;
                  this.underBlockGroundTicks = 0;
               }

               if (this.underBlockAirTicks >= ANCHOR_WAIT_TIMEOUT || this.underBlockGroundTicks >= ANCHOR_WAIT_TIMEOUT) {
                  this.disableFinished();
               }
            }
            break;
         default:
            break;
      }
   }

   private boolean evaluateExitConditionsOncePerTick() {
      int tick = mc.thePlayer.ticksExisted;
      if (this.lastExitEvaluationTick == tick) {
         return this.lastExitEvaluationInterrupted;
      }

      this.lastExitEvaluationTick = tick;
      this.lastExitEvaluationInterrupted = false;
      boolean consecutiveSample = this.lastObservedTick != Integer.MIN_VALUE && tick == this.lastObservedTick + 1;
      double deltaX = consecutiveSample ? mc.thePlayer.posX - this.lastObservedX : 0.0;
      double deltaY = consecutiveSample ? mc.thePlayer.posY - this.lastObservedY : 0.0;
      double deltaZ = consecutiveSample ? mc.thePlayer.posZ - this.lastObservedZ : 0.0;

      if (this.pendingServerPositionCorrection) {
         return this.exitFor(ExitReason.SERVER_POSITION_CORRECTION);
      }

      if (consecutiveSample && deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > MAX_TICK_DISPLACEMENT_SQ) {
         return this.exitFor(ExitReason.LARGE_POSITION_CHANGE);
      }

      if (!Utils.isBindDown(mc.gameSettings.keyBindForward)) {
         return this.exitFor(ExitReason.FORWARD_RELEASED);
      }

      if (Utils.isBindDown(mc.gameSettings.keyBindBack)) {
         return this.exitFor(ExitReason.BACKWARD_PRESSED);
      }

      if (Utils.isBindDown(mc.gameSettings.keyBindSneak)) {
         return this.exitFor(ExitReason.SNEAKING);
      }

      if (this.hasNearbyEnemyPlayer()) {
         return this.exitFor(ExitReason.ENEMY_NEARBY);
      }

      if (this.planGeometryReady && this.getLateralOffset() > MAX_LATERAL_DEVIATION) {
         return this.exitFor(ExitReason.LEFT_PLANNED_LANE);
      }

      if (!this.firstJumpLandingValidated) {
         if (!mc.thePlayer.onGround) {
            this.firstJumpAirborne = true;
         } else if (this.firstJumpAirborne) {
            if (this.countConfirmedFirstJumpPlacements() < MIN_FIRST_JUMP_PLACEMENTS) {
               return this.exitFor(ExitReason.INSUFFICIENT_PLACEMENTS);
            }

            this.firstJumpLandingValidated = true;
         }
      }

      if (mc.thePlayer.isCollidedHorizontally && !this.hasReachedStructure()) {
         this.horizontalCollisionTicks++;
         if (this.horizontalCollisionTicks >= COLLISION_EXIT_TICKS) {
            return this.exitFor(ExitReason.HORIZONTAL_COLLISION);
         }
      } else {
         this.horizontalCollisionTicks = 0;
      }

      if (this.hasPassedStructure()) {
         if (this.placedBlocksThisBuild == 0 && this.passReplanCount < MAX_PASS_REPLANS) {
            this.replanAfterPassingStructure();
            this.updateObservedPosition(tick);
            this.lastExitEvaluationInterrupted = true;
            return true;
         }

         return this.exitFor(ExitReason.PASSED_STRUCTURE);
      }

      if (consecutiveSample && !this.finalCapCoversActualPath(deltaX, deltaZ)) {
         return this.exitFor(ExitReason.CAP_MISSES_PATH);
      }

      this.updateObservedPosition(tick);
      return false;
   }

   private boolean exitFor(ExitReason reason) {
      this.lastExitReason = reason;
      this.lastExitEvaluationInterrupted = true;
      this.disable();
      return true;
   }

   private boolean hasNearbyEnemyPlayer() {
      for (EntityPlayer player : mc.theWorld.playerEntities) {
         if (player == null || player == mc.thePlayer || player.isDead || player.getHealth() <= 0.0F) {
            continue;
         }

         if (mc.thePlayer.getTeam() != null && player.getTeam() != null && mc.thePlayer.isOnSameTeam(player)) {
            continue;
         }

         if (mc.thePlayer.getDistanceSqToEntity(player) <= ENEMY_EXIT_RANGE_SQ) {
            return true;
         }
      }

      return false;
   }

   private void capturePlanGeometry() {
      float yawRad = (float)Math.toRadians(mc.thePlayer.rotationYaw);
      this.planForwardX = -MathHelper.sin(yawRad);
      this.planForwardZ = MathHelper.cos(yawRad);
      double length = Math.sqrt(this.planForwardX * this.planForwardX + this.planForwardZ * this.planForwardZ);
      if (length <= 1.0E-6) {
         this.planGeometryReady = false;
         return;
      }

      this.planForwardX /= length;
      this.planForwardZ /= length;
      this.planLateralX = -this.planForwardZ;
      this.planLateralZ = this.planForwardX;
      this.planOriginX = mc.thePlayer.posX;
      this.planOriginZ = mc.thePlayer.posZ;
      this.planGeometryReady = true;
      this.capProgressReady = false;
   }

   private double getForwardProgress(double x, double z) {
      return (x - this.planOriginX) * this.planForwardX + (z - this.planOriginZ) * this.planForwardZ;
   }

   private double getLateralOffset() {
      double dx = mc.thePlayer.posX - this.planOriginX;
      double dz = mc.thePlayer.posZ - this.planOriginZ;
      return Math.abs(dx * this.planLateralX + dz * this.planLateralZ);
   }

   private double getPlayerProjectionHalfWidth() {
      return PLAYER_HALF_WIDTH * (Math.abs(this.planForwardX) + Math.abs(this.planForwardZ));
   }

   private void updateCapProgress() {
      if (!this.planGeometryReady || this.capBlockPos == null) {
         this.capProgressReady = false;
         return;
      }

      double x = this.capBlockPos.getX();
      double z = this.capBlockPos.getZ();
      double p1 = this.getForwardProgress(x, z);
      double p2 = this.getForwardProgress(x + 1.0, z);
      double p3 = this.getForwardProgress(x, z + 1.0);
      double p4 = this.getForwardProgress(x + 1.0, z + 1.0);
      this.capMinProgress = Math.min(Math.min(p1, p2), Math.min(p3, p4));
      this.capMaxProgress = Math.max(Math.max(p1, p2), Math.max(p3, p4));
      this.capProgressReady = true;
   }

   private boolean hasReachedStructure() {
      if (!this.capProgressReady) {
         return false;
      }

      double progress = this.getForwardProgress(mc.thePlayer.posX, mc.thePlayer.posZ);
      return progress + this.getPlayerProjectionHalfWidth() >= this.capMinProgress;
   }

   private boolean hasPassedStructure() {
      if (!this.capProgressReady) {
         return false;
      }

      double progress = this.getForwardProgress(mc.thePlayer.posX, mc.thePlayer.posZ);
      return progress - this.getPlayerProjectionHalfWidth() > this.capMaxProgress + PASS_MARGIN;
   }

   private boolean finalCapCoversActualPath(double movementX, double movementZ) {
      if (!this.capProgressReady || this.capBlockPos == null || (this.phase != Phase.BUILD && this.phase != Phase.WAIT_UNDER_AND_JUMP)) {
         return true;
      }

      double speedSq = movementX * movementX + movementZ * movementZ;
      if (speedSq < MIN_PATH_SPEED_SQ) {
         movementX = this.planForwardX;
         movementZ = this.planForwardZ;
      }

      double forwardSpeed = movementX * this.planForwardX + movementZ * this.planForwardZ;
      if (forwardSpeed <= 1.0E-4) {
         return false;
      }

      double playerProgress = this.getForwardProgress(mc.thePlayer.posX, mc.thePlayer.posZ);
      double targetProgress = (this.capMinProgress + this.capMaxProgress) * 0.5;
      double travelScale = (targetProgress - playerProgress) / forwardSpeed;
      if (travelScale < 0.0) {
         return false;
      }

      double predictedX = mc.thePlayer.posX + movementX * travelScale;
      double predictedZ = mc.thePlayer.posZ + movementZ * travelScale;
      double minX = this.capBlockPos.getX() - PLAYER_HALF_WIDTH;
      double maxX = this.capBlockPos.getX() + 1.0 + PLAYER_HALF_WIDTH;
      double minZ = this.capBlockPos.getZ() - PLAYER_HALF_WIDTH;
      double maxZ = this.capBlockPos.getZ() + 1.0 + PLAYER_HALF_WIDTH;
      return predictedX >= minX && predictedX <= maxX && predictedZ >= minZ && predictedZ <= maxZ;
   }

   private void replanAfterPassingStructure() {
      this.passReplanCount++;
      this.actions.clear();
      this.clearRotatedActionState();
      this.capBlockPos = null;
      this.capProgressReady = false;
      this.placementStallTicks = 0;
      this.placeAttemptStallTicks = 0;
      this.lockedForward = this.resolveForward();
      this.capturePlanGeometry();
      this.phase = Phase.WAIT_FOR_LAST_TICK;
      this.ticksInPhase = 0;
      this.nextPlanRetryTick = mc.thePlayer.ticksExisted;
   }

   private int countConfirmedFirstJumpPlacements() {
      int confirmed = 0;
      for (Map.Entry<BlockPos, Block> entry : this.firstJumpPlacementCandidates.entrySet()) {
         if (entry.getKey() != null && entry.getValue() != null && BlockUtils.getBlock(entry.getKey()) == entry.getValue()) {
            confirmed++;
         }
      }

      return confirmed;
   }

   private void seedObservedPosition() {
      if (Utils.nullCheck()) {
         this.updateObservedPosition(mc.thePlayer.ticksExisted);
      }
   }

   private void updateObservedPosition(int tick) {
      this.lastObservedX = mc.thePlayer.posX;
      this.lastObservedY = mc.thePlayer.posY;
      this.lastObservedZ = mc.thePlayer.posZ;
      this.lastObservedTick = tick;
   }

   public void markServerPositionCorrection() {
      this.pendingServerPositionCorrection = true;
   }

   @SubscribeEvent
   public void onRenderWorld(RenderWorldLastEvent event) {
      if (!this.isEnabled() || !Utils.nullCheck() || this.actions.isEmpty()) {
         return;
      }

      if (this.phase != Phase.BUILD && this.phase != Phase.WAIT_FOR_LAST_TICK) {
         return;
      }

      GlStateManager.pushMatrix();
      GlStateManager.enableBlend();
      GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
      GlStateManager.disableTexture2D();
      GlStateManager.disableDepth();
      GlStateManager.depthMask(false);

      int index = 0;
      for (PlacementAction action : this.actions) {
         if (action != null && action.placePos != null) {
            if (index == 0) {
               this.renderPlacementBox(action.placePos, 80, 255, 120, 90);
            } else {
               this.renderPlacementBox(action.placePos, 90, 170, 255, 70);
            }
         }

         index++;
      }

      GlStateManager.depthMask(true);
      GlStateManager.enableDepth();
      GlStateManager.enableTexture2D();
      GlStateManager.disableBlend();
      GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
      GlStateManager.popMatrix();
   }

   private List<PlacementAction> tryBuildOnSide(BlockPos forwardBlock, EnumFacing side, AxisAlignedBB projectedPath) {
      BlockPos pillarBase = forwardBlock.offset(side);
      BlockPos pillarMid = pillarBase.up();
      BlockPos pillarTop = pillarBase.up(2);
      if (this.isPlacementTargetAvailable(pillarBase) && this.isPlacementTargetAvailable(pillarMid) && this.isPlacementTargetAvailable(pillarTop)) {
         PlacementAction first = this.findPlacementForSpecificPos(pillarBase);
         if (first == null) {
             return Collections.emptyList();
         }

         Vec3 midHit = this.getFaceCenterVec(pillarBase, EnumFacing.UP);
         Vec3 topHit = this.getFaceCenterVec(pillarMid, EnumFacing.UP);
         if (midHit != null && topHit != null) {
            Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
            double midDistance = eye.distanceTo(midHit);
            double topDistance = eye.distanceTo(topHit);
            if (midDistance <= MAX_PLACE_REACH && topDistance <= MAX_PLACE_REACH) {
               EnumFacing capDirection = side.getOpposite();
               List<PlacementAction> capActions = this.buildCapActionsUntilOverPath(pillarTop, capDirection, projectedPath);
               if (capActions.isEmpty()) {
                  return Collections.emptyList();
               }

               List<PlacementAction> plan = new ArrayList<>(3 + capActions.size());
               plan.add(first);
               plan.add(new PlacementAction(pillarMid, pillarBase, EnumFacing.UP, midHit));
               plan.add(new PlacementAction(pillarTop, pillarMid, EnumFacing.UP, topHit));
               plan.addAll(capActions);
               return plan;
            }
         }
      }

      return Collections.emptyList();
   }

   private List<PlacementAction> buildCapActionsUntilOverPath(BlockPos pillarTop, EnumFacing capDirection, AxisAlignedBB projectedPath) {
      List<PlacementAction> capActions = new ArrayList<>();
      BlockPos anchor = pillarTop;
      Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);

      for (int i = 0; i < MAX_CAP_EXTENSION_BLOCKS_STRAIGHT; i++) {
         BlockPos cap = anchor.offset(capDirection);
         if (!this.isPlacementTargetAvailable(cap)) {
            return Collections.emptyList();
         }

         Vec3 capHit = this.getFaceCenterVec(anchor, capDirection);
         if (capHit == null || eye.distanceTo(capHit) > MAX_PLACE_REACH) {
            return Collections.emptyList();
         }

         capActions.add(new PlacementAction(cap, anchor, capDirection, capHit));
         if (this.isOverProjectedPath(cap, projectedPath)) {
            return capActions;
         }

         anchor = cap;
      }

      return Collections.emptyList();
   }

   private boolean isOverProjectedPath(BlockPos blockPos, AxisAlignedBB projectedPath) {
      double minX = blockPos.getX();
      double maxX = blockPos.getX() + 1.0;
      double minZ = blockPos.getZ();
      double maxZ = blockPos.getZ() + 1.0;
      return projectedPath.maxX > minX && projectedPath.minX < maxX && projectedPath.maxZ > minZ && projectedPath.minZ < maxZ;
   }

   private EnumFacing resolveForward() {
      float yawRad = (float)Math.toRadians(mc.thePlayer.rotationYaw);
      double vx = -MathHelper.sin(yawRad);
      double vz = MathHelper.cos(yawRad);
      return this.horizontalFacingFromVector(vx, vz);
   }

   private EnumFacing horizontalFacingFromVector(double x, double z) {
      EnumFacing best = EnumFacing.SOUTH;
      double bestDot = -Double.MAX_VALUE;

      for (EnumFacing facing : HORIZONTAL_FACINGS) {
         double dot = x * facing.getFrontOffsetX() + z * facing.getFrontOffsetZ();
         if (dot > bestDot) {
            bestDot = dot;
            best = facing;
         }
      }

      return best;
   }

   private PlacementAction findPlacementForSpecificPos(BlockPos placePos) {
      if (!this.isPlacementTargetAvailable(placePos)) {
         return null;
      }

      Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0F);
      PlacementAction bestAction = null;
      double bestDistance = Double.MAX_VALUE;

      for (EnumFacing face : EnumFacing.VALUES) {
         BlockPos anchorPos = placePos.offset(face.getOpposite());
         if (this.isSupportAvailable(anchorPos)) {
            Vec3 hitVec = this.getFaceCenterVec(anchorPos, face);
            if (hitVec != null) {
               double distance = eyePos.distanceTo(hitVec);
               if (distance <= MAX_PLACE_REACH && distance < bestDistance) {
                  bestDistance = distance;
                  bestAction = new PlacementAction(placePos, anchorPos, face, hitVec);
               }
            }
         }
      }

      return bestAction;
   }

   private boolean preparePlacementTick(ClientRotationEvent event) {
      if (this.getBlockSlot() == -1) {
         return false;
      }

      this.hasRotation = false;
      PlacementAction current = this.actions.peekFirst();
      if (current == null) {
         this.clearRotatedActionState();
         this.placeAttemptStallTicks = 0;
         return true;
      }

      if (!this.isPlacementTargetAvailable(current.placePos)) {
         PlacementAction removed = this.actions.pollFirst();
         if (removed == this.rotatedAction) {
            this.clearRotatedActionState();
         }

         this.placementStallTicks = 0;
         this.placeAttemptStallTicks = 0;
         return true;
      } else if (!this.isSupportAvailable(current.anchorPos)) {
         this.placementStallTicks++;
         if (this.placementStallTicks > ANCHOR_WAIT_TIMEOUT) {
            PlacementAction refreshed = this.refreshCurrentActionForPlacePos(current);
            this.placementStallTicks = 0;
            if (refreshed == null) {
               return false;
            }

            this.placeAttemptStallTicks = 0;
         }

         return true;
      } else if (!this.setRotationTo(current, event)) {
         this.placementStallTicks++;
         if (this.placementStallTicks > ANCHOR_WAIT_TIMEOUT) {
            PlacementAction refreshed = this.refreshCurrentActionForPlacePos(current);
            this.placementStallTicks = 0;
            if (refreshed == null) {
               return false;
            }

            this.placeAttemptStallTicks = 0;
         }

         return true;
      }

      this.placementStallTicks = 0;
      if (current != this.rotatedAction) {
         this.rotatedAction = current;
         this.rotatedActionTick = mc.thePlayer.ticksExisted;
         this.placeAttemptStallTicks = 0;
      }

      return true;
   }

   private boolean attemptPlaceCurrentAction() {
      if (Utils.isLocalPlayerSubUpdate()) {
         return true;
      }

      if (this.placementLease == null || !this.placementLease.isActive()) {
         return false;
      }

      int slot = this.getBlockSlot();
      if (slot == -1) {
         return false;
      }

      this.placementLease.claimHotbar(PlacementRuntime.hotbar(), slot);
      PlacementAction current = this.actions.peekFirst();
      if (current == null) {
         this.clearRotatedActionState();
         this.placeAttemptStallTicks = 0;
         return true;
      }

      if (this.hasRotation && current == this.rotatedAction) {
         int currentTick = mc.thePlayer.ticksExisted;
         boolean requirePreRotateTick = this.placedBlocksThisBuild < FIRST_BLOCKS_REQUIRE_PRE_ROTATE;
         if (requirePreRotateTick && currentTick <= this.rotatedActionTick) {
            return true;
         }

         Vec3 hitVec = this.rotatedHitVec != null ? this.rotatedHitVec : current.hitVec;
         if (hitVec == null) {
            return true;
         }

         ItemStack heldStack = mc.thePlayer.getHeldItem();
         Block expectedBlock = heldStack != null && heldStack.getItem() instanceof ItemBlock
            ? ((ItemBlock)heldStack.getItem()).getBlock()
            : null;
         boolean placed = this.placementLease.tryControllerAction(Utils.getBaseClientTick(),
            new PlacementLease.ControllerAction() {
               @Override
               public boolean run() {
                  return mc.playerController.onPlayerRightClick(
                     mc.thePlayer, mc.theWorld, heldStack, current.anchorPos, current.face, hitVec
                  );
               }
            });
         if (placed) {
            Utils.markBlockPlacementSuppressionForCurrentTick();
            mc.thePlayer.swingItem();
            PlacementAction placedAction = this.actions.pollFirst();
            this.placedBlocksThisBuild++;
            if (!this.firstJumpLandingValidated && expectedBlock != null && placedAction != null && placedAction.placePos != null) {
               this.firstJumpPlacementCandidates.put(placedAction.placePos, expectedBlock);
            }
            this.placementStallTicks = 0;
            this.placeAttemptStallTicks = 0;
            if (placedAction == this.rotatedAction) {
               this.clearRotatedActionState();
            } else {
               this.hasRotation = false;
            }

            if (this.actions.isEmpty() && !this.autoJump.isToggled()) {
               this.phase = Phase.WAIT_EXIT;
               this.ticksInPhase = 0;
               this.exitRestoreRotationTick = Integer.MIN_VALUE;
            }

            return true;
         }

         this.placeAttemptStallTicks++;
         return this.placeAttemptStallTicks <= PLACE_ATTEMPT_TIMEOUT;
      }

      return true;
   }

   private void clearRotatedActionState() {
      this.rotatedAction = null;
      this.rotatedActionTick = -1;
      this.rotatedHitVec = null;
      this.hasRotation = false;
   }

   private void disableFinished() {
      this.disable();
      Utils.sendMessage("Head hitter disabled");
   }

   private void renderPlacementBox(BlockPos pos, int red, int green, int blue, int alpha) {
      double renderX = pos.getX() - mc.getRenderManager().viewerPosX;
      double renderY = pos.getY() - mc.getRenderManager().viewerPosY;
      double renderZ = pos.getZ() - mc.getRenderManager().viewerPosZ;
      AxisAlignedBB box = new AxisAlignedBB(renderX, renderY, renderZ, renderX + 1.0, renderY + 1.0, renderZ + 1.0);
      float r = red / 255.0F;
      float g = green / 255.0F;
      float b = blue / 255.0F;
      float fillA = alpha / 255.0F;
      float lineA = Math.min(1.0F, fillA + 0.3F);

      GlStateManager.color(r, g, b, fillA);
      this.drawFilledBox(box);

      GL11.glLineWidth(2.0F);
      GlStateManager.color(r, g, b, lineA);
      this.drawOutlinedBox(box);
      GL11.glLineWidth(1.0F);
   }

   private void drawFilledBox(AxisAlignedBB box) {
      Tessellator tessellator = Tessellator.getInstance();
      WorldRenderer worldRenderer = tessellator.getWorldRenderer();
      worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);

      this.addQuad(worldRenderer, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ);
      this.addQuad(worldRenderer, box.minX, box.maxY, box.minZ, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ);
      this.addQuad(worldRenderer, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, box.maxX, box.minY, box.minZ);
      this.addQuad(worldRenderer, box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ);
      this.addQuad(worldRenderer, box.minX, box.minY, box.minZ, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ);
      this.addQuad(worldRenderer, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.minY, box.maxZ);

      tessellator.draw();
   }

   private void drawOutlinedBox(AxisAlignedBB box) {
      Tessellator tessellator = Tessellator.getInstance();
      WorldRenderer worldRenderer = tessellator.getWorldRenderer();
      worldRenderer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);

      this.addLine(worldRenderer, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ);
      this.addLine(worldRenderer, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ);
      this.addLine(worldRenderer, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ);
      this.addLine(worldRenderer, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ);
      this.addLine(worldRenderer, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ);
      this.addLine(worldRenderer, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ);
      this.addLine(worldRenderer, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ);
      this.addLine(worldRenderer, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ);
      this.addLine(worldRenderer, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ);
      this.addLine(worldRenderer, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ);
      this.addLine(worldRenderer, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ);
      this.addLine(worldRenderer, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ);

      tessellator.draw();
   }

   private void addQuad(
      WorldRenderer worldRenderer,
      double x1,
      double y1,
      double z1,
      double x2,
      double y2,
      double z2,
      double x3,
      double y3,
      double z3,
      double x4,
      double y4,
      double z4
   ) {
      worldRenderer.pos(x1, y1, z1).endVertex();
      worldRenderer.pos(x2, y2, z2).endVertex();
      worldRenderer.pos(x3, y3, z3).endVertex();
      worldRenderer.pos(x4, y4, z4).endVertex();
   }

   private void addLine(WorldRenderer worldRenderer, double x1, double y1, double z1, double x2, double y2, double z2) {
      worldRenderer.pos(x1, y1, z1).endVertex();
      worldRenderer.pos(x2, y2, z2).endVertex();
   }

   private Vec3 getFaceCenterVec(BlockPos blockPos, EnumFacing face) {
      if (blockPos != null && face != null) {
         double x = blockPos.getX() + 0.5 + face.getFrontOffsetX() * 0.5;
         double y = blockPos.getY() + 0.5 + face.getFrontOffsetY() * 0.5;
         double z = blockPos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5;
         return new Vec3(x, y, z);
      }

      return null;
   }

   private boolean setRotationTo(PlacementAction action, ClientRotationEvent event) {
      if (action != null && action.anchorPos != null && action.face != null && action.placePos != null) {
         float baseYaw = event.getBaseYaw() != null ? event.getBaseYaw() : RotationUtils.serverRotations[0];
         float basePitch = event.getBasePitch() != null ? event.getBasePitch() : RotationUtils.serverRotations[1];
         PlacementRayResult legitRay = this.resolveLegitPlacementRay(action, baseYaw, basePitch);
         if (legitRay != null && legitRay.hitVec != null) {
            this.rotationYaw = legitRay.yaw;
            this.rotationPitch = legitRay.pitch;
            this.rotatedHitVec = legitRay.hitVec;
            this.hasRotation = true;
            return true;
         }

         this.rotatedHitVec = null;
         this.hasRotation = false;
      }

      return false;
   }

   private float[] getFixedRotationsForHitVec(Vec3 hitVec, float baseYaw, float basePitch) {
      double dx = hitVec.xCoord - mc.thePlayer.posX;
      double dy = hitVec.yCoord - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
      double dz = hitVec.zCoord - mc.thePlayer.posZ;
      double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
      float targetYaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
      float targetPitch = (float)(-Math.atan2(dy, horizontalDistance) * 180.0 / Math.PI);
      targetYaw = baseYaw + MathHelper.wrapAngleTo180_float(targetYaw - baseYaw);
      targetPitch = MathHelper.clamp_float(
         basePitch + MathHelper.wrapAngleTo180_float(targetPitch - basePitch),
         -90.0F,
         90.0F
      );
      return RotationUtils.fixRotation(targetYaw, targetPitch, baseYaw, basePitch);
   }

   private PlacementRayResult resolveLegitPlacementRay(PlacementAction action, float baseYaw, float basePitch) {
      if (action != null && action.anchorPos != null && action.face != null && action.placePos != null) {
         Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0F);
         PlacementRayResult best = null;
         double bestScore = Double.MAX_VALUE;

         int sampleCount = LEGIT_FACE_SAMPLE_OFFSETS.length + (action.hitVec == null ? 0 : 1);
         double maxReach = MAX_PLACE_REACH + PLACE_RANGE_EPSILON;
         double maxReachSq = maxReach * maxReach;
         for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            int offsetIndex = sampleIndex - (action.hitVec == null ? 0 : 1);
            Vec3 candidateHitVec = offsetIndex < 0
               ? action.hitVec
               : this.getFaceSampleVec(
                  action.anchorPos,
                  action.face,
                  LEGIT_FACE_SAMPLE_OFFSETS[offsetIndex][0],
                  LEGIT_FACE_SAMPLE_OFFSETS[offsetIndex][1]
               );
            if (candidateHitVec != null && eyePos.squareDistanceTo(candidateHitVec) <= maxReachSq) {
               float[] fixed = this.getFixedRotationsForHitVec(candidateHitVec, baseYaw, basePitch);
               MovingObjectPosition mop = this.rayCastBlockDataFace(action.anchorPos, action.face, fixed[0], fixed[1], maxReach);
               if (mop != null && mop.hitVec != null) {
                  BlockPos tracedPlacePos = BlockUtils.offsetPos(mop);
                  if (tracedPlacePos != null && BlockUtils.isBlockPosEqual(tracedPlacePos, action.placePos)) {
                     double yawDelta = Math.abs(MathHelper.wrapAngleTo180_float(fixed[0] - baseYaw));
                     double pitchDelta = Math.abs(fixed[1] - basePitch);
                     double centerDistanceSq = action.hitVec != null ? action.hitVec.squareDistanceTo(mop.hitVec) : 0.0;
                     double score = yawDelta + pitchDelta + centerDistanceSq * 6.0;
                     if (score + 1.0E-6 < bestScore) {
                        bestScore = score;
                        best = new PlacementRayResult(fixed[0], fixed[1], mop.hitVec);
                     }
                  }
               }
            }
         }

         return best;
      }

      return null;
   }

   private MovingObjectPosition rayCastBlockDataFace(BlockPos blockPos, EnumFacing face, float yaw, float pitch, double reach) {
      if (blockPos != null && face != null) {
         MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, yaw, pitch);
         if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK && mop.getBlockPos() != null && mop.sideHit != null && mop.hitVec != null) {
            return BlockUtils.isBlockPosEqual(mop.getBlockPos(), blockPos) && mop.sideHit == face ? mop : null;
         }
      }

      return null;
   }

   private Vec3 getFaceSampleVec(BlockPos blockPos, EnumFacing face, double axisA, double axisB) {
      if (blockPos != null && face != null) {
         double clampedA = MathHelper.clamp_double(axisA, 0.05, 0.95);
         double clampedB = MathHelper.clamp_double(axisB, 0.05, 0.95);
         double x = blockPos.getX();
         double y = blockPos.getY();
         double z = blockPos.getZ();
         switch (face) {
            case UP:
               return new Vec3(x + clampedA, y + 1.0, z + clampedB);
            case DOWN:
               return new Vec3(x + clampedA, y, z + clampedB);
            case NORTH:
               return new Vec3(x + clampedA, y + clampedB, z);
            case SOUTH:
               return new Vec3(x + clampedA, y + clampedB, z + 1.0);
            case EAST:
               return new Vec3(x + 1.0, y + clampedB, z + clampedA);
            case WEST:
               return new Vec3(x, y + clampedB, z + clampedA);
            default:
               return null;
         }
      }

      return null;
   }

   private PlacementAction refreshCurrentActionForPlacePos(PlacementAction current) {
      if (current != null && current.placePos != null) {
         PlacementAction refreshed = this.findPlacementForSpecificPos(current.placePos);
         if (refreshed == null) {
            return null;
         }

         PlacementAction replaced = this.actions.pollFirst();
         if (replaced == this.rotatedAction) {
            this.clearRotatedActionState();
         }

         this.actions.addFirst(refreshed);
         return refreshed;
      }

      return null;
   }

   private int getBlockSlot() {
      int tick = mc.thePlayer.ticksExisted;
      if (this.cachedBlockSlotTick == tick && this.cachedBlockSlot >= 0
         && this.isUsableBlock(mc.thePlayer.inventory.getStackInSlot(this.cachedBlockSlot))) {
         return this.cachedBlockSlot;
      }

      int current = mc.thePlayer.inventory.currentItem;
      ItemStack held = mc.thePlayer.getHeldItem();
      if (this.isUsableBlock(held)) {
         return this.cacheBlockSlot(tick, current);
      }

      for (int slot = 0; slot < 9; slot++) {
         ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
         if (this.isUsableBlock(stack)) {
            return this.cacheBlockSlot(tick, slot);
         }
      }

      return -1;
   }

   private int cacheBlockSlot(int tick, int slot) {
      this.cachedBlockSlotTick = tick;
      this.cachedBlockSlot = slot;
      return slot;
   }

   private boolean isUsableBlock(ItemStack stack) {
      if (stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock) {
         Block block = ((ItemBlock)stack.getItem()).getBlock();
         return block != null && Utils.canBePlaced((ItemBlock)stack.getItem()) && BlockUtils.isNormalBlock(block);
      }

      return false;
   }

   private void tickJumpKey() {
      if (!this.autoJump.isToggled()) return;
      int key = mc.gameSettings.keyBindJump.getKeyCode();
      if (this.placementLease != null
         && this.placementLease.claimInput(PlacementRuntime.input(mc.gameSettings.keyBindJump), true)) {
         KeyBinding.onTick(key);
      }
   }

   private void releaseJumpKey() {
      if (this.placementLease != null) {
         this.placementLease.claimInput(PlacementRuntime.input(mc.gameSettings.keyBindJump), false);
      }
   }

   private void resetJumpKeyState() {
      int key = mc.gameSettings.keyBindJump.getKeyCode();
      if (Utils.isBindDown(mc.gameSettings.keyBindJump)) {
         KeyBinding.setKeyBindState(key, true);
         KeyBinding.onTick(key);
      } else {
         KeyBinding.setKeyBindState(key, false);
      }
   }

   private boolean acquirePlacement() {
      long tick = Utils.getBaseClientTick();
      PlacementCoordinator.get().announce(this, PlacementCoordinator.Priority.HEAD_HITTER,
         mc.thePlayer, mc.theWorld, tick + 1L
      );
      PlacementLease lease = PlacementCoordinator.get().acquire(
         this, PlacementCoordinator.Priority.HEAD_HITTER, mc.thePlayer, mc.theWorld, tick
      );
      if (lease == null) {
         return false;
      }
      this.placementLease = lease;
      return true;
   }

   private void releasePlacementHotbar() {
      if (this.placementLease != null) {
         this.placementLease.releaseHotbar(true);
      }
   }

   private void releasePlacement() {
      if (this.placementLease != null) {
         this.placementLease.release();
         this.placementLease = null;
      }
   }

   private boolean isPlacementActive() {
      return this.placementLease != null && this.placementLease.isActive();
   }

   private void resetState(boolean restoreSlot) {
      this.actions.clear();
      this.clearRotatedActionState();
      this.capBlockPos = null;
      this.lockedForward = null;
      this.ticksInPhase = 0;
      this.jumpAirTicks = 0;
      this.underBlockAirTicks = 0;
      this.underBlockGroundTicks = 0;
      this.placementStallTicks = 0;
      this.placeAttemptStallTicks = 0;
      this.waitUnderJumpStarted = false;
      placeDelay = 0;
      this.nextPlanRetryTick = Integer.MIN_VALUE;
      this.placedBlocksThisBuild = 0;
      this.cachedGroundColumnKey = Long.MIN_VALUE;
      this.cachedGroundY = Integer.MIN_VALUE;
      this.currentClientTick = Integer.MIN_VALUE;
      this.exitRestoreRotationTick = Integer.MIN_VALUE;
      this.cachedBlockSlotTick = Integer.MIN_VALUE;
      this.cachedBlockSlot = -1;
      this.planOriginX = 0.0;
      this.planOriginZ = 0.0;
      this.planForwardX = 0.0;
      this.planForwardZ = 0.0;
      this.planLateralX = 0.0;
      this.planLateralZ = 0.0;
      this.capMinProgress = 0.0;
      this.capMaxProgress = 0.0;
      this.planGeometryReady = false;
      this.capProgressReady = false;
      this.lastObservedX = 0.0;
      this.lastObservedY = 0.0;
      this.lastObservedZ = 0.0;
      this.lastObservedTick = Integer.MIN_VALUE;
      this.lastExitEvaluationTick = Integer.MIN_VALUE;
      this.lastExitEvaluationInterrupted = false;
      this.horizontalCollisionTicks = 0;
      this.passReplanCount = 0;
      this.firstJumpAirborne = false;
      this.firstJumpLandingValidated = false;
      this.pendingServerPositionCorrection = false;
      this.firstJumpPlacementCandidates.clear();
      this.phase = Phase.SIMULATE_AND_PLAN;
      if (restoreSlot) {
         this.releasePlacement();
      }
      this.resetJumpKeyState();
   }

   private void syncCurrentClientTick() {
      this.currentClientTick = mc.thePlayer == null ? Integer.MIN_VALUE : mc.thePlayer.ticksExisted;
   }

   private boolean isPlacementTargetAvailable(BlockPos pos) {
      return pos != null && BlockUtils.replaceable(pos);
   }

   private boolean isSupportAvailable(BlockPos pos) {
      if (pos == null) {
         return false;
      }

      IBlockState state = BlockUtils.getBlockState(pos);
      return !BlockUtils.isInteractable(state.getBlock()) && !state.getBlock().isReplaceable(mc.theWorld, pos);
   }

   private enum Phase {
      SIMULATE_AND_PLAN,
      JUMP_START,
      WAIT_FOR_LAST_TICK,
      BUILD,
      WAIT_UNDER_AND_JUMP,
      WAIT_EXIT
   }

   private enum ExitReason {
      ENEMY_NEARBY,
      FORWARD_RELEASED,
      BACKWARD_PRESSED,
      SNEAKING,
      LEFT_PLANNED_LANE,
      HORIZONTAL_COLLISION,
      INSUFFICIENT_PLACEMENTS,
      LARGE_POSITION_CHANGE,
      SERVER_POSITION_CORRECTION,
      CAP_MISSES_PATH,
      PASSED_STRUCTURE
   }

   private static class PlacementAction {
      private final BlockPos placePos;
      private final BlockPos anchorPos;
      private final EnumFacing face;
      private final Vec3 hitVec;

      private PlacementAction(BlockPos placePos, BlockPos anchorPos, EnumFacing face, Vec3 hitVec) {
         this.placePos = placePos;
         this.anchorPos = anchorPos;
         this.face = face;
         this.hitVec = hitVec;
      }
   }

   private static class PlacementRayResult {
      private final float yaw;
      private final float pitch;
      private final Vec3 hitVec;

      private PlacementRayResult(float yaw, float pitch, Vec3 hitVec) {
         this.yaw = yaw;
         this.pitch = pitch;
         this.hitVec = hitVec;
      }
   }
}

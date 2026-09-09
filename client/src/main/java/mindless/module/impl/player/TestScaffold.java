package mindless.module.impl.player;

import mindless.event.PrePlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.script.ScriptDefaults.client;
import mindless.script.ScriptDefaults.inventory;
import mindless.script.ScriptDefaults.keybinds;
import mindless.script.ScriptDefaults.render;
import mindless.script.ScriptDefaults.world;
import mindless.script.model.Block;
import mindless.script.model.Entity;
import mindless.script.model.ItemStack;
import mindless.script.model.MovementInput;
import mindless.script.model.Simulation;
import mindless.script.model.Vec3;
import mindless.script.packet.serverbound.C07;
import mindless.script.packet.serverbound.C08;
import mindless.script.packet.serverbound.CPacket;
import mindless.script.packet.serverbound.PacketHandler;
import mindless.utility.Utils;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class TestScaffold extends Module {

private final SliderSetting towerMode;
private final SliderSetting keepMode;
private final SliderSetting iceSpacing;
private final SliderSetting clickSpeed;
private final SliderSetting debugMode;
private final ButtonSetting rmbActivate;
private final ButtonSetting potionDisable;
private final ButtonSetting eagle;
private final SliderSetting edgeDistance;
private final SliderSetting sneakDelay;

private final String[] TOWER_MODES =
        {"None", "S-Telly+", "L-Telly+", "L-Telly"};
private final String[] KEEP_MODES = {"None", "Y-Telly+", "Y-Telly"};
private final String[] DBG_MODES = {"None", "Basic", "Verbose", "Visual"};

private final double QUANT = (double) 0.0096f;

private static final float LT_START_MIN = 90.0F;
private static final float LT_START_MAX = 95.0F;
private static final float LT_NORM_MIN = 30.0F;
private static final float LT_NORM_MAX = 35.0F;
private static final int LT_Q0_HOLD = 4;

private static final int LT_Q0_HOLD_GROUND = 12;
private static final float LT_SMOOTH_TICKS = 9.0F;
private static final int LT_STACK_HOLD = 3;
private static final int LOOK_DIAG = 1;
private static final int FLAT_REBUCKET = 1;
private static final float FLAT_HOLD_DEG = 112.5F;
private static final int FLAT_DIAG = 1;
private static final int SPRINT_JUMP_HOLD = 1;

private static final float SPRINT_JUMP_ALIGN = 12.0F;
private static final boolean LOOK_LOCK = true;
private static final int LOOK_LOCK_DRY = 4;
private static final float YAW_EASE_STEP = 1.0F;
private final double[] placeOffsets = new double[]{
        0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375, 0.40625, 0.46875,
        0.53125, 0.59375, 0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
};

private int rotationTick = 0;
private int lastSlot = -1;
private int blockCount = -1;
private int ltDry = 0;
private int ltVerX = 0;
private int ltVerY = 0;
private int ltVerZ = 0;
private int ltVerTick = 0;
private int ltGhost = 0;
private float yaw = -180.0F;
private float pitch = 0.0F;
private boolean canRotate = false;
private int stage = 0;
private int startY = 256;
private boolean shouldKeepY = false;
private boolean towering = false;
private boolean launchLatch = false;
private boolean placedThisTick = false;
private boolean eagleSneaking = false;
private int eagleReleaseTicks = -1;
private float lastSentYaw = 0.0F;
private float lastSentPitch = 0.0F;
private float prevSentPitch = 0.0F;
private float towerPitch = 0.0F;
private int ltStackWait = 0;

private final double[] predY = new double[5];
private boolean predHave = false;
private static final float ACT_SMOOTH_TICKS = 3.0F;

private int actTick = 0;

private int jumpHeld = 0;
private int flatSide = 1;
private boolean flatCard = false;
private float ysA = 0.0F;
private float ysB = 0.0F;
private float ysC = 0.0F;
private int lookSign = 1;

private boolean lookFlat = false;
private float actYawAcc = 0.0F;
private static final float ACT_YAW_LAG = 0.72F;
private boolean rotSentThisTick = false;
private boolean rotSentLastTick = false;
private boolean mfixActive = false;
private int dbgTick = 0;
private float rotSpeed = 0.0F;
private int cardSide = 1;
private float dbgSentMod = 0.0F;
private float dbgMoveErr = 0.0F;
private float camCont = 0.0F;
private boolean camHave = false;
private float gridBase = 0.0F;
private int flatDry = 0;
private boolean smSnapped = false;
private float smYawDec = 0.0F;
private boolean sprintSuppressed = false;
private boolean sprintPrevVirtual = false;
private boolean sprintStopState = false;
private int sprintDwell = 0;
private int lastBucketK = 0;
private int ltRefK = 0;
private boolean diagState = false;
private final String[] FACES = {"NORTH", "SOUTH", "EAST", "WEST"};
private final String[] SIDES_UD = {"UP", "NORTH", "SOUTH", "EAST", "WEST"};
private final int[] OFF_UP = {0, 1, 0};
private final int[] OFF_DOWN = {0, -1, 0};
private final int[] OFF_NORTH = {0, 0, -1};
private final int[] OFF_SOUTH = {0, 0, 1};
private final int[] OFF_EAST = {1, 0, 0};
private final int[] OFF_WEST = {-1, 0, 0};
private final int[][] cands = new int[16][4];
private Vec3 scanHit = null;
private int needX = 0;
private int needZ = 0;
private boolean needValid = false;
private int dbgSel = 0;
private int dbgSelScore = -1;
private int dbgCandN = 0;
private double dbgOver = 0.0;
private Vec3 dbgLastPlaceHit = null;
private long dbgLastPlaceAt = 0L;


private boolean ltSprintJump = true;
private boolean ltFlew = false;
private boolean ltSideFresh = true;
private boolean ltWasEngaged = false;
private double ltPrevMotY = 0.0;
private int ltStandX = 0;
private int ltStandZ = 0;
private int ltGroundAge = 99;
private int ltRow = 0;
private int ltSide = 1;
private boolean ltHavePrev = false;
private int ltOrgX = 0;
private int ltOrgZ = 0;
private int ltDirX = 0;
private int ltDirZ = 0;
private int ltLatX = 0;
private int ltLatZ = 0;
private final int[] ltQx = new int[8];
private final int[] ltQz = new int[8];
private final int[] ltQd = new int[8];
private final int[] ltQb = new int[8];
private int ltQn = 0;
private int ltPendQ = -1;
private int ltQcur = 0;
private int ltQ0Wait = 0;
private int ltQ0Ground = 0;
private char ltWhy = '-';
private char ltBd0 = '-';
private char bdNull = 'n';
private int ltClickCd = 0;
private int ltTapCd = 0;
private int ltStall = 0;
private int ltQskip = 0;
private float targetYaw = 0.0F;
private float targetPitch = 0.0F;

private void aimYaw(float y) {
    targetYaw = y;
    pendJitY = 0.0F;
}



private void aimYawJ(float base, float j) {
    targetYaw = base + j;
    pendJitY = j;
}

private float pendJitY = 0.0F;
private float lastBaseYaw = 0.0F;
private float lastBasePitch = 0.0F;

private void aimPitch(float p) {
    targetPitch = p;
}

private boolean ourPlace = false;
private long nextClickAt = 0L;
private int clickCount = 0;
private long clickWindowStart = 0L;
private float clickCps = 0.0F;
private char clickState = 'D';
private boolean clickerFiring = false;

private float dbgPDelta = 0.0F;
private float dbgPDeltaMax = 0.0F;

private float ltAimYaw = 0.0F;
private float ltAimPitch = 0.0F;
private int ltPendX = 0;
private int ltPendY = 0;
private int ltPendZ = 0;
private String ltPendSide = null;
private Vec3 ltPendHit = null;



public TestScaffold() {
    super("Test Scaffold", "Experimental scaffold port.", category.player);
    this.registerSetting(new DescriptionSetting("Credits to Lizzie - @jenrnr ❤"));
    this.registerSetting(towerMode = new SliderSetting("Tower", 0, TOWER_MODES));
    this.registerSetting(keepMode = new SliderSetting("Keep Y", 0, KEEP_MODES) {
        @Override
        public String getProfileKey() {
            return "Telly.Keep Y";
        }
    });
    GroupSetting yOptions = new GroupSetting("Y Options");
    this.registerSetting(yOptions);
    this.registerSetting(rmbActivate = new ButtonSetting(yOptions, "RMB Activate", false));
    this.registerSetting(potionDisable = new ButtonSetting(yOptions, "On Potion Disable", false));
    this.registerSetting(iceSpacing = new SliderSetting(yOptions, "Ice Spacing", "", -0.60, -0.60, 0.60, 0.01));
    GroupSetting settings = new GroupSetting("Settings");
    this.registerSetting(settings);
    this.registerSetting(eagle = new ButtonSetting(settings, "Eagle", false));
    this.registerSetting(edgeDistance = new SliderSetting(settings, "Edge Distance", "", 0.13, 0.0, 0.5, 0.01));
    this.registerSetting(sneakDelay = new SliderSetting(settings, "Sneak Delay", "ms", 80.0, 0.0, 500.0, 1.0));
    this.registerSetting(clickSpeed = new SliderSetting(settings, "Click Speed", " CPS", 0.0, 0.0, 20.0, 0.5));
    this.registerSetting(debugMode = new SliderSetting("Debug", 0, DBG_MODES));
    this.closetModule = true;
}

@Override
public void onEnable() {
    Entity p = client.getPlayer();
    lastSlot = (p != null) ? inventory.getSlot() : -1;
    blockCount = -1;
    rotationTick = 3;
    yaw = -180.0F;
    pitch = 0.0F;
    canRotate = false;
    rotSentThisTick = false;
    rotSentLastTick = false;
    mfixActive = false;
    resetShared();
    cardSide = 1;
    sprintSuppressed = false;
    sprintPrevVirtual = false;
    scanHit = null;
    needValid = false;
    dbgSel = 0;
    dbgOver = 0.0;
    dbgLastPlaceHit = null;
    dbgLastPlaceAt = 0L;
    ltSide = 1;
    stage = 0;
    startY = 256;
    shouldKeepY = false;
    placedThisTick = false;
}

@Override
public void onDisable() {
    Entity p = client.getPlayer();
    if (p != null && lastSlot != -1) {
        inventory.setSlot(lastSlot);
    }
    client.disableMovementFix();
    if (sprintSuppressed) {
        keybinds.setPressed("sprint",
                sprintPrevVirtual || keybinds.isKeyDown(keybinds.getKeyCode("sprint")));
        sprintSuppressed = false;
    }
    resetShared();
}

public boolean isActivelyScaffolding() {
    return placedThisTick || ourPlace || needValid || ltPendHit != null;
}


private void resetShared() {
    actTick = 0;
    flatSide = 1;
    flatCard = false;
    lookSign = 1;
    lookFlat = false;
    jumpHeld = 0;
    actYawAcc = 0.0F;
    rotSpeed = 0.0F;
    smSnapped = false;
    flatDry = 0;
    camHave = false;
    smYawDec = 0.0F;
    towering = false;
    launchLatch = false;
    sprintStopState = false;
    sprintDwell = 0;
    lastBucketK = 0;
    diagState = false;
    eagleSneaking = false;
    eagleReleaseTicks = -1;
    ltSprintJump = true;
    ltFlew = false;
    ltSideFresh = true;
    ltWasEngaged = false;
    ltPrevMotY = 0.0;
    ltGroundAge = 99;
    ltHavePrev = false;
    ltQn = 0;
    ltPendQ = -1;
    ltStall = 0;
    ltQskip = 0;
    ltQcur = 0;
    ltQ0Wait = 0;
    ltQ0Ground = 0;
    ltClickCd = 0;
    ltTapCd = 0;
    ltPendHit = null;
}



private void autoClick(Entity p) {
    float cpsSet = sld("Click Speed");
    if (cpsSet < 1.0F) {
        clickState = 'D';
        return;
    }
    if (!client.getScreen().isEmpty()) {
        clickState = 'S';
        return;
    }
    if (placedThisTick) {
        clickState = 'P';
        return;
    }
    long now = client.time();
    if (now < nextClickAt) {
        clickState = 'w';
        return;
    }
    if (nextClickAt < now - 500L) {
        nextClickAt = now;
    }
    clickState = 'C';
    clickerFiring = true;
    try {
        client.sendPacket(new C08(p.getHeldItem(),
                new Vec3(-1.0, -1.0, -1.0), 255,
                new Vec3(0.0, 0.0, 0.0)));
    } catch (Throwable t) {
        clickState = 'E';
    } finally {
        clickerFiring = false;
    }

    float cps = cpsSet * rndF(0.88F, 1.12F);
    if (cps < 1.0F) {
        cps = 1.0F;
    }
    float gap = 1000.0F / cps;

    if (rndF(0.0F, 1.0F) < 0.10F) {
        gap *= rndF(1.5F, 2.4F);
    }
    nextClickAt += (long) gap;
    if (nextClickAt < now) {
        nextClickAt = now + (long) gap;
    }
    clickCount++;
    if (now - clickWindowStart >= 1000L) {
        long span = now - clickWindowStart;
        clickCps = (float) clickCount * 1000.0F / (float) Math.max(1L, span);
        clickCount = 0;
        clickWindowStart = now;
    }
}

@SubscribeEvent(priority = EventPriority.HIGHEST)
public void onPreUpdate(PreUpdateEvent event) {
    Entity p = client.getPlayer();
    if (p == null || !world.exists()) {
        return;
    }
    Vec3 pos = p.getPosition();

    placedThisTick = false;
    predHave = false;
    stepJitAmp();
    if (actTick < 1000) {
        actTick++;
    }
    dbgSel = 0;
    dbgSelScore = -1;
    ltPendHit = null;
    rotSentLastTick = rotSentThisTick;
    rotSentThisTick = false;
    if (rotationTick > 0) {
        rotationTick--;
    }

    if (dbgVerbose()) {
        dbgTick++;
        boolean dryStreak = !p.onGround() && ltDry >= 2;
        if (dbgTick % 20 == 0 || dryStreak) {
            dbgPrint(String.format(
                "&7g=&f%b &7twr=&f%b &7iTwr=&f%b &7stage=&f%d &7sY=&f%d &7rot=&f%.0f/%.0f &7cR=&f%b &7blk=&f%d &7mfix=&f%b &7fwd=&f%b &7jmp=&f%b &7ceil=&f%b &7keepY=&f%b &7sprOff=&f%b",
                p.onGround(), towering, towerActive(), stage, startY,
                lastSentYaw, lastSentPitch, canRotate, blockCount, mfixActive,
                fwdPressed(), jumpDown(), hasCeilingAbove(), keepYWanted(),
                sprintSuppressed));
            String selTxt = (dbgSel == 1) ? ("A" + dbgSelScore)
                    : ((dbgSel == 2) ? ("S" + dbgSelScore)
                    : ((dbgSel == 3) ? "AIR" : ((dbgSel == 4) ? "LT"
                    : ((dbgSel == 5) ? "HOLD" : "-"))));
            dbgPrint(String.format(
                "&7fx=&f%.2f/%.2f &7cand=&f%d &7need=&f%s &7sel=&f%s &7k=&f%d &7eag=&f%b/%.2f &7side=&f%d &7off=&f%.1f &7mv=&f%.1f &7pd=&f%.3f/%.2f &7cps=&f%.1f%c &7ys=&f%.0f/%.0f/%.0f &7lt=&f%s",
                pos.x - Math.floor(pos.x), pos.z - Math.floor(pos.z), dbgCandN,
                needValid ? (needX + "," + needZ) : "-", selTxt, lastBucketK,
                eagleSneaking, dbgOver, cardSide, dbgSentMod,
                dbgMoveErr, dbgPDelta, dbgPDeltaMax, clickCps, clickState,
                ysA, ysB, ysC,
                ltDbg()));
        }
    }

    if (p.onGround()) {
        if (stage != 0) {
            stage += (stage > 0) ? -1 : 1;
        }
        if (stage == 0
                && keepYWanted()
                && (!btn("On Potion Disable") || !hasJumpPotion())
                && !jumpDown()) {
            stage = 1;
        }
        startY = shouldKeepY ? startY : fl(pos.y);
        shouldKeepY = false;
        towering = false;
    }


    ItemStack held = p.getHeldItem();
    int count = (held != null && held.isBlock) ? held.stackSize : 0;
    blockCount = Math.min(blockCount, count);
    if (blockCount <= 0) {
        int slot = inventory.getSlot();
        if (blockCount == 0) {
            slot--;
        }
        for (int i = slot; i > slot - 9; i--) {
            int hb = ((i % 9) + 9) % 9;
            ItemStack cand = inventory.getStackInSlot(hb);
            if (cand != null && cand.isBlock) {
                inventory.setSlot(hb);
                blockCount = cand.stackSize;
                break;
            }
        }
    }
    if (iceOn() && keepYWanted()
            && (towering || towerActive() || stage > 0 || fwdPressed())) {
        int iceSlot = -1;
        int altSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inventory.getStackInSlot(i);
            if (st == null || !st.isBlock) {
                continue;
            }
            if (st.name.equals("packed_ice") || st.name.equals("ice")) {
                if (iceSlot < 0) {
                    iceSlot = i;
                }
            } else if (altSlot < 0) {
                altSlot = i;
            }
        }
        if (iceSlot >= 0) {
            boolean wantIce = p.getMotion().y < (double) sld("Ice Spacing")
                    || p.onGround();
            int want = (wantIce || altSlot < 0) ? iceSlot : altSlot;
            if (want != inventory.getSlot()) {
                inventory.setSlot(want);
                ItemStack ns = inventory.getStackInSlot(want);
                blockCount = (ns != null && ns.isBlock) ? ns.stackSize : 0;
            }
        }
    }


    if (ltOn() && !p.onGround() && (ltWasEngaged || towering)) {
        towering = true;
    }
    boolean ltEng = ltOn() && (towering || towerJumpActive());
    if (ltEng && !p.onGround()) {
        ltDry++;
    } else {
        ltDry = 0;
    }
    if (ltVerTick > 0 && --ltVerTick == 0) {
        if (isAir(ltVerX, ltVerY, ltVerZ)) {
            ltGhost++;
            ltQskip = 0;
            ltStall = 0;
            if (ltGhost == 3 && dbgBasic()) {
                dbgPrint("&cplacements are being reverted &7- the server is"
                        + " rejecting them (ghost blocks), not the script");
            }
        }
    }
    if (p.onGround()) {
        ltStandX = fl(pos.x);
        ltStandZ = fl(pos.z);
        ltGroundAge = 0;
    } else if (ltGroundAge < 99) {
        ltGroundAge++;
    }
    if (ltEng && !ltWasEngaged) {
        ltSprintJump = true;
        ltFlew = false;
        ltSideFresh = true;
        ltHavePrev = false;
        ltQn = 0;
        ltPendQ = -1;
    }
    ltWasEngaged = ltEng;
    if (ltClickCd > 0) {
        ltClickCd--;
    }
    if (ltTapCd > 0) {
        ltTapCd--;
    }
    Vec3 lm = p.getMotion();
    if (ltEng) {
        if (p.onGround() && ltFlew) {
            ltFlew = false;
            ltSprintJump = !ltSprintJump;
            ltSideFresh = ltSprintJump;
            ltBuildQueue(lm);
            if (!ltSprintJump) {
                rotationTick = 0;
            }
        }
        if (!p.onGround()) {
            ltFlew = true;
        }
        if (lm.y > 0.3 && ltPrevMotY <= 0.05) {
            ltBuildQueue(lm);
            if (dbgBasic()) {
                dbgPrint("&7lt jump: &f" + (ltSprintJump ? "SPRINT" : "NORMAL")
                        + " &7q=&f" + ltQn + " &7side=&f"
                        + (ltSide > 0 ? "R" : "L"));
            }
        }
    } else {
        ltQn = 0;
        ltPendQ = -1;
    }
    ltPrevMotY = lm.y;

    float camRaw = p.getYaw();
    if (!camHave) {
        camCont = camRaw;
        camHave = true;
    } else {
        camCont += wrap180(camRaw - camCont);
    }
    float cam = camCont;

    float fwdIn = (float) fwdVal();
    float leftIn = (float) leftVal();
    float currentYaw = cam;
    if (fwdIn < 0.0F) {
        currentYaw += 180.0F;
    }
    if (leftIn != 0.0F) {
        float mult = (fwdIn == 0.0F) ? 1.0F : 0.5F * Math.signum(fwdIn);
        currentYaw += -90.0F * mult * Math.signum(leftIn);
    }
    float base = sYaw();
    float yawDiffTo180 = wrapDiff(currentYaw - 180.0F, base);
    float diagonalYaw = isDiag(currentYaw)
            ? yawDiffTo180
            : wrapDiff(currentYaw - 135.0F * (float) cardSide(currentYaw), base);

    if (!canRotate) {

        if (yaw == -180.0F && pitch == 0.0F) {
            pitch = quant(85.0F);
        }
        yaw = quant(diagonalYaw);
    }

    gridBase = cam;
    float camGrid = cam;


    boolean flatGround = p.onGround() && !towering && !towerActive();
    if (flatGround) {
        yaw = quant(diagonalYaw);
        canRotate = true;
    }

    Object[] bd0 = flatGround ? null : blockData();
    ltBd0 = flatGround ? 'F' : (bd0 == null ? bdNull : 'g');
    if (bd0 != null) {
        int[] bp = (int[]) bd0[0];
        String sd = (String) bd0[1];
        double[] xs = placeOffsets;
        double[] ys = placeOffsets;
        double[] zs = placeOffsets;
        if (sd.equals("NORTH")) {
            zs = new double[]{0.0};
        } else if (sd.equals("EAST")) {
            xs = new double[]{1.0};
        } else if (sd.equals("SOUTH")) {
            zs = new double[]{1.0};
        } else if (sd.equals("WEST")) {
            xs = new double[]{0.0};
        } else if (sd.equals("DOWN")) {
            ys = new double[]{0.0};
        } else if (sd.equals("UP")) {
            ys = new double[]{1.0};
        }
        float bestYaw = 0.0F;
        float bestPitch = 0.0F;
        float bestDiff = 0.0F;
        boolean have = false;
        for (double dx : xs) {
            for (double dy : ys) {
                for (double dz : zs) {
                    double relX = (double) bp[0] + dx - pos.x;
                    double relY = (double) bp[1] + dy - pos.y - (double) p.getEyeHeight();
                    double relZ = (double) bp[2] + dz - pos.z;
                    float baseYaw = wrapDiff(yaw, base);
                    float[] rot = rotsTo(relX, relY, relZ, baseYaw, pitch);
                    Vec3 h = rayHit(bp[0], bp[1], bp[2], sd, rot[0], rot[1]);
                    if (h != null) {
                        float aY = rotSentLastTick ? lastSentYaw : baseYaw;
                        float aP = rotSentLastTick ? lastSentPitch : pitch;
                        float totalDiff = Math.abs(wrap180(rot[0] - aY)) + Math.abs(rot[1] - aP);
                        if (!have || totalDiff < bestDiff) {
                            bestYaw = rot[0];
                            bestPitch = rot[1];
                            bestDiff = totalDiff;
                            have = true;
                        }
                    }
                }
            }
        }
        if (have) {
            yaw = bestYaw;
            pitch = bestPitch;
            canRotate = true;
        }
    }

    boolean towerRotating = towering || towerActive();

    Vec3 mot = p.getMotion();
    float evYaw = rotSentLastTick ? lastSentYaw : cam;
    float evPitch = rotSentLastTick ? lastSentPitch : p.getPitch();

    boolean urgent = !rotSentLastTick
            || overAirAt(pos.x, pos.z)
            || overAirAt(pos.x + mot.x * 2.0, pos.z + mot.z * 2.0)
            || overAirAt(pos.x + mot.x * 3.5, pos.z + mot.z * 3.5)
            || overAirAt(pos.x + mot.x * 5.0, pos.z + mot.z * 5.0)
            || flatDry >= 3;

    if (!urgent) {

        float[] eased = humanEase(evYaw, evPitch, yaw, pitch);
        aimYaw(eased[0]);
        aimPitch(eased[1]);
    } else {

        aimYaw(yaw);
        aimPitch(pitch);
        rotSpeed = 0.0F;
        smSnapped = false;
        smYawDec = 0.0F;
    }
    if (towering && (mot.y > 0.0 || pos.y > (double) (startY + 1))) {
        float yawDiff = wrap180(yaw - evYaw);
        float tol = (rotationTick >= 2)
                ? rndF(LT_START_MIN, LT_START_MAX)
                : rndF(LT_NORM_MIN, LT_NORM_MAX);
        if (Math.abs(yawDiff) > tol) {
            aimYaw(evYaw + clampMag(yawDiff, tol));
            if (!(ltOn() && ltQn > 0)) {
                rotationTick = Math.max(rotationTick, 1);
            }
        }
    }
    int kBack = Math.round(wrap180(cam + 180.0F - camGrid) / 45.0F);
    int kCurB = Math.round(wrap180(lastSentYaw - camGrid) / 45.0F);
    while (kBack - kCurB > 4) {
        kBack -= 8;
    }
    while (kBack - kCurB < -4) {
        kBack += 8;
    }
    double lcx = Math.floor(pos.x) + 0.5;
    double lcz = Math.floor(pos.z) + 0.5;
    double lat = (pos.x - lcx) * (double) (-ltDirZ)
            + (pos.z - lcz) * (double) ltDirX;
    if (lat > 0.12) {
        lookSign = 1;
    } else if (lat < -0.12) {
        lookSign = -1;
    }
    if (kCurB == kBack) {
        lookFlat = true;
    }

    int kLook = lookFlat ? kBack : (kBack + LOOK_DIAG * lookSign);
    boolean camAligned = false;
    if (towerRotating && towerActive() && (!ltOn() || ltSprintJump)) {
        camAligned = true;
        lookFlat = false;
        aimYawJ(camGrid, jitter(0.12F));
        if (towerPitch < 30.0F || towerPitch > 89.5F) {
            towerPitch = clampPitch(lastSentPitch);
        }
        towerPitch += rndF(-0.5F, 0.5F);
        if (towerPitch < 30.0F) {
            towerPitch = 30.0F;
        } else if (towerPitch > 89.0F) {
            towerPitch = 89.0F;
        }
        towerPitch += (clampPitch(lastSentPitch) - towerPitch) * 0.25F;
        aimPitch(towerPitch);





        if (towerJumpActive()) {
            rotationTick = 2;
            launchLatch = true;
        } else if (!launchLatch) {
            rotationTick = Math.max(rotationTick, 2);
            launchLatch = true;
        }
        towering = true;
    }
    if (!camAligned) {
        launchLatch = false;
    }

    if (!towerRotating) {
        float snapBase = targetYaw;
        Vec3 fmv2 = p.getMotion();
        double fmLen2 = Math.sqrt(fmv2.x * fmv2.x + fmv2.z * fmv2.z);
        if (fmLen2 >= 0.02) {
            float travY = (float) Math.toDegrees(
                    Math.atan2(-fmv2.x, fmv2.z));
            float offCard = Math.abs(wrap180(travY
                    - (float) Math.round(travY / 90.0F) * 90.0F));
            if (flatCard) {
                if (offCard > 27.0F) {
                    flatCard = false;
                }
            } else if (offCard < 18.0F) {
                flatCard = true;
            }
        } else {
            flatCard = false;
        }
        if (fmLen2 >= 0.02 && flatCard) {
            double lcx3 = Math.floor(pos.x) + 0.5;
            double lcz3 = Math.floor(pos.z) + 0.5;
            double lat3 = ((pos.x - lcx3) * -fmv2.z
                    + (pos.z - lcz3) * fmv2.x) / fmLen2;
            if (lat3 > 0.12) {
                flatSide = 1;
            } else if (lat3 < -0.12) {
                flatSide = -1;
            }
            snapBase = (float) Math.toDegrees(
                    Math.atan2(fmv2.x, -fmv2.z))
                    + (float) (FLAT_DIAG * flatSide) * 45.0F;
        } else if (fmLen2 < 0.02) {
            snapBase = camGrid + (float) lastBucketK * 45.0F;
        }
        float holdW = FLAT_HOLD_DEG;
        if (!flatCard) {
            holdW = 29.5F;
        } else if (fmLen2 >= 0.02 && flatDry >= 2) {
            holdW = 22.5F;
        }
        float snapDelta = wrap180(snapBase - camGrid);
        int k = Math.round(snapDelta / 45.0F);
        if (k != lastBucketK
                && Math.abs(wrap180(snapDelta - (float) lastBucketK * 45.0F))
                    <= holdW) {
            k = lastBucketK;
        }
        lastBucketK = k;
        aimYaw(camGrid + (float) k * 45.0F);
    } else {
        lastBucketK = Math.round(wrap180(targetYaw - camGrid) / 45.0F);
    }

    aimYawJ(targetYaw, jitY());
    ysA = targetYaw;
    aimPitch(clampPitch(targetPitch));

    int flatIdx = -1;
    if (flatGround) {
        int candN = buildCandidates(fl(pos.y) - 1);
        dbgCandN = candN;
        computeNeed(fl(pos.y) - 1);
        if (needValid) {
            if (eagleSneaking) {
                urgent = true;
            }
            for (int c = 0; c < 4 && !urgent; c++) {
                double ccx = pos.x + (((c & 1) == 0) ? -0.3 : 0.3);
                double ccz = pos.z + (((c & 2) == 0) ? -0.3 : 0.3);
                if (fl(ccx) == needX && fl(ccz) == needZ) {
                    urgent = true;
                }
            }
        }
        if (candN > 0) {
            float bestP = Float.NaN;
            float bestD = 0.0F;
            int bestS = -1;
            dbgSel = 0;
            dbgSelScore = -1;
            boolean pitchHold = false;



            if (rotSentLastTick && !towerRotating) {
                int rIdx = matchCandidate(candN, lastBaseYaw, lastBasePitch);
                if (rIdx >= 0 && candScore(rIdx) >= 2
                        && matchCandidate(candN, lastBaseYaw + 0.5F,
                                lastBasePitch) >= 0
                        && matchCandidate(candN, lastBaseYaw - 0.5F,
                                lastBasePitch) >= 0
                        && matchCandidate(candN, lastBaseYaw,
                                clampPitch(lastBasePitch - 0.35F)) >= 0
                        && matchCandidate(candN, lastBaseYaw,
                                clampPitch(lastBasePitch + 0.35F)) >= 0) {
                    aimYawJ(lastBaseYaw, jitY());
                    pitchHold = true;
                    bestP = lastBasePitch;
                    bestS = 2;
                    bestD = 0.0F;
                    dbgSel = 6;
                    dbgSelScore = 2;
                }
            }
            int holdIdx = pitchHold ? -1
                    : matchCandidate(candN, targetYaw, evPitch);
            if (holdIdx >= 0 && candScore(holdIdx) >= 2
                    && matchCandidate(candN, targetYaw,
                            clampPitch(evPitch - 0.35F)) >= 0
                    && matchCandidate(candN, targetYaw,
                            clampPitch(evPitch + 0.35F)) >= 0) {
                pitchHold = true;
                bestP = evPitch;
                bestS = 2;
                bestD = 0.0F;
                dbgSel = 5;
                dbgSelScore = 2;
            }
            if (needValid && !pitchHold) {
                int[] kFull = new int[]{lastBucketK, lastBucketK - 1,
                        lastBucketK + 1, lastBucketK - 2, lastBucketK + 2,
                        lastBucketK - 3, lastBucketK + 3, lastBucketK + 4};
                Vec3 fmv = p.getMotion();
                double fmLen = Math.sqrt(fmv.x * fmv.x + fmv.z * fmv.z);
                int kPose = lastBucketK;
                if (fmLen >= 0.02) {
                    double lcx2 = Math.floor(pos.x) + 0.5;
                    double lcz2 = Math.floor(pos.z) + 0.5;
                    double lat2 = ((pos.x - lcx2) * -fmv.z
                            + (pos.z - lcz2) * fmv.x) / fmLen;
                    if (lat2 > 0.12) {
                        flatSide = 1;
                    } else if (lat2 < -0.12) {
                        flatSide = -1;
                    }
                    float backY = (float) Math.toDegrees(
                            Math.atan2(fmv.x, -fmv.z));
                    float poseY = backY
                            + (float) (FLAT_DIAG * flatSide) * 45.0F;
                    kPose = Math.round(wrap180(poseY - camGrid) / 45.0F);
                }
                int reCap = FLAT_REBUCKET;
                if (!flatCard || flatDry >= 4) {
                    reCap = 9;
                } else if (flatDry >= 2) {
                    reCap = 2;
                }
                int kUse = 1;
                for (int ki = 1; ki < kFull.length; ki++) {
                    if (Math.abs(kFull[ki] - lastBucketK) <= reCap) {
                        kUse++;
                    }
                }
                int[] kCand = new int[kUse];
                kCand[0] = lastBucketK;
                int kw = 1;
                for (int ki = 1; ki < kFull.length; ki++) {
                    if (Math.abs(kFull[ki] - lastBucketK) <= reCap) {
                        kCand[kw] = kFull[ki];
                        kw++;
                    }
                }
                for (int a2 = 0; a2 < kCand.length; a2++) {
                    for (int b2 = a2 + 1; b2 < kCand.length; b2++) {
                        if (Math.abs(kCand[b2] - kPose)
                                < Math.abs(kCand[a2] - kPose)) {
                            int tmp = kCand[a2];
                            kCand[a2] = kCand[b2];
                            kCand[b2] = tmp;
                        }
                    }
                }
                analytic:
                for (int pass = 2; pass >= 1; pass--) {
                    for (int ko = 0; ko < kCand.length; ko++) {
                        float rj = (ko == 0) ? pendJitY : jitY();
                        float ry = (ko == 0) ? targetYaw
                                : camGrid + (float) kCand[ko] * 45.0F
                                        + rj;
                        for (int i = 0; i < candN; i++) {
                            if (candScore(i) != pass) {
                                continue;
                            }
                            float ap = analyticPitch(candN, i, ry);
                            if (!Float.isNaN(ap)) {
                                bestP = ap;
                                bestS = pass;
                                if (ko > 0) {
                                    aimYawJ(ry - rj, rj);
                                    lastBucketK = kCand[ko];
                                }
                                dbgSel = 1;
                                dbgSelScore = pass;
                                break analytic;
                            }
                        }
                    }
                }
            }
            int maxScore = needValid ? 2 : 0;
            float sc = 60.0F;
            while (Float.isNaN(bestP) || bestS < maxScore) {
                float sp2 = Math.min(sc, 90.0F);
                int mi = matchCandidate(candN, targetYaw, quant(sp2));
                if (mi >= 0) {
                    int ms = candScore(mi);
                    float dd = sp2;
                    if (ms > bestS || (ms == bestS && dd < bestD)) {
                        bestP = sp2;
                        bestD = dd;
                        bestS = ms;
                        dbgSel = 2;
                        dbgSelScore = ms;
                    }
                }
                if (sc >= 90.0F) {
                    break;
                }

                float st = (sc >= 84.0F) ? (0.3F + rndF(0.0F, 0.08F))
                        : (1.0F + rndF(-0.38F, 0.38F));
                sc += st;
            }
            if (pitchHold) {
                pitch = evPitch;
                aimPitch(clampPitch(evPitch));
            } else if (!Float.isNaN(bestP)) {
                pitch = quant(bestP);
                if (!urgent) {
                    aimPitch(humanEase(targetYaw, evPitch, targetYaw,
                            pitch)[1]);
                } else {
                    aimPitch(pitch);
                }
                aimPitch(clampPitch(targetPitch));
            }
            flatIdx = matchCandidate(candN, targetYaw, targetPitch);
            if (flatIdx < 0 && !Float.isNaN(bestP)) {
                aimPitch(clampPitch(bestP));
                flatIdx = matchCandidate(candN, targetYaw, targetPitch);
            }
        }
    }

    Vec3 verifiedHit = null;
    int bdRow = (bd0 == null) ? 0
            : ((int[]) bd0[0])[1] + ("UP".equals(bd0[1]) ? 1 : 0);





    boolean bdOk = ltQn == 0 || bdRow <= ltRow + (ltSprintJump ? 0 : 1);

    if (bdOk && bd0 != null) {
        int[] bsp = (int[]) bd0[0];
        int[] bof = sideOffset((String) bd0[1]);
        if (overBuild(bsp[0] + bof[0], bsp[1] + bof[1], bsp[2] + bof[2])) {
            bdOk = false;
            ltBd0 = 'h';
        }
    }
    if (bd0 != null && bdOk) {
        ltBd0 = 'y';
        int[] bp = (int[]) bd0[0];
        String sd = (String) bd0[1];




        if (rotSentLastTick && !towerRotating
                && rayHit(bp[0], bp[1], bp[2], sd, lastBaseYaw, lastBasePitch) != null
                && rayHit(bp[0], bp[1], bp[2], sd, lastBaseYaw + 0.5F, lastBasePitch) != null
                && rayHit(bp[0], bp[1], bp[2], sd, lastBaseYaw - 0.5F, lastBasePitch) != null
                && rayHit(bp[0], bp[1], bp[2], sd, lastBaseYaw,
                        clampPitch(lastBasePitch - 0.35F)) != null
                && rayHit(bp[0], bp[1], bp[2], sd, lastBaseYaw,
                        clampPitch(lastBasePitch + 0.35F)) != null) {
            aimYawJ(lastBaseYaw, jitY());
            aimPitch(lastBasePitch);
        }
        verifiedHit = rayHit(bp[0], bp[1], bp[2], sd, targetYaw, targetPitch);
        if (verifiedHit == null && towerRotating) {

            float fp = clampPitch(Math.max(30.0F, pitch));
            Vec3 h2 = rayHit(bp[0], bp[1], bp[2], sd, targetYaw, fp);
            if (h2 != null) {
                aimPitch(fp);
                verifiedHit = h2;
            }
        }
        if (verifiedHit == null && !towerRotating) {

            int k0 = Math.round(wrap180(yaw - camGrid) / 45.0F);
            int[] ks = new int[]{k0, k0 - 1, k0 + 1, k0 - 2, k0 + 2,
                    k0 - 3, k0 + 3, k0 + 4};
            if (lockHeld() && ltOn() && ltQn > 0 && !camAligned
                    && k0 != kLook) {
                ks = new int[]{kLook, k0, k0 - 1, k0 + 1, k0 - 2, k0 + 2,
                        k0 - 3, k0 + 3, k0 + 4};
            }
            for (int i = 0; i < ks.length && verifiedHit == null; i++) {
                float fj = jitter(0.2F);
                float fy = camGrid + (float) ks[i] * 45.0F + fj;
                float ap = analyticFace(bp[0], bp[1], bp[2], sd, fy);
                if (Float.isNaN(ap)) {
                    continue;
                }
                Vec3 h3 = rayHit(bp[0], bp[1], bp[2], sd, fy, ap);
                if (h3 != null) {
                    aimYawJ(fy - fj, fj);
                    aimPitch(ap);
                    verifiedHit = h3;
                    lastBucketK = ks[i];
                    rotSpeed = 0.0F;
                    dbgSel = 3;
                    dbgSelScore = 2;
                }
            }
        }
    }

    ysB = targetYaw;

    ltWhy = 'N';
    if (ltEng && verifiedHit == null && flatIdx < 0 && ltQn > 0
            && !(p.onGround() && ltSprintJump) && ltCorridorSolve()) {
        float tol = ltSprintJump
                ? rndF(LT_NORM_MIN, LT_NORM_MAX)
                : rndF(LT_START_MIN, LT_START_MAX);
        float dY = wrap180(ltAimYaw - evYaw);
        float dP = ltAimPitch - evPitch;
        float frac = 1.0F
                - (float) Math.pow(0.05, 1.0 / (double) LT_SMOOTH_TICKS);
        aimYawJ(evYaw + clampMag(dY * frac, tol), jitter(0.1F));
        aimPitch(clampPitch(evPitch + clampMag(dP * frac, tol)));
        lastBucketK = Math.round(wrap180(targetYaw - camGrid) / 45.0F);
        dbgSel = 4;
        dbgSelScore = 2;
        Vec3 lh = null;


        if (rotSentLastTick && !camAligned
                && rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                        lastBaseYaw, lastBasePitch) != null
                && rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                        lastBaseYaw + 0.5F, lastBasePitch) != null
                && rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                        lastBaseYaw - 0.5F, lastBasePitch) != null
                && rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                        lastBaseYaw, clampPitch(lastBasePitch - 0.35F)) != null
                && rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                        lastBaseYaw, clampPitch(lastBasePitch + 0.35F)) != null) {
            aimYawJ(lastBaseYaw, jitter(0.1F));
            aimPitch(clampPitch(lastBasePitch));
            lastBucketK = Math.round(wrap180(targetYaw - camGrid) / 45.0F);
            lh = rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                    targetYaw, targetPitch);
        }
        float gkNear = (float) Math.round(wrap180(targetYaw - camGrid) / 45.0F);
        float[] gks = (lockHeld() && ltOn() && ltQn > 0 && !camAligned
                && gkNear != (float) kLook)
                ? new float[]{(float) kLook, gkNear}
                : new float[]{gkNear};
        for (int gi = 0; gi < gks.length && lh == null; gi++) {
            float gk = gks[gi];
            float gTol = (gk == (float) kLook) ? Math.max(tol, 46.0F) : tol;
            float gJit = jitter(0.1F);
            float gYaw = camGrid + gk * 45.0F + gJit;
            if (Math.abs(wrap180(gYaw - targetYaw)) <= gTol) {
                float gFace = faceNearestPitch(ltPendX, ltPendY, ltPendZ,
                        ltPendSide, gYaw, evPitch);
                if (!Float.isNaN(gFace)) {
                    float gPitch = clampPitch(gFace);
                    Vec3 gh = rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                            gYaw, targetPitch);
                    float usePitch = targetPitch;
                    if (gh == null) {
                        usePitch = gPitch;
                        gh = rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                                gYaw, gPitch);
                    }
                    if (gh != null) {
                        aimYawJ(gYaw - gJit, gJit);
                        aimPitch(usePitch);
                        lastBucketK = Math.round(gk);
                        lh = gh;
                    }
                }
            }
        }
        if (lh == null) {
            lh = rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                    targetYaw, targetPitch);
        }
        if (lh == null && Math.abs(dY) <= tol && Math.abs(dP) <= tol) {
            float ap = faceNearestPitch(ltPendX, ltPendY, ltPendZ, ltPendSide,
                    targetYaw, targetPitch);
            if (!Float.isNaN(ap)) {
                aimPitch(clampPitch(ap));
                lh = rayHit(ltPendX, ltPendY, ltPendZ, ltPendSide,
                        targetYaw, targetPitch);
            }
        }
        if (lh == null) {
            lh = ltSweepSighted(targetYaw, targetPitch);
            if (lh != null) {
                ltWhy = 'S';
            }
        }
        if (lh != null) {
            ltPendHit = lh;
            ltStall = 0;
            if (ltWhy != 'S') {
                ltWhy = 'P';
            }
        } else {
            ltWhy = 'R';
            if (ltPendQ >= 0 && ++ltStall >= 3) {
                ltQskip |= 1 << ltPendQ;
                ltStall = 0;
                ltPendQ = -1;
            }
        }
    }

    if (verifiedHit == null && flatIdx < 0 && ltPendHit == null
            && !placedThisTick) {
        float actN = ACT_SMOOTH_TICKS;
        boolean ramping = actN >= 1.0F && (float) actTick <= actN;

        float actFrac = ramping
                ? 1.0F - (float) Math.pow(0.05, 1.0 / (double) actN)
                : 1.0F;
        float dstK = (float) Math.round(wrap180(targetYaw - gridBase) / 45.0F);
        if (lockHeld() && ltOn() && ltQn > 0 && !camAligned) {
            dstK = (float) kLook;
        }
        float curK = (float) Math.round(wrap180(evYaw - gridBase) / 45.0F);
        while (dstK - curK > 4.0F) {
            dstK -= 8.0F;
        }
        while (dstK - curK < -4.0F) {
            dstK += 8.0F;
        }
        float newK = dstK;
        float stepMax = YAW_EASE_STEP;
        boolean stepping = !p.onGround();
        if (ramping) {
            actYawAcc += Math.abs(dstK - curK) * actFrac * ACT_YAW_LAG;
            stepMax = (float) Math.floor(actYawAcc);
            actYawAcc -= stepMax;
            stepping = true;
        } else {
            actYawAcc = 0.0F;
        }
        if (stepping && Math.abs(dstK - curK) > stepMax) {
            newK = curK + Math.signum(dstK - curK) * stepMax;
        }
        aimYawJ(gridBase + newK * 45.0F, jitter(0.18F));
        if (ramping) {
            float dP = targetPitch - evPitch;
            if (Math.abs(dP) > 0.5F) {
                aimPitch(clampPitch(evPitch + dP * actFrac));
            }
        }
    }


    if (mot.x * mot.x + mot.z * mot.z > 0.0016) {
        float moveDir = (float) Math.toDegrees(Math.atan2(-mot.x, mot.z));
        dbgMoveErr = wrap180(moveDir - cam);
    }


    dbgSentMod = Math.abs(wrap180(targetYaw - gridBase
            - (float) Math.round(wrap180(targetYaw - gridBase) / 45.0F) * 45.0F));

    float pjKeep = pendJitY;
    try {
        float baseY = mindless.utility.RotationUtils.serverRotations[0];
        aimYaw(baseY + wrap180(targetYaw - baseY));
    } catch (Throwable t) {
        aimYaw(lastSentYaw + wrap180(targetYaw - lastSentYaw));
    }
    pendJitY = pjKeep;










    targetYaw = mouseQuant(targetYaw, lastSentYaw);
    targetPitch = clampPitch(mouseQuant(targetPitch, lastSentPitch));
    client.setRotations(targetYaw, targetPitch);
    ysC = targetYaw;
    prevSentPitch = lastSentPitch;
    lastSentYaw = targetYaw;
    lastSentPitch = targetPitch;

    lastBaseYaw = targetYaw - pendJitY;
    lastBasePitch = targetPitch;
    rotSentThisTick = true;

    client.enableMovementFix();
    mfixActive = client.isMovementFixActive();

    if (ltPendHit != null && rotationTick > 0 && !ltOn()) {
        ltWhy = 'L';
    }
    if (flatIdx >= 0 && rotationTick <= 0) {
        doPlace(cands[flatIdx][0], cands[flatIdx][1], cands[flatIdx][2],
                FACES[cands[flatIdx][3]], scanHit);
    } else if (verifiedHit != null && rotationTick <= 0) {
        int[] bp = (int[]) bd0[0];
        String sd = (String) bd0[1];
        doPlace(bp[0], bp[1], bp[2], sd, verifiedHit);
    } else if (ltPendHit != null && (ltOn() || rotationTick <= 0)) {

        int[] vo = sideOffset(ltPendSide);
        ltVerX = ltPendX + vo[0];
        ltVerY = ltPendY + vo[1];
        ltVerZ = ltPendZ + vo[2];
        ltVerTick = 4;
        doPlace(ltPendX, ltPendY, ltPendZ, ltPendSide, ltPendHit);
    }

    if (needValid && !placedThisTick && p.onGround() && !towering) {
        flatDry++;
    } else {
        flatDry = 0;
    }

    if (effKeepMode().equals("Y-Telly") && stage > 0 && !p.onGround()) {
        int nextBlockY = fl(pos.y + mot.y);
        if (nextBlockY <= startY && pos.y > (double) (startY + 1)) {
            shouldKeepY = true;
            Object[] bd = blockData();
            if (bd != null && rotationTick <= 0 && !placedThisTick) {
                int[] q = (int[]) bd[0];
                String qs = (String) bd[1];
                Vec3 h = rayHit(q[0], q[1], q[2], qs, lastSentYaw, lastSentPitch);
                if (h != null) {
                    doPlace(q[0], q[1], q[2], qs, h);
                }
            }
        }
    }
    autoClick(p);
}



@SubscribeEvent(priority = EventPriority.HIGHEST)
public void onPrePlayerInput(PrePlayerInputEvent event) {
    MovementInput input = new MovementInput(event, (byte) 0);
    handlePlayerInput(input);
    event.setForward(input.forward);
    event.setStrafe(input.strafe);
    event.setJump(input.jump);
    event.setSneak(input.sneak);
}

private void handlePlayerInput(MovementInput input) {
    Entity p = client.getPlayer();
    if (p == null || !world.exists()) {
        return;
    }
    applySprintPolicy(shouldStopSprint());
    boolean airTap = ltOn() && towering && !p.onGround();
    if (airTap && input.strafe == 0.0F
            && ltTapCd <= 0 && client.isMovementFixActive()) {
        Vec3 mm = p.getMotion();
        double cyr = Math.toRadians((double) pYaw());
        double lat = mm.x * -Math.cos(cyr) + mm.z * -Math.sin(cyr);
        if (Math.abs(lat) > 0.02) {
            input.strafe = (lat > 0.0) ? 1.0F : -1.0F;
            ltTapCd = 2 + (rndF(0.0F, 1.0F) < 0.4F ? 1 : 0);
        }
    }
    if (p.onGround() && stage > 0 && fwdPressed()) {
        input.jump = true;
    }
    if (!p.onGround()) {
        jumpHeld = 0;
    } else if (input.jump && ltOn() && ltSprintJump
            && jumpHeld < SPRINT_JUMP_HOLD
            && Math.abs(wrap180(lastSentYaw - pYaw())) > SPRINT_JUMP_ALIGN) {
        input.jump = false;
        jumpHeld++;
    }

    updateEagle(input);
}

@SubscribeEvent(priority = EventPriority.HIGHEST)
public void onPacketSent(SendPacketEvent event) {
    if (event.isCanceled() || event.getPacket() == null) {
        return;
    }
    CPacket packet = PacketHandler.convertServerBound(event.getPacket());
    if (packet != null && !allowPacket(packet)) {
        event.setCanceled(true);
    }
}

private boolean allowPacket(CPacket packet) {
    if (packet instanceof C08) {
        if (ourPlace) {
            return true;
        }
        if (clickerFiring) {
            return true;
        }
        return !(rotSentThisTick || rotSentLastTick);
    }
    if (!(packet instanceof C07)) {
        return true;
    }
    C07 dig = (C07) packet;
    if (dig.status == null || !dig.status.contains("DESTROY")) {
        return true;
    }
    Entity p = client.getPlayer();
    if (p == null || !p.isHoldingBlock()) {
        return true;
    }
    return !(rotSentThisTick || rotSentLastTick);
}

@SubscribeEvent(priority = EventPriority.HIGHEST)
public void onMouse(MouseEvent event) {
    if (event.button > -1 && !allowMouse(event.button)) {
        event.setCanceled(true);
    }
}

private boolean allowMouse(int button) {
    if (!client.getScreen().isEmpty()) {
        return true;
    }
    return button > 1;
}

@SubscribeEvent(priority = EventPriority.LOWEST)
public void onRenderWorld(RenderWorldLastEvent event) {
    float partialTicks = event.partialTicks;
    if (!dbgVisual() || !world.exists()) {
        return;
    }
    Entity p = client.getPlayer();
    if (p == null || !rotSentThisTick) {
        return;
    }

    Vec3 pos = p.getPosition();
    Vec3 eye = new Vec3(pos.x, pos.y + (double) p.getEyeHeight(), pos.z);
    double yawRad = Math.toRadians((double) lastSentYaw);
    double pitchRad = Math.toRadians((double) lastSentPitch);
    double horizontal = Math.cos(pitchRad);
    Vec3 end = new Vec3(
            eye.x - Math.sin(yawRad) * horizontal * 4.5,
            eye.y - Math.sin(pitchRad) * 4.5,
            eye.z + Math.cos(yawRad) * horizontal * 4.5);

    Object[] ray = client.raycastBlock(4.5, lastSentYaw, lastSentPitch);
    int rayColor = 0xA0FF5555;
    if (validRayResult(ray)) {
        Vec3 support = (Vec3) ray[0];
        Vec3 offset = (Vec3) ray[1];
        end = new Vec3(support.x + offset.x, support.y + offset.y,
                support.z + offset.z);
        rayColor = 0xA055FFFF;
    }
    render.line3D(eye, end, 2.0F, rayColor);

    if (dbgLastPlaceHit != null && client.time() - dbgLastPlaceAt <= 750L) {
        double s = 0.09;
        int placeColor = 0xF055FF55;
        render.line3D(dbgLastPlaceHit.offset(-s, 0.0, 0.0),
                dbgLastPlaceHit.offset(s, 0.0, 0.0), 3.0F, placeColor);
        render.line3D(dbgLastPlaceHit.offset(0.0, -s, 0.0),
                dbgLastPlaceHit.offset(0.0, s, 0.0), 3.0F, placeColor);
        render.line3D(dbgLastPlaceHit.offset(0.0, 0.0, -s),
                dbgLastPlaceHit.offset(0.0, 0.0, s), 3.0F, placeColor);
    }
}



private int buildCandidates(int ty) {
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    int x0 = fl(pos.x - 0.3);
    int x1 = fl(pos.x + 0.3);
    int z0 = fl(pos.z - 0.3);
    int z1 = fl(pos.z + 0.3);
    Vec3 mv = p.getMotion();
    double mvx = mv.x;
    double mvz = mv.z;
    double mvLen = Math.sqrt(mvx * mvx + mvz * mvz);
    boolean fwdOnly = mvLen >= 0.02;
    int n = 0;
    for (int x = x0; x <= x1; x++) {
        for (int z = z0; z <= z1; z++) {
            if (!isSolidSupport(x, ty, z)) {
                continue;
            }
            for (int f = 0; f < 4 && n < 16; f++) {
                int[] off = sideOffset(FACES[f]);
                int rx = x + off[0];
                int rz = z + off[2];
                if (fwdOnly && !(needValid && rx == needX && rz == needZ)
                        && (double) off[0] * mvx + (double) off[2] * mvz
                            < 0.15 * mvLen) {
                    continue;
                }
                if (isAir(rx, ty, rz)) {
                    cands[n][0] = x;
                    cands[n][1] = ty;
                    cands[n][2] = z;
                    cands[n][3] = f;
                    n++;
                }
            }
        }
    }
    return n;
}

private int matchCandidate(int candN, float ry, float rp) {
    scanHit = null;
    Object[] r = client.raycastBlock(4.5, ry, rp);
    if (r == null) {
        return -1;
    }
    Vec3 rpos = (Vec3) r[0];
    Vec3 roff = (Vec3) r[1];
    String rside = (String) r[2];
    int hx = (int) rpos.x;
    int hy = (int) rpos.y;
    int hz = (int) rpos.z;
    for (int i = 0; i < candN; i++) {
        if (hx == cands[i][0] && hy == cands[i][1] && hz == cands[i][2]
                && rside.equals(FACES[cands[i][3]])) {
            scanHit = new Vec3(rpos.x + roff.x, rpos.y + roff.y, rpos.z + roff.z);
            return i;
        }
    }
    return -1;
}

private double simNextX = 0.0;
private double simNextZ = 0.0;


private void buildPred() {
    predHave = true;
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    Vec3 m = p.getMotion();
    for (int t = 0; t < predY.length; t++) {
        predY[t] = pos.y;
    }
    try {
        Simulation sim = Simulation.create();
        sim.setSneak(false);
        predY[0] = sim.getPosition().y;
        for (int t = 1; t < predY.length; t++) {
            sim.tick();
            predY[t] = sim.getPosition().y;
        }
    } catch (Throwable ex) {
        double vy = m.y;
        double y = pos.y;
        for (int t = 1; t < predY.length; t++) {
            vy = (vy - 0.08) * 0.98;
            y += vy;
            predY[t] = y;
        }
    }
}

private boolean overBuild(int cx, int cy, int cz) {
    if (!predHave) {
        buildPred();
    }
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    double dx = ((double) cx + 0.5) - pos.x;
    double dz = ((double) cz + 0.5) - pos.z;
    double hDist = Math.sqrt(dx * dx + dz * dz);
    float ay = (float) Math.toDegrees(Math.atan2(-dx, dz));
    float a90 = Math.abs(ay % 90.0F);
    boolean inter = a90 > 22.5F && a90 < 67.5F;
    int idx = (int) (hDist * (inter ? 1.5 : 1.0) + 0.7);
    if (idx < 0) {
        idx = 0;
    } else if (idx > predY.length - 1) {
        idx = predY.length - 1;
    }




    if ((double) cy + 1.0 <= pos.y + 0.001) {
        return false;
    }
    return (double) cy > predY[idx];
}

private void simNext(int ticks) {
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    try {
        Simulation sim = Simulation.create();
        sim.setSneak(false);
        for (int t = 0; t < ticks; t++) {
            sim.tick();
        }
        Vec3 sp2 = sim.getPosition();
        simNextX = sp2.x;
        simNextZ = sp2.z;
    } catch (Throwable t) {

        Vec3 m = p.getMotion();
        simNextX = pos.x + m.x * (double) (ticks + 1);
        simNextZ = pos.z + m.z * (double) (ticks + 1);
    }
}

private double supportOverhang(double px, double pz) {
    Entity p = client.getPlayer();
    int ty = fl(p.getPosition().y) - 1;
    int cxx = fl(px);
    int czz = fl(pz);
    double best = 999.0;
    for (int x = cxx - 1; x <= cxx + 1; x++) {
        for (int z = czz - 1; z <= czz + 1; z++) {
            if (!isSolidSupport(x, ty, z)) {
                continue;
            }
            double px2 = Math.max((double) x, Math.min(px, (double) x + 1.0));
            double pz2 = Math.max((double) z, Math.min(pz, (double) z + 1.0));
            double d = Math.max(Math.abs(px - px2), Math.abs(pz - pz2));
            if (d < best) {
                best = d;
            }
        }
    }
    return best;
}

private boolean edgeDanger(int simTicks) {
    simNext(simTicks);
    Entity p = client.getPlayer();
    Vec3 m = p.getMotion();
    double sp2 = Math.sqrt(m.x * m.x + m.z * m.z);
    double probeX = simNextX;
    double probeZ = simNextZ;
    if (sp2 > 0.01) {

        double lead = sld("Edge Distance");
        probeX += m.x / sp2 * lead;
        probeZ += m.z / sp2 * lead;
    }
    double over = supportOverhang(probeX, probeZ);
    if (simTicks == 1) {
        dbgOver = over;
    }
    return over > 0.0;
}

private void computeNeed(int ty) {
    needValid = false;
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    try {
        Simulation sim = Simulation.create();
        sim.setSneak(false);
        for (int t = 0; t < 5 && !needValid; t++) {
            sim.tick();
            Vec3 sp2 = sim.getPosition();
            checkNeedCorners(sp2.x, sp2.z, ty);
        }
    } catch (Throwable t) {
        Vec3 m = p.getMotion();
        for (int k = 1; k <= 5 && !needValid; k++) {
            checkNeedCorners(pos.x + m.x * (double) k, pos.z + m.z * (double) k, ty);
        }
    }
}

private void checkNeedCorners(double px, double pz, int ty) {
    Vec3 m = client.getPlayer().getMotion();
    double best = -1.0E9;
    for (int c = 0; c < 4; c++) {
        double ox = ((c & 1) == 0) ? -0.3 : 0.3;
        double oz = ((c & 2) == 0) ? -0.3 : 0.3;
        int nx = fl(px + ox);
        int nz = fl(pz + oz);
        if (!isAir(nx, ty, nz)) {
            continue;
        }
        double fwd = ox * m.x + oz * m.z;
        double dxc = ((double) nx + 0.5) - px;
        double dzc = ((double) nz + 0.5) - pz;
        float cellYaw = (float) Math.toDegrees(Math.atan2(-dxc, dzc));
        float aimOff = Math.abs(wrap180(cellYaw - lastSentYaw));
        fwd += 0.01 * (double) (180.0F - aimOff) / 180.0;
        if (fwd > best) {
            best = fwd;
            needX = nx;
            needZ = nz;
            needValid = true;
        }
    }
}

private float analyticPitch(int candN, int i, float ry) {
    return analyticFace(cands[i][0], cands[i][1], cands[i][2],
            FACES[cands[i][3]], ry);
}

private boolean fgUp = false;
private double fgTn = 0.0;
private double fgTf = 0.0;
private double fgTop = 0.0;
private double fgEy = 0.0;

private boolean faceGeom(int bx, int by, int bz, String side, float ry) {
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    double ex = pos.x;
    double ey = pos.y + (double) p.getEyeHeight();
    double ez = pos.z;
    fgEy = ey;
    double dx = -Math.sin(Math.toRadians((double) ry));
    double dz = Math.cos(Math.toRadians((double) ry));
    if (side.equals("UP")) {
        fgUp = true;
        double topY = (double) by + 1.0;
        fgTop = topY;
        if (ey <= topY + 0.05) {
            return false;
        }
        double tn = 0.001;
        double tf = 4.0;
        if (Math.abs(dx) < 1.0E-6) {
            if (ex < (double) bx + 0.05 || ex > (double) bx + 0.95) {
                return false;
            }
        } else {
            double t1 = ((double) bx + 0.05 - ex) / dx;
            double t2 = ((double) bx + 0.95 - ex) / dx;
            tn = Math.max(tn, Math.min(t1, t2));
            tf = Math.min(tf, Math.max(t1, t2));
        }
        if (Math.abs(dz) < 1.0E-6) {
            if (ez < (double) bz + 0.05 || ez > (double) bz + 0.95) {
                return false;
            }
        } else {
            double t1 = ((double) bz + 0.05 - ez) / dz;
            double t2 = ((double) bz + 0.95 - ez) / dz;
            tn = Math.max(tn, Math.min(t1, t2));
            tf = Math.min(tf, Math.max(t1, t2));
        }
        if (tn >= tf) {
            return false;
        }
        fgTn = tn;
        fgTf = tf;
        return true;
    }
    fgUp = false;
    int[] off = sideOffset(side);
    if (off[1] != 0) {
        return false;
    }
    double t;
    double hitA;
    double lo;
    if (off[0] != 0) {
        double pv = (double) bx + ((off[0] > 0) ? 1.0 : 0.0);
        if ((ex - pv) * (double) off[0] <= 0.0 || Math.abs(dx) < 1.0E-6) {
            return false;
        }
        t = (pv - ex) / dx;
        hitA = ez + dz * t;
        lo = (double) bz;
    } else {
        double pv = (double) bz + ((off[2] > 0) ? 1.0 : 0.0);
        if ((ez - pv) * (double) off[2] <= 0.0 || Math.abs(dz) < 1.0E-6) {
            return false;
        }
        t = (pv - ez) / dz;
        hitA = ex + dx * t;
        lo = (double) bx;
    }
    if (t <= 0.0 || t > 4.0) {
        return false;
    }
    if (hitA < lo + 0.05 || hitA > lo + 0.95) {
        return false;
    }
    fgTn = t;
    fgTf = t;
    return true;
}

private float analyticFace(int bx, int by, int bz, String side, float ry) {
    if (!faceGeom(bx, by, bz, side, ry)) {
        return Float.NaN;
    }
    double[] fr = fgUp ? new double[]{0.5, 0.25, 0.75}
            : new double[]{0.5, 0.8, 0.2};
    for (int k = 0; k < fr.length; k++) {
        float pr;
        if (fgUp) {

            double tm = fgTn + (fgTf - fgTn) * fr[k];
            pr = (float) Math.toDegrees(Math.atan2(fgEy - fgTop, tm));
        } else {
            pr = (float) Math.toDegrees(
                    Math.atan2(fgEy - ((double) by + fr[k]), fgTn));
        }
        if (pr > 90.0F) {
            pr = 90.0F;
        }
        pr = quant(pr);
        if (rayHit(bx, by, bz, side, ry, pr) != null) {
            return pr;
        }
    }
    return Float.NaN;
}

private float faceNearestPitch(int bx, int by, int bz, String side, float ry,
        float curPitch) {
    if (!faceGeom(bx, by, bz, side, ry)) {
        return Float.NaN;
    }
    double pLo;
    double pHi;
    if (fgUp) {

        pLo = Math.toDegrees(Math.atan2(fgEy - fgTop, fgTf));
        pHi = Math.toDegrees(Math.atan2(fgEy - fgTop, fgTn));
    } else {

        pLo = Math.toDegrees(Math.atan2(fgEy - ((double) by + 0.95), fgTn));
        pHi = Math.toDegrees(Math.atan2(fgEy - ((double) by + 0.05), fgTn));
    }

    double margin = Math.min(0.35, (pHi - pLo) * 0.25);
    double loI = pLo + margin;
    double hiI = pHi - margin;
    if (loI > hiI) {
        loI = (pLo + pHi) * 0.5;
        hiI = loI;
    }
    if ((double) curPitch >= loI && (double) curPitch <= hiI
            && rayHit(bx, by, bz, side, ry, curPitch) != null) {
        return curPitch;
    }
    double want = (double) curPitch;
    double span = hiI - loI;
    double step = Math.min(0.45, span * 0.30);
    if (want < loI) {
        want = loI + rndD(0.0, step);
    } else if (want > hiI) {
        want = hiI - rndD(0.0, step);
    }
    float pr = quant(clampPitch((float) want));
    if (rayHit(bx, by, bz, side, ry, pr) != null) {
        return pr;
    }
    double[] fr = {0.5 + rndD(-0.06, 0.06), 0.3 + rndD(-0.05, 0.05),
            0.7 + rndD(-0.05, 0.05)};
    for (int k = 0; k < fr.length; k++) {
        float pf = quant(clampPitch((float) (pLo + (pHi - pLo) * fr[k])));
        if (rayHit(bx, by, bz, side, ry, pf) != null) {
            return pf;
        }
    }
    return Float.NaN;
}

private int candScore(int i) {
    if (!needValid) {
        return 0;
    }
    int[] off = sideOffset(FACES[cands[i][3]]);
    int px2 = cands[i][0] + off[0];
    int pz2 = cands[i][2] + off[2];
    if (px2 == needX && pz2 == needZ) {
        return 2;
    }
    int dd = Math.abs(px2 - needX) + Math.abs(pz2 - needZ);
    return (dd == 1) ? 1 : 0;
}

private void updateEagle(MovementInput input) {
    if (!btn("Eagle") || towering || towerActive()) {
        eagleSneaking = false;
        eagleReleaseTicks = -1;
        return;
    }
    Entity p = client.getPlayer();
    if (!p.onGround()) {
        eagleSneaking = false;
        eagleReleaseTicks = -1;
        return;
    }
    if (edgeDanger(1)) {
        eagleSneaking = true;
        eagleReleaseTicks = -1;
    } else if (eagleSneaking) {
        if (edgeDanger(2)) {
            eagleReleaseTicks = -1;
        } else {
            if (eagleReleaseTicks < 0) {

                double raw = sld("Sneak Delay") / 50.0;
                int baseT = (int) raw;
                eagleReleaseTicks = baseT + ((Math.random() < raw - (double) baseT) ? 1 : 0);
            }
            if (eagleReleaseTicks > 0) {
                eagleReleaseTicks--;
            } else {
                eagleSneaking = false;
            }
        }
    }
    if (eagleSneaking && !input.sneak) {
        input.sneak = true;
        input.forward *= 0.3F;
        input.strafe *= 0.3F;
    }
}

private static final float SPRINT_MAX_OFF = 50.0F;

private float travelYaw() {
    Entity p = client.getPlayer();
    Vec3 m = p.getMotion();
    if (m != null && (m.x * m.x + m.z * m.z) > 0.0016) {
        return (float) (Math.atan2(-m.x, m.z) * 180.0 / Math.PI);
    }
    return curYaw();
}

private boolean shouldStopSprint() {
    if (towering || towerActive()) {
        sprintStopState = false;
        return false;
    }
    float off = Math.abs(wrap180(lastSentYaw - travelYaw()));
    if (sprintStopState) {
        if (off < SPRINT_MAX_OFF - 10.0F) {
            sprintStopState = false;
        }
    } else if (off > SPRINT_MAX_OFF + 5.0F) {
        sprintStopState = true;
    }
    return sprintStopState;
}

private void applySprintPolicy(boolean stop) {
    if (sprintDwell > 0) {
        sprintDwell--;
    }
    if (stop == sprintSuppressed) {

        if (sprintSuppressed) {
            client.setSprinting(false);
            keybinds.setPressed("sprint", false);
        }
        return;
    }
    if (sprintDwell > 0) {
        if (sprintSuppressed) {
            client.setSprinting(false);
            keybinds.setPressed("sprint", false);
        }
        return;
    }
    sprintDwell = 4;
    if (stop) {
        sprintPrevVirtual = keybinds.isPressed("sprint");
        sprintSuppressed = true;
        client.setSprinting(false);
        keybinds.setPressed("sprint", false);
    } else {
        keybinds.setPressed("sprint",
                sprintPrevVirtual || keybinds.isKeyDown(keybinds.getKeyCode("sprint")));
        sprintSuppressed = false;
    }
}

private boolean dbgBasic() {
    String d = debugMode.getSelectedOption();
    return d.equals("Basic") || d.equals("Verbose");
}

private boolean dbgVerbose() {
    return debugMode.getSelectedOption().equals("Verbose");
}

private boolean dbgVisual() {
    return debugMode.getSelectedOption().equals("Visual");
}

private void dbgPrint(String msg) {
    client.print(Utils.formatColor("&8[&bScaffold&8]&r " + msg));
}

private String towerMode() {
    return towerMode.getSelectedOption();
}

private String keepMode() {
    return keepMode.getSelectedOption();
}

private boolean iceOn() {
    return sld("Ice Spacing") > -0.595F;
}

private boolean keepBindHeld() {
    return btn("RMB Activate") && keybinds.isMouseDown(1);
}

private boolean keepYWanted() {
    return !keepMode().equals("None") || keepBindHeld();
}

private String effKeepMode() {
    String m = keepMode();
    return (m.equals("None") && keepBindHeld()) ? "Y-Telly+" : m;
}

private boolean btn(String n) {
    if (n.equals("RMB Activate")) return rmbActivate.isToggled();
    if (n.equals("On Potion Disable")) return potionDisable.isToggled();
    return n.equals("Eagle") && eagle.isToggled();
}

private float sld(String n) {
    if (n.equals("Ice Spacing")) return (float) iceSpacing.getInput();
    if (n.equals("Edge Distance")) return (float) edgeDistance.getInput();
    if (n.equals("Sneak Delay")) return (float) sneakDelay.getInput();
    return n.equals("Click Speed") ? (float) clickSpeed.getInput() : 0.0F;
}

private int fl(double d) {
    return (int) Math.floor(d);
}


private int clampStep(int d) {
    return d > 1 ? 1 : (d < -1 ? -1 : d);
}

private float rndF(float lo, float hi) {
    return (float) (lo + Math.random() * (hi - lo));
}

private float pYaw() {
    return client.getPlayer().getYaw();
}

private float sYaw() {
    Float f = client.getServerYaw();
    return (f == null) ? pYaw() : f.floatValue();
}





private final float MOUSE_COUNT = 0.023437500F;
private float mouseQuant(float value, float ref) {
    float d = value - ref;
    float snapped = Math.round(d / MOUSE_COUNT) * MOUSE_COUNT;
    return ref + snapped;
}

private float wrap180(float a) {
    a = a % 360.0F;
    if (a >= 180.0F) {
        a -= 360.0F;
    }
    if (a < -180.0F) {
        a += 360.0F;
    }
    return a;
}

private float wrapDiff(float angle, float target) {
    return target + wrap180(angle - target);
}

private float clampMag(float a, float max) {
    max = Math.max(0.0F, Math.min(180.0F, max));
    if (a > max) {
        a = max;
    } else if (a < -max) {
        a = -max;
    }
    return a;
}

private float quant(float a) {
    return (float) ((double) a - (double) a % QUANT);
}

private float jitter(float maxDeg) {
    float j = quant(rndF(-maxDeg, maxDeg));
    if (j == 0.0F) {
        j = (rndF(0.0F, 1.0F) < 0.5F) ? (float) QUANT : (float) -QUANT;
    }
    return j;
}

private double rndD(double lo, double hi) {
    return (double) rndF((float) lo, (float) hi);
}

private static final float JIT_LO = 0.11F;
private static final float JIT_HI = 0.46F;
private float jitAmp = 0.28F;

private void stepJitAmp() {
    jitAmp += rndF(-0.055F, 0.055F);
    if (jitAmp < JIT_LO) {
        jitAmp = JIT_LO;
    } else if (jitAmp > JIT_HI) {
        jitAmp = JIT_HI;
    }
}

private float jitY() {
    return jitter(jitAmp);
}

private float clampPitch(float p) {
    return p < -90.0F ? -90.0F : (p > 90.0F ? 90.0F : p);
}

private float[] humanEase(float fromYaw, float fromPitch, float toYaw,
        float toPitch) {
    float dYaw = wrap180(toYaw - fromYaw);
    float dPitch = clampPitch(toPitch) - fromPitch;
    float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    if (dist <= 1.0F) {
        rotSpeed = 0.0F;
        smYawDec = 0.0F;
        return new float[]{toYaw, clampPitch(toPitch)};
    }
    float cap;
    if (!smSnapped) {
        cap = 73.0F;
        smSnapped = true;
        smYawDec = 0.0F;
    } else {
        cap = 43.0F - smYawDec;
        if (smYawDec < 10.0F) {
            smYawDec += rndF(3.0F, 4.0F);
        }
    }
    cap -= rndF(0.1F, 1.0F);
    rotSpeed = cap;
    float step = Math.min(cap, dist);
    float scale = step / dist;
    return new float[]{fromYaw + dYaw * scale, clampPitch(fromPitch + dPitch * scale)};
}

private float smooth(float angle, float sf) {
    return angle * (0.5F + 0.5F * (1.0F - Math.max(0.0F, Math.min(1.0F, sf + rndF(-0.1F, 0.1F)))));
}

private float[] rotsTo(double tx, double ty, double tz, float cy, float cp) {
    double horiz = Math.sqrt(tx * tx + tz * tz);
    float yawDelta = wrap180((float) (Math.atan2(tz, tx) * 180.0 / Math.PI) - 90.0F - cy);
    float pitchDelta = wrap180((float) (-Math.atan2(ty, horiz) * 180.0 / Math.PI) - cp);
    yawDelta = (Math.abs(yawDelta) <= 1.0F) ? 0.0F : smooth(clampMag(yawDelta, 180.0F), 0.0F);
    pitchDelta = (Math.abs(pitchDelta) <= 1.0F) ? 0.0F : smooth(clampMag(pitchDelta, 180.0F), 0.0F);
    return new float[]{quant(cy + yawDelta), quant(cp + pitchDelta)};
}

private float moveAdjust(float yawIn, float forward, float strafe) {
    if (forward < 0.0F) {
        yawIn += 180.0F;
    }
    if (strafe != 0.0F) {
        float mult = (forward == 0.0F) ? 1.0F : 0.5F * Math.signum(forward);
        yawIn += -90.0F * mult * Math.signum(strafe);
    }
    return wrap180(yawIn);
}

private int fwdVal() {
    int v = 0;
    if (keybinds.isKeyDown(keybinds.getKeyCode("forward"))) {
        v++;
    }
    if (keybinds.isKeyDown(keybinds.getKeyCode("back"))) {
        v--;
    }
    return v;
}

private int leftVal() {
    int v = 0;
    if (keybinds.isKeyDown(keybinds.getKeyCode("left"))) {
        v++;
    }
    if (keybinds.isKeyDown(keybinds.getKeyCode("right"))) {
        v--;
    }
    return v;
}

private boolean fwdPressed() {
    boolean f = keybinds.isKeyDown(keybinds.getKeyCode("forward"));
    boolean b = keybinds.isKeyDown(keybinds.getKeyCode("back"));
    boolean l = keybinds.isKeyDown(keybinds.getKeyCode("left"));
    boolean r = keybinds.isKeyDown(keybinds.getKeyCode("right"));
    return (f != b) || (l != r);
}

private boolean jumpDown() {
    return keybinds.isKeyDown(keybinds.getKeyCode("jump"));
}

private int cardSide(float y) {
    float m = ((y + 180.0F) % 90.0F + 90.0F) % 90.0F;
    if (m > 8.0F && m < 37.0F) {
        cardSide = 1;
    } else if (m > 53.0F && m < 82.0F) {
        cardSide = -1;
    }
    return cardSide;
}

private boolean isDiag(float y) {
    float a = Math.abs(y % 90.0F);
    if (diagState) {
        if (a < 16.0F || a > 74.0F) {
            diagState = false;
        }
    } else if (a > 24.0F && a < 66.0F) {
        diagState = true;
    }
    return diagState;
}

private float curYaw() {
    return moveAdjust(pYaw(), (float) fwdVal(), (float) leftVal());
}

private boolean towerActive() {
    Entity p = client.getPlayer();
    if (p.onGround() && fwdPressed() && !hasCeilingAbove()) {
        boolean k = effKeepMode().equals("Y-Telly+")
                || effKeepMode().equals("Y-Telly");
        boolean t = towerMode().equals("S-Telly+") || ltOn();
        return (k && stage > 0) || (t && jumpDown());
    }
    return false;
}

private boolean towerJumpActive() {
    Entity p = client.getPlayer();
    if (p.onGround() && fwdPressed() && !hasCeilingAbove() && jumpDown()) {
        return towerMode().equals("S-Telly+") || ltOn();
    }
    return false;
}

private boolean ltOn() {
    return towerMode().equals("L-Telly+")
            || towerMode().equals("L-Telly");
}

private boolean lockHeld() {
    return LOOK_LOCK && ltDry < LOOK_LOCK_DRY;
}

private boolean ltDev() {
    return towerMode().equals("L-Telly");
}


private String faceFromDelta(int dx, int dz) {
    if (dx > 0) {
        return "EAST";
    }
    if (dx < 0) {
        return "WEST";
    }
    if (dz > 0) {
        return "SOUTH";
    }
    return "NORTH";
}

private int spX = 0;
private int spY = 0;
private int spZ = 0;
private String spFace = null;

private boolean stackClear(Vec3 pos, int cx, int cz) {
    double bk = (pos.x - ((double) cx + 0.5)) * (double) ltDirX
            + (pos.z - ((double) cz + 0.5)) * (double) ltDirZ;
    return bk > 0.8
            || (client.getPlayer().getMotion().y > 0.0 && bk > 0.25);
}


private boolean boxBlocked(Vec3 pos, int cx, int cy, int cz) {
    return (double) cx + 1.0 > pos.x - 0.3 && (double) cx < pos.x + 0.3
            && (double) cy + 1.0 > pos.y && (double) cy < pos.y + 1.8
            && (double) cz + 1.0 > pos.z - 0.3 && (double) cz < pos.z + 0.3;
}

private boolean supportSlot(int si, boolean upFirst, int cx, int cy, int cz) {
    int s = upFirst ? ((si == 0) ? 4 : si - 1) : si;
    spY = cy;
    if (s == 0) {
        spX = cx - ltDirX;
        spZ = cz - ltDirZ;
        spFace = faceFromDelta(ltDirX, ltDirZ);
    } else if (s == 1) {
        spX = cx - ltLatX;
        spZ = cz - ltLatZ;
        spFace = faceFromDelta(ltLatX, ltLatZ);
    } else if (s == 2) {
        spX = cx + ltLatX;
        spZ = cz + ltLatZ;
        spFace = faceFromDelta(-ltLatX, -ltLatZ);
    } else if (s == 3) {
        spX = cx + ltDirX;
        spZ = cz + ltDirZ;
        spFace = faceFromDelta(-ltDirX, -ltDirZ);
    } else {
        spX = cx;
        spY = cy - 1;
        spZ = cz;
        spFace = "UP";
    }
    return isSolidSupport(spX, spY, spZ) && !interactableAt(spX, spY, spZ);
}

private void ltBuildQueue(Vec3 lm) {
    ltQn = 0;
    ltPendQ = -1;
    ltQskip = 0;
    ltQcur = 0;
    ltQ0Wait = 0;
    ltQ0Ground = 0;
    ltStall = 0;
    if (ltGroundAge > 3) {
        return;
    }
    Vec3 bp = client.getPlayer().getPosition();
    ltRow = fl(bp.y) - 1;
    double ax = Math.abs(lm.x);
    double az = Math.abs(lm.z);
    if (ax * ax + az * az < 0.01) {
        return;
    }
    int dTx = 0;
    int dTz = 0;
    if (ax >= az * 2.4) {
        dTx = (lm.x > 0.0) ? 1 : -1;
    } else if (az >= ax * 2.4) {
        dTz = (lm.z > 0.0) ? 1 : -1;
    } else {
        return;
    }
    if (ltSprintJump && ltSideFresh) {
        ltSideFresh = false;

        if (ltHavePrev) {
            int ldx = ltStandX - ltOrgX;
            int ldz = ltStandZ - ltOrgZ;
            if (ldx * ltLatX + ldz * ltLatZ >= 1) {
                ltSide = -ltSide;
            }
        } else {
            double lean = lm.x * (double) (-dTz) + lm.z * (double) dTx;
            if (lean > 0.02) {
                ltSide = 1;
            } else if (lean < -0.02) {
                ltSide = -1;
            }
        }
        ltOrgX = ltStandX;
        ltOrgZ = ltStandZ;
        ltHavePrev = true;
    }
    int latX = -dTz * ltSide;
    int latZ = dTx * ltSide;
    ltDirX = dTx;
    ltDirZ = dTz;
    ltLatX = latX;
    ltLatZ = latZ;
    int[][] rel;
    if (ltDev()) {
        rel = ltSprintJump
                ? new int[][]{{1, 0, 0}, {1, 0, 1}, {2, 0, 0}, {2, 0, 1},
                        {3, 0, 0}}
                : new int[][]{{0, 0, 1}, {1, 0, 0}, {1, 0, 1}, {2, 0, 1}};
    } else {
        rel = ltSprintJump
                ? new int[][]{{1, 0, 0}, {2, 0, 0}, {3, 0, 0}}
                : new int[][]{{0, 0, 1}, {1, 0, 1}, {2, 0, 1}};
    }
    for (int i = 0; i < rel.length; i++) {
        ltQx[ltQn] = ltStandX + rel[i][0] * dTx + rel[i][1] * latX;
        ltQz[ltQn] = ltStandZ + rel[i][0] * dTz + rel[i][1] * latZ;
        ltQd[ltQn] = rel[i][2];
    ltQb[ltQn] = (ltDev() && ltSprintJump && rel[i][2] > 0
            && i < rel.length - 1) ? 1 : 0;
        ltQn++;
    }
}

private Vec3 ltSweepSighted(float ty, float tp) {
    if (!ltDev() || ltQn <= 0) {
        return null;
    }
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    for (int q = ltQcur; q < ltQn; q++) {
        if ((ltQskip & (1 << q)) != 0 || q == ltPendQ) {
            continue;
        }
        int cx = ltQx[q];
        int cy = ltRow + ltQd[q];
        int cz = ltQz[q];
        if (!isAir(cx, cy, cz)) {
            continue;
        }
        if (ltQb[q] == 1 && !stackClear(pos, cx, cz)) {
            continue;
        }
        if (boxBlocked(pos, cx, cy, cz)) {
            continue;
        }
        boolean upFirst = ltQd[q] > 0;
        for (int si = 0; si < 5; si++) {
            if (!supportSlot(si, upFirst, cx, cy, cz)) {
                continue;
            }
            int sx = spX;
            int sy = spY;
            int sz = spZ;
            String face = spFace;
            Vec3 h = rayHit(sx, sy, sz, face, ty, tp);
            if (h != null) {
                ltPendX = sx;
                ltPendY = sy;
                ltPendZ = sz;
                ltPendSide = face;
                ltQcur = q;
                return h;
            }
        }
    }
    return null;
}

private boolean ltCorridorSolve() {
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    double ex = pos.x;
    double ez = pos.z;
    float cam = gridBase;
    for (int w = ltQn - 1; w > ltQcur; w--) {
        if (!isAir(ltQx[w], ltRow + ltQd[w], ltQz[w])) {
            ltQcur = w;
            if (ltPendQ >= 0 && ltPendQ < w) {
                ltPendQ = -1;
            }
            break;
        }
    }
    int leadStack = -1;
    if (ltDev()) {
        for (int w = ltQn - 1; w >= 0; w--) {
            if (ltQd[w] > 0 && !isAir(ltQx[w], ltRow + ltQd[w], ltQz[w])) {
                leadStack = w;
                break;
            }
        }
    }
    for (int q = ltDev() ? Math.max(0, ltQcur - 2) : ltQcur;
            q < ltQn; q++) {
        if ((ltQskip & (1 << q)) != 0) {
            continue;
        }
        int cx = ltQx[q];
        int cy = ltRow + ltQd[q];
        int cz = ltQz[q];
        if (ltQd[q] > 0 && q < leadStack && isAir(cx, cy, cz)) {
            ltQskip |= (1 << q);
            if (q == ltPendQ) {
                ltPendQ = -1;
            }
            continue;
        }
        if (ltQb[q] == 1) {
            if (!stackClear(pos, cx, cz)) {
                if (ltStackWait < LT_STACK_HOLD) {
                    ltStackWait++;
                    ltWhy = 'W';
                    return false;
                }
                continue;
            }
        }
        if (!isAir(cx, cy, cz)) {
            if (q == ltPendQ) {
                ltPendQ = -1;
            }
            continue;
        }
        double ddx = (double) cx + 0.5 - ex;
        double ddz = (double) cz + 0.5 - ez;
        if (ddx * ddx + ddz * ddz > 18.0) {
            continue;
        }
        if (boxBlocked(pos, cx, cy, cz)) {
            if (ltDev() && !ltSprintJump && q == 0) {
                boolean gnd = p.onGround();
                if (gnd) {
                    ltQ0Ground++;
                } else {
                    ltQ0Wait++;
                }
                if (gnd ? ltQ0Ground <= LT_Q0_HOLD_GROUND
                        : ltQ0Wait <= LT_Q0_HOLD) {
                    ltWhy = 'W';
                    return false;
                }
            }
            continue;
        }
        int pass0 = (q == ltPendQ) ? 0 : 1;
        boolean upFirst = ltQd[q] > 0
                || (!p.onGround() && p.getMotion().y > 0.0);
        for (int pass = pass0; pass < 2; pass++) {
            for (int si = 0; si < 5; si++) {
                if (!supportSlot(si, upFirst, cx, cy, cz)) {
                    continue;
                }
                int sx = spX;
                int sy = spY;
                int sz = spZ;
                String face = spFace;
                float ry;
                float ap;
                if (pass == 0) {
                    ry = ltAimYaw;
                    ap = faceNearestPitch(sx, sy, sz, face, ry, lastSentPitch);
                } else {
                    int[] off = sideOffset(face);
                    double fcx = (double) sx + 0.5 + (double) off[0] * 0.5;
                    double fcz = (double) sz + 0.5 + (double) off[2] * 0.5;
                    float fc = (float) Math.toDegrees(
                            Math.atan2(-(fcx - ex), fcz - ez));

                    int bk = Math.round(wrap180(fc - cam) / 45.0F);
                    float held = cam + (float) ltRefK * 45.0F;
                    float[] rys = new float[]{
                            held,
                            cam + (float) bk * 45.0F,
                            cam + (float) (bk - 1) * 45.0F,
                            cam + (float) (bk + 1) * 45.0F,
                            fc};
                    float[] rkey = new float[rys.length];
                    float backYaw = (float) Math.toDegrees(
                            Math.atan2((double) ltDirX, (double) -ltDirZ))
                            + (lookFlat ? 0.0F
                                    : (float) (LOOK_DIAG * lookSign) * 45.0F);
                    for (int a = 0; a < rys.length; a++) {
                        rkey[a] = lockHeld()
                                ? Math.abs(wrap180(rys[a] - backYaw))
                                        + 0.05F
                                        * Math.abs(wrap180(rys[a] - held))
                                : Math.abs(wrap180(rys[a] - held));
                    }
                    for (int a = 0; a < rys.length; a++) {
                        for (int b2 = a + 1; b2 < rys.length; b2++) {
                            if (rkey[b2] < rkey[a]) {
                                float sw = rys[a];
                                rys[a] = rys[b2];
                                rys[b2] = sw;
                                float sk = rkey[a];
                                rkey[a] = rkey[b2];
                                rkey[b2] = sk;
                            }
                        }
                    }
                    ry = Float.NaN;
                    ap = Float.NaN;
                    for (int b = 0; b < rys.length; b++) {
                        float cy2 = rys[b] + jitY();
                        float bap = faceNearestPitch(sx, sy, sz, face, cy2,
                                lastSentPitch);
                        if (!Float.isNaN(bap)) {
                            ry = cy2;
                            ap = bap;
                            break;
                        }
                    }
                }
                if (Float.isNaN(ap)) {
                    continue;
                }
                if (q != ltPendQ) {
                    ltStall = 0;
                }
                ltPendQ = q;
                if (q > ltQcur) {
                    ltQcur = q;
                }
                ltPendX = sx;
                ltPendY = sy;
                ltPendZ = sz;
                ltPendSide = face;
                ltAimYaw = ry;
                ltAimPitch = ap;
                ltRefK = Math.round(wrap180(ry - cam) / 45.0F);
                return true;
            }
        }
    }
    return false;
}

private String ltDbg() {
    if (!(ltOn() && (towering || towerJumpActive()))) {
        return "b" + ltBd0 + "/r" + ltWhy;
    }
    return (ltSprintJump ? "S" : "N") + (ltSide > 0 ? "R" : "L") + ":" + ltQn
            + "/c" + ltQcur + "/k" + ltQskip + "/w" + ltQ0Wait
            + "/d" + ltDry + "/x" + ltGhost + "/b" + ltBd0
            + "/r" + ltWhy + "/f" + (ltPendSide == null ? "-" : ltPendSide);
}

private boolean hasCeilingAbove() {
    Vec3 pos = client.getPlayer().getPosition();
    double minY = pos.y + 1.0;
    double maxY = pos.y + 1.0 + 1.8;
    int y0 = fl(minY);
    int y1 = fl(maxY - 1.0E-6);
    for (int y = y0; y <= y1; y++) {
        if (isSolidSupport(fl(pos.x - 0.3), y, fl(pos.z - 0.3))) return true;
        if (isSolidSupport(fl(pos.x + 0.3), y, fl(pos.z - 0.3))) return true;
        if (isSolidSupport(fl(pos.x - 0.3), y, fl(pos.z + 0.3))) return true;
        if (isSolidSupport(fl(pos.x + 0.3), y, fl(pos.z + 0.3))) return true;
    }
    return false;
}

private boolean hasJumpPotion() {
    for (Object[] e : client.getPlayer().getPotionEffects()) {
        if (((Integer) e[0]).intValue() == 8) {
            return true;
        }
    }
    return false;
}

private static final String REPLACEABLE = ",air,water,flowing_water,lava,"
        + "flowing_lava,fire,tallgrass,deadbush,double_plant,vine,";

private boolean isReplaceableName(Block b) {
    String n = b.name;
    if (n == null) {
        return false;
    }
    if (n.equals("snow_layer")) {
        return b.height <= 0.125;
    }
    return REPLACEABLE.contains("," + n + ",");
}

private boolean isAir(int x, int y, int z) {
    Block b = world.getBlockAt(x, y, z);
    return b != null && isReplaceableName(b);
}

private boolean isSolidSupport(int x, int y, int z) {
    Block b = world.getBlockAt(x, y, z);
    return b != null && b.name != null && !isReplaceableName(b);
}

private boolean interactableAt(int x, int y, int z) {
    Block b = world.getBlockAt(x, y, z);
    return b != null && b.interactable;
}


private int[] sideOffset(String side) {
    if (side.equals("UP")) {
        return OFF_UP;
    }
    if (side.equals("DOWN")) {
        return OFF_DOWN;
    }
    if (side.equals("NORTH")) {
        return OFF_NORTH;
    }
    if (side.equals("SOUTH")) {
        return OFF_SOUTH;
    }
    if (side.equals("EAST")) {
        return OFF_EAST;
    }
    return OFF_WEST;
}

private Object[] blockData() {
    bdNull = 'n';
    Entity p = client.getPlayer();
    Vec3 pos = p.getPosition();
    int fx = fl(pos.x);
    int fy = fl(pos.y);
    int fz = fl(pos.z);
    int ty = ((stage != 0 && !shouldKeepY) ? Math.min(fy, startY) : fy) - 1;
    int tx = fx;
    int tz = fz;
    if (!isAir(tx, ty, tz)) {
        if (!p.onGround()) {
            bdNull = 'u';
            return null;
        }
        Vec3 dm = p.getMotion();
        bdNull = 'r';
        double sp = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
        if (sp < 0.01) {
            return null;
        }
        double ux = dm.x / sp;
        double uz = dm.z / sp;
        int rx = fx;
        int rz = fz;
        boolean gFound = false;
        for (double d = 0.25; d <= 2.0 && !gFound; d += 0.25) {
            int wx = fl(pos.x + ux * d);
            int wz = fl(pos.z + uz * d);
            if (wx == rx && wz == rz) {
                continue;
            }
            wx = rx + clampStep(wx - rx);
            wz = rz + clampStep(wz - rz);
            if (wx != rx && wz != rz
                    && !isSolidSupport(rx, ty, wz) && !isSolidSupport(wx, ty, rz)) {

                if (isAir(rx, ty, wz)) {
                    tx = rx;
                    tz = wz;
                } else if (isAir(wx, ty, rz)) {
                    tx = wx;
                    tz = rz;
                } else {
                    return null;
                }
                gFound = true;
            } else if (isAir(wx, ty, wz)) {
                tx = wx;
                tz = wz;
                gFound = true;
            } else if (isSolidSupport(wx, ty, wz)) {
                rx = wx;
                rz = wz;
            } else {
                return null;
            }
        }
        if (!gFound) {
            return null;
        }
    }
    double reach = 4.5;
    String[] sides = SIDES_UD;
    int bestX = 0;
    int bestY = 0;
    int bestZ = 0;
    boolean found = false;
    double bestDist = 0.0;
    for (int dx = -4; dx <= 4; dx++) {
        for (int dy = -4; dy <= 0; dy++) {
            for (int dz = -4; dz <= 4; dz++) {
                int cx = tx + dx;
                int cy = ty + dy;
                int cz = tz + dz;
                if (!isSolidSupport(cx, cy, cz)) {
                    continue;
                }
                if (interactableAt(cx, cy, cz)) {
                    continue;
                }
                double ddx = (double) cx + 0.5 - pos.x;
                double ddy = (double) cy + 0.5 - pos.y;
                double ddz = (double) cz + 0.5 - pos.z;
                if (Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) > reach) {
                    continue;
                }
                if (!(stage == 0 || shouldKeepY || cy < startY)) {
                    continue;
                }
                boolean hasAirFace = false;
                for (String s : sides) {
                    int[] off = sideOffset(s);
                    if (isAir(cx + off[0], cy + off[1], cz + off[2])) {
                        hasAirFace = true;
                        break;
                    }
                }
                if (!hasAirFace) {
                    continue;
                }
                double tdx = (double) cx - (double) tx;
                double tdy = (double) cy - (double) ty;
                double tdz = (double) cz - (double) tz;
                double td = tdx * tdx + tdy * tdy + tdz * tdz;
                if (!found || td < bestDist) {
                    found = true;
                    bestDist = td;
                    bestX = cx;
                    bestY = cy;
                    bestZ = cz;
                }
            }
        }
    }
    if (!found) {
        bdNull = 'n';
        return null;
    }
    String side = bestFacing(bestX, bestY, bestZ, tx, ty, tz);
    if (side == null) {
        bdNull = 'n';
        return null;
    }
    return new Object[]{new int[]{bestX, bestY, bestZ}, side};
}

private String bestFacing(int sx, int sy, int sz, int tx, int ty, int tz) {
    String[] sides = SIDES_UD;
    String best = null;
    double bestDist = 0.0;
    boolean have = false;
    for (String s : sides) {
        int[] off = sideOffset(s);
        int nx = sx + off[0];
        int ny = sy + off[1];
        int nz = sz + off[2];
        if (ny <= ty) {
            double ddx = (double) nx - (double) tx;
            double ddy = (double) ny - (double) ty;
            double ddz = (double) nz - (double) tz;
            double dist = ddx * ddx + ddy * ddy + ddz * ddz;
            if (!have || dist < bestDist || (dist == bestDist && s.equals("UP"))) {
                have = true;
                bestDist = dist;
                best = s;
            }
        }
    }
    return best;
}

private Vec3 rayHit(int bx, int by, int bz, String side, float ry, float rp) {
    Object[] r = client.raycastBlock(4.5, ry, rp);
    if (!validRayResult(r)) {
        return null;
    }
    Vec3 rpos = (Vec3) r[0];
    Vec3 roff = (Vec3) r[1];
    String rside = (String) r[2];
    if (rpos.x != (double) bx || rpos.y != (double) by || rpos.z != (double) bz) {
        return null;
    }
    if (!rside.equals(side)) {
        return null;
    }
    return new Vec3(rpos.x + roff.x, rpos.y + roff.y, rpos.z + roff.z);
}

private boolean validRayResult(Object[] ray) {
    return ray != null && ray.length >= 3 && ray[0] instanceof Vec3
            && ray[1] instanceof Vec3 && ray[2] instanceof String;
}

private void doPlace(int bx, int by, int bz, String side, Vec3 hit) {
    if (hit == null) {
        return;
    }
    Entity p = client.getPlayer();
    if (!p.isHoldingBlock() || blockCount <= 0) {
        return;
    }





    Vec3 exactHit = rayHit(bx, by, bz, side, lastSentYaw, lastSentPitch);
    if (exactHit == null) {
        return;
    }
    ItemStack heldStack = p.getHeldItem();
    Vec3 support = new Vec3((double) bx, (double) by, (double) bz);
    if (!client.canPlaceBlock(heldStack, support, side)) {
        return;
    }
    boolean placed;
    ourPlace = true;
    try {
        placed = client.placeBlock(support, side, exactHit);
    } finally {
        ourPlace = false;
    }
    if (placed) {
        if (!client.isCreative()) {
            blockCount--;
        }
        placedThisTick = true;
        dbgLastPlaceHit = exactHit;
        dbgLastPlaceAt = client.time();
        ltDry = 0;
        ltStackWait = 0;
        dbgPDelta = rotSentLastTick ? (lastSentPitch - prevSentPitch) : 0.0F;
        if (Math.abs(dbgPDelta) > Math.abs(dbgPDeltaMax)) {
            dbgPDeltaMax = dbgPDelta;
        }
        if (dbgBasic()) {
            int[] o = sideOffset(side);
            dbgPrint(String.format("&7placed &f(%d,%d,%d) &7face=&f%s",
                    bx + o[0], by + o[1], bz + o[2], side));
        }
        client.swing();
    }
}


private boolean overAirAt(double px, double pz) {
    Entity p = client.getPlayer();
    int y = fl(p.getPosition().y) - 1;
    if (isSolidSupport(fl(px - 0.3), y, fl(pz - 0.3))) return false;
    if (isSolidSupport(fl(px + 0.3), y, fl(pz - 0.3))) return false;
    if (isSolidSupport(fl(px - 0.3), y, fl(pz + 0.3))) return false;
    if (isSolidSupport(fl(px + 0.3), y, fl(pz + 0.3))) return false;
    return true;
}

}

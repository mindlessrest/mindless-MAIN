static final Map<String, Integer> BLOCK_SCORE = new HashMap<String, Integer>();
static final double HALF_WIDTH = 0.3;
static final double[][] CORNERS = {{-HALF_WIDTH, -HALF_WIDTH}, {HALF_WIDTH, -HALF_WIDTH}, {-HALF_WIDTH, HALF_WIDTH}, {HALF_WIDTH, HALF_WIDTH}};

static {
    BLOCK_SCORE.put("obsidian", 0);
    BLOCK_SCORE.put("end_stone", 1);
    BLOCK_SCORE.put("planks", 2);
    BLOCK_SCORE.put("log", 2);
    BLOCK_SCORE.put("log2", 2);
    BLOCK_SCORE.put("glass", 3);
    BLOCK_SCORE.put("stained_glass", 3);
    BLOCK_SCORE.put("hardened_clay", 4);
    BLOCK_SCORE.put("stained_hardened_clay", 4);
    BLOCK_SCORE.put("stone", 5);
    BLOCK_SCORE.put("wool", 5);
}

Vec3 placeAtBlock;
String hitSide;
Vec3 hitVec;
boolean placeQueued;
boolean placing;
boolean autoClickerWasOn;
int plannedSlot = -1;
float aimYaw;
float aimPitch;
Vec3 targetHitPos;
String targetSide;
boolean hasAim;
boolean resetting;
Vec3 lastPlaced;
int clutchBlocksPlaced;
boolean movementFixOwned;
float appliedYaw;
float appliedPitch;
boolean hasAppliedRotation;
boolean tellyEngaged;
int tellyStartY = Integer.MIN_VALUE;
// Smoothing variables
float smoothYaw;
float smoothPitch;
boolean smoothInit;
// Jump timing control
long lastPlaceTime;
long jumpDelay = 50; // ms
long lastJumpTime;
long rotationDelay = 0; // No delay by default
boolean jumpStarted = false;
// Gap filling
List<Vec3> detectedGaps = new ArrayList<Vec3>();
// CPS tracking
long lastPlaceAttempt = 0;
int placeAttempts = 0;
int successfulPlaces = 0;
int airborneTicks;
boolean speedTellyActive;

void onLoad() {
    modules.registerButton("Speed Telly on RMB", false);
    modules.registerSlider("Rotations", "Speed", "%", 90, 50, 100, 1);
    modules.registerSlider("Rotations", "Randomness", "%", 15, 0, 50, 1);
    modules.registerSlider("Rotations", "Tolerance", "°", 25, 5, 45, 1);
    modules.registerSlider("Timing", "Jump Delay", "ms", 50, 0, 200, 5);
}

void onEnable() {
    hasAim = false;
    resetting = false;
    clutchBlocksPlaced = 0;
    movementFixOwned = false;
    hasAppliedRotation = false;
    tellyEngaged = false;
    tellyStartY = Integer.MIN_VALUE;
    smoothInit = false;
    lastPlaceTime = 0;
    lastJumpTime = 0;
    jumpStarted = false;
    detectedGaps.clear();
    lastPlaceAttempt = 0;
    placeAttempts = 0;
    successfulPlaces = 0;
    airborneTicks = 0;
}

void onDisable() {
    clearAim(false);
    disablePlacing(true);
    placeQueued = false;
    tellyEngaged = false;
    tellyStartY = Integer.MIN_VALUE;
    detectedGaps.clear();
    airborneTicks = 0;
}

void onPrePlayerInput(MovementInput input) {
    if (!isScreenClosed()) return;
    Entity player = client.getPlayer();
    if (player == null || client.isFlying()) return;
    
    // Handle jump timing to reduce delays
    long currentTime = client.time();
    jumpDelay = (long) modules.getSlider(scriptName, "Jump Delay");
    if (currentTime - lastPlaceTime < jumpDelay && !player.onGround()) {
        input.jump = false; // Prevent premature jumping
    }
    
    // Detect when player starts jumping (on ground -> not on ground)
    if (player.onGround() && !jumpStarted) {
        jumpStarted = true;
        lastJumpTime = currentTime;
    } else if (player.onGround()) {
        jumpStarted = false;
    }
    
    if (!player.onGround()) return;
    try {
        Simulation sim = Simulation.create();
        sim.setForward(input.forward);
        sim.setStrafe(input.strafe);
        sim.setJump(input.jump);
        sim.setSneak(input.sneak);
        sim.tick();
        if (!sim.onGround() && !input.jump) {
            input.jump = true;
        }
    } catch (Exception ignored) {
    }
}

Float[] getRotations() {
    Entity player = client.getPlayer();
    if (player == null || !world.exists()) return null;

    // Initialize smooth rotation tracking
    if (!smoothInit) {
        smoothYaw = player.getYaw();
        smoothPitch = player.getPitch();
        smoothInit = true;
    }

    if (!hasAppliedRotation) {
        appliedYaw = player.getYaw();
        appliedPitch = player.getPitch();
        hasAppliedRotation = true;
    }

    runPrePlayerInteract(player);

    float baseYaw = appliedYaw;
    float basePitch = appliedPitch;

    if (resetting) {
        aimYaw = player.getYaw();
        aimPitch = player.getPitch();
        float[] smoothed = getRotationsSmoothed(smoothYaw, smoothPitch, aimYaw, aimPitch, true);
        appliedYaw = smoothed[0];
        appliedPitch = smoothed[1];
        if (Math.abs(wrapAngleTo180(smoothed[0] - aimYaw)) < 0.5f && Math.abs(smoothed[1] - aimPitch) < 0.5f) {
            resetting = false;
            hasAppliedRotation = false;
            smoothInit = false;
            restoreInputsAndAutoClicker();
            setMovementFix(false);
            return null;
        }
        setMovementFix(true);
        smoothYaw = smoothed[0];
        smoothPitch = smoothed[1];
        return new Float[]{Float.valueOf(smoothed[0]), Float.valueOf(smoothed[1])};
    }

    if (!hasAim) {
        hasAppliedRotation = false;
        // Gradually return to player's natural rotation
        float[] resetSmooth = getRotationsSmoothed(smoothYaw, smoothPitch, player.getYaw(), player.getPitch(), true);
        smoothYaw = resetSmooth[0];
        smoothPitch = resetSmooth[1];
        return new Float[]{Float.valueOf(resetSmooth[0]), Float.valueOf(resetSmooth[1])};
    }

    // Apply smooth rotation transitions
    float[] smoothed = getRotationsSmoothed(smoothYaw, smoothPitch, aimYaw, aimPitch, false);
    appliedYaw = smoothed[0];
    appliedPitch = smoothed[1];
    smoothYaw = smoothed[0];
    smoothPitch = smoothed[1];

    if (placing && targetHitPos != null) {
        Object[] ray = client.raycastBlock(4.5, smoothed[0], smoothed[1]);
        if (ray != null && sameBlock((Vec3) ray[0], targetHitPos) && sideEquals((String) ray[2], targetSide)) {
            double tolerance = modules.getSlider(scriptName, "Tolerance");
            if (Math.abs(wrapAngleTo180(smoothed[0] - baseYaw)) <= tolerance
                    && Math.abs(smoothed[1] - basePitch) <= tolerance) {
                placeAtBlock = (Vec3) ray[0];
                hitSide = ((String) ray[2]).toUpperCase();
                hitVec = ((Vec3) ray[0]).offset((Vec3) ray[1]);
                placeQueued = true;
            }
        }
    }

    setMovementFix(true);
    return new Float[]{Float.valueOf(smoothed[0]), Float.valueOf(smoothed[1])};
}

float[] getRotationsSmoothed(float currentYaw, float currentPitch, float targetYaw, float targetPitch, boolean snapback) {
    float curYaw = currentYaw;
    float curPitch = currentPitch;
    float deltaYaw = wrapAngleTo180(targetYaw - curYaw);
    float deltaPitch = targetPitch - curPitch;

    if (Math.abs(deltaYaw) < 0.1f) curYaw = targetYaw;
    if (Math.abs(deltaPitch) < 0.1f) curPitch = targetPitch;
    if (curYaw == targetYaw && curPitch == targetPitch) {
        return new float[]{curYaw, clampPitch(curPitch)};
    }

    float maxStep = snapback ? 100f : (speedTellyActive ? 80f : (isMovingDiagonal() ? 70f : 35f));
    double randomnessSetting = modules.getSlider(scriptName, "Randomness");
    float factor = 1f - (float) randomRange(0, randomnessSetting / 100.0);
    maxStep *= factor;

    float totalDelta = Math.abs(deltaYaw) + Math.abs(deltaPitch);
    if (totalDelta <= maxStep) {
        curYaw = targetYaw;
        curPitch = targetPitch;
    } else if (maxStep > 0) {
        float scale = maxStep / totalDelta;
        curYaw += deltaYaw * scale;
        curPitch += deltaPitch * scale;
    }

    return new float[]{curYaw, clampPitch(curPitch)};
}

void onPreUpdate() {
    if (!world.exists() || client.getPlayer() == null || !placeQueued) return;

    placeQueued = false;
    if (placeAtBlock != null && hitSide != null && hitVec != null) {
        // CPS limiting - allow faster placement
        long currentTime = client.time();
        if (currentTime - lastPlaceAttempt < 40) { // Allow up to 25 CPS
            return;
        }
        
        lastPlaceAttempt = currentTime;
        placeAttempts++;
        
        boolean placed = false;
        try {
            placed = client.placeBlock(placeAtBlock, hitSide, hitVec);
        } catch (Exception ignored) {
        }
        if (!placed) {
            try {
                placed = client.placeBlock(placeAtBlock, hitSide.toLowerCase(), hitVec);
            } catch (Exception ignored) {
            }
        }
        if (!placed) {
            placed = sendPlacePacket();
        }
        if (placed) {
            successfulPlaces++;
            if (!hitSide.equalsIgnoreCase("UP")) clutchBlocksPlaced++;
            lastPlaced = placeAtBlock;
            client.swing();
            lastPlaceTime = client.time(); // Track placement time for jump control
            
            // Remove placed block from gaps list
            Iterator<Vec3> iterator = detectedGaps.iterator();
            while (iterator.hasNext()) {
                Vec3 gap = iterator.next();
                if (sameBlock(gap, placeAtBlock)) {
                    iterator.remove();
                }
            }
        }
    }
}

boolean sendPlacePacket() {
    try {
        Entity player = client.getPlayer();
        if (player == null) return false;
        ItemStack held = player.getHeldItem();
        if (held == null) return false;
        Vec3 offset = hitVec.offset(placeAtBlock.inverse());
        client.sendPacket(new C08(held, placeAtBlock, sideOrdinal(hitSide), offset));
        return true;
    } catch (Exception ignored) {
    }
    return false;
}

boolean onMouse(int button, boolean state, int x, int y) {
    if ((placing || resetting || hasAim) && button > -1) {
        return false;
    }
    return true;
}

void runPrePlayerInteract(Entity player) {
    if (player.onGround()) {
        clutchBlocksPlaced = 0;
    }

    if (!isScreenClosed()) {
        clearAim(true);
        disablePlacing(false);
        detectedGaps.clear();
        airborneTicks = 0;
        return;
    }

    if (keybinds.isMouseDown(1)) {
        keybinds.setPressed("use", false);
    }

    boolean speedTelly = modules.getButton(scriptName, "Speed Telly on RMB");
    speedTellyActive = speedTelly && keybinds.isMouseDown(1);
    if (player.onGround()) {
        airborneTicks = 0;
        tellyEngaged = false;
        tellyStartY = Integer.MIN_VALUE;
        clearAim(true);
        disablePlacing(true);
        detectedGaps.clear();
        return;
    }
    airborneTicks++;
    if (!tellyEngaged) {
        if (speedTellyActive) {
            tellyEngaged = true;
        } else if (keybinds.isPressed("jump")) {
            tellyStartY = Integer.MIN_VALUE;
            tellyEngaged = true;
        } else if (airborneTicks == 1) {
            tellyStartY = (int) Math.floor(player.getPosition().y) - 1;
            tellyEngaged = true;
        } else {
            clearAim(true);
            disablePlacing(false);
            detectedGaps.clear();
            return;
        }
    }

    // Check for gaps in placements
    checkForGaps(player);

    int placeY = (tellyEngaged && tellyStartY != Integer.MIN_VALUE) ? tellyStartY : (int) Math.floor(player.getPosition().y) - 1;
    Vec3 below = new Vec3(Math.floor(player.getPosition().x), placeY, Math.floor(player.getPosition().z));
    if (!canPlaceThrough(below)) {
        disablePlacing(false);
        return;
    }

    int weakSlot = pickBlockSlot();
    if (weakSlot == -1) {
        disablePlacing(false);
        return;
    }

    plannedSlot = weakSlot;
    AimResult target = clutchAim(player);
    if (target != null) {
        targetHitPos = target.rayPos;
        targetSide = target.side;
        aimYaw = target.yaw;
        aimPitch = target.pitch;
        hasAim = true;
        resetting = false;
    }

    if (hasAim && !placing) enablePlacing();

    if (placing || resetting || hasAim) {
        releaseClickKeys();
        equipPlannedSlot();
    }
}

// Check for gaps in block placements
void checkForGaps(Entity player) {
    Vec3 playerPos = player.getPosition();
    int playerFeetX = (int) Math.floor(playerPos.x);
    int playerFeetZ = (int) Math.floor(playerPos.z);
    int placeY = (tellyEngaged && tellyStartY != Integer.MIN_VALUE) ? tellyStartY : (int) Math.floor(playerPos.y) - 1;
    
    // Clear old gaps
    detectedGaps.clear();
    
    // Check area around player for gaps (3-5 block distances)
    for (int x = playerFeetX - 5; x <= playerFeetX + 5; x++) {
        for (int z = playerFeetZ - 5; z <= playerFeetZ + 5; z++) {
            Vec3 blockPos = new Vec3(x, placeY, z);
            
            // Skip if it's air or already has a block we can place on
            if (canPlaceThrough(blockPos)) continue;
            
            // Check if this block was placed by the player (by checking neighbors)
            boolean hasPlayerPlacedNeighbor = false;
            for (int nx = -1; nx <= 1; nx++) {
                for (int nz = -1; nz <= 1; nz++) {
                    if (nx == 0 && nz == 0) continue;
                    Vec3 neighborPos = new Vec3(x + nx, placeY, z + nz);
                    if (sameBlock(neighborPos, lastPlaced)) {
                        hasPlayerPlacedNeighbor = true;
                        break;
                    }
                }
                if (hasPlayerPlacedNeighbor) break;
            }
            
            // If no player-placed neighbor, this might be a gap
            if (!hasPlayerPlacedNeighbor) {
                // Check adjacent positions for air blocks we can place on
                for (int nx = -1; nx <= 1; nx++) {
                    for (int nz = -1; nz <= 1; nz++) {
                        if (nx == 0 && nz == 0) continue;
                        Vec3 adjacentPos = new Vec3(x + nx, placeY, z + nz);
                        if (canPlaceThrough(adjacentPos)) {
                            // Check if this adjacent position is within 3-5 blocks of player
                            double distX = Math.abs(adjacentPos.x - playerPos.x);
                            double distZ = Math.abs(adjacentPos.z - playerPos.z);
                            if ((distX >= 3 && distX <= 5) || (distZ >= 3 && distZ <= 5)) {
                                // Check if we can place on the existing block
                                Vec3 placeOnPos = new Vec3(x, placeY, z);
                                if (!canPlaceThrough(placeOnPos)) {
                                    detectedGaps.add(adjacentPos);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

void enablePlacing() {
    if (placing) return;
    placing = true;
    if (!autoClickerWasOn) {
        try {
            autoClickerWasOn = modules.isEnabled("AutoClicker");
        } catch (Exception ignored) {
        }
    }
    if (autoClickerWasOn) {
        try {
            modules.disable("AutoClicker");
        } catch (Exception ignored) {
        }
    }
}

void disablePlacing(boolean forceRestore) {
    if (!placing && !forceRestore) return;

    placing = false;
    plannedSlot = -1;

    if (forceRestore) {
        restoreInputsAndAutoClicker();
    }
}

void clearAim(boolean allowSnapback) {
    targetHitPos = null;
    targetSide = null;
    lastPlaced = null;
    clutchBlocksPlaced = 0;
    if (allowSnapback && hasAim) {
        resetting = true;
    } else {
        setMovementFix(false);
    }
    hasAim = false;
    smoothInit = false;
}

void restoreInputsAndAutoClicker() {
    try {
        if (isScreenClosed()) {
            keybinds.setPressed("attack", keybinds.isMouseDown(0));
            keybinds.setPressed("use", keybinds.isMouseDown(1));
        }
    } catch (Exception ignored) {
    }
    if (autoClickerWasOn) {
        try {
            modules.enable("AutoClicker");
        } catch (Exception ignored) {
        }
        autoClickerWasOn = false;
    }
}

void releaseClickKeys() {
    try {
        keybinds.setPressed("attack", false);
        keybinds.setPressed("use", false);
    } catch (Exception ignored) {
    }
}

AimResult clutchAim(Entity player) {
    Vec3 playerPos = player.getPosition();
    Vec3 eye = new Vec3(playerPos.x, playerPos.y + player.getEyeHeight(), playerPos.z);

    int feetX = (int) Math.floor(playerPos.x);
    int feetZ = (int) Math.floor(playerPos.z);
    int feetY = (int) Math.floor(playerPos.y);
    int minX = feetX - 5;
    int maxX = feetX + 4;
    int minZ = feetZ - 5;
    int maxZ = feetZ + 4;
    int maxY;
    int minY;
    if (tellyEngaged && tellyStartY != Integer.MIN_VALUE) {
        maxY = tellyStartY;
        minY = tellyStartY;
    } else {
        maxY = feetY - 1;
        minY = feetY - 4;
    }

    boolean keepY = tellyEngaged && tellyStartY != Integer.MIN_VALUE;

    List<BlockCandidate> candidates = new ArrayList<BlockCandidate>();
    for (int y = maxY; y >= minY; y--) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vec3 pos = new Vec3(x, y, z);
                if (canPlaceThrough(pos)) continue;

                double score = distPointToAABB(playerPos, pos);
                if (sameBlock(pos, lastPlaced)) score *= 0.95;
                candidates.add(new BlockCandidate(score, pos));
            }
        }
    }

    // Add gap filling targets
    for (Vec3 gapPos : detectedGaps) {
        double score = distPointToAABB(playerPos, gapPos) * 0.8; // Prioritize gaps
        candidates.add(new BlockCandidate(score, gapPos));
    }

    candidates.sort((a, b) -> Double.compare(a.score, b.score));

    ItemStack held = plannedSlot >= 0 && plannedSlot <= 8 ? inventory.getStackInSlot(plannedSlot) : null;
    double reachVal = 4.5;
    if (keepY) {
        AimResult tellyAim = getBestRotationsToTelly(held, eye, reachVal);
        if (tellyAim != null) return tellyAim;
    }
    for (BlockCandidate candidate : candidates) {
        boolean underPlayer = isBlockUnderPlayer(candidate.pos, playerPos);
        AimResult result = getBestRotationsToBlock(held, candidate.pos, eye, reachVal, underPlayer, keepY);
        if (result != null) return result;
    }

    return null;
}

boolean isBlockUnderPlayer(Vec3 blockPos, Vec3 pos) {
    if (blockPos.y >= Math.floor(pos.y)) return false;
    for (double[] corner : CORNERS) {
        int cx = (int) Math.floor(pos.x + corner[0]);
        int cz = (int) Math.floor(pos.z + corner[1]);
        if (blockPos.x == cx && blockPos.z == cz) return true;
    }
    return false;
}

AimResult getBestRotationsToBlock(ItemStack held, Vec3 targetCell, Vec3 eye, double reachVal, boolean underPlayer, boolean sideFacesOnly) {
    if (held == null) return null;
    double inset = 0.05;
    double step = 0.2;
    double jitter = step * 0.1;
    boolean faceSouth = Math.abs(eye.z - (targetCell.z + 1)) < Math.abs(eye.z - targetCell.z);
    boolean faceEast = Math.abs(eye.x - (targetCell.x + 1)) < Math.abs(eye.x - targetCell.x);
    float baseYaw = normYaw(anchorYaw());
    float basePitch = anchorPitch();
    int n = (int) Math.round(1 / step);

    List<RotationCandidate> candidates = new ArrayList<RotationCandidate>();
    candidates.add(new RotationCandidate(0, baseYaw, basePitch));

    for (int row = 0; row <= n; row++) {
        double v = clamp01(row * step + randomRange(-jitter, jitter));
        for (int col = 0; col <= n; col++) {
            double u = clamp01(col * step + randomRange(-jitter, jitter));

            if (underPlayer && !sideFacesOnly) {
                float[] rV = getRotationsWrapped(eye, targetCell.x + u, targetCell.y + 1 - inset, targetCell.z + v);
                double costV = Math.abs(wrapYawDelta(baseYaw, rV[0])) + Math.abs(rV[1] - basePitch);
                candidates.add(new RotationCandidate(costV, rV[0], rV[1]));
            }

            float[] rZ = getRotationsWrapped(eye, targetCell.x + u, targetCell.y + v, faceSouth ? targetCell.z + 1 - inset : targetCell.z + inset);
            double costZ = Math.abs(wrapYawDelta(baseYaw, rZ[0])) + Math.abs(rZ[1] - basePitch);
            candidates.add(new RotationCandidate(costZ, rZ[0], rZ[1]));

            float[] rX = getRotationsWrapped(eye, faceEast ? targetCell.x + 1 - inset : targetCell.x + inset, targetCell.y + v, targetCell.z + u);
            double costX = Math.abs(wrapYawDelta(baseYaw, rX[0])) + Math.abs(rX[1] - basePitch);
            candidates.add(new RotationCandidate(costX, rX[0], rX[1]));
        }
    }

    candidates.sort((a, b) -> Double.compare(a.cost, b.cost));

    for (RotationCandidate candidate : candidates) {
        float yaw = unwrapYaw(candidate.yaw, anchorYaw());
        // Strict raycast - check multiple points for better accuracy
        Object[] ray = strictRaycastBlock(reachVal, yaw, candidate.pitch, targetCell);
        if (ray == null) continue;

        String face = ((String) ray[2]).toUpperCase();
        if (face.equals("DOWN")) continue;
        if (face.equals("UP") && (sideFacesOnly || !underPlayer)) continue;
        if (!sameBlock((Vec3) ray[0], targetCell)) continue;
        if (!canPlaceOn(held, (Vec3) ray[0], face)) continue;

        return new AimResult((Vec3) ray[0], face, yaw, candidate.pitch);
    }

    return null;
}

// Strict raycast that checks multiple points on the block face
Object[] strictRaycastBlock(double distance, float yaw, float pitch, Vec3 targetBlock) {
    // First try normal raycast
    Object[] primaryRay = client.raycastBlock(distance, yaw, pitch);
    if (primaryRay != null && sameBlock((Vec3) primaryRay[0], targetBlock)) {
        return primaryRay;
    }
    
    // Try additional raycasts at corners for more accuracy
    Vec3 eye = getEyePosition();
    if (eye == null) return primaryRay;
    
    // Check four corners of the target block face
    double[][] offsets = {{0.1, 0.1}, {0.1, 0.9}, {0.9, 0.1}, {0.9, 0.9}};
    for (double[] offset : offsets) {
        Vec3 checkPos = new Vec3(
            targetBlock.x + offset[0],
            targetBlock.y + 0.5,
            targetBlock.z + offset[1]
        );
        Object[] ray = client.raycastBlock(distance, yaw, pitch);
        if (ray != null && sameBlock((Vec3) ray[0], targetBlock)) {
            return ray;
        }
    }
    
    return primaryRay;
}

Vec3 getEyePosition() {
    Entity player = client.getPlayer();
    if (player == null) return null;
    Vec3 pos = player.getPosition();
    return new Vec3(pos.x, pos.y + player.getEyeHeight(), pos.z);
}

AimResult getBestRotationsToTelly(ItemStack held, Vec3 eye, double reachVal) {
    if (held == null) return null;
    Vec3 targetCell = new Vec3(Math.floor(eye.x), tellyStartY, Math.floor(eye.z));
    if (!canPlaceThrough(targetCell)) return null;
    return getBestRotationsToFillCell(held, eye, reachVal, targetCell);
}

AimResult getBestRotationsToFillCell(ItemStack held, Vec3 eye, double reachVal, Vec3 fillCell) {
    if (held == null) return null;
    double inset = 0.05;
    double cx = Math.floor(fillCell.x);
    double cy = Math.floor(fillCell.y);
    double cz = Math.floor(fillCell.z);

    List<double[]> aimPoints = new ArrayList<double[]>();
    aimPoints.add(new double[]{cx + 0.5, cy + 0.5, cz + 1 + inset});
    aimPoints.add(new double[]{cx + 0.5, cy + 0.5, cz - inset});
    aimPoints.add(new double[]{cx + 1 + inset, cy + 0.5, cz + 0.5});
    aimPoints.add(new double[]{cx - inset, cy + 0.5, cz + 0.5});

    for (double[] p : aimPoints) {
        float[] r = getRotationsWrapped(eye, p[0], p[1], p[2]);
        float yaw = unwrapYaw(r[0], anchorYaw());
        Object[] ray = client.raycastBlock(reachVal, yaw, r[1]);
        if (ray == null) continue;

        String face = ((String) ray[2]).toUpperCase();
        if (face.equals("DOWN")) continue;
        Vec3 hit = (Vec3) ray[0];
        if (!isPlacementAt(hit, face, cx, cy, cz)) continue;
        if (!canPlaceOn(held, hit, face)) continue;

        return new AimResult(hit, face, yaw, r[1]);
    }

    return null;
}

boolean isPlacementAt(Vec3 hit, String face, double cx, double cy, double cz) {
    int pcx = (int) Math.floor(hit.x);
    int pcy = (int) Math.floor(hit.y);
    int pcz = (int) Math.floor(hit.z);
    if (face.equals("UP")) pcy += 1;
    else if (face.equals("DOWN")) pcy -= 1;
    else if (face.equals("NORTH")) pcz -= 1;
    else if (face.equals("SOUTH")) pcz += 1;
    else if (face.equals("WEST")) pcx -= 1;
    else if (face.equals("EAST")) pcx += 1;
    return pcx == (int) cx && pcy == (int) cy && pcz == (int) cz;
}

int pickBlockSlot() {
    boolean playingBedwars = isPlayingBedwars();
    if (!playingBedwars) {
        int current = inventory.getSlot();
        if (isBlockSlot(current)) {
            if (inventory.getStackInSlot(current).stackSize > 5) return current;
            int swap = findSwapSlot(current);
            if (swap != -1) return swap;
            return current;
        }

        for (int slot = 8; slot >= 0; --slot) {
            if (isBlockSlot(slot) && inventory.getStackInSlot(slot).stackSize > 5) return slot;
        }

        for (int slot = 8; slot >= 0; --slot) {
            if (isBlockSlot(slot)) return slot;
        }
        return -1;
    }

    int best = -1;
    int bestScore = Integer.MIN_VALUE;

    for (int slot = 8; slot >= 0; --slot) {
        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null || stack.stackSize <= 5 || !stack.isBlock) continue;

        Integer score = BLOCK_SCORE.get(normalizeName(stack.name));
        if (score == null) continue;

        if (score > bestScore) {
            bestScore = score;
            best = slot;
        }
    }

    if (best == -1) {
        for (int slot = 8; slot >= 0; --slot) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack == null || stack.stackSize == 0 || !stack.isBlock) continue;

            Integer score = BLOCK_SCORE.get(normalizeName(stack.name));
            if (score == null) continue;

            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
    }

    if (best != -1 && inventory.getStackInSlot(best).stackSize == 5) {
        int swap = findSwapSlot(best);
        if (swap != -1) return swap;
    }
    return best;
}

int findSwapSlot(int excludeSlot) {
    String excludeName = null;
    ItemStack currentStack = inventory.getStackInSlot(excludeSlot);
    if (currentStack != null) excludeName = normalizeName(currentStack.name);

    for (int slot = 8; slot >= 0; --slot) {
        if (slot == excludeSlot) continue;
        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null || stack.stackSize <= 5 || !stack.isBlock) continue;
        if (excludeName != null && normalizeName(stack.name).equals(excludeName)) return slot;
    }

    for (int slot = 8; slot >= 0; --slot) {
        if (slot == excludeSlot) continue;
        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null || stack.stackSize <= 5 || !stack.isBlock) continue;
        return slot;
    }

    return -1;
}

boolean isBlockSlot(int slot) {
    if (slot < 0 || slot > 8) return false;
    ItemStack stack = inventory.getStackInSlot(slot);
    return stack != null && stack.stackSize > 0 && stack.isBlock;
}

boolean isPlayingBedwars() {
    try {
        String ip = client.getServerIP();
        if (ip == null || !ip.toLowerCase().contains("hypixel")) return false;
        List<String> scoreboard = world.getScoreboard();
        if (scoreboard == null) return false;
        for (String line : scoreboard) {
            if (line == null) continue;
            String s = util.strip(line).toLowerCase();
            if (s.contains("bedwars") || s.contains("bed wars")) return true;
        }
    } catch (Exception ignored) {
    }
    return false;
}

void equipPlannedSlot() {
    int current = inventory.getSlot();
    if (plannedSlot != -1 && plannedSlot != current) {
        inventory.setSlot(plannedSlot);
    }
}

boolean canPlaceThrough(Vec3 pos) {
    Block block = world.getBlockAt(pos);
    if (block == null) return false;
    String name = normalizeName(block.name);
    return "air".equals(name) || "water".equals(name) || "lava".equals(name) || "fire".equals(name);
}

float anchorYaw() {
    if (hasAppliedRotation) return appliedYaw;
    Entity player = client.getPlayer();
    return player != null ? player.getYaw() : 0f;
}

float anchorPitch() {
    if (hasAppliedRotation) return appliedPitch;
    Entity player = client.getPlayer();
    return player != null ? player.getPitch() : 0f;
}

boolean canPlaceOn(ItemStack stack, Vec3 pos, String side) {
    try {
        if (client.canPlaceBlock(stack, pos, side)) return true;
    } catch (Exception ignored) {
    }
    try {
        return client.canPlaceBlock(stack, pos, side.toLowerCase());
    } catch (Exception ignored) {
    }
    return false;
}

void setMovementFix(boolean on) {
    if (on && !movementFixOwned) {
        client.enableMovementFix();
        movementFixOwned = true;
    } else if (!on && movementFixOwned) {
        client.disableMovementFix();
        movementFixOwned = false;
    }
}

boolean isScreenClosed() {
    String screen = client.getScreen();
    return screen == null || screen.isEmpty() || "null".equals(screen);
}

boolean isMovingDiagonal() {
    Entity player = client.getPlayer();
    if (player == null) return false;
    Vec3 motion = player.getMotion();
    if (motion == null) return false;
    return Math.abs(motion.x) > 0.05 && Math.abs(motion.z) > 0.05;
}

static double distPointToAABB(Vec3 point, Vec3 blockPos) {
    double minX = blockPos.x;
    double minY = blockPos.y;
    double minZ = blockPos.z;
    double maxX = blockPos.x + 1;
    double maxY = blockPos.y + 1;
    double maxZ = blockPos.z + 1;
    double dx = Math.max(minX - point.x, Math.max(0, point.x - maxX));
    double dy = Math.max(minY - point.y, Math.max(0, point.y - maxY));
    double dz = Math.max(minZ - point.z, Math.max(0, point.z - maxZ));
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

static boolean sameBlock(Vec3 a, Vec3 b) {
    if (a == null || b == null) return false;
    return a.x == b.x && a.y == b.y && a.z == b.z;
}

static boolean sideEquals(String a, String b) {
    if (a == null || b == null) return false;
    return a.equalsIgnoreCase(b);
}

static String normalizeName(String name) {
    if (name == null) return "";
    if (name.startsWith("minecraft:")) name = name.substring(10);
    return name;
}

static double clamp01(double v) {
    return v < 0 ? 0 : v > 1 ? 1 : v;
}

static int sideOrdinal(String side) {
    String s = side.toUpperCase();
    if (s.equals("DOWN")) return 0;
    if (s.equals("UP")) return 1;
    if (s.equals("NORTH")) return 2;
    if (s.equals("SOUTH")) return 3;
    if (s.equals("WEST")) return 4;
    if (s.equals("EAST")) return 5;
    return 1;
}

static double randomRange(double min, double max) {
    return min + Math.random() * (max - min);
}

static float normYaw(float yaw) {
    yaw = ((yaw % 360f) + 360f) % 360f;
    return yaw > 180f ? yaw - 360f : yaw;
}

static float wrapYawDelta(float base, float target) {
    return wrapAngleTo180(target - base);
}

static float unwrapYaw(float yaw, float prevYaw) {
    return prevYaw + wrapAngleTo180(yaw - prevYaw);
}

static float wrapAngleTo180(float value) {
    value = value % 360.0f;
    if (value >= 180.0f) value -= 360.0f;
    if (value < -180.0f) value += 360.0f;
    return value;
}

static float clampPitch(float pitch) {
    return Math.max(-90f, Math.min(90f, pitch));
}

static float[] getRotationsWrapped(Vec3 eye, double tx, double ty, double tz) {
    double dx = tx - eye.x;
    double dy = ty - eye.y;
    double dz = tz - eye.z;
    double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
    float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
    float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontalDistance));
    return new float[]{normYaw(yaw), clampPitch(pitch)};
}

static class BlockCandidate {
    final double score;
    final Vec3 pos;

    BlockCandidate(double score, Vec3 pos) {
        this.score = score;
        this.pos = pos;
    }
}

static class RotationCandidate {
    final double cost;
    final float yaw;
    final float pitch;

    RotationCandidate(double cost, float yaw, float pitch) {
        this.cost = cost;
        this.yaw = yaw;
        this.pitch = pitch;
    }
}

static class AimResult {
    final Vec3 rayPos;
    final String side;
    final float yaw;
    final float pitch;

    AimResult(Vec3 rayPos, String side, float yaw, float pitch) {
        this.rayPos = rayPos;
        this.side = side;
        this.yaw = yaw;
        this.pitch = pitch;
    }
}
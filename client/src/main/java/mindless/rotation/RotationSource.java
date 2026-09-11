package mindless.rotation;

public enum RotationSource {
    WATER_BUCKET(700),
    CLUTCH(690),
    LADDER_CLUTCH(680),
    LONG_JUMP(600),
    JUMP_45(590),
    ROD_AIMBOT(580),
    BED_AURA(500),
    KILL_AURA(490),
    DISPLACE(480),
    AUTO_HEAD_HITTER(400),
    AUTO_BLOCKIN(390),
    SCAFFOLD(370),
    BRIDGE_ASSIST(360),
    BED_DEFENDER(350),
    SCRIPT(340),
    ANTI_FIREBALL(300),
    AIM_ASSIST(200),
    SPEED(100),
    LEGACY(0);

    private final int priority;

    RotationSource(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }
}

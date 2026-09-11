package mindless.module.impl.combat.aura;

public final class AuraAutoBlockController {
    public static final int NONE = 0, VANILLA = 1, HYPIXEL = 2, BLINK = 3,
            INTERACT = 4, SPOOF = 5, SWAP = 6, LEGIT = 7, FAKE = 8;

    public interface Actions {
        boolean blocking();
        boolean clearInterval();
        boolean slotsMatch();
        boolean release(boolean slot);
        boolean spoof();
        boolean swapSword();
        boolean attack();
        boolean block(boolean attacked);
        void releaseBuffer();
    }

    public boolean active, render, restartPending, cleanupPending;
    private int cleanupAttempts;
    public int phase;
    public long hold, releaseDelay;

    public void step(int mode, boolean combat, boolean eligible, boolean useHeld,
                     int hurtTicks, int hurtLimit, long holdDuration, long delayDuration,
                     Actions actions) {
        boolean attack = combat;
        boolean suppressed = mode != NONE && mode != FAKE && eligible
                && (hurtTicks > hurtLimit || releaseDelay > 0);
        hold = AuraTiming.decrement(hold);
        releaseDelay = AuraTiming.decrement(releaseDelay);
        if (cleanupPending || !combat || !eligible || suppressed) {
            actions.releaseBuffer();
            render = restartPending = false;
            if (active && actions.blocking()) {
                attack = false;
                cleanupPending = true;
                if (!actions.clearInterval() || cleanupAttempts >= 20) return;
                cleanupAttempts++;
                if (!actions.release(false)) return;
            }
            active = cleanupPending = false;
            cleanupAttempts = 0;
            if (hold > 0) hold = 0;
            phase = 0;
        }
        if (!combat) return;
        if (suppressed) {
            eligible = false;
            active = render = true;
        }
        boolean start = false;
        if (eligible) {
            active = mode != NONE && mode != FAKE;
            render = mode != NONE && mode != VANILLA && mode != SPOOF;
            if (mode != HYPIXEL && mode != BLINK && mode != INTERACT) {
                actions.releaseBuffer();
                restartPending = false;
            }
            boolean clear = actions.clearInterval();
            switch (mode) {
                case NONE:
                case FAKE:
                    start = useHeld && !actions.blocking() && clear;
                    break;
                case VANILLA:
                    start = !actions.blocking() && clear;
                    break;
                case SPOOF:
                    if (clear && actions.slotsMatch() && (!actions.blocking() || hold <= 0)
                            && actions.spoof()) {
                        start = true;
                        hold += holdDuration;
                    }
                    break;
                case HYPIXEL:
                case BLINK:
                case INTERACT:
                case SWAP:
                case LEGIT:
                    if (!clear || ((mode == INTERACT || mode == SWAP) && !actions.slotsMatch())) break;
                    if (phase == 0) {
                        if (!actions.blocking()) {
                            start = true;
                            hold += holdDuration;
                        }
                        restartPending = mode == HYPIXEL || mode == BLINK || mode == INTERACT;
                        phase = 1;
                    } else {
                        boolean earlyRelease = mode == BLINK || mode == INTERACT;
                        if ((earlyRelease || hold <= 0) && actions.blocking()) {
                            boolean released = mode == SWAP ? actions.swapSword()
                                    : actions.release(mode == INTERACT);
                            attack = false;
                            if (!released) break;
                        }
                        if (hold <= 0) {
                            releaseDelay += delayDuration;
                            phase = 0;
                        } else if (mode == HYPIXEL) {
                            restartPending = true;
                        }
                    }
                    break;
                default:
                    throw new IllegalArgumentException("auto block mode");
            }
        }
        boolean attacked = attack && actions.attack();
        if (start && !actions.block(attacked)) {
            phase = 0;
            hold = 0;
            restartPending = false;
            actions.releaseBuffer();
        }
    }

    public void reset() {
        active = render = restartPending = cleanupPending = false;
        cleanupAttempts = 0;
        phase = 0;
        hold = releaseDelay = 0;
    }
}

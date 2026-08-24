package mindless.module.impl.bedwars;

import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the next game event off the scoreboard and puts it somewhere you will look.
 *
 * <p>Hypixel already counts down to the diamond and emerald upgrades, sudden death and game end
 * -- on the sidebar, in small text, in the corner, behind whatever else is going on. The
 * information is fine; where it lives is the problem.
 *
 * <p>Read from the sidebar rather than timed from the game start. A timer of our own drifts,
 * breaks on a rejoin, and has to know the mode; the scoreboard is the server's own count and is
 * right by construction. The cost is that it only works in English, which is also true of the
 * mod this came from.
 */
public class EventTimers extends BedwarsHud {
    /** "Diamond II in 3:45", "Sudden Death in 0:30", and the rest of the family. */
    private static final Pattern EVENT_LINE =
            Pattern.compile("^(.+?)\\s+in\\s+(\\d{1,2}:\\d{2})$");

    /** Under this many seconds the countdown turns red: enough time to react, not much more. */
    private static final int URGENT_SECONDS = 30;

    private final ButtonSetting dynamicColour;

    private String event = "";
    private String remaining = "";

    public EventTimers() {
        super("Event Timers", 0.02f, 0.50f);
        this.registerSetting(dynamicColour = new ButtonSetting("Colour by urgency", true));
    }

    @Override
    public void onDisable() {
        event = "";
        remaining = "";
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || !Utils.nullCheck()) return;

        if (Utils.getBedwarsStatus() != 2) {
            this.event = "";
            this.remaining = "";
            return;
        }

        for (String raw : Utils.getSidebarLines()) {
            Matcher matcher = EVENT_LINE.matcher(Utils.stripColor(raw).trim());
            if (!matcher.matches()) continue;
            this.event = matcher.group(1).trim();
            this.remaining = matcher.group(2);
            return;
        }

        // No event line at all: the game is over, or between phases.
        this.event = "";
        this.remaining = "";
    }

    /** Seconds left, or -1 when the countdown cannot be read. */
    private int secondsLeft() {
        int colon = remaining.indexOf(':');
        if (colon <= 0) return -1;
        try {
            return Integer.parseInt(remaining.substring(0, colon)) * 60
                    + Integer.parseInt(remaining.substring(colon + 1));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private String eventColour() {
        String lower = event.toLowerCase();
        if (lower.startsWith("diamond")) return "§b";
        if (lower.startsWith("emerald")) return "§2";
        if (lower.contains("sudden death")) return "§5";
        if (lower.contains("game end")) return "§c";
        if (lower.contains("bed")) return "§6";
        return "§f";
    }

    @Override
    protected boolean shouldDraw() {
        return Utils.getBedwarsStatus() == 2 && !event.isEmpty();
    }

    @Override
    protected List<String> lines() {
        List<String> out = new ArrayList<String>(1);
        if (event.isEmpty()) return out;

        int left = secondsLeft();
        String timeColour = "§f";
        if (dynamicColour.isToggled() && left >= 0) {
            timeColour = left <= URGENT_SECONDS ? "§c" : left <= 60 ? "§e" : "§a";
        }
        out.add(eventColour() + event + " §7in " + timeColour + remaining);
        return out;
    }
}

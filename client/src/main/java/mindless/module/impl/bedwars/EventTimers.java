package mindless.module.impl.bedwars;

import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public class EventTimers extends BedwarsHud {
private static final Pattern EVENT_LINE =
            Pattern.compile("^(.+?)\\s+in\\s+(\\d{1,2}:\\d{2})$");
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
        this.event = "";
        this.remaining = "";
    }
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

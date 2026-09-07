package mindless.module.impl.bedwars;

import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import mindless.utility.HypixelLanguage;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public class EventTimers extends BedwarsHud {
private static final Pattern EVENT_LINE =
            Pattern.compile("^(.+?)\\s+(\\d{1,2}:\\d{2})$");
private static final int URGENT_SECONDS = 30;

    private final ButtonSetting dynamicColour;

    private String event = "";
    private String remaining = "";

    public EventTimers() {
        super("Event Timers", "Counts down to the next game event.", 0.02f, 0.50f);
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
            this.event = trimConnector(matcher.group(1).trim());
            this.remaining = matcher.group(2);
            return;
        }
        this.event = "";
        this.remaining = "";
    }

    private String trimConnector(String value) {
        String connector = HypixelLanguage.first(HypixelLanguage.Key.TIMER_IN);
        if (!connector.isEmpty() && value.toLowerCase().endsWith(" " + connector)) {
            return value.substring(0, value.length() - connector.length()).trim();
        }
        return value;
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
        if (HypixelLanguage.contains(event, HypixelLanguage.Key.DIAMOND)) return "§b";
        if (HypixelLanguage.contains(event, HypixelLanguage.Key.EMERALD)) return "§2";
        if (HypixelLanguage.contains(event, HypixelLanguage.Key.SUDDEN_DEATH)) return "§5";
        if (HypixelLanguage.contains(event, HypixelLanguage.Key.GAME_END)) return "§c";
        if (HypixelLanguage.contains(event, HypixelLanguage.Key.BED)) return "§6";
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

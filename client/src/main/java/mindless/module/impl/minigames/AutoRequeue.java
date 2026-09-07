package mindless.module.impl.minigames;

import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.util.IChatComponent;
import net.minecraft.event.ClickEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class AutoRequeue extends Module {
    private SliderSetting delay;

    private String receivedMessage = "";
    private long receiveTime = 0;

    public AutoRequeue() {
        super("Auto Requeue", "Queues the next game as soon as this ends.", category.bedwars);
        this.registerSetting(new DescriptionSetting("Automatically requeues games."));
        this.registerSetting(delay = new SliderSetting("Delay", " second", 0.5, 0, 5, 0.1));
        this.closetModule = true;
    }

    @Override
    public void onDisable() {
        receivedMessage = "";
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!receivedMessage.isEmpty() && System.currentTimeMillis() - receiveTime >= delay.getInput() * 1000) {
            mc.thePlayer.sendChatMessage(receivedMessage);
            receivedMessage = "";
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        if (e.type == 2 || !Utils.nullCheck()) {
            return;
        }
        String command = findPlayCommand(e.message);
        if (command != null) {
            this.receivedMessage = command;
            this.receiveTime = System.currentTimeMillis();
        }
    }

    private String findPlayCommand(IChatComponent component) {
        if (component == null) return null;
        if (component.getChatStyle() != null) {
            ClickEvent click = component.getChatStyle().getChatClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND) {
                String value = click.getValue();
                if (value != null && value.toLowerCase().startsWith("/play")) return value;
            }
        }
        for (IChatComponent child : component.getSiblings()) {
            String command = findPlayCommand(child);
            if (command != null) return command;
        }
        return null;
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            receivedMessage = ""; // will not requeue if you left the world where the message was sent
        }
    }
}

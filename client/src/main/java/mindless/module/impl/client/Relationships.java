package mindless.module.impl.client;

import mindless.Mindless;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.PlayerListSetting;
import mindless.utility.PlayerRelationsManager;

public class Relationships extends Module {
    public final GroupSetting friendsGroup = new GroupSetting("Friends");
    public final GroupSetting enemiesGroup = new GroupSetting("Enemies");
    public final GroupSetting middleClickGroup = new GroupSetting("Middle click");

    public final PlayerListSetting friends = new PlayerListSetting(friendsGroup, "Players", PlayerRelationsManager.RelationType.FRIEND, "Type a username", 32);
    public final PlayerListSetting enemies = new PlayerListSetting(enemiesGroup, "Players", PlayerRelationsManager.RelationType.ENEMY, "Type a username", 32);
    public final ButtonSetting middleClickFriends = new ButtonSetting(middleClickGroup, "Middle click friends",
        Mindless.playerRelationsManager != null && Mindless.playerRelationsManager.isMiddleClickFriends());

    public Relationships() {
        super("Relationships", category.client, 0);

        friendsGroup.setOpened(true);
        enemiesGroup.setOpened(true);
        middleClickGroup.setOpened(true);

        registerSetting(friendsGroup);
        registerSetting(friends);
        registerSetting(enemiesGroup);
        registerSetting(enemies);
        registerSetting(middleClickGroup);
        registerSetting(middleClickFriends);

        this.ignoreOnSave = true;
        this.hidden = true;
    }

    @Override
    public void guiButtonToggled(ButtonSetting buttonSetting) {
        if (buttonSetting == middleClickFriends && Mindless.playerRelationsManager != null) {
            Mindless.playerRelationsManager.setMiddleClickFriends(buttonSetting.isToggled());
        }
    }

    @Override
    public void onEnable() {
        if (Mindless.playerRelationsManager != null) {
            Mindless.playerRelationsManager.setActive(true);
        }
    }

    @Override
    public void onDisable() {
        if (Mindless.playerRelationsManager != null) {
            Mindless.playerRelationsManager.setActive(false);
        }
    }
}

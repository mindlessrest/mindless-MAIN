package mindless.utility.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.ProfiledButtonSetting;
import mindless.module.setting.impl.ProfiledSliderSetting;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProfileMigrationsTest {
    @Test
    public void oldScaffoldTowerModesMigrateWithoutRestoringMotionScaling() {
        for (int mode = 0; mode <= 3; mode++) {
            JsonObject profile = new JsonObject();
            profile.addProperty("configVersion", 4);
            JsonObject scaffold = new JsonObject();
            scaffold.addProperty("name", "Scaffold");
            scaffold.addProperty("tower", mode);
            scaffold.addProperty("ground-motion", 200);
            scaffold.addProperty("air-motion", 50);
            scaffold.addProperty("speed-motion", 175);
            scaffold.addProperty("keep-y", 3);
            JsonArray modules = new JsonArray();
            modules.add(scaffold);
            profile.add("modules", modules);
            assertTrue(ProfileMigrations.migrate(profile, null, "test"));
            assertEquals(mode == 2 ? 1 : mode == 3 ? 2 : mode, scaffold.get("tower").getAsInt());
            assertFalse(scaffold.has("ground-motion"));
            assertFalse(scaffold.has("air-motion"));
            assertFalse(scaffold.has("speed-motion"));
            assertEquals(3, scaffold.get("keep-y").getAsInt());
            assertFalse(ProfileMigrations.migrate(profile, null, "test"));
        }
    }

    @Test
    public void malformedScaffoldTowerAndUnrelatedSettingsSurviveMigration() {
        JsonObject profile = new JsonObject();
        profile.addProperty("configVersion", 4);
        JsonObject scaffold = new JsonObject();
        scaffold.addProperty("name", "Scaffold");
        scaffold.addProperty("tower", "unknown");
        scaffold.addProperty("safe-walk", true);
        JsonArray modules = new JsonArray();
        modules.add(scaffold);
        modules.add(new com.google.gson.JsonPrimitive("invalid"));
        profile.add("modules", modules);
        assertTrue(ProfileMigrations.migrate(profile, null, "test"));
        assertEquals("unknown", scaffold.get("tower").getAsString());
        assertTrue(scaffold.get("safe-walk").getAsBoolean());
    }
    @Test
    public void versionTwoBedBreakerGetsIndependentSilentDefaults() {
        JsonObject profile = profileWithBedBreaker();
        JsonObject bedBreaker = profile.getAsJsonArray("modules").get(0).getAsJsonObject();
        bedBreaker.addProperty("Mode", 1);
        bedBreaker.addProperty("Range", 4.2D);

        assertTrue(ProfileMigrations.migrate(profile, null, "test"));
        assertEquals(ProfileMigrations.CURRENT_VERSION, ProfileMigrations.versionOf(profile));
        assertEquals(1, bedBreaker.get("Mode").getAsInt());
        assertEquals(4.2D, bedBreaker.get("Range").getAsDouble(), 0.0D);
        assertEquals(5.5D, bedBreaker.get("Silent.Range").getAsDouble(), 0.0D);
        assertEquals(33, bedBreaker.get("Silent.Speed").getAsInt());
        assertTrue(bedBreaker.get("Silent.Ground spoof").getAsBoolean());
        assertEquals(0, bedBreaker.get("Silent.Ignore velocity").getAsInt());
        assertTrue(bedBreaker.get("Silent.Surroundings").getAsBoolean());
        assertTrue(bedBreaker.get("Silent.Tool check").getAsBoolean());
        assertTrue(bedBreaker.get("Silent.Whitelist").getAsBoolean());
        assertTrue(bedBreaker.get("Silent.Swing").getAsBoolean());
        assertEquals(1, bedBreaker.get("Silent.Move fix").getAsInt());
        assertEquals(1, bedBreaker.get("Silent.Show target").getAsInt());
        assertEquals(1, bedBreaker.get("Silent.Show progress").getAsInt());
        assertFalse(ProfileMigrations.migrate(profile, null, "test"));
    }

    @Test
    public void versionTwoBedBreakerKeepsExistingSilentValues() {
        JsonObject profile = profileWithBedBreaker();
        JsonObject bedBreaker = profile.getAsJsonArray("modules").get(0).getAsJsonObject();
        bedBreaker.addProperty("Silent.Range", 3.4D);
        bedBreaker.addProperty("Silent.Ground spoof", false);

        assertTrue(ProfileMigrations.migrate(profile, null, "test"));
        assertEquals(3.4D, bedBreaker.get("Silent.Range").getAsDouble(), 0.0D);
        assertFalse(bedBreaker.get("Silent.Ground spoof").getAsBoolean());
    }

    @Test
    public void silentSettingsDoNotReadUnqualifiedLegacyKeys() {
        ProfiledSliderSetting range = new ProfiledSliderSetting(new GroupSetting("Silent"), "Range", " block",
                5.5D, 2.0D, 6.0D, 0.1D, "Silent.Range");
        ProfiledButtonSetting ground = new ProfiledButtonSetting(new GroupSetting("Silent"), "Ground spoof",
                true, "Silent.Ground spoof");
        JsonObject legacy = new JsonObject();
        legacy.addProperty("Range", 2.0D);
        legacy.addProperty("Ground spoof", false);
        range.loadProfile(legacy);
        ground.loadProfile(legacy);
        assertEquals(5.5D, range.getInput(), 0.0D);
        assertTrue(ground.isToggled());
    }

    private JsonObject profileWithBedBreaker() {
        JsonObject profile = new JsonObject();
        profile.addProperty("configVersion", 2);
        JsonObject bedBreaker = new JsonObject();
        bedBreaker.addProperty("name", "Bed Breaker");
        JsonArray modules = new JsonArray();
        modules.add(bedBreaker);
        profile.add("modules", modules);
        return profile;
    }
}

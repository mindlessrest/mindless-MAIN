package mindless.utility.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Version stamping and repair for saved profiles.
 *
 * Settings are stored by name, and sliders store a raw number. That is fine until an option list
 * changes: a saved index can then point at an option that no longer exists, or at a different one
 * than it did when it was written, and the profile silently produces the wrong behaviour with no
 * indication anything is stale. Head style did exactly that.
 *
 * Every profile therefore carries a configVersion. On load, a file older than CURRENT_VERSION is
 * copied aside once and then walked through the migrations between its version and this one, in
 * order.
 *
 * Rules for anything added here:
 *
 *   A migration only ever edits the JsonObject it is handed. It must not touch the file, the
 *   module list or any live setting; loading proceeds from whatever it returns.
 *
 *   A migration must tolerate absent, misspelled and wrongly typed fields. Profiles are edited by
 *   hand and half of them will not look the way you expect. Leave anything you do not recognise
 *   alone rather than normalising it away -- an unknown key is far more likely to belong to a
 *   module that is not loaded right now than to be junk.
 *
 *   Never renumber a slider option in place. Append new options to the end of the list and add a
 *   migration that remaps the old numbers, so a profile written by an older build keeps meaning
 *   what it said.
 */
public final class ProfileMigrations {

    /** Bump when a migration is added below. */
    public static final int CURRENT_VERSION = 2;

    private static final String VERSION_KEY = "configVersion";

    private ProfileMigrations() {
    }

    public static int versionOf(JsonObject profile) {
        if (profile == null) {
            return CURRENT_VERSION;
        }
        JsonElement stamp = profile.get(VERSION_KEY);
        if (stamp == null || !stamp.isJsonPrimitive()) {
            // Written before stamping existed.
            return 0;
        }
        try {
            return stamp.getAsInt();
        }
        catch (Exception malformed) {
            return 0;
        }
    }

    public static void stamp(JsonObject profile) {
        if (profile != null) {
            profile.addProperty(VERSION_KEY, CURRENT_VERSION);
        }
    }

    /**
     * Bring a loaded profile up to the current version.
     *
     * @return true when the profile was changed and is worth writing back.
     */
    public static boolean migrate(JsonObject profile, File source, String profileName) {
        if (profile == null) {
            return false;
        }
        int from = versionOf(profile);
        if (from >= CURRENT_VERSION) {
            return false;
        }

        // Keep the file as it was found, once, before touching anything. The ordinary .bak is
        // rewritten on every save and would be overwritten by the first autosave after this.
        preserveOriginal(source, profileName, from);

        for (int version = from; version < CURRENT_VERSION; version++) {
            try {
                applyStep(profile, version);
            }
            catch (Exception failed) {
                // A failed step must not cost the user the rest of the profile. Stop here and
                // load what we have; the stamp is not advanced, so it will be retried next time
                // once the step is fixed.
                System.err.println("[Mindless] profile migration " + version + " -> " + (version + 1)
                        + " failed for " + profileName + ": " + failed);
                return false;
            }
        }

        stamp(profile);
        return true;
    }

    /**
     * One step, taking a profile from {@code version} to {@code version + 1}.
     *
     * Version 0 is any profile written before stamping existed. There is nothing to repair in it:
     * the head style rename was done by swapping which renderer each label draws, not by
     * renumbering the options, so an existing selection already means what its name says. The step
     * exists so those files pick up a stamp and later migrations know where they started.
     */
    private static void applyStep(JsonObject profile, int version) {
        switch (version) {
            case 0:
                break;
            case 1:
                mergeScaffoldModules(profile);
                break;
            default:
                break;
        }
    }

    private static void mergeScaffoldModules(JsonObject profile) {
        JsonElement modulesElement = profile.get("modules");
        if (modulesElement == null || !modulesElement.isJsonArray()) {
            return;
        }
        JsonArray modules = modulesElement.getAsJsonArray();
        JsonObject scaffold = null;
        JsonObject telly = null;
        for (int i = 0; i < modules.size(); i++) {
            JsonElement element = modules.get(i);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject module = element.getAsJsonObject();
            JsonElement name = module.get("name");
            if (name == null || !name.isJsonPrimitive()) {
                continue;
            }
            String moduleName = name.getAsString();
            if ("Scaffold".equalsIgnoreCase(moduleName)) {
                scaffold = module;
            }
            else if ("Test Scaffold".equalsIgnoreCase(moduleName)) {
                telly = module;
            }
        }
        if (telly == null) {
            return;
        }
        boolean tellyEnabled = false;
        try {
            tellyEnabled = telly.has("enabled") && telly.get("enabled").getAsBoolean();
        }
        catch (Exception ignored) {
        }
        if (scaffold == null) {
            scaffold = telly;
            scaffold.addProperty("name", "Scaffold");
            scaffold.addProperty("Mode", 1);
            if (scaffold.has("Keep Y")) {
                scaffold.add("Telly.Keep Y", scaffold.remove("Keep Y"));
            }
        }
        else {
            for (java.util.Map.Entry<String, JsonElement> entry : telly.entrySet()) {
                String key = entry.getKey();
                if ("name".equals(key) || "enabled".equals(key) || "hidden".equals(key)
                        || "keybind".equals(key)) {
                    continue;
                }
                scaffold.add("Keep Y".equals(key) ? "Telly.Keep Y" : key, entry.getValue());
            }
            java.util.Iterator<JsonElement> iterator = modules.iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == telly) {
                    iterator.remove();
                    break;
                }
            }
        }
        if (tellyEnabled) {
            scaffold.addProperty("Mode", 1);
            scaffold.addProperty("enabled", true);
            if (telly.has("hidden")) {
                scaffold.add("hidden", telly.get("hidden"));
            }
            if (telly.has("keybind")) {
                scaffold.add("keybind", telly.get("keybind"));
            }
        }
    }

    private static void preserveOriginal(File source, String profileName, int fromVersion) {
        if (source == null || !source.isFile() || source.length() <= 0L) {
            return;
        }
        try {
            File preserved = new File(source.getParentFile(),
                    profileName + ".v" + fromVersion + ".backup");
            if (preserved.exists()) {
                return;
            }
            Files.copy(source.toPath(), preserved.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception unwritable) {
            // A missing backup is not a reason to refuse the migration; the original is still
            // intact on disk until the next save, and the save path writes through a temp file.
        }
    }
}

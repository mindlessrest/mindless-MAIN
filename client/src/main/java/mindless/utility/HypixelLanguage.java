package mindless.utility;

import net.minecraft.client.Minecraft;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Small dictionary for the Hypixel text that cannot be identified structurally.
 * Callers should prefer packets, click commands, item ids, formatting and numeric
 * shapes first; this is only the fallback for text that the server localizes.
 */
public final class HypixelLanguage {
    public enum Key {
        BED_INTRO, RESPAWNED, RESPAWN_IN, TEAM_SWAP, BED_DESTRUCTION, YOUR_BED,
        GAME_START_ONE, ENDER_CHEST, PERMANENT_BAN, ACCOUNT_BLOCKED,
        TEMPORARY_BAN, TEMPORARY_BLOCKED, FROM_SERVER, WIN, VICTORY,
        MODE, MAP, PURSE, PIGGY, BITS, TIMER_IN, SUDDEN_DEATH, GAME_END, BED,
        UNLOCKED, MAXED, COST, TIER, IRON, GOLD, DIAMOND, EMERALD,
        QUICK_BUY, BLOCKS, MELEE, ARMOR, TOOLS, RANGED, POTIONS, UTILITY,
        ROTATING_ITEMS, UPGRADES_TRAPS
    }

    private static final Map<String, EnumMap<Key, String[]>> LANGUAGES =
            new HashMap<String, EnumMap<Key, String[]>>();

    static {
        add("en",
                Key.BED_INTRO, "protect your bed and destroy the enemy bed",
                "destroy the enemy bed and then eliminate them", "every few seconds brings a new surprise",
                Key.RESPAWNED, "you have respawned",
                Key.RESPAWN_IN, "you will respawn in",
                Key.TEAM_SWAP, "your team swapped and you are now",
                Key.BED_DESTRUCTION, "bed destruction",
                Key.YOUR_BED, "your bed",
                Key.GAME_START_ONE, "the game starts in 1 second",
                Key.ENDER_CHEST, "ender chest",
                Key.PERMANENT_BAN, "permanently banned",
                Key.ACCOUNT_BLOCKED, "account has been blocked",
                Key.TEMPORARY_BAN, "temporarily banned",
                Key.TEMPORARY_BLOCKED, "temporarily blocked",
                Key.FROM_SERVER, "from this server",
                Key.WIN, "you won", Key.VICTORY, "victory",
                Key.MODE, "mode", Key.MAP, "map", Key.PURSE, "purse",
                Key.PIGGY, "piggy", Key.BITS, "bits", Key.TIMER_IN, "in",
                Key.SUDDEN_DEATH, "sudden death", Key.GAME_END, "game end", Key.BED, "bed",
                Key.UNLOCKED, "unlocked", Key.MAXED, "maxed", Key.COST, "cost",
                Key.TIER, "tier", Key.IRON, "iron", Key.GOLD, "gold",
                Key.DIAMOND, "diamond", Key.EMERALD, "emerald",
                Key.QUICK_BUY, "quick buy", Key.BLOCKS, "blocks", Key.MELEE, "melee",
                Key.ARMOR, "armor", Key.TOOLS, "tools", Key.RANGED, "ranged",
                Key.POTIONS, "potions", Key.UTILITY, "utility",
                Key.ROTATING_ITEMS, "rotating items", Key.UPGRADES_TRAPS, "upgrades & traps");

        add("de", Key.BED_INTRO, "beschütze dein bett", Key.RESPAWNED, "du bist wiederbelebt",
                Key.RESPAWN_IN, "du wirst wiederbelebt in", Key.TEAM_SWAP, "dein team wurde getauscht",
                Key.BED_DESTRUCTION, "bettzerstörung", Key.YOUR_BED, "dein bett",
                Key.GAME_START_ONE, "das spiel startet in 1 sekunde", Key.ENDER_CHEST, "endertruhe",
                Key.PERMANENT_BAN, "permanent gebannt", Key.TEMPORARY_BAN, "temporär gebannt",
                Key.WIN, "du hast gewonnen", Key.VICTORY, "sieg", Key.TIMER_IN, "in",
                Key.MODE, "modus", Key.MAP, "karte", Key.PURSE, "geldbörse", Key.PIGGY, "sparschwein",
                Key.BITS, "bits", Key.SUDDEN_DEATH, "plötzlicher tod", Key.GAME_END, "spielende", Key.BED, "bett",
                Key.UNLOCKED, "freigeschaltet", Key.MAXED, "maximal", Key.COST, "kosten",
                Key.TIER, "stufe", Key.IRON, "eisen", Key.GOLD, "gold",
                Key.DIAMOND, "diamant", Key.EMERALD, "smaragd",
                Key.QUICK_BUY, "schnellkauf", Key.BLOCKS, "blöcke", Key.MELEE, "nahkampf",
                Key.ARMOR, "rüstung", Key.TOOLS, "werkzeuge", Key.RANGED, "fernkampf",
                Key.POTIONS, "tränke", Key.UTILITY, "nützliches",
                Key.ROTATING_ITEMS, "rotierende gegenstände", Key.UPGRADES_TRAPS, "aufwertungen & fallen");
        add("fr", Key.BED_INTRO, "protégez votre lit", Key.RESPAWNED, "vous avez réapparu",
                Key.RESPAWN_IN, "vous réapparaîtrez dans", Key.TEAM_SWAP, "votre équipe a changé",
                Key.BED_DESTRUCTION, "destruction de lit", Key.YOUR_BED, "votre lit",
                Key.GAME_START_ONE, "la partie commence dans 1 seconde", Key.ENDER_CHEST, "coffre de l'ender",
                Key.PERMANENT_BAN, "banni définitivement", Key.TEMPORARY_BAN, "banni temporairement",
                Key.WIN, "vous avez gagné", Key.VICTORY, "victoire", Key.TIMER_IN, "dans",
                Key.MODE, "mode", Key.MAP, "carte", Key.PURSE, "bourse", Key.PIGGY, "tirelire",
                Key.BITS, "bits", Key.SUDDEN_DEATH, "mort subite", Key.GAME_END, "fin du jeu", Key.BED, "lit",
                Key.UNLOCKED, "débloqué", Key.MAXED, "maximum", Key.COST, "coût",
                Key.TIER, "niveau", Key.IRON, "fer", Key.GOLD, "or",
                Key.DIAMOND, "diamant", Key.EMERALD, "émeraude",
                Key.QUICK_BUY, "achat rapide", Key.BLOCKS, "blocs", Key.MELEE, "mêlée",
                Key.ARMOR, "armure", Key.TOOLS, "outils", Key.RANGED, "à distance",
                Key.POTIONS, "potions", Key.UTILITY, "utilitaire",
                Key.ROTATING_ITEMS, "objets rotatifs", Key.UPGRADES_TRAPS, "améliorations et pièges");
        add("es", Key.BED_INTRO, "protege tu cama", Key.RESPAWNED, "has reaparecido",
                Key.RESPAWN_IN, "reaparecerás en", Key.TEAM_SWAP, "tu equipo ha cambiado",
                Key.BED_DESTRUCTION, "destrucción de cama", Key.YOUR_BED, "tu cama",
                Key.GAME_START_ONE, "el juego comienza en 1 segundo", Key.ENDER_CHEST, "cofre de ender",
                Key.PERMANENT_BAN, "baneado permanentemente", Key.TEMPORARY_BAN, "baneado temporalmente",
                Key.WIN, "has ganado", Key.VICTORY, "victoria", Key.TIMER_IN, "en",
                Key.MODE, "modo", Key.MAP, "mapa", Key.PURSE, "monedero", Key.PIGGY, "hucha",
                Key.BITS, "bits", Key.SUDDEN_DEATH, "muerte súbita", Key.GAME_END, "fin del juego", Key.BED, "cama",
                Key.UNLOCKED, "desbloqueado", Key.MAXED, "máximo", Key.COST, "coste", "costo",
                Key.TIER, "nivel", Key.IRON, "hierro", Key.GOLD, "oro",
                Key.DIAMOND, "diamante", Key.EMERALD, "esmeralda",
                Key.QUICK_BUY, "compra rápida", Key.BLOCKS, "bloques", Key.MELEE, "cuerpo a cuerpo",
                Key.ARMOR, "armadura", Key.TOOLS, "herramientas", Key.RANGED, "a distancia",
                Key.POTIONS, "pociones", Key.UTILITY, "utilidad",
                Key.ROTATING_ITEMS, "objetos rotativos", Key.UPGRADES_TRAPS, "mejoras y trampas");
        add("pt", Key.BED_INTRO, "proteja sua cama", Key.RESPAWNED, "você renasceu",
                Key.RESPAWN_IN, "você renascerá em", Key.TEAM_SWAP, "sua equipe mudou",
                Key.BED_DESTRUCTION, "destruição de cama", Key.YOUR_BED, "sua cama",
                Key.GAME_START_ONE, "o jogo começa em 1 segundo", Key.ENDER_CHEST, "baú do ender",
                Key.PERMANENT_BAN, "banido permanentemente", Key.TEMPORARY_BAN, "banido temporariamente",
                Key.WIN, "você ganhou", Key.VICTORY, "vitória", Key.TIMER_IN, "em",
                Key.MODE, "modo", Key.MAP, "mapa", Key.PURSE, "bolsa", Key.PIGGY, "cofrinho",
                Key.BITS, "bits", Key.SUDDEN_DEATH, "morte súbita", Key.GAME_END, "fim do jogo", Key.BED, "cama",
                Key.UNLOCKED, "desbloqueado", Key.MAXED, "máximo", Key.COST, "custo",
                Key.TIER, "nível", Key.IRON, "ferro", Key.GOLD, "ouro",
                Key.DIAMOND, "diamante", Key.EMERALD, "esmeralda",
                Key.QUICK_BUY, "compra rápida", Key.BLOCKS, "blocos", Key.MELEE, "corpo a corpo",
                Key.ARMOR, "armadura", Key.TOOLS, "ferramentas", Key.RANGED, "à distância",
                Key.POTIONS, "poções", Key.UTILITY, "utilidades",
                Key.ROTATING_ITEMS, "itens rotativos", Key.UPGRADES_TRAPS, "melhorias e armadilhas");
        add("nl", Key.BED_INTRO, "bescherm je bed", Key.RESPAWNED, "je bent opnieuw gespawned",
                Key.RESPAWN_IN, "je respawnt over", Key.BED_DESTRUCTION, "bed vernietiging",
                Key.YOUR_BED, "jouw bed", Key.ENDER_CHEST, "enderkist", Key.VICTORY, "overwinning",
                Key.TIMER_IN, "over", Key.IRON, "ijzer", Key.GOLD, "goud",
                Key.DIAMOND, "diamant", Key.EMERALD, "smaragd");
        add("it", Key.BED_INTRO, "proteggi il tuo letto", Key.RESPAWNED, "sei rinato",
                Key.RESPAWN_IN, "rinascerai tra", Key.BED_DESTRUCTION, "distruzione del letto",
                Key.YOUR_BED, "il tuo letto", Key.ENDER_CHEST, "cassa dell'ender",
                Key.WIN, "hai vinto", Key.VICTORY, "vittoria", Key.TIMER_IN, "tra",
                Key.IRON, "ferro", Key.GOLD, "oro", Key.DIAMOND, "diamante", Key.EMERALD, "smeraldo");
        add("pl", Key.BED_INTRO, "chroń swoje łóżko", Key.RESPAWNED, "odrodziłeś się",
                Key.RESPAWN_IN, "odrodzisz się za", Key.BED_DESTRUCTION, "zniszczenie łóżka",
                Key.YOUR_BED, "twoje łóżko", Key.ENDER_CHEST, "skrzynia kresu",
                Key.WIN, "wygrałeś", Key.VICTORY, "zwycięstwo", Key.TIMER_IN, "za",
                Key.IRON, "żelazo", Key.GOLD, "złoto", Key.DIAMOND, "diament", Key.EMERALD, "szmaragd");
        add("tr", Key.BED_INTRO, "yatağını koru", Key.RESPAWNED, "yeniden doğdun",
                Key.RESPAWN_IN, "yeniden doğmana", Key.BED_DESTRUCTION, "yatak yıkımı",
                Key.YOUR_BED, "yatağın", Key.ENDER_CHEST, "ender sandığı",
                Key.WIN, "kazandın", Key.VICTORY, "zafer", Key.TIMER_IN, "içinde",
                Key.IRON, "demir", Key.GOLD, "altın", Key.DIAMOND, "elmas", Key.EMERALD, "zümrüt");
        add("ru", Key.BED_INTRO, "защитите свою кровать", Key.RESPAWNED, "вы возродились",
                Key.RESPAWN_IN, "вы возродитесь через", Key.BED_DESTRUCTION, "кровать уничтожена",
                Key.YOUR_BED, "ваша кровать", Key.ENDER_CHEST, "эндер-сундук",
                Key.WIN, "вы победили", Key.VICTORY, "победа", Key.TIMER_IN, "через",
                Key.IRON, "железо", Key.GOLD, "золото", Key.DIAMOND, "алмаз", Key.EMERALD, "изумруд");
    }

    private HypixelLanguage() {}

    private static void add(String language, Object... values) {
        EnumMap<Key, String[]> map = LANGUAGES.get(language);
        if (map == null) {
            map = new EnumMap<Key, String[]>(Key.class);
            LANGUAGES.put(language, map);
        }
        for (int i = 0; i + 1 < values.length;) {
            Key key = (Key) values[i++];
            int start = i;
            while (i < values.length && values[i] instanceof String) i++;
            String[] phrases = new String[i - start];
            for (int j = 0; j < phrases.length; j++) phrases[j] = normalize((String) values[start + j]);
            map.put(key, phrases);
        }
    }

    public static boolean contains(String text, Key key) {
        String normalized = normalize(text);
        for (String phrase : phrases(key)) if (normalized.contains(phrase)) return true;
        return false;
    }

    public static boolean equals(String text, Key key) {
        String normalized = normalize(text);
        for (String phrase : phrases(key)) if (normalized.equals(phrase)) return true;
        return false;
    }

    public static String removeLabel(String text, Key key) {
        if (text == null) return "";
        int colon = text.indexOf(':');
        if (colon < 0) return text;
        String label = text.substring(0, colon);
        return contains(label, key) ? text.substring(colon + 1).trim() : text;
    }

    public static String valueAfterLabel(String text, Key key) {
        if (text == null) return null;
        int colon = text.indexOf(':');
        if (colon < 0 || !contains(text.substring(0, colon), key)) return null;
        return text.substring(colon + 1).trim();
    }

    public static String first(Key key) {
        String[] values = phrases(key);
        return values.length == 0 ? "" : values[0];
    }

    private static String[] phrases(Key key) {
        EnumMap<Key, String[]> current = LANGUAGES.get(language());
        if (current != null && current.containsKey(key)) return current.get(key);
        EnumMap<Key, String[]> english = LANGUAGES.get("en");
        return english.containsKey(key) ? english.get(key) : new String[0];
    }

    private static String language() {
        try {
            String code = Minecraft.getMinecraft().gameSettings.language;
            if (code != null && code.length() >= 2) return code.substring(0, 2).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {}
        return "en";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).trim();
    }
}

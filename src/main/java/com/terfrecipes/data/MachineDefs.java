package com.terfrecipes.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Presentation hints for each machine (display name, icon, meaning of the numeric parameters...).
 * <p>
 * Recipes themselves are never listed here: a machine that appears in the datapack but not in
 * this file still gets a category using {@link #DEFAULT_KEY} settings. The bundled
 * {@code machines.json} can be overridden by {@code config/terf-recipes/machines.json}.
 */
public final class MachineDefs {

    public static final String DEFAULT_KEY = "_default";

    public static final class ParamDef {
        /** Text shown before the value. */
        public String label;
        /** ticks | number | fluid | hidden */
        public String format = "number";
        /** Optional suffix, e.g. "FE/t". */
        public String unit;
    }

    public static final class MachineDef {
        public String name;
        /** Item key used as category icon / catalyst (vanilla id or TERF material id). */
        public String icon;
        /** row | grid */
        public String layout = "row";
        /** item | fluid : how the recipe path keys must be interpreted. */
        public String input = "item";
        /** Number of leading path keys that are a "mode" (e.g. fission_fuel_loader.load) instead of inputs. */
        public int modeKeys = 0;
        /** Parameter giving the amount of the n-th input, e.g. {"a":0,"b":1}. */
        public Map<String, Integer> inputCounts = new LinkedHashMap<>();
        /** Fixed recipe duration in ticks when the datapack hardcodes it (shown in the arrow). */
        public Integer fixedTicks;
        /** Parameter holding the duration in ticks (animates the arrow). */
        public String timeParam;
        /** Keys of the recipe leaf holding output commands. */
        public List<String> outputKeys = List.of("z");
        public Map<String, ParamDef> params = new LinkedHashMap<>();
        /** Free text added to every recipe of this machine. */
        public String note;
        /** Multiblock view: auto (default) | layers | list | hidden. */
        public String structure;
        /** Hides every recipe of this machine (its multiblock page stays). */
        public boolean hideRecipes;
        /** Recipe ids to hide, without the "machine/" prefix ("*" = anything). */
        public List<String> hiddenRecipes = List.of();
    }

    private final Map<String, MachineDef> defs;

    private MachineDefs(Map<String, MachineDef> defs) {
        this.defs = defs;
    }

    public MachineDef get(String machine) {
        MachineDef def = defs.get(machine);
        if (def != null) return def;
        MachineDef fallback = defs.getOrDefault(DEFAULT_KEY, new MachineDef());
        MachineDef copy = new MachineDef();
        copy.icon = fallback.icon;
        copy.params = fallback.params;
        copy.outputKeys = fallback.outputKeys;
        copy.name = prettify(machine);
        return copy;
    }

    public static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.replace(':', '_').replace('.', '_').split("_")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** Loads the bundled defaults, then applies the overrides (machine by machine). */
    public static MachineDefs load(InputStream bundled, Reader override) {
        Gson gson = new Gson();
        Map<String, MachineDef> map = new LinkedHashMap<>();
        if (bundled != null) readInto(gson, new InputStreamReader(bundled, StandardCharsets.UTF_8), map);
        if (override != null) readInto(gson, override, map);
        return new MachineDefs(map);
    }

    private static void readInto(Gson gson, Reader reader, Map<String, MachineDef> map) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
            if (e.getKey().startsWith("//") || !e.getValue().isJsonObject()) continue;
            map.put(e.getKey(), gson.fromJson(e.getValue(), MachineDef.class));
        }
    }
}

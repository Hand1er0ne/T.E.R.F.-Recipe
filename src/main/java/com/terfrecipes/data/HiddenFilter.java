package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules read from {@code config/terf-recipes/hidden.txt}, one per line:
 * <pre>
 * machine:dem                      hide a whole machine tab
 * output:terf:gravity_gun          hide every recipe producing this item / material / fluid
 * input:minecraft:netherite_ingot  hide every recipe using this ingredient
 * recipe:fabricator/*gold*         hide recipes whose id matches (* = anything)
 * item:terf:command_block_staff    hide a custom item from the JEI item list
 * </pre>
 * Keys are compared without the "minecraft:" prefix, case-insensitively.
 */
public final class HiddenFilter {

    public static final String DEFAULT_FILE = """
            # TERF Recipes - recipes / items to hide in JEI (one rule per line, # = comment)
            # Apply changes in game with /terfrecipes reload
            #
            # machine:dem                      -> hides a whole machine tab
            # output:terf:gravity_gun          -> hides recipes producing this item (vanilla id, TERF id or fluid id)
            # input:minecraft:netherite_ingot  -> hides recipes using this ingredient
            # recipe:fabricator/*gold*         -> hides recipes by id (* = anything). The id is shown
            #                                     in the output tooltip with advanced tooltips (F3+H)
            # item:terf:command_block_staff    -> hides a custom item from the JEI item list
            """;

    private final Set<String> machines = new HashSet<>();
    private final Set<String> outputs = new HashSet<>();
    private final Set<String> inputs = new HashSet<>();
    private final Set<String> items = new HashSet<>();
    private final List<Pattern> recipes = new ArrayList<>();
    private final List<String> invalid = new ArrayList<>();

    public static HiddenFilter parse(String text) {
        HiddenFilter f = new HiddenFilter();
        if (text == null) return f;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash).strip();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0 || colon == line.length() - 1) {
                f.invalid.add(raw);
                continue;
            }
            String kind = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            switch (kind) {
                case "machine" -> f.machines.add(norm(value));
                case "output" -> f.outputs.add(norm(value));
                case "input" -> f.inputs.add(norm(value));
                case "item" -> f.items.add(norm(value));
                case "recipe" -> f.recipes.add(glob(value));
                default -> f.invalid.add(raw);
            }
        }
        return f;
    }

    public boolean isEmpty() {
        return machines.isEmpty() && outputs.isEmpty() && inputs.isEmpty() && items.isEmpty() && recipes.isEmpty();
    }

    public List<String> invalidLines() {
        return invalid;
    }

    public boolean hidesMachine(String machine) {
        return machines.contains(norm(machine));
    }

    public boolean hidesItem(String key) {
        return items.contains(norm(key));
    }

    public boolean hidesRecipe(TerfRecipe r) {
        if (hidesMachine(r.machine())) return true;
        String id = r.id().toLowerCase(Locale.ROOT);
        for (Pattern p : recipes) if (p.matcher(id).matches()) return true;
        if (!outputs.isEmpty()) {
            for (TerfRecipe.Output o : r.outputs()) {
                if (o.key() != null && outputs.contains(norm(o.key()))) return true;
            }
        }
        if (!inputs.isEmpty()) {
            for (TerfRecipe.Input in : r.inputs()) {
                if (in.key() != null && inputs.contains(norm(in.key()))) return true;
            }
        }
        return false;
    }

    static String norm(String key) {
        String k = key.strip().toLowerCase(Locale.ROOT);
        return k.startsWith("minecraft:") ? k.substring("minecraft:".length()) : k;
    }

    private static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (char c : g.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString());
    }
}

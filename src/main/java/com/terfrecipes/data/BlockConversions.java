package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Machines that transform a block placed on them instead of using {@code recipes} in the storage
 * (Arc Furnace, Purifier): their functions hold lines like
 * {@code execute if block ~ ~1 ~ cobblestone run return run setblock ~ ~1 ~ magma_block}.
 * Only the functions listed in machines.json ({@code "conversionFunctions"}) are read, since the
 * same pattern is also used for unrelated things (lamps, pipes...).
 */
final class BlockConversions {

    /** if block POS A run [return run] setblock POS B  (same POS) */
    private static final Pattern SETBLOCK = Pattern.compile(
            "if block (\\S+ \\S+ \\S+) ([a-z0-9_:./]+)(?:\\[[^\\]]*\\])? (?:run return run |run )setblock \\1 ([a-z0-9_:./]+)");
    /** if block POS A ... custom_item_summon {count:N,id:"X"} */
    private static final Pattern SUMMON = Pattern.compile(
            "if block \\S+ \\S+ \\S+ ([a-z0-9_:./]+)\\S* .*custom_item_summon \\{count:(\\d+),id:\"([^\"]+)\"\\}");

    private BlockConversions() {
    }

    static Map<String, List<TerfRecipe>> extract(MachineDefs defs, Function<String, String> functionReader) {
        Map<String, List<TerfRecipe>> out = new LinkedHashMap<>();
        if (functionReader == null) return out;
        for (String machine : defs.machines()) {
            MachineDefs.MachineDef def = defs.get(machine);
            if (def.conversionFunctions == null || def.conversionFunctions.isEmpty()) continue;
            List<TerfRecipe> list = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String fn : def.conversionFunctions) {
                String body = functionReader.apply("terf:entity/machines/" + machine + "/" + fn);
                if (body == null) continue;
                for (String line : StorageEmulator.joinContinuations(body)) {
                    TerfRecipe.Output output = null;
                    String input = null;
                    Matcher m = SETBLOCK.matcher(line);
                    if (m.find()) {
                        input = ns(m.group(2));
                        output = new TerfRecipe.Output(TerfRecipe.OutputKind.BLOCK, ns(m.group(3)), 1, null, null, null);
                    } else {
                        Matcher s = SUMMON.matcher(line);
                        if (s.find()) {
                            input = ns(s.group(1));
                            output = new TerfRecipe.Output(TerfRecipe.OutputKind.MATERIAL, s.group(3),
                                    Integer.parseInt(s.group(2)), null, null, null);
                        }
                    }
                    if (input == null || !seen.add(input)) continue;
                    List<String> notes = new ArrayList<>();
                    if (def.note != null && !def.note.isEmpty()) notes.add(def.note);
                    list.add(new TerfRecipe(machine, machine + "/" + input, null,
                            List.of(new TerfRecipe.Input(TerfRecipe.InputKind.ITEM, input, 1, 0)),
                            List.of(output), Map.of(), notes));
                }
            }
            if (!list.isEmpty()) out.put(machine, list);
        }
        return out;
    }

    private static String ns(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }
}

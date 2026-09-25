package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Walks {@code storage terf:constants recipes} and produces {@link TerfRecipe}s.
 * <p>
 * The datapack stores recipes as nested compounds whose keys are the inputs, e.g.
 * {@code recipes.hadron_collider.<itemA>.<itemB> = {z:'<output command>', a:1, b:6, ...}}
 * (the machine reads {@code recipes.$(name).$(a).$(b)} with a macro). So the generic rule is:
 * descend until a compound holding an output command is found; the keys on the way are the inputs.
 * List-based machines (the assembler) store {@code [{items:[{id,count}], z:'...'}]}.
 */
final class RecipeExtractor {

    private static final int MAX_DEPTH = 16;

    private final MachineDefs defs;
    private final OutputParser outputParser;
    private final List<String> errors;

    RecipeExtractor(MachineDefs defs, OutputParser outputParser, List<String> errors) {
        this.defs = defs;
        this.outputParser = outputParser;
        this.errors = errors;
    }

    @SuppressWarnings("unchecked")
    Map<String, List<TerfRecipe>> extract(Object recipesRoot) {
        Map<String, List<TerfRecipe>> out = new LinkedHashMap<>();
        if (!(recipesRoot instanceof Map<?, ?> root)) return out;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root).entrySet()) {
            String machine = e.getKey();
            MachineDefs.MachineDef def = defs.get(machine);
            List<TerfRecipe> list = new ArrayList<>();
            try {
                if (e.getValue() instanceof List<?> l) {
                    int i = 0;
                    for (Object o : l) {
                        if (o instanceof Map<?, ?> m) list.add(fromListEntry(machine, def, (Map<String, Object>) m, i));
                        i++;
                    }
                } else if (e.getValue() instanceof Map<?, ?> m) {
                    walk(machine, def, (Map<String, Object>) m, new ArrayList<>(), list);
                }
            } catch (RuntimeException ex) {
                errors.add("Machine " + machine + ": " + ex);
            }
            if (!list.isEmpty()) out.put(machine, list);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void walk(String machine, MachineDefs.MachineDef def, Map<String, Object> node, List<String> keys,
                      List<TerfRecipe> out) {
        if (keys.size() > MAX_DEPTH) return;
        if (isLeaf(node, def)) {
            try {
                out.add(fromLeaf(machine, def, keys, node));
            } catch (RuntimeException ex) {
                errors.add("Recipe " + machine + "." + String.join(".", keys) + ": " + ex);
            }
            return;
        }
        for (Map.Entry<String, Object> e : node.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> child) {
                keys.add(e.getKey());
                walk(machine, def, (Map<String, Object>) child, keys, out);
                keys.remove(keys.size() - 1);
            }
        }
    }

    private static boolean isLeaf(Map<String, Object> node, MachineDefs.MachineDef def) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            if (!(e.getValue() instanceof String)) continue;
            String k = e.getKey();
            if (def.outputKeys.contains(k) || k.equals("z") || k.chars().allMatch(Character::isDigit)) return true;
        }
        return false;
    }

    private TerfRecipe fromLeaf(String machine, MachineDefs.MachineDef def, List<String> keys, Map<String, Object> leaf) {
        int modeKeys = Math.min(def.modeKeys, keys.size());
        String mode = modeKeys > 0 ? MachineDefs.prettify(String.join(" ", keys.subList(0, modeKeys))) : null;
        List<String> inputKeys = keys.subList(modeKeys, keys.size());

        List<TerfRecipe.Input> inputs = new ArrayList<>();
        if ("fluid".equals(def.input)) {
            // fluid ids contain dots (terf.heavy_water) which the storage path turned into nesting
            inputs.add(new TerfRecipe.Input(TerfRecipe.InputKind.FLUID, String.join(".", inputKeys), 1, 0));
        } else {
            for (int i = 0; i < inputKeys.size(); i++) {
                String k = inputKeys.get(i);
                boolean empty = k.equals("z") || k.isEmpty();
                inputs.add(new TerfRecipe.Input(empty ? TerfRecipe.InputKind.EMPTY : TerfRecipe.InputKind.ITEM,
                        empty ? null : k, 1, i));
            }
        }
        inputs = applyInputCounts(def, inputs, leaf);

        List<TerfRecipe.Output> outputs = parseOutputs(def, leaf);
        Map<String, Snbt.Num> params = collectParams(def, leaf);
        List<String> notes = collectNotes(def, leaf);

        String title = mode;
        String id = machine + "/" + String.join("/", keys);
        return new TerfRecipe(machine, id, title, inputs, outputs, params, notes);
    }

    @SuppressWarnings("unchecked")
    private TerfRecipe fromListEntry(String machine, MachineDefs.MachineDef def, Map<String, Object> entry, int index) {
        List<TerfRecipe.Input> inputs = new ArrayList<>();
        if (entry.get("items") instanceof List<?> items) {
            int slot = 0;
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> item = (Map<String, Object>) m;
                String id = Snbt.asString(item.get("id"));
                Integer count = Snbt.asInt(item.get("count"));
                if (id != null) inputs.add(new TerfRecipe.Input(TerfRecipe.InputKind.ITEM, id, count == null ? 1 : count, slot++));
            }
        }
        List<TerfRecipe.Output> outputs = parseOutputs(def, entry);
        String title = null;
        for (TerfRecipe.Output o : outputs) {
            if (o.kind() == TerfRecipe.OutputKind.SPECIAL) {
                title = o.text();
                break;
            }
        }
        if (title == null && entry.get("name") instanceof String name) {
            title = MachineDefs.prettify(name.replaceAll("_terf\\d+$", ""));
        }
        return new TerfRecipe(machine, machine + "/" + index, title, inputs, outputs,
                collectParams(def, entry), collectNotes(def, entry));
    }

    private static List<TerfRecipe.Input> applyInputCounts(MachineDefs.MachineDef def, List<TerfRecipe.Input> inputs,
                                                           Map<String, Object> leaf) {
        if (def.inputCounts.isEmpty()) return inputs;
        List<TerfRecipe.Input> out = new ArrayList<>(inputs);
        for (Map.Entry<String, Integer> e : def.inputCounts.entrySet()) {
            Integer amount = Snbt.asInt(leaf.get(e.getKey()));
            int idx = e.getValue();
            if (amount == null || idx < 0 || idx >= out.size()) continue;
            TerfRecipe.Input in = out.get(idx);
            out.set(idx, new TerfRecipe.Input(in.kind(), in.key(), Math.max(1, amount), in.slot()));
        }
        return out;
    }

    private List<TerfRecipe.Output> parseOutputs(MachineDefs.MachineDef def, Map<String, Object> leaf) {
        List<String> keys = new ArrayList<>();
        for (String k : def.outputKeys) if (leaf.get(k) instanceof String) keys.add(k);
        if (!keys.contains("z") && leaf.get("z") instanceof String) keys.add("z");
        TreeMap<Integer, String> numeric = new TreeMap<>();
        for (String k : leaf.keySet()) {
            if (!keys.contains(k) && !k.isEmpty() && k.chars().allMatch(Character::isDigit) && leaf.get(k) instanceof String) {
                numeric.put(Integer.parseInt(k), k);
            }
        }
        keys.addAll(numeric.values());
        List<TerfRecipe.Output> out = new ArrayList<>();
        for (String k : keys) out.addAll(outputParser.parse((String) leaf.get(k)));
        return withDisplayItem(out, leaf);
    }

    /**
     * Special outputs (entities...) have no item. When the recipe has a "start" command that
     * summons an item_display (assembler "s"), reuse its item as the icon of the result.
     */
    @SuppressWarnings("unchecked")
    private static List<TerfRecipe.Output> withDisplayItem(List<TerfRecipe.Output> outputs, Map<String, Object> leaf) {
        if (!(leaf.get("s") instanceof String start)) return outputs;
        int idx = start.indexOf("item:{");
        if (idx < 0) return outputs;
        Map<String, Object> item;
        try {
            Object parsed = Snbt.parsePrefix(start, idx + 5).value();
            if (!(parsed instanceof Map<?, ?> m)) return outputs;
            item = (Map<String, Object>) m;
        } catch (RuntimeException e) {
            return outputs;
        }
        String id = Snbt.asString(item.get("id"));
        if (id == null) return outputs;
        Map<String, Object> components = item.get("components") instanceof Map<?, ?> c ? (Map<String, Object>) c : null;
        List<TerfRecipe.Output> result = new ArrayList<>();
        for (TerfRecipe.Output o : outputs) {
            if (o.kind() == TerfRecipe.OutputKind.SPECIAL && o.key() == null) {
                result.add(new TerfRecipe.Output(o.kind(), id, o.count(), components, o.chance(), o.text()));
            } else {
                result.add(o);
            }
        }
        return result;
    }

    private static Map<String, Snbt.Num> collectParams(MachineDefs.MachineDef def, Map<String, Object> leaf) {
        Map<String, Snbt.Num> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : leaf.entrySet()) {
            if (e.getValue() instanceof Snbt.Num n && !def.inputCounts.containsKey(e.getKey())) {
                MachineDefs.ParamDef p = def.params.get(e.getKey());
                if (p != null && "hidden".equals(p.format)) continue;
                params.put(e.getKey(), n);
            }
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    private static List<String> collectNotes(MachineDefs.MachineDef def, Map<String, Object> leaf) {
        List<String> notes = new ArrayList<>();
        // e.g. opencore "operations":[{desc:"MELT",min:1328,max:9999,time:1200},...]
        for (Object value : leaf.values()) {
            if (!(value instanceof List<?> list)) continue;
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && ((Map<String, Object>) m).get("desc") instanceof String desc) {
                    Map<String, Object> step = (Map<String, Object>) m;
                    StringBuilder sb = new StringBuilder(desc);
                    Integer min = Snbt.asInt(step.get("min"));
                    Integer max = Snbt.asInt(step.get("max"));
                    Integer time = Snbt.asInt(step.get("time"));
                    if (min != null && max != null) sb.append(": ").append(min.equals(max) ? min + "" : min + " .. " + max);
                    if (time != null) sb.append(" (").append(Format.ticks(time)).append(')');
                    notes.add(sb.toString());
                }
            }
        }
        if (leaf.get("fail") instanceof List<?> fails && !fails.isEmpty()) {
            notes.add("!Failure is dangerous (explosion / effects)");
        }
        if (def.note != null && !def.note.isEmpty()) notes.add(def.note);
        return notes;
    }
}

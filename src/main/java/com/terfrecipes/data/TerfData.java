package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything the mod knows about the datapack: custom materials and machine recipes.
 * Built by replaying {@code terf:startup} with {@link StorageEmulator} and walking
 * {@code storage terf:constants}.
 */
public final class TerfData {

    public static final String STORAGE = "terf:constants";
    public static final String STARTUP_FUNCTION = "terf:startup";

    /** Default components given by {@code terf:require/custom_item_summon} before merging the material. */
    private static final String BASE_ITEM = "minecraft:recovery_compass";

    private final Map<String, Map<String, Object>> materials;
    private final Map<String, List<TerfRecipe>> recipesByMachine;
    private final List<String> errors;
    private final String sourceDescription;
    private final HiddenFilter hidden;
    private final int hiddenRecipeCount;
    private final List<Multiblock> multiblocks;
    private Map<String, Map<String, Object>> fluids = Map.of();

    private TerfData(Map<String, Map<String, Object>> materials, Map<String, List<TerfRecipe>> recipesByMachine,
                     List<String> errors, String sourceDescription, List<Multiblock> multiblocks) {
        this(materials, recipesByMachine, errors, sourceDescription, HiddenFilter.parse(null), 0, multiblocks);
    }

    private TerfData(Map<String, Map<String, Object>> materials, Map<String, List<TerfRecipe>> recipesByMachine,
                     List<String> errors, String sourceDescription, HiddenFilter hidden, int hiddenRecipeCount,
                     List<Multiblock> multiblocks) {
        this.materials = materials;
        this.recipesByMachine = recipesByMachine;
        this.errors = errors;
        this.sourceDescription = sourceDescription;
        this.hidden = hidden;
        this.hiddenRecipeCount = hiddenRecipeCount;
        this.multiblocks = multiblocks;
    }

    /** Returns a copy without the recipes matched by {@code filter} (materials are kept so ingredients still resolve). */
    public TerfData filtered(HiddenFilter filter) {
        if (filter == null || filter.isEmpty()) return this;
        Map<String, List<TerfRecipe>> kept = new LinkedHashMap<>();
        int removed = 0;
        for (Map.Entry<String, List<TerfRecipe>> e : recipesByMachine.entrySet()) {
            List<TerfRecipe> list = new ArrayList<>();
            for (TerfRecipe r : e.getValue()) {
                if (filter.hidesRecipe(r)) removed++;
                else list.add(r);
            }
            if (!list.isEmpty()) kept.put(e.getKey(), Collections.unmodifiableList(list));
        }
        List<Multiblock> keptStructures = new ArrayList<>();
        for (Multiblock mb : multiblocks) if (!filter.hidesMachine(mb.machine()) && !filter.hidesMachine(mb.id())) keptStructures.add(mb);
        TerfData copy = new TerfData(materials, Collections.unmodifiableMap(kept), errors, sourceDescription, filter,
                hiddenRecipeCount + removed, Collections.unmodifiableList(keptStructures));
        copy.fluids = fluids;
        return copy;
    }

    /** Whether a custom item must stay out of JEI's item list ("item:" rule). */
    public boolean isItemHidden(String materialId) {
        return hidden.hidesItem(materialId);
    }

    public int hiddenRecipeCount() {
        return hiddenRecipeCount;
    }

    public static TerfData empty() {
        return new TerfData(Map.of(), Map.of(), List.of(), "none", List.of());
    }

    /** Material id -> item compound ({id, count, components}) as custom_item_summon would summon it. */
    public Map<String, Map<String, Object>> materials() {
        return materials;
    }

    public Map<String, List<TerfRecipe>> recipesByMachine() {
        return recipesByMachine;
    }

    /**
     * {@code storage terf:constants fluid_dictionary}: fluid id ("terf.hydrogen", "water") ->
     * {name, color_hex, color_dec, chem:{text,color}, temp...}.
     */
    public Map<String, Map<String, Object>> fluids() {
        return fluids;
    }

    /** Multiblock structures, in the order of the datapack's setup table. */
    public List<Multiblock> multiblocks() {
        return multiblocks;
    }

    /** Whether no recipe produces this material (e.g. filled variants). */
    public boolean isObtainedByRecipe(String materialId) {
        for (List<TerfRecipe> list : recipesByMachine.values()) {
            for (TerfRecipe r : list) {
                for (TerfRecipe.Output o : r.outputs()) {
                    if (o.kind() == TerfRecipe.OutputKind.MATERIAL && materialId.equals(o.key())) return true;
                }
            }
        }
        return false;
    }

    public int recipeCount() {
        return recipesByMachine.values().stream().mapToInt(List::size).sum();
    }

    public List<String> errors() {
        return errors;
    }

    public String sourceDescription() {
        return sourceDescription;
    }

    public boolean isEmpty() {
        return recipesByMachine.isEmpty() && materials.isEmpty();
    }

    /** Material keys whose {@code custom_data.tag} equals {@code tag} (e.g. extrusion molds). */
    @SuppressWarnings("unchecked")
    public List<String> materialsWithTag(String tag) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : materials.entrySet()) {
            Object comps = e.getValue().get("components");
            if (comps instanceof Map<?, ?> c && c.get("custom_data") instanceof Map<?, ?> cd
                    && tag.equals(Snbt.asString(((Map<String, Object>) cd).get("tag")))) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ building

    /**
     * @param startupText    content of data/terf/function/startup.mcfunction
     * @param functionReader reads other functions (for random output tables); may return null
     */
    public static TerfData build(String startupText, Function<String, String> functionReader, MachineDefs defs,
                                 String sourceDescription) {
        return build(startupText, functionReader, null, defs, sourceDescription);
    }

    /**
     * @param dataReader reads any datapack file by path ("data/ns/tags/block/x.json"); may be null
     */
    public static TerfData build(String startupText, Function<String, String> functionReader,
                                 Function<String, String> dataReader, MachineDefs defs, String sourceDescription) {
        StorageEmulator emulator = new StorageEmulator();
        emulator.run(startupText);
        Map<String, Object> constants = emulator.storage(STORAGE);
        List<String> errors = new ArrayList<>(emulator.errors());

        Map<String, Map<String, Object>> materials = buildMaterials(constants);
        OutputParser outputs = new OutputParser(functionReader == null ? id -> null : functionReader);
        RecipeExtractor extractor = new RecipeExtractor(defs, outputs, errors);
        Map<String, List<TerfRecipe>> recipes = extractor.extract(constants.get("recipes"));
        recipes = promoteInlineItems(recipes, materials);
        int[] hiddenByDefs = {0};
        recipes = hideFromDefs(recipes, defs, hiddenByDefs);
        List<Multiblock> structures;
        try {
            structures = new StructureExtractor(functionReader, dataReader, defs).extract(constants.get("mb_setup_functions"));
        } catch (RuntimeException e) {
            errors.add("Multiblock structures: " + e);
            structures = List.of();
        }
        TerfData result = new TerfData(Collections.unmodifiableMap(materials), Collections.unmodifiableMap(recipes),
                Collections.unmodifiableList(errors), sourceDescription, HiddenFilter.parse(null), hiddenByDefs[0],
                Collections.unmodifiableList(structures));
        Map<String, Map<String, Object>> fluids = new LinkedHashMap<>();
        collectFluids(constants.get("fluid_dictionary"), "", fluids);
        result.fluids = Collections.unmodifiableMap(fluids);
        return result;
    }

    /**
     * Some custom items are never declared in {@code materials}: their components are written
     * directly in the recipe's summon command (e.g. A.R.T Mount / Turret / Rail). Such an output
     * carrying a {@code custom_data.id} becomes a material too, so it shows in JEI's item list and
     * recipes using it as an ingredient (keyed by that id) resolve.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<TerfRecipe>> promoteInlineItems(Map<String, List<TerfRecipe>> recipes,
                                                                    Map<String, Map<String, Object>> materials) {
        Map<String, List<TerfRecipe>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<TerfRecipe>> e : recipes.entrySet()) {
            List<TerfRecipe> list = new ArrayList<>();
            for (TerfRecipe r : e.getValue()) {
                List<TerfRecipe.Output> outputs = new ArrayList<>();
                boolean changed = false;
                for (TerfRecipe.Output o : r.outputs()) {
                    String customId = null;
                    if (o.kind() == TerfRecipe.OutputKind.ITEM && o.components() != null
                            && o.components().get("custom_data") instanceof Map<?, ?> cd) {
                        customId = Snbt.asString(((Map<String, Object>) cd).get("id"));
                    }
                    if (customId == null || customId.isEmpty()) {
                        outputs.add(o);
                        continue;
                    }
                    if (!materials.containsKey(customId)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", o.key());
                        item.put("count", new Snbt.Num("1"));
                        item.put("components", Snbt.copy(o.components()));
                        materials.put(customId, item);
                    }
                    outputs.add(new TerfRecipe.Output(TerfRecipe.OutputKind.MATERIAL, customId, o.count(), null, o.chance(), o.text()));
                    changed = true;
                }
                list.add(changed ? new TerfRecipe(r.machine(), r.id(), r.title(), r.inputs(), outputs, r.params(), r.notes()) : r);
            }
            out.put(e.getKey(), list);
        }
        return out;
    }

    /** Applies the {@code hideRecipes} / {@code hiddenRecipes} defaults of machines.json. */
    private static Map<String, List<TerfRecipe>> hideFromDefs(Map<String, List<TerfRecipe>> recipes, MachineDefs defs,
                                                              int[] removed) {
        Map<String, List<TerfRecipe>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<TerfRecipe>> e : recipes.entrySet()) {
            MachineDefs.MachineDef def = defs.get(e.getKey());
            if (def.hideRecipes) {
                removed[0] += e.getValue().size();
                continue;
            }
            StringBuilder rules = new StringBuilder();
            if (def.hiddenRecipes != null) {
                for (String g : def.hiddenRecipes) rules.append("recipe:").append(e.getKey()).append('/').append(g).append('\n');
            }
            HiddenFilter filter = HiddenFilter.parse(rules.toString());
            List<TerfRecipe> list = new ArrayList<>();
            for (TerfRecipe r : e.getValue()) {
                if (!filter.isEmpty() && filter.hidesRecipe(r)) removed[0]++;
                else list.add(r);
            }
            if (!list.isEmpty()) out.put(e.getKey(), list);
        }
        return out;
    }

    /**
     * The dictionary is written with paths like {@code fluid_dictionary.terf.heavy_water}, so dotted
     * fluid ids end up nested: walk down until a compound holding a "name".
     */
    @SuppressWarnings("unchecked")
    private static void collectFluids(Object node, String prefix, Map<String, Map<String, Object>> out) {
        if (!(node instanceof Map<?, ?> map)) return;
        Map<String, Object> m = (Map<String, Object>) map;
        if (!prefix.isEmpty() && m.get("name") instanceof String) {
            out.put(prefix, m);
            return;
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            collectFluids(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), out);
        }
    }

    /** Mirrors terf:require/custom_item_summon. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> buildMaterials(Map<String, Object> constants) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!(constants.get("materials") instanceof Map<?, ?> mats)) return out;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) mats).entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> material)) continue;
            String id = e.getKey();
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("rarity", "common");
            Map<String, Object> customData = new LinkedHashMap<>();
            customData.put("id", id);
            components.put("custom_data", customData);
            components.put("item_model", "error");
            components.put("item_name", id);
            Snbt.deepMerge(components, (Map<String, Object>) material);

            Object itemId = components.remove("id");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", itemId == null ? BASE_ITEM : Snbt.asString(itemId));
            item.put("count", new Snbt.Num("1"));
            item.put("components", components);
            out.put(id, item);
        }
        return out;
    }
}

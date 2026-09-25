package com.terfrecipes.client;

import com.terfrecipes.TERFRecipes;
import com.terfrecipes.data.Format;
import com.terfrecipes.data.Multiblock;
import com.terfrecipes.data.Snbt;
import com.terfrecipes.data.TerfData;
import com.terfrecipes.data.TerfRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns datapack keys (vanilla ids, TERF material ids, mold tags, fluid ids) into ItemStacks,
 * building custom items with exactly the components the datapack would give them.
 */
public final class ItemResolver {

    /** custom_data key we put on fluid / special display stacks so JEI can tell them apart. */
    public static final String FLUID_TAG = "terf_recipes_fluid";
    public static final String SPECIAL_TAG = "terf_recipes_special";

    /** Components kept when a full decode fails (e.g. a registry entry missing on this client). */
    private static final Set<String> SAFE_COMPONENTS = Set.of(
            "item_name", "custom_name", "item_model", "custom_model_data", "custom_data", "rarity",
            "max_stack_size", "lore", "enchantment_glint_override", "dyed_color", "max_damage", "damage");

    private static final Map<String, List<ItemStack>> KEY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ItemStack> FLUID_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ItemStack> MATERIAL_CACHE = new ConcurrentHashMap<>();

    private ItemResolver() {
    }

    public static void clearCache() {
        KEY_CACHE.clear();
        FLUID_CACHE.clear();
        MATERIAL_CACHE.clear();
        BLOCK_CACHE.clear();
    }

    // ------------------------------------------------------------------ inputs

    /**
     * Resolves an input key the way the machines compare items:
     * {@code custom_data.id} for TERF items, else the item id. Molds are matched by
     * {@code custom_data.tag}, so a tag key expands to every mold that has it.
     */
    public static List<ItemStack> resolveKey(String key) {
        List<ItemStack> cached = KEY_CACHE.get(key);
        if (cached != null) return cached;
        List<ItemStack> resolved = resolveKeyUncached(key);
        KEY_CACHE.put(key, resolved);
        return resolved;
    }

    private static List<ItemStack> resolveKeyUncached(String key) {
        TerfData data = TerfDataManager.data();
        if (data.materials().containsKey(key)) return List.of(material(key));

        Item item = findItem(key);
        if (item != null) return List.of(new ItemStack(item));

        List<String> tagged = data.materialsWithTag(key);
        if (!tagged.isEmpty()) {
            List<ItemStack> out = new ArrayList<>();
            for (String m : tagged) out.add(material(m));
            return out;
        }
        return List.of(unknown(key));
    }

    public static List<ItemStack> resolveInput(TerfRecipe.Input input) {
        if (input.kind() == TerfRecipe.InputKind.EMPTY) return List.of();
        if (input.kind() == TerfRecipe.InputKind.FLUID) return List.of(fluid(input.key()));
        List<ItemStack> base = resolveKey(input.key());
        if (input.count() <= 1) return base;
        List<ItemStack> out = new ArrayList<>(base.size());
        for (ItemStack s : base) out.add(s.copyWithCount(input.count()));
        return out;
    }

    // ------------------------------------------------------------------ outputs

    public static ItemStack resolveOutput(TerfRecipe.Output output) {
        ItemStack stack = switch (output.kind()) {
            case MATERIAL -> {
                TerfData data = TerfDataManager.data();
                yield data.materials().containsKey(output.key()) ? material(output.key()) : unknown(output.key());
            }
            case ITEM -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", output.key());
                item.put("count", new Snbt.Num("1"));
                if (output.components() != null) item.put("components", output.components());
                ItemStack built = fromItemCompound(item);
                yield built.isEmpty() ? unknown(output.key()) : built;
            }
            case BLOCK -> {
                Item item = findItem(output.key());
                if (item == null) {
                    Identifier id = normalize(output.key());
                    if (id != null) {
                        var block = BuiltInRegistries.BLOCK.getOptional(id);
                        if (block.isPresent() && block.get().asItem() != Items.AIR) item = block.get().asItem();
                    }
                }
                yield item != null ? new ItemStack(item) : unknown(output.key());
            }
            case FLUID -> fluid(output.key());
            case SPECIAL -> special(output);
        };
        if (output.kind() != TerfRecipe.OutputKind.FLUID && output.count() > 1) stack = stack.copyWithCount(output.count());
        return stack;
    }

    // ------------------------------------------------------------------ builders

    /** A TERF custom item, exactly as terf:require/custom_item_summon would summon it. */
    public static ItemStack material(String materialId) {
        ItemStack cached = MATERIAL_CACHE.get(materialId);
        if (cached != null) return cached;
        Map<String, Object> item = TerfDataManager.data().materials().get(materialId);
        ItemStack stack = item == null ? ItemStack.EMPTY : fromItemCompound(item);
        if (stack.isEmpty()) stack = unknown(materialId);
        else addDistinguishingLore(stack, materialId, item);
        MATERIAL_CACHE.put(materialId, stack);
        return stack;
    }

    /**
     * Some TERF items share name and model (e.g. an empty and a filled Electromagnetic Capsule).
     * Adds their capsule contents and id to the tooltip so they can be told apart in JEI.
     */
    @SuppressWarnings("unchecked")
    private static void addDistinguishingLore(ItemStack stack, String materialId, Map<String, Object> item) {
        List<Component> extra = new ArrayList<>();
        if (item.get("components") instanceof Map<?, ?> comps
                && comps.get("custom_data") instanceof Map<?, ?> cd
                && cd.get("terf") instanceof Map<?, ?> terf
                && terf.get("capsule_contents") instanceof List<?> contents) {
            for (Object o : contents) {
                if (!(o instanceof Map<?, ?> c)) continue;
                String fluid = Snbt.asString(((Map<String, Object>) c).get("id"));
                Integer amount = Snbt.asInt(((Map<String, Object>) c).get("amount"));
                Integer max = Snbt.asInt(((Map<String, Object>) c).get("max"));
                if (fluid == null || "empty".equals(fluid) || amount == null || amount == 0) {
                    extra.add(Component.literal("Empty" + (max != null ? " (capacity " + max + ")" : "")).withStyle(ChatFormatting.GRAY));
                } else {
                    extra.add(Component.literal(Format.fluidName(fluid) + ": " + amount + (max != null ? "/" + max : ""))
                            .withStyle(ChatFormatting.AQUA));
                }
            }
        }
        if (sharesName(materialId)) {
            extra.add(Component.literal(materialId).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (extra.isEmpty()) return;
        ItemLore lore = stack.get(DataComponents.LORE);
        List<Component> lines = new ArrayList<>(lore == null ? List.of() : lore.lines());
        lines.addAll(extra);
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    /** True when another custom item has the same item_name. */
    @SuppressWarnings("unchecked")
    private static boolean sharesName(String materialId) {
        Map<String, Map<String, Object>> mats = TerfDataManager.data().materials();
        Object name = nameOf(mats.get(materialId));
        if (name == null) return false;
        for (Map.Entry<String, Map<String, Object>> e : mats.entrySet()) {
            if (!e.getKey().equals(materialId) && name.equals(nameOf(e.getValue()))) return true;
        }
        return false;
    }

    private static Object nameOf(Map<String, Object> item) {
        return item != null && item.get("components") instanceof Map<?, ?> c ? c.get("item_name") : null;
    }

    /** All custom items of the datapack (added to JEI's item list). */
    public static List<ItemStack> allMaterials() {
        List<ItemStack> out = new ArrayList<>();
        TerfData data = TerfDataManager.data();
        for (String id : data.materials().keySet()) {
            if (!data.isItemHidden(id)) out.add(material(id));
        }
        return out;
    }

    public static ItemStack fluid(String fluidId) {
        return FLUID_CACHE.computeIfAbsent(fluidId, id -> { // no nested cache access inside
            Item base = switch (id) {
                case "water", "minecraft:water" -> Items.WATER_BUCKET;
                case "lava", "minecraft:lava" -> Items.LAVA_BUCKET;
                default -> Items.BUCKET;
            };
            ItemStack stack = new ItemStack(base);
            stack.set(DataComponents.ITEM_NAME, Component.literal(Format.fluidName(id)).withStyle(ChatFormatting.AQUA));
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("Fluid: " + id).withStyle(ChatFormatting.DARK_GRAY))));
            CompoundTag tag = new CompoundTag();
            tag.putString(FLUID_TAG, id);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            return stack;
        });
    }

    private static ItemStack special(TerfRecipe.Output output) {
        ItemStack stack = ItemStack.EMPTY;
        if (output.key() != null) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", output.key());
            item.put("count", new Snbt.Num("1"));
            if (output.components() != null) item.put("components", output.components());
            stack = fromItemCompound(item);
        }
        if (stack.isEmpty()) stack = new ItemStack(Items.NETHER_STAR);
        stack.set(DataComponents.ITEM_NAME, Component.literal(output.text()).withStyle(ChatFormatting.LIGHT_PURPLE));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Not an item (entity / effect)").withStyle(ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putString(SPECIAL_TAG, output.text());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static ItemStack unknown(String key) {
        ItemStack stack = new ItemStack(Items.BARRIER);
        stack.set(DataComponents.ITEM_NAME, Component.literal(key).withStyle(ChatFormatting.RED));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Unknown item in the TERF datapack").withStyle(ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putString(SPECIAL_TAG, "unknown:" + key);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /**
     * Decodes {@code {id:..,count:..,components:{..}}} with the vanilla item codec. Falls back to
     * a reduced component set, then to the bare item, so a single bad component never hides a recipe.
     */
    @SuppressWarnings("unchecked")
    public static ItemStack fromItemCompound(Map<String, Object> item) {
        Map<String, Object> copy = (Map<String, Object>) Snbt.copy(item);
        Identifier id = normalize(Snbt.asString(copy.get("id")));
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return ItemStack.EMPTY;
        copy.put("id", id.toString());
        copy.put("count", new Snbt.Num("1")); // real count applied later (codec rejects count > max stack size)

        ItemStack full = decode(copy);
        if (!full.isEmpty()) return full;

        if (copy.get("components") instanceof Map<?, ?> comps) {
            Map<String, Object> safe = new LinkedHashMap<>();
            ((Map<String, Object>) comps).forEach((k, v) -> {
                String plain = k.startsWith("minecraft:") ? k.substring(10) : k;
                if (SAFE_COMPONENTS.contains(plain)) safe.put(k, v);
            });
            copy.put("components", safe);
            ItemStack reduced = decode(copy);
            if (!reduced.isEmpty()) return reduced;
            copy.remove("components");
        }
        ItemStack bare = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        if (item.get("components") instanceof Map<?, ?> comps && comps.get("item_name") instanceof String name) {
            bare.set(DataComponents.ITEM_NAME, Component.literal(name));
        }
        return bare;
    }

    private static ItemStack decode(Map<String, Object> item) {
        String snbt = Snbt.write(item);
        try {
            CompoundTag tag = TagParser.parseCompoundFully(snbt);
            RegistryOps<Tag> ops = registries().createSerializationContext(NbtOps.INSTANCE);
            return ItemStack.CODEC.parse(ops, tag)
                    .resultOrPartial(err -> TERFRecipes.LOGGER.debug("[TERF Recipes] {} -> {}", snbt, err))
                    .orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            TERFRecipes.LOGGER.debug("[TERF Recipes] Could not decode {}", snbt, e);
            return ItemStack.EMPTY;
        }
    }

    private static HolderLookup.Provider registries() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) return mc.level.registryAccess();
        if (mc.getConnection() != null) return mc.getConnection().registryAccess();
        IntegratedServerHolder holder = new IntegratedServerHolder(mc);
        return holder.access();
    }

    /** Last resort before joining a world: the integrated server's registries, else only built-ins. */
    private record IntegratedServerHolder(Minecraft mc) {
        HolderLookup.Provider access() {
            if (mc.getSingleplayerServer() != null) return mc.getSingleplayerServer().registryAccess();
            return net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        }
    }

    public static Identifier normalize(String id) {
        if (id == null || id.isEmpty()) return null;
        return Identifier.tryParse(id.toLowerCase());
    }

    private static Item findItem(String key) {
        Identifier id = normalize(key);
        if (id == null) return null;
        return BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR).orElse(null);
    }

    // ------------------------------------------------------------------ blocks (multiblock structures)

    private static final Map<String, List<ItemStack>> BLOCK_CACHE = new ConcurrentHashMap<>();

    /** Items representing a block predicate of a structure (a tag gives several items). */
    public static List<ItemStack> blockStacks(Multiblock.BlockSpec spec) {
        List<ItemStack> cached = BLOCK_CACHE.get(spec.raw());
        if (cached != null) return cached;
        List<ItemStack> out = new ArrayList<>();
        for (String alt : spec.alternatives()) {
            if (alt.startsWith("#")) {
                Identifier tagId = normalize(alt.substring(1));
                if (tagId == null) continue;
                var key = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, tagId);
                for (var holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) addBlockItem(holder.value(), out);
            } else {
                Identifier id = normalize(alt);
                if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b -> addBlockItem(b, out));
            }
        }
        if (out.isEmpty()) out.add(unknown(spec.raw()));
        List<ItemStack> result = List.copyOf(out);
        BLOCK_CACHE.put(spec.raw(), result);
        return result;
    }

    private static void addBlockItem(net.minecraft.world.level.block.Block block, List<ItemStack> out) {
        Item item = block.asItem();
        if (item == Items.AIR) {
            // blocks without item form
            if (block == net.minecraft.world.level.block.Blocks.WATER) item = Items.WATER_BUCKET;
            else if (block == net.minecraft.world.level.block.Blocks.LAVA) item = Items.LAVA_BUCKET;
            else if (block == net.minecraft.world.level.block.Blocks.FIRE) item = Items.FLINT_AND_STEEL;
            else if (block == net.minecraft.world.level.block.Blocks.WATER_CAULDRON
                    || block == net.minecraft.world.level.block.Blocks.LAVA_CAULDRON) item = Items.CAULDRON;
            else return;
        }
        for (ItemStack s : out) if (s.getItem() == item) return;
        out.add(new ItemStack(item));
    }

    /** Subtype used by JEI to tell TERF items apart: custom_data id / fluid / special marker. */
    public static String subtypeOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return null;
        CompoundTag tag = data.copyTag();
        return tag.getString("id")
                .or(() -> tag.getString(FLUID_TAG).map(s -> "fluid:" + s))
                .or(() -> tag.getString(SPECIAL_TAG).map(s -> "special:" + s))
                .orElse(null);
    }
}

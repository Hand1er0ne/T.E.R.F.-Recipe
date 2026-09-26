package com.terfrecipes.client.jei;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.terfrecipes.TERFRecipes;
import com.terfrecipes.client.ItemResolver;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The datapack's own crafting-table / furnace / stonecutter recipes ({@code data/*&#47;recipe/*.json}).
 * <p>
 * In singleplayer JEI reads them from the world. On a server it only sees what the server sends,
 * which is not the datapack's recipes, so the mod parses the files itself and adds them to JEI's
 * vanilla tabs. Files that replace a vanilla recipe by a barrier (the datapack's way to disable a
 * recipe, e.g. granite slabs) hide that vanilla recipe instead.
 */
final class DatapackVanillaRecipes {

    private static final List<IRecipeHolderType<?>> TYPES = List.of(RecipeTypes.CRAFTING, RecipeTypes.SMELTING,
            RecipeTypes.BLASTING, RecipeTypes.SMOKING, RecipeTypes.CAMPFIRE_COOKING, RecipeTypes.STONECUTTING);

    /** Vanilla recipe ids replaced or disabled by the datapack (hidden once JEI is running). */
    private static final Set<Identifier> overridden = new HashSet<>();

    private DatapackVanillaRecipes() {
    }

    static void register(IRecipeRegistration registration, Map<String, String> files) {
        overridden.clear();
        if (files.isEmpty()) return;
        RegistryOps<JsonElement> ops = ItemResolver.registries().createSerializationContext(JsonOps.INSTANCE);
        List<RecipeHolder<?>> holders = new ArrayList<>();
        int failed = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            Identifier id = idOf(e.getKey());
            if (id == null) continue;
            try {
                JsonElement json = JsonParser.parseString(e.getValue());
                if (disablesRecipe(json)) {
                    overridden.add(id);
                    continue;
                }
                Recipe<?> recipe = Recipe.DIRECT_CODEC.parse(ops, json).getOrThrow();
                // a file under data/minecraft replaces the vanilla recipe of the same id: hide that
                // one and show ours under another id (JEI tells recipes apart by id)
                Identifier shownId = id;
                if (id.getNamespace().equals("minecraft")) {
                    overridden.add(id);
                    shownId = TERFRecipes.id("datapack/" + id.getPath());
                }
                holders.add(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, shownId), recipe));
            } catch (RuntimeException ex) {
                failed++;
                TERFRecipes.LOGGER.debug("[TERF Recipes] Could not read recipe {}: {}", e.getKey(), ex.toString());
            }
        }
        for (IRecipeHolderType<?> type : TYPES) addOfType(registration, type, holders);
        TERFRecipes.LOGGER.info("[TERF Recipes] {} datapack crafting recipes added to JEI ({} unreadable, {} vanilla recipes replaced)",
                holders.size(), failed, overridden.size());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Recipe<?>> void addOfType(IRecipeRegistration registration, IRecipeHolderType<T> type,
                                                       List<RecipeHolder<?>> holders) {
        List<RecipeHolder<T>> list = new ArrayList<>();
        for (RecipeHolder<?> h : holders) {
            if (type.getUid().equals(net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(h.value().getType()))) list.add((RecipeHolder<T>) h);
        }
        if (!list.isEmpty()) registration.addRecipes(type, list);
    }

    /** Hides the vanilla recipes the datapack disabled or replaced. */
    static void hideOverridden(IJeiRuntime runtime) {
        if (overridden.isEmpty()) return;
        for (IRecipeHolderType<?> type : TYPES) hide(runtime, type);
    }

    private static <T extends Recipe<?>> void hide(IJeiRuntime runtime, IRecipeHolderType<T> type) {
        try {
            List<RecipeHolder<T>> toHide = runtime.getRecipeManager().createRecipeLookup(type).get()
                    .filter(h -> overridden.contains(h.id().identifier()))
                    .toList();
            if (!toHide.isEmpty()) runtime.getRecipeManager().hideRecipes(type, toHide);
        } catch (RuntimeException e) {
            TERFRecipes.LOGGER.debug("[TERF Recipes] Could not hide replaced vanilla recipes", e);
        }
    }

    /** {@code {"result": {"id": "minecraft:barrier"}}}: the datapack's way to turn a vanilla recipe off. */
    private static boolean disablesRecipe(JsonElement json) {
        if (!json.isJsonObject()) return false;
        JsonObject o = json.getAsJsonObject();
        if (!(o.get("result") instanceof JsonObject result) || !result.has("id")) return false;
        String id = result.get("id").getAsString();
        return id.equals("minecraft:barrier") || id.equals("barrier");
    }

    /** "data/terf/recipe/blasting/x.json" -> terf:blasting/x */
    private static Identifier idOf(String path) {
        String[] parts = path.split("/", 4);
        if (parts.length < 4 || !parts[2].equals("recipe") || !parts[3].endsWith(".json")) return null;
        return Identifier.tryBuild(parts[1], parts[3].substring(0, parts[3].length() - 5));
    }
}

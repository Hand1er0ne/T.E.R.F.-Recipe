package com.terfrecipes.client.jei;

import com.terfrecipes.TERFRecipes;
import com.terfrecipes.client.ItemResolver;
import com.terfrecipes.client.TerfDataManager;
import com.terfrecipes.data.Multiblock;
import com.terfrecipes.data.TerfData;
import com.terfrecipes.data.TerfRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JEI entry point (declared as the {@code jei_mod_plugin} entrypoint in fabric.mod.json).
 * JEI calls these methods every time it starts (i.e. when joining a world/server), and the first
 * one reloads the datapack so the categories always match the current world.
 */
public class TerfJeiPlugin implements IModPlugin {

    private static final Identifier UID = TERFRecipes.id("jei_plugin");

    /** Machine -> recipe type registered during the current JEI session. */
    private static final Map<String, IRecipeType<TerfRecipe>> TYPES = new LinkedHashMap<>();
    /** Recipes currently shown, per machine (used to swap them on /terfrecipes reload). */
    private static final Map<String, List<TerfRecipe>> SHOWN = new LinkedHashMap<>();
    private static @Nullable IJeiRuntime runtime;
    /** Multiblock pages currently shown. */
    private static List<MultiblockCategory.Page> shownPages = List.of();
    private static boolean multiblockCategoryRegistered;

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    public static IRecipeType<TerfRecipe> typeFor(String machine) {
        return TYPES.computeIfAbsent(machine, m -> IRecipeType.create(TERFRecipes.MOD_ID,
                m.toLowerCase().replaceAll("[^a-z0-9/._-]", "_"), TerfRecipe.class));
    }

    // ------------------------------------------------------------------ registration

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        TerfDataManager.reload();
        TYPES.clear();
        SHOWN.clear();
        shownPages = List.of();
        multiblockCategoryRegistered = false;

        // TERF items are vanilla items (recovery_compass, carrot_on_a_stick...) told apart by custom_data.id
        Set<Item> bases = new LinkedHashSet<>(List.of(Items.RECOVERY_COMPASS, Items.CARROT_ON_A_STICK,
                Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.BARRIER, Items.NETHER_STAR));
        for (ItemStack stack : ItemResolver.allMaterials()) bases.add(stack.getItem());
        for (Item item : bases) {
            try {
                registration.registerSubtypeInterpreter(item, (stack, context) -> ItemResolver.subtypeOf(stack));
            } catch (RuntimeException e) {
                // another plugin already owns this item's subtypes; custom items will share its entry
                TERFRecipes.LOGGER.debug("[TERF Recipes] Could not register subtypes for {}", item, e);
            }
        }
    }

    @Override
    public void registerExtraIngredients(IExtraIngredientRegistration registration) {
        List<ItemStack> extra = new ArrayList<>(ItemResolver.allMaterials());
        registration.addExtraItemStacks(extra);
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper gui = registration.getJeiHelpers().getGuiHelper();
        TerfData data = TerfDataManager.data();
        for (Map.Entry<String, List<TerfRecipe>> e : data.recipesByMachine().entrySet()) {
            String machine = e.getKey();
            registration.addRecipeCategories(new MachineCategory(machine, TerfDataManager.defs().get(machine),
                    typeFor(machine), e.getValue(), gui));
        }
        if (!data.multiblocks().isEmpty()) {
            registration.addRecipeCategories(new MultiblockCategory(data.multiblocks(), gui));
            multiblockCategoryRegistered = true;
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        for (Map.Entry<String, List<TerfRecipe>> e : TerfDataManager.data().recipesByMachine().entrySet()) {
            List<TerfRecipe> recipes = new ArrayList<>(e.getValue());
            registration.addRecipes(typeFor(e.getKey()), recipes);
            SHOWN.put(e.getKey(), recipes);
        }
        if (multiblockCategoryRegistered) {
            shownPages = MultiblockCategory.pages(TerfDataManager.data().multiblocks());
            registration.addRecipes(MultiblockCategory.TYPE, new ArrayList<>(shownPages));
        }

        // info page for custom items that no machine recipe produces (filled capsules, loot...)
        TerfData data = TerfDataManager.data();
        for (String id : data.materials().keySet()) {
            if (data.isItemHidden(id) || data.isObtainedByRecipe(id)) continue;
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("No TERF machine recipe produces this item."));
            String base = variantBase(data, id);
            if (base != null) {
                lines.add(Component.literal("It is a variant of " + ItemResolver.material(base).getHoverName().getString()
                        + " (" + base + "), obtained by using or filling it in game.").withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.literal("It is obtained another way (vanilla crafting, loot, events, commands...).")
                        .withStyle(ChatFormatting.GRAY));
            }
            registration.addItemStackInfo(ItemResolver.material(id), lines.toArray(Component[]::new));
        }
    }

    /** "terf:electromagnetic_capsule_full" -> "terf:electromagnetic_capsule" when that item exists. */
    private static @Nullable String variantBase(TerfData data, String id) {
        String best = null;
        for (String other : data.materials().keySet()) {
            if (!other.equals(id) && id.startsWith(other + "_") && (best == null || other.length() > best.length())) best = other;
        }
        return best;
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        TerfData data = TerfDataManager.data();
        ItemStack core = data.materials().containsKey("terf:multiblock_core")
                ? ItemResolver.material("terf:multiblock_core") : ItemStack.EMPTY;
        for (String machine : data.recipesByMachine().keySet()) {
            ItemStack station = !core.isEmpty() ? core : MachineCategory.iconStack(TerfDataManager.defs().get(machine));
            if (!station.isEmpty()) registration.addCraftingStation(typeFor(machine), station);
        }
        if (multiblockCategoryRegistered && !core.isEmpty()) {
            registration.addCraftingStation(MultiblockCategory.TYPE, core);
        }
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    // ------------------------------------------------------------------ live reload

    /**
     * Swaps the displayed recipes after {@code /terfrecipes reload}. Recipes of machines that
     * did not exist when JEI started cannot get a category until JEI restarts (rejoin).
     *
     * @return number of machines that need a rejoin to be shown
     */
    public static int applyReload(TerfData data) {
        IJeiRuntime rt = runtime;
        if (rt == null) return 0;
        int missing = 0;
        for (Map.Entry<String, List<TerfRecipe>> e : SHOWN.entrySet()) {
            rt.getRecipeManager().hideRecipes(TYPES.get(e.getKey()), e.getValue());
        }
        Map<String, List<TerfRecipe>> nowShown = new LinkedHashMap<>();
        for (Map.Entry<String, List<TerfRecipe>> e : data.recipesByMachine().entrySet()) {
            IRecipeType<TerfRecipe> type = TYPES.get(e.getKey());
            if (type == null) {
                missing++;
                continue;
            }
            List<TerfRecipe> recipes = new ArrayList<>(e.getValue());
            rt.getRecipeManager().addRecipes(type, recipes);
            nowShown.put(e.getKey(), recipes);
        }
        // machines emptied by hidden.txt lose their tab, machines back in the data get it again
        for (Map.Entry<String, IRecipeType<TerfRecipe>> e : TYPES.entrySet()) {
            if (nowShown.containsKey(e.getKey())) rt.getRecipeManager().unhideRecipeCategory(e.getValue());
            else rt.getRecipeManager().hideRecipeCategory(e.getValue());
        }
        SHOWN.clear();
        SHOWN.putAll(nowShown);

        if (multiblockCategoryRegistered) {
            if (!shownPages.isEmpty()) rt.getRecipeManager().hideRecipes(MultiblockCategory.TYPE, shownPages);
            List<Multiblock> structures = data.multiblocks();
            shownPages = MultiblockCategory.pages(structures);
            if (!shownPages.isEmpty()) rt.getRecipeManager().addRecipes(MultiblockCategory.TYPE, new ArrayList<>(shownPages));
        }
        return missing;
    }

    public static boolean isRuntimeAvailable() {
        return runtime != null;
    }
}

package com.terfrecipes.client.jei;

import com.terfrecipes.client.ItemResolver;
import com.terfrecipes.data.TerfRecipe;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JEI "+" button for the Fabricator: fills the Crafter's 3x3 grid from the player's inventory.
 * <p>
 * JEI's built-in transfer needs JEI on the server. This one only does what the player could do
 * by hand (vanilla inventory clicks), so it works on any server.
 */
final class CrafterTransferHandler implements IRecipeTransferHandler<CrafterMenu, TerfRecipe> {

    private static final int GRID = 9;
    private static final int INV_START = 9;
    private static final int INV_END = 45; // exclusive (45 = result slot)

    private final IRecipeType<TerfRecipe> type;
    private final IRecipeTransferHandlerHelper helper;

    CrafterTransferHandler(IRecipeType<TerfRecipe> type, IRecipeTransferHandlerHelper helper) {
        this.type = type;
        this.helper = helper;
    }

    @Override
    public Class<? extends CrafterMenu> getContainerClass() {
        return CrafterMenu.class;
    }

    @Override
    public Optional<MenuType<CrafterMenu>> getMenuType() {
        return Optional.of(MenuType.CRAFTER_3x3);
    }

    @Override
    public IRecipeType<TerfRecipe> getRecipeType() {
        return type;
    }

    @Override
    @SuppressWarnings("removal")
    public @Nullable IRecipeTransferError transferRecipe(CrafterMenu menu, TerfRecipe recipe, IRecipeSlotsView slots, Player player,
                                                         boolean maxTransfer, boolean doTransfer) {
        return transfer(menu, recipe, slots, player, maxTransfer, doTransfer);
    }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(IRecipeTransferContext<TerfRecipe, CrafterMenu> context, boolean doTransfer) {
        IRecipeTransferError error = transfer(context.getContainer(), context.getRecipe(), context.getRecipeSlots(),
                context.getPlayer(), context.isMaxTransfer(), doTransfer);
        if (doTransfer) context.completeRecipeTransfer(error == null ? RecipeTransferResult.SUCCESS : RecipeTransferResult.REJECTED);
        return error;
    }

    /** Item + TERF id: two custom items on the same vanilla item are different ingredients. */
    private static String key(ItemStack stack) {
        String sub = ItemResolver.subtypeOf(stack);
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + (sub == null ? "" : sub);
    }

    private @Nullable IRecipeTransferError transfer(CrafterMenu menu, TerfRecipe recipe, IRecipeSlotsView slots, Player player,
                                                    boolean maxTransfer, boolean doTransfer) {
        // what the player has (the grid is emptied first, so its items count too)
        Map<String, Integer> available = new HashMap<>();
        Map<String, Integer> maxStack = new HashMap<>();
        for (int i = 0; i < INV_END && i < menu.slots.size(); i++) {
            ItemStack s = menu.slots.get(i).getItem();
            if (s.isEmpty()) continue;
            available.merge(key(s), s.getCount(), Integer::sum);
            maxStack.put(key(s), s.getMaxStackSize());
        }

        // one ingredient per grid slot
        String[] wanted = new String[GRID];
        int[] perSet = new int[GRID];
        Map<String, Integer> needed = new HashMap<>();
        List<IRecipeSlotView> inputViews = slots.getSlotViews(RecipeIngredientRole.INPUT);
        List<IRecipeSlotView> missing = new ArrayList<>();
        Map<String, Integer> left = new HashMap<>(available);
        for (int i = 0; i < recipe.inputs().size(); i++) {
            TerfRecipe.Input in = recipe.inputs().get(i);
            if (in.kind() != TerfRecipe.InputKind.ITEM || in.slot() < 0 || in.slot() >= GRID) continue;
            int count = Math.max(1, in.count());
            String chosen = null;
            for (ItemStack option : ItemResolver.resolveInput(in)) {
                String k = key(option);
                if (left.getOrDefault(k, 0) >= count) {
                    chosen = k;
                    break;
                }
            }
            if (chosen == null) {
                if (i < inputViews.size()) missing.add(inputViews.get(i));
                continue;
            }
            left.merge(chosen, -count, Integer::sum);
            wanted[in.slot()] = chosen;
            perSet[in.slot()] = count;
            needed.merge(chosen, count, Integer::sum);
        }
        if (!missing.isEmpty()) {
            return helper.createUserErrorForMissingSlots(Component.literal("Missing ingredients"), missing);
        }
        for (int g = 0; g < GRID; g++) {
            if (wanted[g] != null && menu.isSlotDisabled(g)) {
                return helper.createUserErrorWithTooltip(Component.literal("A slot of the Crafter is disabled"));
            }
        }

        int sets = 1;
        if (maxTransfer) {
            sets = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : needed.entrySet()) {
                int perSlot = 0;
                for (int g = 0; g < GRID; g++) if (e.getKey().equals(wanted[g])) perSlot = Math.max(perSlot, perSet[g]);
                sets = Math.min(sets, available.get(e.getKey()) / e.getValue());
                sets = Math.min(sets, maxStack.getOrDefault(e.getKey(), 64) / Math.max(1, perSlot));
            }
            sets = Math.max(1, sets);
        }
        if (!doTransfer) return null;

        Minecraft mc = Minecraft.getInstance();
        MultiPlayerGameMode gm = mc.gameMode;
        if (gm == null) return helper.createInternalError();
        int id = menu.containerId;

        // 1. empty the grid into the inventory
        for (int g = 0; g < GRID; g++) {
            if (!menu.slots.get(g).getItem().isEmpty()) gm.handleContainerInput(id, g, 0, ContainerInput.QUICK_MOVE, player);
        }
        // 2. fill each grid slot from the inventory, like a player would with the mouse
        for (int g = 0; g < GRID; g++) {
            if (wanted[g] == null) continue;
            int remaining = perSet[g] * sets;
            for (int j = INV_START; j < INV_END && remaining > 0 && j < menu.slots.size(); j++) {
                ItemStack source = menu.slots.get(j).getItem();
                if (source.isEmpty() || !key(source).equals(wanted[g])) continue;
                gm.handleContainerInput(id, j, 0, ContainerInput.PICKUP, player); // take the stack
                int carried = menu.getCarried().getCount();
                if (carried <= remaining) {
                    gm.handleContainerInput(id, g, 0, ContainerInput.PICKUP, player); // drop it all
                    remaining -= carried;
                } else {
                    for (int k = 0; k < remaining; k++) gm.handleContainerInput(id, g, 1, ContainerInput.PICKUP, player); // one by one
                    remaining = 0;
                }
                if (!menu.getCarried().isEmpty()) gm.handleContainerInput(id, j, 0, ContainerInput.PICKUP, player); // put the rest back
            }
        }
        return null;
    }
}

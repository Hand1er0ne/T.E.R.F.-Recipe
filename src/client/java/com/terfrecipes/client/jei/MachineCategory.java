package com.terfrecipes.client.jei;

import com.terfrecipes.TERFRecipes;
import com.terfrecipes.client.ItemResolver;
import com.terfrecipes.data.Format;
import com.terfrecipes.data.MachineDefs;
import com.terfrecipes.data.Snbt;
import com.terfrecipes.data.TerfRecipe;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One JEI category per TERF machine. The layout is computed from the recipes themselves
 * (number of inputs / outputs, lines of text), so new machines or bigger recipes added to the
 * datapack are displayed without code changes.
 */
public class MachineCategory implements IRecipeCategory<TerfRecipe> {

    private static final int SLOT = 18;
    private static final int ARROW_W = 24;
    private static final int ARROW_H = 17;
    private static final int GAP = 6;
    private static final int LINE_H = 10;
    private static final int MIN_WIDTH = 150;
    private static final int MAX_COLUMNS = 4;
    private static final int TEXT_COLOR = 0xFF404040;

    private final String machine;
    private final MachineDefs.MachineDef def;
    private final IRecipeType<TerfRecipe> type;
    private final IDrawable icon;

    // layout
    private final boolean grid;
    private final int inCols;
    private final int outCols;
    private final int arrowX;
    private final int outX;
    private final int slotsHeight;
    private final int width;
    private final int height;

    public MachineCategory(String machine, MachineDefs.MachineDef def, IRecipeType<TerfRecipe> type,
                           List<TerfRecipe> recipes, IGuiHelper guiHelper) {
        this.machine = machine;
        this.def = def;
        this.type = type;
        this.icon = guiHelper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack(machine, def));
        this.grid = "grid".equals(def.layout);

        int maxIn = 1;
        int maxOut = 1;
        int maxLines = 0;
        for (TerfRecipe r : recipes) {
            maxIn = Math.max(maxIn, r.inputs().size());
            maxOut = Math.max(maxOut, r.outputs().size());
            maxLines = Math.max(maxLines, textLines(r).size());
        }
        if (grid) {
            this.inCols = 3;
            maxIn = Math.max(9, maxIn);
        } else {
            this.inCols = Math.min(maxIn, MAX_COLUMNS);
        }
        this.outCols = Math.min(maxOut, 3);
        int inRows = (maxIn + inCols - 1) / inCols;
        int outRows = (maxOut + outCols - 1) / outCols;

        this.arrowX = inCols * SLOT + GAP;
        this.outX = arrowX + ARROW_W + GAP;
        this.slotsHeight = Math.max(inRows, outRows) * SLOT;
        this.width = Math.max(MIN_WIDTH, outX + outCols * SLOT);
        this.height = slotsHeight + (maxLines > 0 ? 4 + maxLines * LINE_H : 0);
    }

    /** Icon of a machine: "icon" of machines.json, else the block the core goes in, else the _default icon. */
    public static ItemStack iconStack(String machine, MachineDefs.MachineDef def) {
        if (def.icon != null) {
            List<ItemStack> stacks = ItemResolver.resolveKey(def.icon);
            if (!stacks.isEmpty()) return stacks.get(0);
        }
        for (com.terfrecipes.data.Multiblock mb : com.terfrecipes.client.TerfDataManager.data().multiblocks()) {
            if (!machine.equals(mb.machine())) continue;
            com.terfrecipes.data.Multiblock.BlockSpec core = mb.blocks().get(new com.terfrecipes.data.Multiblock.Pos(0, 0, 0));
            if (core == null) continue;
            List<ItemStack> stacks = ItemResolver.blockStacks(core);
            if (!stacks.isEmpty() && !stacks.get(0).is(net.minecraft.world.item.Items.BARRIER)) return stacks.get(0);
        }
        String fallback = com.terfrecipes.client.TerfDataManager.defs().get(MachineDefs.DEFAULT_KEY).icon;
        List<ItemStack> stacks = ItemResolver.resolveKey(fallback != null ? fallback : "terf:multiblock_core");
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0);
    }

    public String machine() {
        return machine;
    }

    @Override
    public IRecipeType<TerfRecipe> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return Component.literal(def.name != null ? def.name : MachineDefs.prettify(machine));
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public @Nullable IDrawable getIcon() {
        return icon;
    }

    @Override
    public @Nullable Identifier getIdentifier(TerfRecipe recipe) {
        String path = recipe.id().toLowerCase().replaceAll("[^a-z0-9/._-]", "_");
        return Identifier.tryBuild(TERFRecipes.MOD_ID, path);
    }

    // ------------------------------------------------------------------ slots

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, TerfRecipe recipe, IFocusGroup focuses) {
        int y0 = (slotsHeight - rows(recipe.inputs().size(), inCols) * SLOT) / 2;
        if (grid) y0 = 0;
        for (int i = 0; i < recipe.inputs().size(); i++) {
            TerfRecipe.Input input = recipe.inputs().get(i);
            int index = grid ? input.slot() : i;
            int x = (index % inCols) * SLOT;
            int y = y0 + (index / inCols) * SLOT;
            IRecipeSlotBuilder slot = builder.addInputSlot(x + 1, y + 1).setStandardSlotBackground();
            List<ItemStack> stacks = ItemResolver.resolveInput(input);
            if (!stacks.isEmpty()) slot.addItemStacks(stacks);
            if (input.kind() == TerfRecipe.InputKind.FLUID) {
                int amount = input.count();
                slot.addRichTooltipCallback((view, tooltip) ->
                        tooltip.add(Component.literal("Amount: " + amount + " mB").withStyle(ChatFormatting.AQUA)));
            } else if (stacks.size() > 1) {
                slot.addRichTooltipCallback((view, tooltip) ->
                        tooltip.add(Component.literal("Any item tagged \"" + input.key() + "\"").withStyle(ChatFormatting.GRAY)));
            }
        }

        int outRows = rows(recipe.outputs().size(), outCols);
        int oy0 = (slotsHeight - outRows * SLOT) / 2;
        for (int i = 0; i < recipe.outputs().size(); i++) {
            TerfRecipe.Output output = recipe.outputs().get(i);
            int x = outX + (i % outCols) * SLOT;
            int y = oy0 + (i / outCols) * SLOT;
            IRecipeSlotBuilder slot = builder.addOutputSlot(x + 1, y + 1).setStandardSlotBackground();
            slot.add(ItemResolver.resolveOutput(output));
            slot.addRichTooltipCallback((view, tooltip) -> {
                // recipe id for hidden.txt ("recipe:" rules), only with advanced tooltips (F3+H)
                if (Minecraft.getInstance().options.advancedItemTooltips) {
                    tooltip.add(Component.literal("TERF recipe: " + recipe.id()).withStyle(ChatFormatting.DARK_GRAY));
                }
            });
            if (output.kind() == TerfRecipe.OutputKind.FLUID || output.chance() != null) {
                slot.addRichTooltipCallback((view, tooltip) -> {
                    if (output.kind() == TerfRecipe.OutputKind.FLUID) {
                        tooltip.add(Component.literal("Amount: " + output.count() + " mB").withStyle(ChatFormatting.AQUA));
                    }
                    if (output.chance() != null) {
                        tooltip.add(Component.literal("Chance: " + Format.trim(output.chance()) + "%").withStyle(ChatFormatting.GOLD));
                    }
                });
            }
        }
    }

    private static int rows(int count, int cols) {
        return Math.max(1, (count + cols - 1) / cols);
    }

    // ------------------------------------------------------------------ extras

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, TerfRecipe recipe, IFocusGroup focuses) {
        int arrowY = (slotsHeight - ARROW_H) / 2;
        Integer ticks = recipeTicks(recipe);
        if (ticks != null && ticks > 0) {
            builder.addAnimatedRecipeArrowWidget(Math.min(ticks, 20 * 60))
                    .setPosition(arrowX, arrowY)
                    .setTooltip(Component.literal("Time: " + Format.ticks(ticks) + " (" + ticks + " ticks)"));
        } else {
            builder.addRecipeArrowWidget().setPosition(arrowX, arrowY);
        }

        List<FormattedText> lines = textLines(recipe);
        if (!lines.isEmpty()) {
            builder.addText(lines, width, lines.size() * LINE_H)
                    .setPosition(0, slotsHeight + 4)
                    .setColor(TEXT_COLOR)
                    .setShadow(false);
        }
    }

    private @Nullable Integer recipeTicks(TerfRecipe recipe) {
        if (def.timeParam != null && recipe.params().get(def.timeParam) != null) {
            return (int) recipe.params().get(def.timeParam).longValue();
        }
        return def.fixedTicks;
    }

    private List<FormattedText> textLines(TerfRecipe recipe) {
        List<FormattedText> lines = new ArrayList<>();
        if (recipe.title() != null) lines.add(Component.literal(recipe.title()).withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD));

        StringBuilder params = new StringBuilder();
        for (Map.Entry<String, Snbt.Num> e : recipe.params().entrySet()) {
            MachineDefs.ParamDef p = def.params.get(e.getKey());
            String label = p != null && p.label != null ? p.label : e.getKey();
            String format = p != null ? p.format : "number";
            String value = "ticks".equals(format) ? Format.ticks(e.getValue().longValue())
                    : Format.number(e.getValue().doubleValue());
            String unit = p != null && p.unit != null ? " " + p.unit : "";
            String part = label + ": " + value + unit;
            if (!params.isEmpty() && fontWidth(params + "  " + part) > MIN_WIDTH) {
                lines.add(Component.literal(params.toString()));
                params.setLength(0);
            }
            if (!params.isEmpty()) params.append("  ");
            params.append(part);
        }
        if (!params.isEmpty()) lines.add(Component.literal(params.toString()));

        for (String note : recipe.notes()) {
            if (note.startsWith("!")) lines.add(Component.literal(note.substring(1)).withStyle(ChatFormatting.DARK_RED));
            else lines.add(Component.literal(note).withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private static int fontWidth(String s) {
        Minecraft mc = Minecraft.getInstance();
        return mc.font != null ? mc.font.width(s) : s.length() * 6;
    }
}

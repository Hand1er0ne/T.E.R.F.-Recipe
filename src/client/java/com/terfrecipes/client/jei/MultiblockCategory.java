package com.terfrecipes.client.jei;

import com.terfrecipes.TERFRecipes;
import com.terfrecipes.client.ItemResolver;
import com.terfrecipes.client.TerfDataManager;
import com.terfrecipes.data.Multiblock;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "TERF Multiblocks" tab. Each structure gives a first page with the list of blocks needed and,
 * for the small structures, one page per layer (top-down view, the player stands at the bottom
 * of the grid, facing up, when placing the Multiblock Core).
 */
public class MultiblockCategory implements IRecipeCategory<MultiblockCategory.Page> {

    /**
     * @param layer -1 for the block list, otherwise the layer index (0 = lowest)
     */
    public record Page(Multiblock multiblock, int layer) {
        public boolean isSummary() {
            return layer < 0;
        }

        public int y() {
            return multiblock.minY() + layer;
        }

        public int layerCount() {
            return multiblock.maxY() - multiblock.minY() + 1;
        }
    }

    public static final IRecipeType<Page> TYPE = IRecipeType.create(TERFRecipes.MOD_ID, "multiblock", Page.class);

    private static final int SLOT = 18;
    private static final int HEADER = 12;
    private static final int FOOTER_LINES = 2;
    private static final int LINE_H = 10;
    private static final int LIST_COLUMNS = 9;
    private static final int LIST_MAX_ROWS = 6;
    private static final int CORE_COLOR = 0xFFE0352B;
    private static final int TEXT_COLOR = 0xFF404040;

    private final IDrawable icon;
    private final int width;
    private final int height;
    private final int gridHeight;

    public MultiblockCategory(List<Multiblock> structures, IGuiHelper gui) {
        ItemStack core = TerfDataManager.data().materials().containsKey("terf:multiblock_core")
                ? ItemResolver.material("terf:multiblock_core") : new ItemStack(net.minecraft.world.item.Items.CRAFTER);
        this.icon = gui.createDrawableIngredient(VanillaTypes.ITEM_STACK, core);

        int maxCols = LIST_COLUMNS;
        int maxRows = 1;
        for (Multiblock mb : structures) {
            int listRows = Math.min(LIST_MAX_ROWS, (mb.counts().size() + LIST_COLUMNS - 1) / LIST_COLUMNS);
            maxRows = Math.max(maxRows, listRows);
            if (!mb.complex()) {
                maxCols = Math.max(maxCols, mb.maxX() - mb.minX() + 1);
                maxRows = Math.max(maxRows, mb.maxZ() - mb.minZ() + 1);
            }
        }
        this.gridHeight = maxRows * SLOT;
        this.width = Math.max(170, maxCols * SLOT + 8);
        this.height = HEADER + gridHeight + 4 + FOOTER_LINES * LINE_H;
    }

    /** All pages for the given structures. */
    public static List<Page> pages(List<Multiblock> structures) {
        List<Page> out = new ArrayList<>();
        for (Multiblock mb : structures) {
            out.add(new Page(mb, -1));
            if (!mb.complex()) {
                int layers = mb.maxY() - mb.minY() + 1;
                for (int l = 0; l < layers; l++) out.add(new Page(mb, l));
            }
        }
        return out;
    }

    @Override
    public IRecipeType<Page> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.literal("TERF Multiblocks");
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
    public @Nullable Identifier getIdentifier(Page page) {
        return Identifier.tryBuild(TERFRecipes.MOD_ID, "multiblock/" + page.multiblock().id() + "/" + (page.isSummary() ? "blocks" : "layer_" + page.layer()));
    }

    // ------------------------------------------------------------------ slots

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Page page, IFocusGroup focuses) {
        Multiblock mb = page.multiblock();
        if (page.isSummary()) {
            Map<String, Integer> counts = mb.counts();
            List<Multiblock.BlockSpec> specs = mb.distinctSpecs();
            boolean scroll = specs.size() > LIST_COLUMNS * LIST_MAX_ROWS;
            for (int i = 0; i < specs.size(); i++) {
                Multiblock.BlockSpec spec = specs.get(i);
                int count = counts.getOrDefault(spec.raw(), 1);
                IRecipeSlotBuilder slot = scroll ? builder.addInputSlot()
                        : builder.addInputSlot(4 + (i % LIST_COLUMNS) * SLOT + 1, HEADER + (i / LIST_COLUMNS) * SLOT + 1)
                        .setStandardSlotBackground();
                List<ItemStack> stacks = new ArrayList<>();
                for (ItemStack s : ItemResolver.blockStacks(spec)) stacks.add(s.copyWithCount(count));
                slot.addItemStacks(stacks);
                slot.addRichTooltipCallback((view, tooltip) -> blockTooltip(tooltip, spec, count));
            }
            return;
        }

        int y = page.y();
        int cols = mb.maxX() - mb.minX() + 1;
        int x0 = (width - cols * SLOT) / 2;
        for (int z = mb.maxZ(); z >= mb.minZ(); z--) {
            for (int x = mb.maxX(); x >= mb.minX(); x--) {
                Multiblock.BlockSpec spec = mb.blocks().get(new Multiblock.Pos(x, y, z));
                int col = mb.maxX() - x;
                int row = mb.maxZ() - z;
                if (spec == null) continue;
                IRecipeSlotBuilder slot = builder.addInputSlot(x0 + col * SLOT + 1, HEADER + row * SLOT + 1)
                        .setStandardSlotBackground();
                slot.addItemStacks(ItemResolver.blockStacks(spec));
                slot.addRichTooltipCallback((view, tooltip) -> blockTooltip(tooltip, spec, 1));
            }
        }
    }

    private static void blockTooltip(ITooltipBuilder tooltip, Multiblock.BlockSpec spec, int count) {
        switch (spec.role()) {
            case CORE -> tooltip.add(Component.literal("Place the Multiblock Core in this block").withStyle(ChatFormatting.RED));
            case POWER -> tooltip.add(Component.literal("Power input").withStyle(ChatFormatting.YELLOW));
            case FLUID -> tooltip.add(Component.literal("Fluid port").withStyle(ChatFormatting.AQUA));
            default -> {
            }
        }
        if (!spec.states().isEmpty()) {
            tooltip.add(Component.literal("[" + spec.states() + "]").withStyle(ChatFormatting.GRAY));
        }
        if (spec.isTag()) {
            tooltip.add(Component.literal("Any block of " + spec.id()).withStyle(ChatFormatting.GRAY));
        }
        if (count > 1) tooltip.add(Component.literal("x" + count).withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------------ extras

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, Page page, IFocusGroup focuses) {
        Multiblock mb = page.multiblock();
        String info = page.isSummary()
                ? (mb.complex() ? "Blocks needed (list only)" : "Blocks needed - " + page.layerCount() + " layer(s) on next pages")
                : "Layer " + (page.layer() + 1) + "/" + page.layerCount() + (page.y() == 0 ? " (core level)" : " (" + (page.y() > 0 ? "+" : "") + page.y() + ")");
        builder.addText(List.of(Component.literal(mb.name()).withStyle(ChatFormatting.BOLD)), width, LINE_H)
                .setPosition(0, 0).setColor(TEXT_COLOR).setShadow(false);
        builder.addText(List.of(Component.literal(info)), width, LINE_H)
                .setPosition(0, 0).setColor(0xFF707070).setShadow(false)
                .setTextAlignment(mezz.jei.api.gui.placement.HorizontalAlignment.RIGHT);

        if (page.isSummary()) {
            List<IRecipeSlotDrawable> slots = builder.getRecipeSlots().getSlots(RecipeIngredientRole.INPUT);
            if (slots.size() > LIST_COLUMNS * LIST_MAX_ROWS) {
                builder.addScrollGridWidget(slots, LIST_COLUMNS - 1, LIST_MAX_ROWS).setPosition(4, HEADER);
            }
        }

        List<FormattedText> footer = new ArrayList<>();
        if (!page.isSummary()) {
            footer.add(Component.literal("Top view: you stand below the grid, facing up").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (String note : mb.notes()) footer.add(Component.literal(note).withStyle(ChatFormatting.DARK_GRAY));
        if (!footer.isEmpty()) {
            builder.addText(footer.subList(0, Math.min(FOOTER_LINES, footer.size())), width, FOOTER_LINES * LINE_H)
                    .setPosition(0, HEADER + gridHeight + 4).setColor(TEXT_COLOR).setShadow(false);
        }
    }

    @Override
    public void draw(Page page, IRecipeSlotsView recipeSlotsView, GuiGraphicsExtractor gui, double mouseX, double mouseY) {
        if (page.isSummary() || page.y() != 0) return;
        Multiblock mb = page.multiblock();
        int cols = mb.maxX() - mb.minX() + 1;
        int x0 = (width - cols * SLOT) / 2;
        // red frame around the block holding the Multiblock Core
        int col = mb.maxX();
        int row = mb.maxZ();
        gui.outline(x0 + col * SLOT, HEADER + row * SLOT, SLOT, SLOT, CORE_COLOR);
    }
}

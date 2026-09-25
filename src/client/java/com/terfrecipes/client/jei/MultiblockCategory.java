package com.terfrecipes.client.jei;

import com.mojang.blaze3d.platform.InputConstants;
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
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.gui.widgets.ISlottedRecipeWidget;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "TERF Multiblocks" tab: ONE page per machine.
 * <ul>
 *     <li>left: every block needed with its amount (scrollable)</li>
 *     <li>right (small machines only): top view of one layer; switch layers with the arrows in the
 *     header or the mouse wheel over the grid. You stand below the grid, facing up; the red frame is
 *     the block where the Multiblock Core goes.</li>
 * </ul>
 */
public class MultiblockCategory implements IRecipeCategory<Multiblock> {

    public static final IRecipeType<Multiblock> TYPE = IRecipeType.create(TERFRecipes.MOD_ID, "multiblock", Multiblock.class);

    private static final int SLOT = 18;
    private static final int LINE_H = 10;
    private static final int HEADER = 2 * LINE_H + 4;
    private static final int FOOTER_LINES = 2;
    private static final int LIST_COLUMNS_SIDE = 4;
    private static final int LIST_COLUMNS_FULL = 8;
    private static final int SCROLLBAR = 14;
    private static final int GAP = 8;
    private static final int MIN_ROWS = 3;
    private static final int CORE_COLOR = 0xFFE0352B;
    private static final int TEXT_COLOR = 0xFF404040;
    private static final int INFO_COLOR = 0xFF707070;
    private static final int BUTTON_W = 11;

    private final IDrawable icon;
    private final int width;
    private final int height;
    private final int rows;
    private final int gridX;
    private final int gridCols;

    public MultiblockCategory(List<Multiblock> structures, IGuiHelper gui) {
        ItemStack core = TerfDataManager.data().materials().containsKey("terf:multiblock_core")
                ? ItemResolver.material("terf:multiblock_core") : new ItemStack(net.minecraft.world.item.Items.CRAFTER);
        this.icon = gui.createDrawableIngredient(VanillaTypes.ITEM_STACK, core);

        int maxCols = 1;
        int maxRows = MIN_ROWS;
        for (Multiblock mb : structures) {
            if (!mb.complex()) {
                maxCols = Math.max(maxCols, mb.maxX() - mb.minX() + 1);
                maxRows = Math.max(maxRows, mb.maxZ() - mb.minZ() + 1);
            }
        }
        this.rows = maxRows;
        this.gridCols = maxCols;
        this.gridX = LIST_COLUMNS_SIDE * SLOT + SCROLLBAR + GAP;
        int listFullWidth = LIST_COLUMNS_FULL * SLOT + SCROLLBAR;
        this.width = Math.max(Math.max(170, listFullWidth), gridX + maxCols * SLOT);
        this.height = HEADER + rows * SLOT + 4 + FOOTER_LINES * LINE_H;
    }

    /** One entry per machine. */
    public static List<Multiblock> pages(List<Multiblock> structures) {
        return new ArrayList<>(structures);
    }

    @Override
    public IRecipeType<Multiblock> getRecipeType() {
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
    public @Nullable Identifier getIdentifier(Multiblock mb) {
        return Identifier.tryBuild(TERFRecipes.MOD_ID, "multiblock/" + mb.id());
    }

    private static boolean hasLayers(Multiblock mb) {
        return !mb.complex();
    }

    // ------------------------------------------------------------------ slots

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Multiblock mb, IFocusGroup focuses) {
        // 1. block list (positions handled by a scroll grid in createRecipeExtras)
        Map<String, Integer> counts = mb.counts();
        for (Multiblock.BlockSpec spec : mb.distinctSpecs()) {
            int count = counts.getOrDefault(spec.raw(), 1);
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack s : ItemResolver.blockStacks(spec)) stacks.add(s.copyWithCount(count));
            builder.addInputSlot()
                    .addItemStacks(stacks)
                    .addRichTooltipCallback((view, tooltip) -> blockTooltip(tooltip, spec, count));
        }
        if (!hasLayers(mb)) return;

        // 2. layer view: every block of every layer; the widget only shows the current layer
        for (LayerCell cell : cells(mb)) {
            IRecipeSlotBuilder slot = builder.addInputSlot(cell.x + 1, cell.y + 1).setStandardSlotBackground();
            slot.addItemStacks(ItemResolver.blockStacks(cell.spec));
            slot.addRichTooltipCallback((view, tooltip) -> blockTooltip(tooltip, cell.spec, 1));
        }
    }

    private record LayerCell(int layer, int x, int y, Multiblock.BlockSpec spec) {
    }

    /** Layer cells in a stable order (same order in setRecipe and createRecipeExtras). */
    private List<LayerCell> cells(Multiblock mb) {
        List<LayerCell> out = new ArrayList<>();
        int cols = mb.maxX() - mb.minX() + 1;
        int x0 = gridX + (gridCols - cols) * SLOT / 2;
        for (int y = mb.minY(); y <= mb.maxY(); y++) {
            for (int z = mb.maxZ(); z >= mb.minZ(); z--) {
                for (int x = mb.maxX(); x >= mb.minX(); x--) {
                    Multiblock.BlockSpec spec = mb.blocks().get(new Multiblock.Pos(x, y, z));
                    if (spec == null) continue;
                    out.add(new LayerCell(y - mb.minY(), x0 + (mb.maxX() - x) * SLOT, HEADER + (mb.maxZ() - z) * SLOT, spec));
                }
            }
        }
        return out;
    }

    private static void blockTooltip(ITooltipBuilder tooltip, Multiblock.BlockSpec spec, int count) {
        switch (spec.role()) {
            case CORE -> tooltip.add(Component.literal("Place the Multiblock Core in this block").withStyle(ChatFormatting.RED));
            case POWER -> {
                tooltip.add(Component.literal("Power input: corner of a power wire").withStyle(ChatFormatting.YELLOW));
                tooltip.add(Component.literal("(turns red while energy flows)").withStyle(ChatFormatting.DARK_GRAY));
            }
            case FLUID -> {
                tooltip.add(Component.literal("Fluid port: pipe connection").withStyle(ChatFormatting.AQUA));
                tooltip.add(Component.literal("(pipe corners turn red while fluid flows)").withStyle(ChatFormatting.DARK_GRAY));
            }
            default -> {
            }
        }
        if (!spec.states().isEmpty()) tooltip.add(Component.literal("[" + spec.states() + "]").withStyle(ChatFormatting.GRAY));
        if (spec.isTag()) tooltip.add(Component.literal("Any block of " + spec.id()).withStyle(ChatFormatting.GRAY));
        if (count > 1) tooltip.add(Component.literal("x" + count).withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------------ extras

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, Multiblock mb, IFocusGroup focuses) {
        // header line 1: machine name
        builder.addText(List.of(Component.literal(mb.name()).withStyle(ChatFormatting.BOLD)), width, LINE_H)
                .setPosition(0, 0).setColor(TEXT_COLOR).setShadow(false);

        List<IRecipeSlotDrawable> all = builder.getRecipeSlots().getSlots(RecipeIngredientRole.INPUT);
        int listCount = mb.distinctSpecs().size();
        List<IRecipeSlotDrawable> listSlots = all.subList(0, Math.min(listCount, all.size()));
        int listCols = hasLayers(mb) ? LIST_COLUMNS_SIDE : LIST_COLUMNS_FULL;
        builder.addScrollGridWidget(listSlots, listCols, rows).setPosition(0, HEADER);

        if (hasLayers(mb)) {
            List<LayerCell> cells = cells(mb);
            List<IRecipeSlotDrawable> layerSlots = all.subList(listSlots.size(), all.size());
            LayerView view = new LayerView(mb, cells, layerSlots);
            builder.addSlottedWidget(view, layerSlots);
            builder.addInputHandler(view);
        } else {
            builder.addText(List.of(Component.literal("Blocks needed (" + mb.blocks().size() + ")")), width, LINE_H)
                    .setPosition(0, LINE_H + 1).setColor(INFO_COLOR).setShadow(false);
        }

        List<FormattedText> footer = new ArrayList<>();
        if (hasLayers(mb)) {
            footer.add(Component.literal("Top view: you stand below the grid. Red frame = core").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (String note : mb.notes()) footer.add(Component.literal(note).withStyle(ChatFormatting.DARK_GRAY));
        if (!footer.isEmpty()) {
            builder.addText(footer.subList(0, Math.min(FOOTER_LINES, footer.size())), width, FOOTER_LINES * LINE_H)
                    .setPosition(0, HEADER + rows * SLOT + 4).setColor(TEXT_COLOR).setShadow(false);
        }
    }

    /**
     * Draws the slots of the current layer only, plus the "Layer n/m" line with its arrows.
     * One instance per displayed recipe, so the selected layer is kept while the page is open.
     */
    private final class LayerView implements ISlottedRecipeWidget, IJeiInputHandler {
        private final Multiblock mb;
        private final List<LayerCell> cells;
        private final List<IRecipeSlotDrawable> slots;
        private final int layerCount;
        private int layer;

        LayerView(Multiblock mb, List<LayerCell> cells, List<IRecipeSlotDrawable> slots) {
            this.mb = mb;
            this.cells = cells;
            this.slots = slots;
            this.layerCount = mb.maxY() - mb.minY() + 1;
            this.layer = Math.max(0, Math.min(layerCount - 1, -mb.minY())); // start on the core level
        }

        // --- geometry (category coordinates; the widget sits at 0,0)

        private int prevX() {
            return width - 2 * BUTTON_W - 2;
        }

        private int nextX() {
            return width - BUTTON_W;
        }

        private int buttonsY() {
            return LINE_H + 1;
        }

        @Override
        public ScreenPosition getPosition() {
            return new ScreenPosition(0, 0);
        }

        // --- drawing

        @Override
        public void drawWidget(GuiGraphicsExtractor gui, double mouseX, double mouseY) {
            var font = Minecraft.getInstance().font;
            int y = mb.minY() + layer;
            String label = "Layer " + (layer + 1) + "/" + layerCount
                    + (y == 0 ? " (core level)" : " (" + (y > 0 ? "+" : "") + y + ")");
            gui.text(font, label, gridX, buttonsY(), INFO_COLOR, false);

            drawButton(gui, font, prevX(), "<", layer > 0, mouseX, mouseY);
            drawButton(gui, font, nextX(), ">", layer < layerCount - 1, mouseX, mouseY);

            for (int i = 0; i < slots.size() && i < cells.size(); i++) {
                if (cells.get(i).layer != layer) continue;
                IRecipeSlotDrawable slot = slots.get(i);
                slot.draw(gui, slot.isMouseOver(mouseX, mouseY));
            }

            if (y == 0) {
                int cols = mb.maxX() - mb.minX() + 1;
                int x0 = gridX + (gridCols - cols) * SLOT / 2;
                gui.outline(x0 + mb.maxX() * SLOT, HEADER + mb.maxZ() * SLOT, SLOT, SLOT, CORE_COLOR);
            }
        }

        private void drawButton(GuiGraphicsExtractor gui, net.minecraft.client.gui.Font font, int x, String s,
                                boolean enabled, double mouseX, double mouseY) {
            int y = buttonsY() - 1;
            boolean hover = enabled && mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + LINE_H;
            gui.fill(x, y, x + BUTTON_W, y + LINE_H, hover ? 0xFF8B8B8B : 0xFFC6C6C6);
            gui.outline(x, y, BUTTON_W, LINE_H, 0xFF555555);
            gui.centeredText(font, s, x + BUTTON_W / 2 + 1, y + 1, enabled ? 0xFF202020 : 0xFFA0A0A0);
        }

        @Override
        public Optional<RecipeSlotUnderMouse> getSlotUnderMouse(double mouseX, double mouseY) {
            for (int i = 0; i < slots.size() && i < cells.size(); i++) {
                if (cells.get(i).layer != layer) continue;
                IRecipeSlotDrawable slot = slots.get(i);
                if (slot.isMouseOver(mouseX, mouseY)) return Optional.of(new RecipeSlotUnderMouse(slot, 0, 0));
            }
            return Optional.empty();
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip, double mouseX, double mouseY) {
            if (mouseY >= buttonsY() - 1 && mouseY < buttonsY() - 1 + LINE_H && mouseX >= prevX() && mouseX < nextX() + BUTTON_W) {
                tooltip.add(Component.literal("Change layer (or scroll over the grid)"));
            }
        }

        // --- input (the input area covers the whole page; coordinates are relative to it)

        @Override
        public ScreenRectangle getArea() {
            return new ScreenRectangle(0, 0, width, height);
        }

        @Override
        public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
            if (input.getKey().getType() != InputConstants.Type.MOUSE) return false;
            int y = buttonsY() - 1;
            if (mouseY < y || mouseY >= y + LINE_H) return false;
            int delta;
            if (mouseX >= prevX() && mouseX < prevX() + BUTTON_W) delta = -1;
            else if (mouseX >= nextX() && mouseX < nextX() + BUTTON_W) delta = 1;
            else return false;
            if (!input.isSimulate()) setLayer(layer + delta);
            return true;
        }

        @Override
        public boolean handleMouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
            if (mouseX < gridX || mouseY < HEADER || mouseY >= HEADER + rows * SLOT || scrollDeltaY == 0) return false;
            setLayer(layer + (scrollDeltaY > 0 ? 1 : -1));
            return true;
        }

        private void setLayer(int l) {
            layer = Math.max(0, Math.min(layerCount - 1, l));
        }
    }
}

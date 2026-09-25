package com.terfrecipes.client.jei;

import com.mojang.blaze3d.platform.InputConstants;
import com.terfrecipes.TERFRecipes;
import com.terfrecipes.client.ItemResolver;
import com.terfrecipes.client.TerfDataManager;
import com.terfrecipes.client.render.GuiAccess;
import com.terfrecipes.client.render.StructureRenderState;
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
    private static final int MIN_ROWS = 7;
    private static final int CORE_COLOR = 0xFFE0352B;
    private static final int TEXT_COLOR = 0xFF404040;
    private static final int INFO_COLOR = 0xFF707070;
    private static final int BUTTON_W = 14;
    private static final int TOGGLE_W = 20;
    private static final int BTN_H = 12;
    /**
     * +1: the core's local "forward" (^ ^ ^1) points to the player who placed it, as
     * multiblock_core_rotate turns the core by the player's yaw + 180. Flip to -1 if in game the
     * arrow points to the wrong side.
     */
    private static final int FRONT_SIGN = 1;
    private static final int FRONT_COLOR = 0xFF55FF55;
    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");
    private static final Identifier BUTTON_OFF = Identifier.withDefaultNamespace("widget/button_disabled");
    private static final int VIEW_MIN_W = 130;
    private static final int VIEW_BG = 0xFF1E1E24;

    private final IDrawable icon;
    private final int width;
    private final int height;
    private final int rows;
    private final int btnY;
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
        this.width = Math.max(Math.max(170, listFullWidth), gridX + Math.max(VIEW_MIN_W, maxCols * SLOT));
        // view controls (2D/3D, layers) in a row under the view
        this.btnY = HEADER + rows * SLOT + 3;
        this.height = btnY + BTN_H + 3 + FOOTER_LINES * LINE_H;
    }

    /** One entry per machine. */
    public static List<Multiblock> pages(List<Multiblock> structures) {
        List<Multiblock> sorted = new ArrayList<>(structures);
        sorted.sort(java.util.Comparator.comparing(mb -> mb.name().toLowerCase(java.util.Locale.ROOT)));
        return sorted;
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
        builder.addInvisibleIngredients(RecipeIngredientRole.OUTPUT).add(ItemResolver.structureMarker(mb.machine()));

        // 1. block list (positions handled by a scroll grid in createRecipeExtras)
        Map<String, Integer> counts = mb.counts();
        for (Multiblock.BlockSpec spec : mb.distinctSpecs()) {
            int count = counts.getOrDefault(spec.raw(), 1);
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack s : ItemResolver.blockStacks(spec)) stacks.add(s.copyWithCount(count));
            builder.addInputSlot()
                    .addItemStacks(stacks)
                    .addRichTooltipCallback((view, tooltip) -> blockTooltip(tooltip, mb, spec, count));
        }
        if (!hasLayers(mb)) return;

        // 2. layer view: every block of every layer; the widget only shows the current layer
        for (LayerCell cell : cells(mb)) {
            IRecipeSlotBuilder slot = builder.addInputSlot(cell.x + 1, cell.y + 1).setStandardSlotBackground();
            slot.addItemStacks(ItemResolver.blockStacks(cell.spec));
            slot.addRichTooltipCallback((view, tooltip) -> blockTooltip(tooltip, mb, cell.spec, 1));
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

    private static void blockTooltip(ITooltipBuilder tooltip, Multiblock mb, Multiblock.BlockSpec spec, int count) {
        // explanations written in machines.json ("blockTips")
        var tips = TerfDataManager.defs().get(mb.machine()).blockTips;
        String tip = tips == null ? null : tips.get(spec.id().replace("minecraft:", ""));
        if (tip != null) {
            for (var line : Minecraft.getInstance().font.getSplitter().splitLines(tip, 200, net.minecraft.network.chat.Style.EMPTY)) {
                tooltip.add(Component.literal(line.getString()).withStyle(ChatFormatting.AQUA));
            }
        }
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
        // header line 1: machine name; a click on it opens the machine's recipes
        Component title = Component.literal(mb.name()).withStyle(ChatFormatting.BOLD);
        if (TerfDataManager.data().recipesByMachine().containsKey(mb.machine())) {
            LinkButton link = LinkButton.title(0, 0, title, TEXT_COLOR,
                    List.of(Component.literal("Click: show what the " + mb.name() + " makes")),
                    () -> TerfJeiPlugin.showRecipes(mb.machine()), width, height);
            builder.addWidget(link);
            builder.addInputHandler(link);
        } else {
            builder.addText(List.of(title), width, LINE_H).setPosition(0, 0).setColor(TEXT_COLOR).setShadow(false);
        }

        List<IRecipeSlotDrawable> all = builder.getRecipeSlots().getSlots(RecipeIngredientRole.INPUT);
        int listCount = mb.distinctSpecs().size();
        List<IRecipeSlotDrawable> listSlots = all.subList(0, Math.min(listCount, all.size()));
        builder.addScrollGridWidget(listSlots, LIST_COLUMNS_SIDE, rows).setPosition(0, HEADER);

        // 3D view for every structure; the 2D layer grid only for the small ones
        List<LayerCell> cells = hasLayers(mb) ? cells(mb) : List.of();
        List<IRecipeSlotDrawable> layerSlots = all.subList(listSlots.size(), all.size());
        LayerView view = new LayerView(mb, cells, layerSlots);
        builder.addSlottedWidget(view, layerSlots);
        builder.addInputHandler(view);

        // ghost structure in the world, where the player looks
        String inWorld = "Show in world";
        LinkButton show = LinkButton.button(gridX + Minecraft.getInstance().font.width(inWorld) + 8, btnY, inWorld,
                List.of(Component.literal("Shows the structure as ghost blocks"),
                        Component.literal("on the block you are looking at").withStyle(ChatFormatting.GRAY),
                        Component.literal("(where the Multiblock Core goes)").withStyle(ChatFormatting.GRAY)),
                () -> {
                    Minecraft.getInstance().setScreen(null);
                    com.terfrecipes.client.render.Hologram.show(mb);
                }, width, height);
        builder.addWidget(show);
        builder.addInputHandler(show);

        List<FormattedText> footer = new ArrayList<>();
        footer.add(Component.literal(hasLayers(mb) ? "Drag: rotate, scroll: zoom. Green = player side"
                : "Drag: rotate, scroll: zoom. Green = player side").withStyle(ChatFormatting.DARK_GRAY));
        for (String note : mb.notes()) footer.add(Component.literal(note).withStyle(ChatFormatting.DARK_GRAY));
        if (!footer.isEmpty()) {
            builder.addText(footer.subList(0, Math.min(FOOTER_LINES, footer.size())), width, FOOTER_LINES * LINE_H)
                    .setPosition(0, btnY + BTN_H + 3).setColor(TEXT_COLOR).setShadow(false);
        }
    }

    /**
     * Right part of the page: the 3D view (default) or, for small machines, the top view of one
     * layer ("2D"). One instance per displayed recipe, so the camera and layer are kept while the
     * page is open.
     */
    private final class LayerView implements ISlottedRecipeWidget, IJeiInputHandler {
        private final Multiblock mb;
        private final List<LayerCell> cells;
        private final List<IRecipeSlotDrawable> slots;
        private final int layerCount;
        private final boolean has2d;
        private int layer;
        private boolean view3d = true;
        private float yaw = 225f;
        private float pitch = 30f;
        private float zoom = 1f;
        private boolean dragging;

        LayerView(Multiblock mb, List<LayerCell> cells, List<IRecipeSlotDrawable> slots) {
            this.mb = mb;
            this.cells = cells;
            this.slots = slots;
            this.has2d = hasLayers(mb);
            this.layerCount = mb.maxY() - mb.minY() + 1;
            this.layer = layerCount - 1; // 3D view starts with the whole structure
        }

        // --- geometry (category coordinates; the widget sits at 0,0)

        private int prevX() {
            return width - 2 * BUTTON_W - 1;
        }

        private int nextX() {
            return width - BUTTON_W;
        }

        private int toggleX() {
            return prevX() - TOGGLE_W - 2;
        }

        private boolean onButtonRow(double mouseY) {
            return mouseY >= btnY && mouseY < btnY + BTN_H;
        }

        private boolean inView(double mouseX, double mouseY) {
            return mouseX >= gridX && mouseX < width && mouseY >= HEADER && mouseY < HEADER + rows * SLOT;
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
            String label = view3d ? "Layers 1-" + (layer + 1) + " of " + layerCount
                    : "Layer " + (layer + 1) + "/" + layerCount
                    + (y == 0 ? " (core)" : " (" + (y > 0 ? "+" : "") + y + ")");
            gui.text(font, label, gridX, LINE_H + 2, INFO_COLOR, false);

            if (has2d) drawButton(gui, font, toggleX(), view3d ? "2D" : "3D", TOGGLE_W, true, mouseX, mouseY);
            drawButton(gui, font, prevX(), "\u25C0", BUTTON_W, layer > 0, mouseX, mouseY);
            drawButton(gui, font, nextX(), "\u25B6", BUTTON_W, layer < layerCount - 1, mouseX, mouseY);

            if (view3d) {
                draw3d(gui, font, mouseX, mouseY);
                return;
            }

            for (int i = 0; i < slots.size() && i < cells.size(); i++) {
                if (cells.get(i).layer != layer) continue;
                IRecipeSlotDrawable slot = slots.get(i);
                slot.draw(gui, slot.isMouseOver(mouseX, mouseY));
            }

            int cols = mb.maxX() - mb.minX() + 1;
            int x0 = gridX + (gridCols - cols) * SLOT / 2;
            if (y == 0) gui.outline(x0 + mb.maxX() * SLOT, HEADER + mb.maxZ() * SLOT, SLOT, SLOT, CORE_COLOR);
            // green edge = side where the player stands to place the core
            int depth = mb.maxZ() - mb.minZ() + 1;
            int edgeY = FRONT_SIGN > 0 ? HEADER - 2 : HEADER + depth * SLOT;
            gui.fill(x0, edgeY, x0 + cols * SLOT, edgeY + 2, FRONT_COLOR);
        }

        /** Screen box of the core block in the last 3D frame, and tip of the "player side" arrow. */
        private @Nullable ScreenRectangle coreBox;
        private float @Nullable [] frontTip;

        private void draw3d(GuiGraphicsExtractor gui, net.minecraft.client.gui.Font font, double mouseX, double mouseY) {
            int vx0 = gridX;
            int vy0 = HEADER;
            int vx1 = width;
            int vy1 = HEADER + rows * SLOT;
            gui.fill(vx0, vy0, vx1, vy1, VIEW_BG);

            List<StructureRenderState.Placed> blocks = new ArrayList<>();
            long second = System.currentTimeMillis() / 1000;
            // chosen state of every visible block (tags cycle every second)
            Map<Multiblock.Pos, net.minecraft.world.level.block.state.BlockState> chosen = new java.util.HashMap<>();
            Map<Multiblock.Pos, ItemStack> chosenItem = new java.util.HashMap<>();
            for (Map.Entry<Multiblock.Pos, Multiblock.BlockSpec> e : mb.blocks().entrySet()) {
                Multiblock.Pos p = e.getKey();
                if (p.y() - mb.minY() > layer) continue;
                List<net.minecraft.world.level.block.state.BlockState> states = ItemResolver.blockStates(e.getValue());
                List<ItemStack> items = ItemResolver.blockStacks(e.getValue());
                if (!states.isEmpty()) chosen.put(p, states.get((int) (second % states.size())));
                chosenItem.put(p, items.isEmpty() ? ItemStack.EMPTY : items.get((int) (second % items.size())));
            }
            for (Map.Entry<Multiblock.Pos, ItemStack> e : chosenItem.entrySet()) {
                Multiblock.Pos p = e.getKey();
                var state = chosen.get(p);
                if (state != null) {
                    // bars / panes / walls join their neighbours (+x = east, +z = south in the view)
                    state = ItemResolver.connected(state, d -> chosen.get(new Multiblock.Pos(
                            p.x() + d.getStepX(), p.y() + d.getStepY(), p.z() + d.getStepZ())));
                }
                blocks.add(new StructureRenderState.Placed(p.x(), p.y(), p.z(), state, e.getValue()));
            }

            float sx = mb.maxX() - mb.minX() + 1;
            float sy = mb.maxY() - mb.minY() + 1;
            float sz = mb.maxZ() - mb.minZ() + 1;
            float cx = mb.minX() + sx / 2f;
            float cy = mb.minY() + sy / 2f;
            float cz = mb.minZ() + sz / 2f;
            float diagonal = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            float scale = Math.min(vx1 - vx0, vy1 - vy0) / Math.max(1f, diagonal) * zoom;
            boolean coreVisible = -mb.minY() <= layer;
            ItemStack core = coreVisible && TerfDataManager.data().materials().containsKey("terf:multiblock_core")
                    ? ItemResolver.material("terf:multiblock_core") : ItemStack.EMPTY;

            // the widget is drawn with JEI's translation: the picture-in-picture needs screen coordinates
            org.joml.Vector2f a = gui.pose().transformPosition(new org.joml.Vector2f(vx0, vy0));
            org.joml.Vector2f b = gui.pose().transformPosition(new org.joml.Vector2f(vx1, vy1));
            StructureRenderState state = new StructureRenderState(blocks, core, cx, cy, cz, yaw, pitch,
                    Math.round(a.x), Math.round(a.y), Math.round(b.x), Math.round(b.y), scale, GuiAccess.scissor(gui));
            GuiAccess.submit(gui, state);

            // --- overlays, projected with the same camera as the renderer
            org.joml.Matrix4f cam = new org.joml.Matrix4f()
                    .rotateZ((float) Math.PI)
                    .rotateX((float) Math.toRadians(pitch))
                    .rotateY((float) Math.toRadians(yaw))
                    .translate(-cx, -cy, -cz);
            float ox = (vx0 + vx1) / 2f;
            float oy = (vy0 + vy1) / 2f;
            java.util.function.BiFunction<float[], Void, float[]> project = (p, unused) -> {
                org.joml.Vector3f v = cam.transformPosition(new org.joml.Vector3f(p[0], p[1], p[2]));
                return new float[]{ox + v.x * scale, oy + v.y * scale};
            };
            ScreenRectangle clip = new ScreenRectangle(vx0, vy0, vx1 - vx0, vy1 - vy0);

            // arrow on the core's floor, from the core to beyond the structure, towards the player's side
            float frontZ = FRONT_SIGN > 0 ? mb.maxZ() + 2.2f : mb.minZ() - 1.2f;
            float[] from = project.apply(new float[]{0.5f, 0f, 0.5f + FRONT_SIGN * 0.6f}, null);
            float[] to = project.apply(new float[]{0.5f, 0f, frontZ}, null);
            drawArrow(gui, from, to, clip);
            frontTip = to;
            drawClippedText(gui, font, "Player", to, clip);

            // brackets around the core block
            coreBox = null;
            if (coreVisible) {
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int k = 0; k < 8; k++) {
                    float[] c = project.apply(new float[]{k & 1, (k >> 1) & 1, (k >> 2) & 1}, null);
                    minX = Math.min(minX, c[0]);
                    maxX = Math.max(maxX, c[0]);
                    minY = Math.min(minY, c[1]);
                    maxY = Math.max(maxY, c[1]);
                }
                int bx0 = Math.max(vx0, Math.round(minX) - 1), by0 = Math.max(vy0, Math.round(minY) - 1);
                int bx1 = Math.min(vx1, Math.round(maxX) + 1), by1 = Math.min(vy1, Math.round(maxY) + 1);
                if (bx1 > bx0 + 2 && by1 > by0 + 2) {
                    coreBox = new ScreenRectangle(bx0, by0, bx1 - bx0, by1 - by0);
                    int len = Math.max(2, Math.min(bx1 - bx0, by1 - by0) / 4);
                    boolean hover = containsPoint(coreBox, mouseX, mouseY);
                    int color = hover ? 0xFFFFFFFF : CORE_COLOR;
                    // four corners
                    gui.fill(bx0, by0, bx0 + len, by0 + 1, color);
                    gui.fill(bx0, by0, bx0 + 1, by0 + len, color);
                    gui.fill(bx1 - len, by0, bx1, by0 + 1, color);
                    gui.fill(bx1 - 1, by0, bx1, by0 + len, color);
                    gui.fill(bx0, by1 - 1, bx0 + len, by1, color);
                    gui.fill(bx0, by1 - len, bx0 + 1, by1, color);
                    gui.fill(bx1 - len, by1 - 1, bx1, by1, color);
                    gui.fill(bx1 - 1, by1 - len, bx1, by1, color);
                }
            }
        }

        private void drawArrow(GuiGraphicsExtractor gui, float[] from, float[] to, ScreenRectangle clip) {
            float dx = to[0] - from[0];
            float dy = to[1] - from[1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 4) return;
            float ux = dx / len, uy = dy / len;
            drawLine(gui, from[0], from[1], to[0], to[1], clip);
            float head = Math.min(7f, len / 2);
            // arrow head: two strokes at +-30 degrees
            for (int side = -1; side <= 1; side += 2) {
                float hx = -ux * 0.866f - side * uy * 0.5f;
                float hy = -uy * 0.866f + side * ux * 0.5f;
                drawLine(gui, to[0], to[1], to[0] + hx * head, to[1] + hy * head, clip);
            }
        }

        /** 2 px thick line made of small fills (the GUI has no line primitive). */
        private void drawLine(GuiGraphicsExtractor gui, float x0, float y0, float x1, float y1, ScreenRectangle clip) {
            int steps = (int) Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            for (int i = 0; i <= steps; i++) {
                float t = steps == 0 ? 0 : (float) i / steps;
                int x = Math.round(x0 + (x1 - x0) * t);
                int y = Math.round(y0 + (y1 - y0) * t);
                if (x >= clip.left() && x + 2 <= clip.right() && y >= clip.top() && y + 2 <= clip.bottom()) {
                    gui.fill(x, y, x + 2, y + 2, FRONT_COLOR);
                }
            }
        }

        private void drawClippedText(GuiGraphicsExtractor gui, net.minecraft.client.gui.Font font, String text, float[] at,
                                     ScreenRectangle clip) {
            int w = font.width(text);
            int x = Math.max(clip.left() + 1, Math.min(clip.right() - w - 1, Math.round(at[0]) - w / 2));
            int y = Math.max(clip.top() + 1, Math.min(clip.bottom() - 9, Math.round(at[1]) + 4));
            gui.text(font, text, x, y, FRONT_COLOR, true);
        }

        private boolean playerEdge2d(double mouseX, double mouseY) {
            int cols = mb.maxX() - mb.minX() + 1;
            int x0 = gridX + (gridCols - cols) * SLOT / 2;
            int edgeY = FRONT_SIGN > 0 ? HEADER - 2 : HEADER + (mb.maxZ() - mb.minZ() + 1) * SLOT;
            return mouseX >= x0 && mouseX < x0 + cols * SLOT && mouseY >= edgeY - 1 && mouseY < edgeY + 3;
        }

        private static boolean containsPoint(ScreenRectangle r, double x, double y) {
            return x >= r.left() && x < r.right() && y >= r.top() && y < r.bottom();
        }

        private boolean coreCell2d(double mouseX, double mouseY) {
            if (mb.minY() + layer != 0) return false;
            int cols = mb.maxX() - mb.minX() + 1;
            int x0 = gridX + (gridCols - cols) * SLOT / 2 + mb.maxX() * SLOT;
            int y0 = HEADER + mb.maxZ() * SLOT;
            return mouseX >= x0 && mouseX < x0 + SLOT && mouseY >= y0 && mouseY < y0 + SLOT;
        }

        private void coreTooltip(ITooltipBuilder tooltip) {
            tooltip.add(Component.literal("Multiblock Core").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            tooltip.add(Component.literal("1. Place the core here, standing on the").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("   \"Player\" side: it turns to face you").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("2. Put the required block into it").withStyle(ChatFormatting.GRAY));
            Multiblock.BlockSpec spec = mb.blocks().get(new Multiblock.Pos(0, 0, 0));
            if (spec != null) {
                List<ItemStack> stacks = ItemResolver.blockStacks(spec);
                if (!stacks.isEmpty()) {
                    tooltip.add(Component.literal("   Required block: ").withStyle(ChatFormatting.GRAY)
                            .append(stacks.get(0).getHoverName().copy().withStyle(ChatFormatting.WHITE)));
                }
            }
        }

        /** Vanilla button look (nine-sliced widget sprites). */
        private void drawButton(GuiGraphicsExtractor gui, net.minecraft.client.gui.Font font, int x, String s, int w,
                                boolean enabled, double mouseX, double mouseY) {
            boolean hover = enabled && mouseX >= x && mouseX < x + w && onButtonRow(mouseY);
            Identifier sprite = !enabled ? BUTTON_OFF : hover ? BUTTON_HOVER : BUTTON;
            gui.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sprite, x, btnY, w, BTN_H);
            gui.centeredText(font, s, x + w / 2, btnY + 2, enabled ? 0xFFFFFFFF : 0xFFA0A0A0);
        }

        @Override
        public Optional<RecipeSlotUnderMouse> getSlotUnderMouse(double mouseX, double mouseY) {
            if (view3d) return Optional.empty();
            for (int i = 0; i < slots.size() && i < cells.size(); i++) {
                if (cells.get(i).layer != layer) continue;
                IRecipeSlotDrawable slot = slots.get(i);
                if (slot.isMouseOver(mouseX, mouseY)) return Optional.of(new RecipeSlotUnderMouse(slot, 0, 0));
            }
            return Optional.empty();
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip, double mouseX, double mouseY) {
            boolean onButtons = onButtonRow(mouseY);
            if (onButtons && has2d && mouseX >= toggleX() && mouseX < toggleX() + TOGGLE_W) {
                tooltip.add(Component.literal(view3d ? "Switch to the top view (one layer)" : "Switch to the 3D view"));
            } else if (onButtons && mouseX >= prevX() && mouseX < nextX() + BUTTON_W) {
                tooltip.add(Component.literal(view3d ? "Hide / show the top layers" : "Change layer (or scroll over the grid)"));
            } else if (view3d && coreBox != null && containsPoint(coreBox, mouseX, mouseY)) {
                coreTooltip(tooltip);
            } else if (view3d && frontTip != null && Math.abs(mouseX - frontTip[0]) < 12 && Math.abs(mouseY - frontTip[1]) < 8) {
                tooltip.add(Component.literal("Stand on this side to place the core").withStyle(ChatFormatting.GREEN));
                tooltip.add(Component.literal("It turns to face the player who places it").withStyle(ChatFormatting.GRAY));
            } else if (!view3d && playerEdge2d(mouseX, mouseY)) {
                tooltip.add(Component.literal("Player side: stand here to place the core").withStyle(ChatFormatting.GREEN));
            } else if (!view3d && coreCell2d(mouseX, mouseY)) {
                coreTooltip(tooltip);
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
            if (onButtonRow(mouseY)) {
                if (has2d && mouseX >= toggleX() && mouseX < toggleX() + TOGGLE_W) {
                    if (!input.isSimulate()) {
                        view3d = !view3d;
                        // top view opens on the core level, 3D view on the whole structure
                        setLayer(view3d ? layerCount - 1 : -mb.minY());
                    }
                    return true;
                }
                int delta;
                if (mouseX >= prevX() && mouseX < prevX() + BUTTON_W) delta = -1;
                else if (mouseX >= nextX() && mouseX < nextX() + BUTTON_W) delta = 1;
                else return false;
                if (!input.isSimulate()) setLayer(layer + delta);
                return true;
            }
            if (view3d && inView(mouseX, mouseY)) {
                // grab the click so dragging rotates the view instead of starting a JEI drag
                dragging = true;
                return true;
            }
            return false;
        }

        @Override
        public boolean handleMouseDragged(double mouseX, double mouseY, InputConstants.Key mouseKey, double dragX, double dragY) {
            if (!view3d || (!dragging && !inView(mouseX, mouseY))) return false;
            yaw = (yaw + (float) dragX * 1.5f) % 360f;
            pitch = Math.max(-90f, Math.min(90f, pitch + (float) dragY * 1.5f));
            return true;
        }

        @Override
        public void handleMouseMoved(double mouseX, double mouseY) {
            if (!inView(mouseX, mouseY)) dragging = false;
        }

        @Override
        public boolean handleMouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
            if (!inView(mouseX, mouseY) || scrollDeltaY == 0) return false;
            if (view3d) zoom = Math.max(0.3f, Math.min(3f, zoom * (scrollDeltaY > 0 ? 1.15f : 1f / 1.15f)));
            else setLayer(layer + (scrollDeltaY > 0 ? 1 : -1));
            return true;
        }

        private void setLayer(int l) {
            layer = Math.max(0, Math.min(layerCount - 1, l));
        }
    }
}

package com.terfrecipes.client.jei;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * A clickable shortcut drawn on a recipe page: a vanilla-looking button, or a text link
 * (underlined on hover). Used to jump between a machine's recipes and its structure.
 */
final class LinkButton implements IRecipeWidget, IJeiInputHandler {

    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");
    static final int HEIGHT = 12;

    private final int x;
    private final int y;
    private final int w;
    private final int h;
    private final Component label;
    private final boolean textLink;
    private final boolean shadow;
    private final int color;
    private final List<Component> tooltip;
    private final Runnable action;
    private final int pageWidth;
    private final int pageHeight;

    private LinkButton(int x, int y, int w, int h, Component label, boolean textLink, boolean shadow, int color,
                       List<Component> tooltip, Runnable action, int pageWidth, int pageHeight) {
        this.shadow = shadow;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.label = label;
        this.textLink = textLink;
        this.color = color;
        this.tooltip = tooltip;
        this.action = action;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
    }

    /** Button whose right edge is at {@code right}. */
    static LinkButton button(int right, int y, String label, List<Component> tooltip, Runnable action, int pageWidth, int pageHeight) {
        int w = Minecraft.getInstance().font.width(label) + 8;
        return new LinkButton(right - w, y, w, HEIGHT, Component.literal(label), false, true, 0xFFFFFFFF, tooltip, action,
                pageWidth, pageHeight);
    }

    /** Text link whose right edge is at {@code right}. */
    static LinkButton text(int right, int y, String label, int color, List<Component> tooltip, Runnable action, int pageWidth, int pageHeight) {
        int w = Minecraft.getInstance().font.width(label);
        return new LinkButton(right - w, y, w, 9, Component.literal(label), true, true, color, tooltip, action, pageWidth, pageHeight);
    }

    /** A page title that is also a link (underlined on hover), left edge at {@code x}. */
    static LinkButton title(int x, int y, Component title, int color, List<Component> tooltip, Runnable action,
                            int pageWidth, int pageHeight) {
        int w = Minecraft.getInstance().font.width(title);
        return new LinkButton(x, y, w, 9, title, true, false, color, tooltip, action, pageWidth, pageHeight);
    }

    private boolean over(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public ScreenPosition getPosition() {
        return new ScreenPosition(0, 0);
    }

    @Override
    public void drawWidget(GuiGraphicsExtractor gui, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        boolean hover = over(mouseX, mouseY);
        if (textLink) {
            int c = hover ? (shadow ? 0xFFFFFFFF : 0xFF3F76E4) : color;
            gui.text(font, label, x, y, c, shadow);
            if (hover) gui.fill(x, y + 9, x + w, y + 10, c);
        } else {
            gui.blitSprite(RenderPipelines.GUI_TEXTURED, hover ? BUTTON_HOVER : BUTTON, x, y, w, h);
            gui.centeredText(font, label, x + w / 2, y + 2, color);
        }
    }

    @Override
    public void getTooltip(ITooltipBuilder builder, double mouseX, double mouseY) {
        if (over(mouseX, mouseY)) builder.addAll(tooltip);
    }

    @Override
    public ScreenRectangle getArea() {
        return new ScreenRectangle(0, 0, pageWidth, pageHeight);
    }

    @Override
    public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
        if (input.getKey().getType() != InputConstants.Type.MOUSE || !over(mouseX, mouseY)) return false;
        if (!input.isSimulate()) Minecraft.getInstance().execute(action); // after JEI finished handling the click
        return true;
    }
}

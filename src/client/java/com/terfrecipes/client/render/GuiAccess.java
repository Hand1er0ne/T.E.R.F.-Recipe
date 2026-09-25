package com.terfrecipes.client.render;

import com.terfrecipes.TERFRecipes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GuiGraphicsExtractor keeps its render state and scissor stack private (vanilla only submits
 * its own picture-in-picture elements), so they are reached by reflection. Minecraft 26.x is not
 * obfuscated, so the field names are stable.
 */
public final class GuiAccess {

    private static @Nullable Field renderState;
    private static @Nullable Field scissorStack;
    private static @Nullable Method peek;
    private static boolean failed;

    private GuiAccess() {
    }

    private static synchronized boolean init() {
        if (renderState != null) return true;
        if (failed) return false;
        try {
            renderState = GuiGraphicsExtractor.class.getDeclaredField("guiRenderState");
            renderState.setAccessible(true);
            try {
                scissorStack = GuiGraphicsExtractor.class.getDeclaredField("scissorStack");
                scissorStack.setAccessible(true);
                peek = scissorStack.getType().getDeclaredMethod("peek");
                peek.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                scissorStack = null;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            TERFRecipes.LOGGER.warn("[TERF Recipes] 3D view unavailable: {}", e.toString());
            return false;
        }
    }

    public static void submit(GuiGraphicsExtractor gui, PictureInPictureRenderState state) {
        if (!init()) return;
        try {
            ((GuiRenderState) renderState.get(gui)).addPicturesInPictureState(state);
        } catch (ReflectiveOperationException | RuntimeException e) {
            TERFRecipes.LOGGER.debug("[TERF Recipes] Could not submit the 3D view", e);
        }
    }

    public static @Nullable ScreenRectangle scissor(GuiGraphicsExtractor gui) {
        if (!init() || scissorStack == null || peek == null) return null;
        try {
            return (ScreenRectangle) peek.invoke(scissorStack.get(gui));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}

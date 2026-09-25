package com.terfrecipes.client.render;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One frame of the 3D multiblock view, submitted to the GUI as a picture-in-picture element.
 *
 * @param scale  GUI pixels per block
 * @param yaw    rotation around the vertical axis, degrees
 * @param pitch  rotation around the horizontal axis, degrees
 * @param center   structure center (blocks are drawn relative to it)
 * @param coreItem the Multiblock Core model, drawn at 0,0,0 like the datapack's item_display (EMPTY = none)
 */
public record StructureRenderState(
        List<Placed> blocks,
        ItemStack coreItem,
        float centerX, float centerY, float centerZ,
        float yaw, float pitch,
        int x0, int y0, int x1, int y1,
        float scale,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {

    /** A block to draw at (x, y, z): its block state, or an item when the block has no model (fluids, chests...). */
    public record Placed(int x, int y, int z, @Nullable BlockState state, ItemStack item) {
    }

    public StructureRenderState(List<Placed> blocks, ItemStack coreItem, float centerX, float centerY, float centerZ, float yaw, float pitch,
                                int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea) {
        this(blocks, coreItem, centerX, centerY, centerZ, yaw, pitch, x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }
}

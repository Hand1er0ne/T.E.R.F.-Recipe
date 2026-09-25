package com.terfrecipes.client.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.terfrecipes.TERFRecipes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.LightLayer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a multiblock structure in 3D into an off-screen texture (vanilla picture-in-picture
 * mechanism, the same one used for the player in the inventory). Registered through Fabric's
 * PictureInPictureRendererRegistry in {@code TERFRecipesClient}.
 */
public class StructureRenderer extends PictureInPictureRenderer<StructureRenderState> {

    private static final int FULL_BRIGHT = 0xF000F0;

    public StructureRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<StructureRenderState> getRenderStateClass() {
        return StructureRenderState.class;
    }

    @Override
    protected String getTextureLabel() {
        return "terf_recipes_structure";
    }

    /** Origin in the middle of the texture (the default is the bottom edge). */
    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2f;
    }

    @Override
    protected void renderToTexture(StructureRenderState state, PoseStack pose) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        try {
            mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
            FeatureRenderDispatcher dispatcher = mc.gameRenderer.getFeatureRenderDispatcher();
            SubmitNodeCollector collector = dispatcher.getSubmitNodeStorage();

            // the texture space is y-down like the screen: turn the world upside up, then the camera
            pose.mulPose(Axis.ZP.rotation((float) Math.PI));
            pose.mulPose(Axis.XP.rotationDegrees(state.pitch()));
            pose.mulPose(Axis.YP.rotationDegrees(state.yaw()));
            pose.translate(-state.centerX(), -state.centerY(), -state.centerZ());

            BlockPos lightPos = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
            var biome = level.getBiome(lightPos);
            for (StructureRenderState.Placed b : state.blocks()) {
                pose.pushPose();
                pose.translate(b.x(), b.y(), b.z());
                BlockState st = b.state();
                if (st != null && st.getBlock() instanceof LiquidBlock && !st.getFluidState().isEmpty()) {
                    // water / lava source: tesselated like in the world
                    renderFluid(mc, st, pose, biome, level);
                } else if (st != null && hasGeometry(mc, st)) {
                    FullBrightBlock block = new FullBrightBlock();
                    block.blockState = st;
                    block.blockPos = BlockPos.ZERO;
                    block.randomSeedPos = BlockPos.ZERO;
                    block.biome = biome;
                    block.cardinalLighting = level.cardinalLighting();
                    block.lightEngine = level.getLightEngine();
                    collector.submitMovingBlock(pose, block);
                } else if (!b.item().isEmpty()) {
                    // drawn by a block entity renderer (chest, decorated pot, sign...): its item, full size
                    pose.translate(0.5f, 0.5f, 0.5f);
                    if (st != null && st.getBlock().getClass().getSimpleName().startsWith("Wall")
                            && st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                        // wall sign / banner: flat against the block it hangs on, facing out
                        Direction facing = st.getValue(BlockStateProperties.HORIZONTAL_FACING);
                        pose.translate(-facing.getStepX() * 0.44f, 0f, -facing.getStepZ() * 0.44f);
                        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
                    }
                    ItemStackRenderState item = new ItemStackRenderState();
                    mc.getItemModelResolver().updateForTopItem(item, b.item(), ItemDisplayContext.NONE, level, null, 0);
                    item.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                }
                pose.popPose();
            }
            if (!state.coreItem().isEmpty()) {
                // same place as the datapack's display: summoned at the bottom-center of the core block
                pose.pushPose();
                pose.translate(0.5f, 0f, 0.5f);
                ItemStackRenderState core = new ItemStackRenderState();
                mc.getItemModelResolver().updateForTopItem(core, state.coreItem(), ItemDisplayContext.NONE, level, null, 0);
                core.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                pose.popPose();
            }
            dispatcher.renderAllFeatures();
        } catch (RuntimeException e) {
            TERFRecipes.LOGGER.debug("[TERF Recipes] 3D structure view failed", e);
        }
    }

    private static final java.util.Map<BlockState, Boolean> GEOMETRY = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether the block model has faces (block-entity blocks like chests or pots have none). */
    private static boolean hasGeometry(Minecraft mc, BlockState state) {
        return GEOMETRY.computeIfAbsent(state, st -> {
            try {
                java.util.List<BlockStateModelPart> parts = new java.util.ArrayList<>();
                mc.getModelManager().getBlockStateModelSet().get(st).collectParts(RandomSource.create(42L), parts);
                for (BlockStateModelPart part : parts) {
                    if (!part.getQuads(null).isEmpty()) return true;
                    for (Direction d : Direction.values()) if (!part.getQuads(d).isEmpty()) return true;
                }
                return false;
            } catch (RuntimeException e) {
                return true;
            }
        });
    }

    private void renderFluid(Minecraft mc, BlockState st, PoseStack pose, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome,
                             ClientLevel level) {
        FullBrightBlock getter = new FullBrightBlock();
        getter.blockState = st;
        getter.blockPos = BlockPos.ZERO;
        getter.randomSeedPos = BlockPos.ZERO;
        getter.biome = biome;
        getter.cardinalLighting = level.cardinalLighting();
        getter.lightEngine = level.getLightEngine();
        Matrix4f matrix = new Matrix4f(pose.last().pose());
        Matrix3f normal = new Matrix3f(pose.last().normal());
        new FluidRenderer(mc.getModelManager().getFluidStateModelSet()).tesselate(getter, BlockPos.ZERO,
                layer -> new Transformed(bufferSource.getBuffer(switch (layer) {
                    case SOLID -> RenderTypes.solidMovingBlock();
                    case CUTOUT -> RenderTypes.cutoutMovingBlock();
                    default -> RenderTypes.translucentMovingBlock();
                }), matrix, normal),
                st, st.getFluidState());
    }

    /** Applies the pose to vertices written in block space (the fluid tesselator writes raw positions). */
    private record Transformed(VertexConsumer out, Matrix4f matrix, Matrix3f normal) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            Vector3f v = matrix.transformPosition(x, y, z, new Vector3f());
            out.addVertex(v.x, v.y, v.z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            out.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            out.setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            out.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            out.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            out.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            Vector3f n = normal.transform(x, y, z, new Vector3f()).normalize();
            out.setNormal(n.x, n.y, n.z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            out.setLineWidth(width);
            return this;
        }
    }

    /** A lone block lit as if in full daylight, wherever the player stands. */
    private static final class FullBrightBlock extends MovingBlockRenderState {
        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 15;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            return 15;
        }
    }
}

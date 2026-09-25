package com.terfrecipes.client.render;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.terfrecipes.TERFRecipes;
import com.terfrecipes.client.ItemResolver;
import com.terfrecipes.data.Multiblock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Show in world": the structure of a machine drawn as ghost blocks where it has to be built.
 * <ul>
 *     <li>anchored on the block the player looks at (where the Multiblock Core goes), turned so
 *     that the core faces the player, like the datapack does when the core is placed;</li>
 *     <li>blocks already placed correctly disappear, wrong ones are shown in red;</li>
 *     <li>keys (rebindable, "TERF Recipes" category): rotate, layer by layer, move here, remove.</li>
 * </ul>
 * Everything is client side, so it also works on servers without the mod.
 */
public final class Hologram {

    private static final int GHOST_COLOR = 0x88C8E6FF;   // missing block: light blue, translucent
    private static final int WRONG_COLOR = 0xCCFF3030;   // another block is there: red
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Local properties whose value depends on the structure's orientation (checked after rotation). */
    private static final Set<String> ORIENTED = Set.of("facing", "axis", "rotation", "shape", "hinge");

    private static KeyMapping rotateKey;
    private static KeyMapping layerKey;
    private static KeyMapping moveKey;
    private static KeyMapping clearKey;

    private static @Nullable Multiblock shown;
    private static BlockPos anchor = BlockPos.ZERO;
    private static Rotation rotation = Rotation.NONE;
    /** -1 = whole structure, else only this layer (0 = bottom). */
    private static int layer = -1;
    private static int tick;
    private static boolean completeAnnounced;

    private Hologram() {
    }

    public static void init() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(TERFRecipes.MOD_ID, "hologram"));
        rotateKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.terf-recipes.hologram_rotate",
                InputConstants.Type.KEYSYM, InputConstants.KEY_R, category));
        layerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.terf-recipes.hologram_layer",
                InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));
        moveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.terf-recipes.hologram_move",
                InputConstants.Type.KEYSYM, InputConstants.KEY_G, category));
        clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.terf-recipes.hologram_clear",
                InputConstants.Type.KEYSYM, InputConstants.KEY_H, category));
        ClientTickEvents.END_CLIENT_TICK.register(Hologram::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(Hologram::render);
    }

    // ------------------------------------------------------------------ control

    public static boolean isShown() {
        return shown != null;
    }

    /** Shows a structure where the player is looking (called from the JEI page). */
    public static void show(Multiblock mb) {
        shown = mb;
        layer = -1;
        completeAnnounced = false;
        placeHere();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[TERF Recipes] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(mb.name() + " shown in the world. ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("[" + keyName(rotateKey) + "] rotate  [" + keyName(layerKey) + "] layers  ["
                            + keyName(moveKey) + "] move here  [" + keyName(clearKey) + "] remove").withStyle(ChatFormatting.GRAY)));
        }
    }

    public static void clear() {
        shown = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) mc.gui.setOverlayMessage(Component.empty(), false);
    }

    private static String keyName(KeyMapping key) {
        return key.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
    }

    /** Core on the block space in front of the looked-at face, facing the player. */
    private static void placeHere() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            anchor = bhr.getBlockPos().relative(bhr.getDirection());
        } else {
            anchor = mc.player.blockPosition().relative(mc.player.getDirection(), 3);
        }
        // the datapack turns the core towards the player who places it: local "front" (+z) = towards the player
        Direction front = mc.player.getDirection().getOpposite();
        for (Rotation r : Rotation.values()) {
            if (r.rotate(Direction.SOUTH) == front) rotation = r;
        }
    }

    private static void tick(Minecraft mc) {
        if (rotateKey == null) return;
        boolean active = shown != null && mc.level != null && mc.player != null;
        while (rotateKey.consumeClick()) if (active) rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        while (moveKey.consumeClick()) if (active) placeHere();
        while (clearKey.consumeClick()) if (active) clear();
        while (layerKey.consumeClick()) {
            if (!active) continue;
            int count = shown.maxY() - shown.minY() + 1;
            layer = layer + 1 >= count ? -1 : layer + 1;
        }
        if (shown == null) return;
        if (mc.level == null) {
            shown = null; // left the world
            return;
        }
        if (++tick % 10 != 0) return;
        int[] status = status(mc.level);
        int count = shown.maxY() - shown.minY() + 1;
        String where = layer < 0 ? "" : " - layer " + (layer + 1) + "/" + count;
        Component msg;
        if (status[0] == 0 && status[1] == 0) {
            msg = Component.literal(shown.name() + where + ": complete!").withStyle(ChatFormatting.GREEN);
            if (layer < 0 && !completeAnnounced && mc.player != null) {
                completeAnnounced = true;
                mc.player.sendSystemMessage(Component.literal("[TERF Recipes] " + shown.name()
                        + " is built. Place the Multiblock Core in its block.").withStyle(ChatFormatting.GREEN));
            }
        } else {
            msg = Component.literal(shown.name() + where + ": ").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(status[0] + " missing").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(status[1] > 0 ? ", " + status[1] + " wrong" : "").withStyle(ChatFormatting.RED));
        }
        mc.gui.setOverlayMessage(msg, false);
    }

    // ------------------------------------------------------------------ comparison

    private static boolean visible(Multiblock.Pos p) {
        return layer < 0 || p.y() - shown.minY() == layer;
    }

    private static BlockPos worldPos(Multiblock.Pos p) {
        return anchor.offset(new BlockPos(p.x(), p.y(), p.z()).rotate(rotation));
    }

    /** [missing, wrong] among the visible blocks. */
    private static int[] status(ClientLevel level) {
        int missing = 0, wrong = 0;
        for (Map.Entry<Multiblock.Pos, Multiblock.BlockSpec> e : shown.blocks().entrySet()) {
            if (!visible(e.getKey())) continue;
            BlockState there = level.getBlockState(worldPos(e.getKey()));
            if (matches(e.getValue(), there)) continue;
            if (there.isAir() || there.canBeReplaced()) missing++;
            else wrong++;
        }
        return new int[]{missing, wrong};
    }

    /** Same test as the datapack's "if block": the block (or tag), and the states that do not depend on rotation. */
    static boolean matches(Multiblock.BlockSpec spec, BlockState state) {
        boolean blockOk = false;
        for (String alt : spec.alternatives()) {
            if (alt.startsWith("#")) {
                Identifier tag = Identifier.tryParse(alt.substring(1));
                if (tag != null && state.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, tag))) blockOk = true;
            } else {
                Identifier id = Identifier.tryParse(alt);
                if (id != null && BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(id)) blockOk = true;
                // the powered wire corner and the slab it shows as are the same thing for the player
                if (alt.equals("minecraft:red_glazed_terracotta") && state.getBlock() == net.minecraft.world.level.block.Blocks.GRANITE_SLAB) blockOk = true;
            }
            if (blockOk) break;
        }
        if (!blockOk) return false;
        if (spec.states().isEmpty()) return true;
        for (String kv : spec.states().split(",")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String key = kv.substring(0, eq).strip();
            String value = kv.substring(eq + 1).strip();
            if (ORIENTED.contains(key)) continue;
            Property<?> prop = state.getBlock().getStateDefinition().getProperty(key);
            if (prop != null && !valueName(state, prop).equals(value)) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }

    // ------------------------------------------------------------------ rendering

    private static void render(LevelRenderContext ctx) {
        Multiblock mb = shown;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (mb == null || level == null) return;
        try {
            Vec3 cam = ctx.levelState().cameraRenderState.pos;
            PoseStack pose = ctx.poseStack();
            MultiBufferSource.BufferSource buffers = ctx.bufferSource();
            RenderType type = RenderTypes.translucentMovingBlock();
            VertexConsumer out = buffers.getBuffer(type);
            long second = System.currentTimeMillis() / 1000;

            // chosen state of each visible position, turned with the structure
            Map<Multiblock.Pos, BlockState> chosen = new HashMap<>();
            for (Map.Entry<Multiblock.Pos, Multiblock.BlockSpec> e : mb.blocks().entrySet()) {
                if (!visible(e.getKey())) continue;
                List<BlockState> states = ItemResolver.blockStates(e.getValue());
                if (!states.isEmpty()) chosen.put(e.getKey(), states.get((int) (second % states.size())));
            }

            RandomSource random = RandomSource.create();
            List<BlockStateModelPart> parts = new ArrayList<>();
            for (Map.Entry<Multiblock.Pos, BlockState> e : chosen.entrySet()) {
                Multiblock.Pos p = e.getKey();
                BlockPos wp = worldPos(p);
                BlockState there = level.getBlockState(wp);
                if (matches(mb.blocks().get(p), there)) continue; // already built
                boolean wrong = !there.isAir() && !there.canBeReplaced();
                BlockState ghost = ItemResolver.connected(e.getValue(), d -> chosen.get(new Multiblock.Pos(
                        p.x() + d.getStepX(), p.y() + d.getStepY(), p.z() + d.getStepZ()))).rotate(rotation);

                pose.pushPose();
                pose.translate(wp.getX() - cam.x, wp.getY() - cam.y, wp.getZ() - cam.z);
                if (wrong) {
                    // slightly bigger than the block that is there, so the red shows on top
                    pose.translate(-0.01f, -0.01f, -0.01f);
                    pose.scale(1.02f, 1.02f, 1.02f);
                } else {
                    pose.translate(0.1f, 0.1f, 0.1f);
                    pose.scale(0.8f, 0.8f, 0.8f); // a bit smaller: easier to see what is still missing
                }
                QuadInstance quad = new QuadInstance();
                quad.setColor(wrong ? WRONG_COLOR : GHOST_COLOR);
                quad.setLightCoords(FULL_BRIGHT);
                quad.setOverlayCoords(OverlayTexture.NO_OVERLAY);
                parts.clear();
                random.setSeed(42L);
                mc.getModelManager().getBlockStateModelSet().get(ghost).collectParts(random, parts);
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad q : part.getQuads(null)) out.putBakedQuad(pose.last(), q, quad);
                    for (Direction d : Direction.values()) {
                        for (BakedQuad q : part.getQuads(d)) out.putBakedQuad(pose.last(), q, quad);
                    }
                }
                pose.popPose();
            }
            buffers.endBatch(type);
        } catch (RuntimeException e) {
            TERFRecipes.LOGGER.debug("[TERF Recipes] Hologram rendering failed", e);
        }
    }
}

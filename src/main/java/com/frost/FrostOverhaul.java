package com.frost;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.*;

public class FrostOverhaul implements ModInitializer {

    private static final Set<UUID> ACTIVE_MINERS = new HashSet<>();

    @Override
    public void onInitialize() {
        // 1. Boost Reach (7.0 blocks) & Mining Speed (Deepslate insta-mine helper) every second
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    AttributeInstance reach = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
                    if (reach != null && reach.getBaseValue() < 7.0) {
                        reach.setBaseValue(7.0);
                    }
                    AttributeInstance breakSpeed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
                    if (breakSpeed != null && breakSpeed.getBaseValue() < 3.5) {
                        breakSpeed.setBaseValue(3.5);
                    }
                }
            }
        });

        // 2. Smarter / Faster Hostile Mob AI (longer agro range & faster movement)
        ServerEntityEvents.ENTITY_LOAD.register((Entity entity, ServerLevel level) -> {
            if (entity instanceof Monster monster) {
                AttributeInstance follow = monster.getAttribute(Attributes.FOLLOW_RANGE);
                if (follow != null && follow.getBaseValue() < 40.0) {
                    follow.setBaseValue(40.0);
                }
                AttributeInstance speed = monster.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.setBaseValue(speed.getBaseValue() * 1.18);
                }
            }
        });

        // 3. 3x3 Mining, Veinminer, Full Tree Chopper (Logs + Leaves), and Drop Teleporting
        PlayerBlockBreakEvents.AFTER.register((Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) -> {
            if (level.isClientSide || !(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (ACTIVE_MINERS.contains(player.getUUID())) {
                return;
            }

            ItemStack tool = player.getMainHandItem();
            ACTIVE_MINERS.add(player.getUUID());

            try {
                // Always teleport drops of the primary block too
                collectAndTeleportDrops(serverLevel, serverPlayer, pos, state, tool, false);

                // Hold Sneak (Shift) to mine single blocks normally
                if (player.isShiftKeyDown()) {
                    return;
                }

                // A. VEINMINER (Any Ore with Pickaxe)
                if (tool.getItem() instanceof PickaxeItem && isOre(state)) {
                    veinMine(serverLevel, serverPlayer, pos, state.getBlock(), tool, 64);
                    return;
                }

                // B. FULL TREE CHOPPER (Logs + Connected Leaves with Axe)
                if (tool.getItem() instanceof AxeItem && state.is(BlockTags.LOGS)) {
                    chopTree(serverLevel, serverPlayer, pos, tool, 250);
                    return;
                }

                // C. 3x3 MINER (Pickaxe or Shovel)
                if (tool.getItem() instanceof PickaxeItem || tool.getItem() instanceof ShovelItem) {
                    mine3x3(serverLevel, serverPlayer, pos, tool);
                }
            } finally {
                ACTIVE_MINERS.remove(player.getUUID());
            }
        });
    }

    private boolean isOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.DIAMOND_ORES)
                || state.is(BlockTags.EMERALD_ORES) || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.getBlock().getDescriptionId().contains("quartz_ore")
                || state.getBlock().getDescriptionId().contains("ancient_debris");
    }

    private void mine3x3(ServerLevel level, ServerPlayer player, BlockPos center, ItemStack tool) {
        HitResult hit = player.pick(7.5, 0.0f, false);
        Direction face = (hit instanceof BlockHitResult bhr) ? bhr.getDirection() : Direction.NORTH;

        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;

                BlockPos target = switch (face.getAxis()) {
                    case Y -> center.offset(a, 0, b);
                    case X -> center.offset(0, a, b);
                    case Z -> center.offset(a, b, 0);
                };

                BlockState targetState = level.getBlockState(target);
                if (!targetState.isAir() && targetState.getDestroySpeed(level, target) >= 0 && tool.isCorrectToolForDrops(targetState)) {
                    collectAndTeleportDrops(level, player, target, targetState, tool, true);
                }
            }
        }
    }

    private void veinMine(ServerLevel level, ServerPlayer player, BlockPos start, Block targetBlock, ItemStack tool, int maxBlocks) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        int broken = 0;
        while (!queue.isEmpty() && broken < maxBlocks) {
            BlockPos current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = current.offset(dx, dy, dz);
                        if (!visited.contains(next)) {
                            visited.add(next);
                            BlockState nextState = level.getBlockState(next);
                            if (nextState.is(targetBlock)) {
                                queue.add(next);
                                collectAndTeleportDrops(level, player, next, nextState, tool, true);
                                broken++;
                            }
                        }
                    }
                }
            }
        }
    }

    private void chopTree(ServerLevel level, ServerPlayer player, BlockPos start, ItemStack tool, int maxBlocks) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        int broken = 0;
        while (!queue.isEmpty() && broken < maxBlocks) {
            BlockPos current = queue.poll();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos next = current.offset(dx, dy, dz);
                        if (!visited.contains(next)) {
                            visited.add(next);
                            BlockState nextState = level.getBlockState(next);
                            if (nextState.is(BlockTags.LOGS) || nextState.is(BlockTags.LEAVES)) {
                                queue.add(next);
                                collectAndTeleportDrops(level, player, next, nextState, tool, true);
                                broken++;
                            }
                        }
                    }
                }
            }
        }
    }

    private void collectAndTeleportDrops(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state, ItemStack tool, boolean breakBlock) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, tool);
        if (breakBlock) {
            level.removeBlock(pos, false);
        }
        for (ItemStack drop : drops) {
            if (!player.getInventory().add(drop)) {
                player.drop(drop, false);
            }
        }
    }
}

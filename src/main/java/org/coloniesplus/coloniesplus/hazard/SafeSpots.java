package org.coloniesplus.coloniesplus.hazard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds places a citizen can stand safely: solid ground, room for its body, and nothing that
 * hurts it. Shared by hazard escape and unsticking, so both agree on what "safe" means.
 */
final class SafeSpots
{
    private static final int SEARCH_RADIUS   = 8;
    private static final int SEARCH_HEIGHT   = 3;
    private static final int MIN_DISTANCE_SQ = 2;

    /**
     * Each block up or down costs as much as four blocks sideways (distances are squared), so
     * spots at the citizen's own height win over roofs, wall tops and cellars.
     */
    private static final int VERTICAL_COST = 16;

    /**
     * A spot must connect to at least this many other standable spots, so a citizen is never put
     * into a sealed pocket or onto a tiny ledge it cannot leave.
     */
    private static final int MIN_OPEN_AREA = 12;

    private SafeSpots()
    {
    }

    /**
     * The nearest spot a citizen can stand on without touching a hazard, at least a block away,
     * preferring its own height and only where it can walk on from.
     */
    @Nullable
    static BlockPos find(final Level level, final BlockPos from)
    {
        final List<BlockPos> candidates = new ArrayList<>();
        for (final BlockPos pos : BlockPos.betweenClosed(
            from.offset(-SEARCH_RADIUS, -SEARCH_HEIGHT, -SEARCH_RADIUS),
            from.offset(SEARCH_RADIUS, SEARCH_HEIGHT, SEARCH_RADIUS)))
        {
            if (pos.distSqr(from) >= MIN_DISTANCE_SQ && isSafe(level, pos))
            {
                candidates.add(pos.immutable());
            }
        }
        candidates.sort(Comparator.comparingInt(pos -> cost(pos, from)));
        for (final BlockPos pos : candidates)
        {
            if (isOpenArea(level, pos))
            {
                return pos;
            }
        }
        return null;
    }

    private static int cost(final BlockPos pos, final BlockPos from)
    {
        final int dx = pos.getX() - from.getX();
        final int dy = pos.getY() - from.getY();
        final int dz = pos.getZ() - from.getZ();
        return dx * dx + dz * dz + VERTICAL_COST * dy * dy;
    }

    /**
     * Whether a walk of a few steps from {@code start} (one block up or down at a time) reaches
     * {@link #MIN_OPEN_AREA} standable spots.
     */
    static boolean isOpenArea(final Level level, final BlockPos start)
    {
        final Set<BlockPos> visited = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty())
        {
            final BlockPos pos = queue.poll();
            for (final Direction side : Direction.Plane.HORIZONTAL)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    final BlockPos next = pos.relative(side).above(dy);
                    if (next.distManhattan(start) > SEARCH_RADIUS || visited.contains(next) || !isSafe(level, next))
                    {
                        continue;
                    }
                    if (visited.size() + 1 >= MIN_OPEN_AREA)
                    {
                        return true;
                    }
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return false;
    }

    static boolean isSafe(final Level level, final BlockPos feet)
    {
        final BlockPos floor = feet.below();
        final BlockState floorState = level.getBlockState(floor);
        if (!floorState.isFaceSturdy(level, floor, Direction.UP) || isHazard(floorState) || !level.getFluidState(floor.above()).isEmpty())
        {
            return false;
        }

        for (final BlockPos body : new BlockPos[] {feet, feet.above()})
        {
            final BlockState state = level.getBlockState(body);
            if (!state.getCollisionShape(level, body).isEmpty() || isHazard(state) || !level.getFluidState(body).isEmpty())
            {
                return false;
            }
            // Cactus hurts whatever brushes against its sides.
            for (final Direction side : Direction.Plane.HORIZONTAL)
            {
                if (level.getBlockState(body.relative(side)).is(Blocks.CACTUS))
                {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return true if the block at {@code pos} or the one under it hurts a citizen.
     */
    static boolean isHazard(final Level level, final BlockPos pos)
    {
        return isHazard(level.getBlockState(pos)) || isHazard(level.getBlockState(pos.below()));
    }

    static boolean isHazard(final BlockState state)
    {
        return state.is(Blocks.CACTUS)
            || state.is(BlockTags.FIRE)
            || state.is(BlockTags.CAMPFIRES)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.WITHER_ROSE)
            || state.getFluidState().is(FluidTags.LAVA);
    }
}

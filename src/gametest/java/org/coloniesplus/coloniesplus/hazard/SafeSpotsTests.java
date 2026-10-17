package org.coloniesplus.coloniesplus.hazard;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.coloniesplus.coloniesplus.Coloniesplus;

/**
 * Where hazard escape and unsticking put citizens. Each test builds a stone floor across the
 * 24x10x24 test area and searches from its middle.
 */
@GameTestHolder(Coloniesplus.MODID)
@PrefixGameTestTemplate(false)
public class SafeSpotsTests
{
    private static final BlockPos MIDDLE = new BlockPos(12, 1, 12);

    @GameTest(template = "empty")
    public static void findsSpotNextToCitizen(final GameTestHelper helper)
    {
        floor(helper);
        final BlockPos found = find(helper, MIDDLE);
        final BlockPos middle = helper.absolutePos(MIDDLE);
        helper.assertTrue(found != null, "No safe spot found on an open floor");
        helper.assertTrue(found.getY() == middle.getY(), "Safe spot is not at the citizen's height: " + found);
        helper.assertTrue(found.distSqr(middle) <= 2, "Safe spot is not right next to the citizen: " + found + " vs " + middle);
        helper.succeed();
    }

    /**
     * A citizen stuck in a wall of stone with a 1x2 hole beside it must be put in the open area
     * further away: not in the hole it could never walk out of, and not on top of the wall.
     */
    @GameTest(template = "empty")
    public static void skipsSealedPocket(final GameTestHelper helper)
    {
        floor(helper);
        fill(helper, 0, 1, 0, 17, 2, 23, Blocks.STONE);
        helper.setBlock(13, 1, 12, Blocks.AIR);
        helper.setBlock(13, 2, 12, Blocks.AIR);

        final BlockPos found = find(helper, MIDDLE);
        helper.assertTrue(found != null, "No safe spot found next to the open area");
        helper.assertTrue(!found.equals(helper.absolutePos(new BlockPos(13, 1, 12))), "Citizen was put into a sealed pocket");
        boolean inOpenArea = false;
        for (final BlockPos open : BlockPos.betweenClosed(18, 1, 0, 23, 1, 23))
        {
            inOpenArea |= helper.absolutePos(open).equals(found);
        }
        helper.assertTrue(inOpenArea, "Safe spot is not in the open area: " + found);
        helper.succeed();
    }

    /**
     * A citizen stuck inside a block of stone is put on the ground beside it, even though the top
     * of the block is closer in a straight line.
     */
    @GameTest(template = "empty")
    public static void prefersOwnHeightOverRoof(final GameTestHelper helper)
    {
        floor(helper);
        fill(helper, 9, 1, 9, 15, 3, 15, Blocks.STONE);

        final BlockPos found = find(helper, MIDDLE);
        helper.assertTrue(found != null, "No safe spot found around the block");
        helper.assertTrue(found.getY() == helper.absolutePos(MIDDLE).getY(), "Citizen was put on the roof instead of the ground: " + found);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hazardsAreNotSafe(final GameTestHelper helper)
    {
        floor(helper);
        helper.setBlock(4, 1, 4, Blocks.FIRE);
        helper.setBlock(8, 0, 4, Blocks.MAGMA_BLOCK);
        helper.setBlock(13, 1, 4, Blocks.CACTUS);

        helper.assertTrue(isSafe(helper, new BlockPos(18, 1, 18)), "An open floor spot is not safe");
        helper.assertTrue(!isSafe(helper, new BlockPos(4, 1, 4)), "Standing in fire counts as safe");
        helper.assertTrue(!isSafe(helper, new BlockPos(8, 1, 4)), "Standing on magma counts as safe");
        helper.assertTrue(!isSafe(helper, new BlockPos(12, 1, 4)), "Standing next to a cactus counts as safe");
        helper.succeed();
    }

    /**
     * @return the spot in world coordinates; compare with {@link GameTestHelper#absolutePos}.
     */
    private static BlockPos find(final GameTestHelper helper, final BlockPos from)
    {
        return SafeSpots.find(helper.getLevel(), helper.absolutePos(from));
    }

    private static boolean isSafe(final GameTestHelper helper, final BlockPos pos)
    {
        return SafeSpots.isSafe(helper.getLevel(), helper.absolutePos(pos));
    }

    private static void floor(final GameTestHelper helper)
    {
        fill(helper, 0, 0, 0, 23, 0, 23, Blocks.STONE);
    }

    private static void fill(final GameTestHelper helper, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2, final Block block)
    {
        for (final BlockPos pos : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2))
        {
            helper.setBlock(pos, block);
        }
    }
}

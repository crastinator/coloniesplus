package org.coloniesplus.coloniesplus.conversion;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.coloniesplus.coloniesplus.Coloniesplus;
import org.coloniesplus.coloniesplus.Config;

import java.util.List;

/**
 * What builders convert, from plain inventories standing in for the hut, its racks and the
 * builder. Each test sets the config it depends on, so the run's config file does not matter.
 */
@GameTestHolder(Coloniesplus.MODID)
@PrefixGameTestTemplate(false)
public class ConversionPlannerTests
{
    private static final ConversionPlanner.Options NONE         = new ConversionPlanner.Options(false, false);
    private static final ConversionPlanner.Options SUBSTITUTION = new ConversionPlanner.Options(true, false);
    private static final ConversionPlanner.Options ALL          = new ConversionPlanner.Options(true, true);

    /**
     * Crafter hut levels in {@link CrafterProgress.Trade} order: wood, stone, wool, metal.
     */
    private static final CrafterProgress NO_HUTS = CrafterProgress.fromLevels(new int[] {0, 0, 0, 0});
    private static final CrafterProgress SAWMILL_1 = CrafterProgress.fromLevels(new int[] {1, 0, 0, 0});
    private static final CrafterProgress SAWMILL_5 = CrafterProgress.fromLevels(new int[] {5, 0, 0, 0});

    @GameTest(template = "empty")
    public static void grassBecomesDirtWithoutLimits(final GameTestHelper helper)
    {
        defaults();
        final ConversionPlanner.Plan plan = plan(helper, NO_HUTS, NONE, Items.DIRT, 8, List.of(), new ItemStack(Items.GRASS_BLOCK, 10));
        helper.assertTrue(plan != null, "Grass blocks were not turned into dirt with no huts and both switches off");
        assertInputs(helper, plan, new ItemStack(Items.GRASS_BLOCK, 8));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void grassTheBuildNeedsIsKept(final GameTestHelper helper)
    {
        defaults();
        final ConversionPlanner.Plan plan = plan(helper, NO_HUTS, ALL, Items.DIRT, 5, List.of(new ItemStack(Items.GRASS_BLOCK, 5)), new ItemStack(Items.GRASS_BLOCK, 5));
        helper.assertTrue(plan == null, "Grass blocks the build needs were turned into dirt");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anyLogSawsIntoPlanks(final GameTestHelper helper)
    {
        defaults();
        final ConversionPlanner.Plan plan = plan(helper, SAWMILL_5, SUBSTITUTION, Items.OAK_PLANKS, 8, List.of(), new ItemStack(Items.SPRUCE_LOG, 2));
        helper.assertTrue(plan != null, "Spruce logs were not sawn into oak planks");
        assertInputs(helper, plan, new ItemStack(Items.SPRUCE_LOG, 2));
        helper.assertTrue(plan.outputs().stream().anyMatch(stack -> stack.is(Items.OAK_PLANKS) && stack.getCount() == 8), "Plan does not make 8 oak planks: " + plan.outputs());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void logsSwapForOtherLogs(final GameTestHelper helper)
    {
        defaults();
        final ConversionPlanner.Plan plan = plan(helper, SAWMILL_5, SUBSTITUTION, Items.OAK_LOG, 6, List.of(), new ItemStack(Items.SPRUCE_LOG, 4), new ItemStack(Items.CRIMSON_STEM, 4));
        helper.assertTrue(plan != null, "Spruce logs and crimson stems were not swapped for oak logs");
        helper.assertTrue(plan.inputs().stream().mapToInt(ItemStack::getCount).sum() == 6, "Plan does not use 6 logs: " + plan.inputs());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void logsOnlySwapForTheSameForm(final GameTestHelper helper)
    {
        defaults();
        final ConversionPlanner.Plan plan = plan(helper, SAWMILL_5, SUBSTITUTION, Items.STRIPPED_OAK_LOG, 4, List.of(),
            new ItemStack(Items.SPRUCE_LOG, 4), new ItemStack(Items.STRIPPED_BIRCH_WOOD, 4), new ItemStack(Items.SPRUCE_WOOD, 4));
        helper.assertTrue(plan == null, "A stripped log was made from logs or wood of another form");

        final ConversionPlanner.Plan stripped = plan(helper, SAWMILL_5, SUBSTITUTION, Items.STRIPPED_OAK_LOG, 4, List.of(), new ItemStack(Items.STRIPPED_SPRUCE_LOG, 4));
        helper.assertTrue(stripped != null, "Stripped spruce logs were not swapped for stripped oak logs");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void logsNeedASawmill(final GameTestHelper helper)
    {
        defaults();
        final ConversionPlanner.Plan plan = plan(helper, NO_HUTS, SUBSTITUTION, Items.OAK_PLANKS, 8, List.of(), new ItemStack(Items.SPRUCE_LOG, 2));
        helper.assertTrue(plan == null, "Logs were sawn without a Sawmill");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void logSawingCanBeTurnedOff(final GameTestHelper helper)
    {
        defaults();
        Config.logsToPlanks = false;
        try
        {
            final ConversionPlanner.Plan plan = plan(helper, SAWMILL_5, ALL, Items.OAK_PLANKS, 8, List.of(), new ItemStack(Items.SPRUCE_LOG, 2), new ItemStack(Items.OAK_LOG, 2));
            helper.assertTrue(plan == null, "Logs were sawn with logsToPlanks off");
        }
        finally
        {
            Config.logsToPlanks = true;
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lowSawmillWastesPlanks(final GameTestHelper helper)
    {
        defaults();
        // 40% waste at level 1: 10 planks cost 14.
        final ConversionPlanner.Plan plan = plan(helper, SAWMILL_1, SUBSTITUTION, Items.OAK_PLANKS, 10, List.of(), new ItemStack(Items.BIRCH_PLANKS, 20));
        helper.assertTrue(plan != null, "Birch planks were not swapped for oak planks");
        assertInputs(helper, plan, new ItemStack(Items.BIRCH_PLANKS, 14));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recipesUseLogsOfAnyWood(final GameTestHelper helper)
    {
        defaults();
        // 4 oak stairs take 6 planks; 2 spruce logs saw into 8.
        final ConversionPlanner.Plan plan = plan(helper, SAWMILL_5, ALL, Items.OAK_STAIRS, 4, List.of(), new ItemStack(Items.SPRUCE_LOG, 2));
        helper.assertTrue(plan != null, "Oak stairs were not crafted from spruce logs");
        assertInputs(helper, plan, new ItemStack(Items.SPRUCE_LOG, 2));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void notEnoughMaterialMeansNoPlan(final GameTestHelper helper)
    {
        defaults();
        final ConversionPlanner.Plan plan = plan(helper, SAWMILL_5, ALL, Items.OAK_PLANKS, 64, List.of(), new ItemStack(Items.BIRCH_PLANKS, 10));
        helper.assertTrue(plan == null, "A plan was made without enough material");
        helper.succeed();
    }

    /**
     * The shipped defaults, whatever the run's config file says.
     */
    private static void defaults()
    {
        Config.logsToPlanks = true;
        Config.requireCrafterHuts = true;
        Config.wastePercentByLevel = List.of(40, 30, 20, 10, 0);
    }

    private static ConversionPlanner.Plan plan(
        final GameTestHelper helper,
        final CrafterProgress progress,
        final ConversionPlanner.Options options,
        final net.minecraft.world.item.Item target,
        final int needed,
        final List<ItemStack> buildNeeds,
        final ItemStack... held)
    {
        final ItemStackHandler inventory = new ItemStackHandler(27);
        for (int i = 0; i < held.length; i++)
        {
            inventory.setStackInSlot(i, held[i].copy());
        }
        final List<IItemHandler> handlers = List.of(inventory);
        return ConversionPlanner.plan(handlers, buildNeeds, progress, new ItemStack(target), needed, options, helper.getLevel());
    }

    private static void assertInputs(final GameTestHelper helper, final ConversionPlanner.Plan plan, final ItemStack... expected)
    {
        final List<ItemStack> inputs = plan.inputs();
        helper.assertTrue(inputs.size() == expected.length, "Plan uses " + inputs + ", expected " + List.of(expected));
        for (final ItemStack stack : expected)
        {
            helper.assertTrue(inputs.stream().anyMatch(input -> ItemStack.isSameItem(input, stack) && input.getCount() == stack.getCount()),
                "Plan uses " + inputs + ", expected " + List.of(expected));
        }
    }
}

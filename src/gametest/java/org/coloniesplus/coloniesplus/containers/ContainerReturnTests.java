package org.coloniesplus.coloniesplus.containers;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.coloniesplus.coloniesplus.Coloniesplus;

import java.util.List;

/**
 * Moving bowls between inventories: only plain ones, only as many as fit, and never losing any.
 */
@GameTestHolder(Coloniesplus.MODID)
@PrefixGameTestTemplate(false)
public class ContainerReturnTests
{
    @GameTest(template = "empty")
    public static void movesPlainBowlsUpToRoom(final GameTestHelper helper)
    {
        final ItemStack named = new ItemStack(Items.BOWL, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Grandma's bowl"));
        final ItemStackHandler source = handler(9, new ItemStack(Items.BOWL, 20), named);
        final ItemStackHandler restaurant = handler(9);

        final int moved = ContainerReturn.drain(source, Items.BOWL, List.of(new ContainerReturn.Destination(restaurant, 16)), helper.getLevel(), null);

        helper.assertTrue(moved == 16, "Moved " + moved + " bowls, expected 16 (the room left)");
        helper.assertTrue(count(restaurant, Items.BOWL) == 16, "Restaurant holds " + count(restaurant, Items.BOWL) + " bowls, expected 16");
        helper.assertTrue(count(source, Items.BOWL) == 7, "Source holds " + count(source, Items.BOWL) + " bowls, expected 4 plain + 3 named");
        helper.assertTrue(source.getStackInSlot(1).has(DataComponents.CUSTOM_NAME), "A named bowl was moved");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullDestinationLosesNothing(final GameTestHelper helper)
    {
        final ItemStackHandler source = handler(9, new ItemStack(Items.BOWL, 20));
        final ItemStackHandler full = handler(1, new ItemStack(Items.STONE, 64));

        final int moved = ContainerReturn.drain(source, Items.BOWL, List.of(new ContainerReturn.Destination(full, Integer.MAX_VALUE)), helper.getLevel(), null);

        helper.assertTrue(moved == 0, "Moved " + moved + " bowls into a full inventory");
        helper.assertTrue(count(source, Items.BOWL) == 20, "Bowls went missing: " + count(source, Items.BOWL) + " of 20 left");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fillsDestinationsInOrder(final GameTestHelper helper)
    {
        final ItemStackHandler source = handler(9, new ItemStack(Items.BOWL, 40));
        final ItemStackHandler kitchen = handler(9);
        final ItemStackHandler warehouse = handler(9);

        final int moved = ContainerReturn.drain(source, Items.BOWL,
            List.of(new ContainerReturn.Destination(kitchen, 10), new ContainerReturn.Destination(warehouse, Integer.MAX_VALUE)), helper.getLevel(), null);

        helper.assertTrue(moved == 40, "Moved " + moved + " bowls, expected all 40");
        helper.assertTrue(count(kitchen, Items.BOWL) == 10, "Kitchen got " + count(kitchen, Items.BOWL) + " bowls, expected 10");
        helper.assertTrue(count(warehouse, Items.BOWL) == 30, "Warehouse got " + count(warehouse, Items.BOWL) + " bowls, expected 30");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bucketsGoToCowhandThenWarehouse(final GameTestHelper helper)
    {
        final ItemStackHandler chef = handler(9, new ItemStack(Items.BUCKET, 16), new ItemStack(Items.BUCKET, 4));
        final ItemStackHandler cowhand = handler(9, new ItemStack(Items.BUCKET, 6));
        final ItemStackHandler warehouse = handler(9);

        // The cowhand keeps 16, so it has room for 10 more.
        ContainerReturn.drain(chef, Items.BUCKET,
            List.of(new ContainerReturn.Destination(cowhand, 16 - 6), new ContainerReturn.Destination(warehouse, Integer.MAX_VALUE)), helper.getLevel(), null);

        helper.assertTrue(count(cowhand, Items.BUCKET) == 16, "Cowhand holds " + count(cowhand, Items.BUCKET) + " buckets, expected 16");
        helper.assertTrue(count(warehouse, Items.BUCKET) == 10, "Warehouse got " + count(warehouse, Items.BUCKET) + " buckets, expected 10");
        helper.assertTrue(count(chef, Items.BUCKET) == 0, "Chef still holds " + count(chef, Items.BUCKET) + " buckets");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void restaurantsKeepFoodOnly(final GameTestHelper helper)
    {
        final int stacks = org.coloniesplus.coloniesplus.Config.restaurantFoodStacksPerLevel;
        org.coloniesplus.coloniesplus.Config.restaurantFoodStacksPerLevel = 4;
        try
        {
            helper.assertTrue(RestaurantPantry.isKeptFood(new ItemStack(Items.BREAD)), "Bread is not kept");
            helper.assertTrue(RestaurantPantry.isKeptFood(new ItemStack(Items.COOKED_MUTTON)), "Cooked mutton is not kept");
            helper.assertTrue(!RestaurantPantry.isKeptFood(new ItemStack(Items.STONE)), "Stone is kept as food");
            helper.assertTrue(!RestaurantPantry.isKeptFood(new ItemStack(Items.SPIDER_EYE)), "Poisonous food is kept");
            helper.assertTrue(RestaurantPantry.keptFood(3) == 3 * 4 * 64, "A level 3 restaurant keeps " + RestaurantPantry.keptFood(3) + " food, expected 768");
        }
        finally
        {
            org.coloniesplus.coloniesplus.Config.restaurantFoodStacksPerLevel = stacks;
        }
        helper.succeed();
    }

    private static ItemStackHandler handler(final int slots, final ItemStack... stacks)
    {
        final ItemStackHandler handler = new ItemStackHandler(slots);
        for (int i = 0; i < stacks.length; i++)
        {
            handler.setStackInSlot(i, stacks[i]);
        }
        return handler;
    }

    private static int count(final IItemHandler handler, final net.minecraft.world.item.Item item)
    {
        int count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
        {
            if (handler.getStackInSlot(slot).is(item))
            {
                count += handler.getStackInSlot(slot).getCount();
            }
        }
        return count;
    }
}

package org.coloniesplus.coloniesplus.containers;

import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IAltersRequiredItems;
import com.minecolonies.api.colony.buildings.modules.IBuildingModuleView;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.util.FoodUtils;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.util.TriConsumer;
import org.coloniesplus.coloniesplus.Config;

import java.util.function.Predicate;

/**
 * Keeps food in restaurants. MineColonies only keeps one stack per building level of each menu
 * item there, so couriers haul everything else to the warehouse: food a player stocked, raw food
 * and extra cooked meals, while citizens starve (MineColonies #11421, #11364). This adds any
 * edible food to the restaurant's keep list, up to a few stacks per level, so a full restaurant
 * can still be emptied.
 */
public class RestaurantPantry extends AbstractBuildingModule implements IAltersRequiredItems
{
    private static final Predicate<ItemStack> EDIBLE = stack -> FoodUtils.canEat(stack, null, null);

    /**
     * Not shown in the hut, so there is no view.
     */
    public static void register()
    {
        ModBuildings.cook.get().getModuleProducers().add(
            new BuildingEntry.ModuleProducer<RestaurantPantry, IBuildingModuleView>("coloniesplus_pantry", RestaurantPantry::new, null));
    }

    @Override
    public void alterItemsToBeKept(final TriConsumer<Predicate<ItemStack>, Integer, Boolean> consumer)
    {
        if (Config.keepRestaurantFood)
        {
            consumer.accept(EDIBLE, keptFood(building.getBuildingLevel()), false);
        }
    }

    /**
     * @return how much food in total a restaurant of this level keeps.
     */
    static int keptFood(final int buildingLevel)
    {
        return Math.max(1, buildingLevel) * Config.restaurantFoodStacksPerLevel * 64;
    }

    static boolean isKeptFood(final ItemStack stack)
    {
        return EDIBLE.test(stack);
    }
}

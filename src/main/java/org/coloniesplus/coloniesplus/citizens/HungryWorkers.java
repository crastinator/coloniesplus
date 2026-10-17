package org.coloniesplus.coloniesplus.citizens;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.FoodUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.coloniesplus.coloniesplus.Config;
import org.slf4j.Logger;

/**
 * Starving citizens eat the food they carry. MineColonies only lets a citizen eat when its job
 * gives way, so waiters and cooks can starve holding a pocket full of meals, and then the whole
 * restaurant stops serving (MineColonies #11442, #11449). This only steps in well below the point
 * where citizens normally go to eat, so restaurant trips stay the usual way of eating.
 */
public final class HungryWorkers
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One item per citizen per pass, so a starving citizen eats every few seconds until it is fed.
     */
    private static final int CHECK_INTERVAL_TICKS = 100;

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(final ServerTickEvent.Post event)
    {
        if (!Config.hungryWorkersEat || ++tickCounter < CHECK_INTERVAL_TICKS)
        {
            return;
        }
        tickCounter = 0;

        for (final ServerLevel level : event.getServer().getAllLevels())
        {
            for (final IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getColonies(level))
            {
                try
                {
                    for (final ICitizenData data : colony.getCitizenManager().getCitizens())
                    {
                        data.getEntity().ifPresent(citizen -> feed(data, citizen));
                    }
                }
                catch (final RuntimeException e)
                {
                    LOGGER.error("Feeding hungry citizens failed for colony {}", colony.getID(), e);
                }
            }
        }
    }

    private static void feed(final ICitizenData data, final AbstractEntityCitizen citizen)
    {
        if (!citizen.isAlive() || citizen.isSleeping() || data.getSaturation() > Config.eatBelowSaturation)
        {
            return;
        }

        // Its own food first, picked the way MineColonies picks it.
        final int slot = FoodUtils.getBestFoodForCitizen(data.getInventory(), data, null);
        if (slot >= 0)
        {
            final ItemStack food = data.getInventory().getStackInSlot(slot);
            if (FoodUtils.canEat(food, data.getHomeBuilding(), data.getWorkBuilding()))
            {
                eat(data, citizen, food);
                return;
            }
        }

        // A waiter or cook with empty pockets takes a bite from its own restaurant.
        final IBuilding work = data.getWorkBuilding();
        if (work == null || work.getBuildingType() != ModBuildings.cook.get())
        {
            return;
        }
        final IItemHandler pantry = work.getItemHandlerCap();
        if (pantry == null)
        {
            return;
        }
        for (int i = 0; i < pantry.getSlots(); i++)
        {
            final ItemStack stack = pantry.getStackInSlot(i);
            if (!stack.isEmpty() && FoodUtils.canEat(stack, data.getHomeBuilding(), work))
            {
                final ItemStack bite = pantry.extractItem(i, 1, false);
                if (!bite.isEmpty())
                {
                    eat(data, citizen, bite);
                }
                return;
            }
        }
    }

    /**
     * Eats one item of {@code food} the way MineColonies does: saturation, any bowl or bottle
     * handed back, and the food remembered for variety.
     */
    private static void eat(final ICitizenData data, final AbstractEntityCitizen citizen, final ItemStack food)
    {
        final ItemStack shown = food.copyWithCount(1);
        citizen.swing(InteractionHand.MAIN_HAND);
        citizen.playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
        data.getCitizenFoodHandler().addLastEaten(food.getItem());
        ItemStackUtils.consumeFood(food, citizen, null);
        LOGGER.debug("Citizen {} was starving and ate {}", citizen.getName().getString(), shown);
    }
}

package org.coloniesplus.coloniesplus.containers;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.coloniesplus.coloniesplus.Config;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings empty bowls, bottles and buckets back to where they are reused. Citizens keep the bowl after
 * eating and huts collect them, and couriers rarely pick them up, so restaurants run out while
 * stacks sit in researchers' pockets. Every so often this moves them straight to the buildings
 * that use them, then to the warehouse. Nothing is moved that does not fit, so nothing is lost.
 */
public final class ContainerReturn
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A building that asked for a container keeps it, and so do its workers, for this long after
     * the request was last seen open, so a delivery is not taken back before it is used.
     */
    private static final int PROTECT_AFTER_REQUEST_TICKS = 6000;

    /**
     * Which containers are returned, which buildings use them, and whether those buildings get
     * them back before the warehouse does (up to {@code keepAtUser} each).
     */
    private record Rule(Item item, List<DeferredHolder<BuildingEntry, BuildingEntry>> users, boolean deliverToUsers, int keepAtUser)
    {
        boolean isUser(@Nullable final IBuilding building)
        {
            return building != null && users.stream().anyMatch(user -> building.getBuildingType() == user.get());
        }
    }

    private static final List<Rule> RULES = List.of(
        // Restaurants and kitchens serve bowl meals, so bowls go back to them first.
        new Rule(Items.BOWL, List.of(ModBuildings.cook, ModBuildings.kitchen), true, 64),
        // Bottles pile up wherever honey and potions are drunk; the warehouse hands them to whoever needs them.
        new Rule(Items.GLASS_BOTTLE, List.of(ModBuildings.alchemist, ModBuildings.beekeeper), false, 0),
        // Chefs cooking with milk are left holding the empty buckets; the cowhand needs them to milk again.
        new Rule(Items.BUCKET, List.of(ModBuildings.cowboy), true, 16));

    /**
     * When a building last had an open request for a container. Server thread only.
     */
    private final Map<Wanted, Long> lastWanted = new HashMap<>();

    private int tickCounter;

    private record Wanted(int colony, BlockPos building, Item item)
    {
    }

    @SubscribeEvent
    public void onServerTick(final ServerTickEvent.Post event)
    {
        if (!Config.returnContainers || ++tickCounter < Config.containerReturnIntervalTicks)
        {
            return;
        }
        tickCounter = 0;

        for (final ServerLevel level : event.getServer().getAllLevels())
        {
            final long now = level.getGameTime();
            lastWanted.values().removeIf(time -> now - time > PROTECT_AFTER_REQUEST_TICKS);
            for (final IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getColonies(level))
            {
                try
                {
                    returnContainers(colony, level, now);
                }
                catch (final RuntimeException e)
                {
                    LOGGER.error("Returning bowls and bottles failed for colony {}", colony.getID(), e);
                }
            }
        }
    }

    private void returnContainers(final IColony colony, final ServerLevel level, final long now)
    {
        // Built once per pass: each call makes a new combined handler over the hut and its racks.
        final Map<IBuilding, IItemHandler> handlers = new HashMap<>();
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            final IItemHandler handler = building.getItemHandlerCap();
            if (handler != null)
            {
                handlers.put(building, handler);
            }
        }

        for (final Rule rule : RULES)
        {
            final List<Destination> destinations = destinations(handlers, rule);
            if (destinations.isEmpty())
            {
                continue;
            }

            final Map<IBuilding, Boolean> protectedBuildings = new HashMap<>();
            int moved = 0;
            for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
            {
                final IBuilding work = citizen.getWorkBuilding();
                // What couriers carry is on its way to someone who asked for it.
                if (rule.isUser(work)
                    || (work != null && work.getBuildingType() == ModBuildings.deliveryman.get())
                    || (work != null && protectedBuildings.computeIfAbsent(work, b -> isProtected(colony, b, rule.item(), now))))
                {
                    continue;
                }
                final BlockPos dropAt = citizen.getEntity().map(Entity::blockPosition)
                    .orElse(work != null ? work.getPosition() : null);
                moved += drain(citizen.getInventory(), rule.item(), destinations, level, dropAt);
            }

            for (final Map.Entry<IBuilding, IItemHandler> entry : handlers.entrySet())
            {
                final IBuilding building = entry.getKey();
                if (rule.isUser(building)
                    || building.getBuildingType() == ModBuildings.wareHouse.get()
                    || protectedBuildings.computeIfAbsent(building, b -> isProtected(colony, b, rule.item(), now)))
                {
                    continue;
                }
                moved += drain(entry.getValue(), rule.item(), destinations, level, building.getPosition());
            }

            if (moved > 0)
            {
                LOGGER.debug("Returned {} {} in colony {}", moved, rule.item(), colony.getID());
            }
        }
    }

    /**
     * Built user buildings first (if the rule says so, and only up to its {@code keepAtUser}),
     * then every warehouse.
     */
    private static List<Destination> destinations(final Map<IBuilding, IItemHandler> handlers, final Rule rule)
    {
        final List<Destination> users = new ArrayList<>();
        final List<Destination> warehouses = new ArrayList<>();
        for (final Map.Entry<IBuilding, IItemHandler> entry : handlers.entrySet())
        {
            final IBuilding building = entry.getKey();
            if (building.getBuildingLevel() <= 0)
            {
                continue;
            }
            if (rule.deliverToUsers() && rule.isUser(building))
            {
                final int room = rule.keepAtUser() - count(entry.getValue(), rule.item());
                if (room > 0)
                {
                    users.add(new Destination(entry.getValue(), room));
                }
            }
            else if (building.getBuildingType() == ModBuildings.wareHouse.get())
            {
                warehouses.add(new Destination(entry.getValue(), Integer.MAX_VALUE));
            }
        }
        users.addAll(warehouses);
        return users;
    }

    /**
     * Moves every matching stack out of {@code source} into the destinations, as far as they have
     * room. Should an insert take less than its simulation promised and the items not fit back,
     * they are dropped at {@code dropAt} rather than lost.
     *
     * @return how many items were moved.
     */
    static int drain(
        final IItemHandler source,
        final Item item,
        final List<Destination> destinations,
        final ServerLevel level,
        @Nullable final BlockPos dropAt)
    {
        int moved = 0;
        for (int slot = 0; slot < source.getSlots(); slot++)
        {
            final ItemStack stack = source.getStackInSlot(slot);
            if (!stack.is(item) || !stack.isComponentsPatchEmpty())
            {
                continue;
            }

            for (final Destination destination : destinations)
            {
                final int wanted = Math.min(source.getStackInSlot(slot).getCount(), destination.room);
                if (wanted <= 0)
                {
                    continue;
                }

                // Only take what the destination will actually accept.
                final ItemStack offered = source.extractItem(slot, wanted, true);
                final int fits = offered.getCount() - ItemHandlerHelper.insertItemStacked(destination.handler, offered, true).getCount();
                if (fits <= 0)
                {
                    continue;
                }

                final ItemStack taken = source.extractItem(slot, fits, false);
                final ItemStack leftover = ItemHandlerHelper.insertItemStacked(destination.handler, taken, false);
                if (!leftover.isEmpty())
                {
                    putBack(source, slot, leftover, level, dropAt);
                }
                final int delivered = taken.getCount() - leftover.getCount();
                destination.room -= delivered;
                moved += delivered;

                if (source.getStackInSlot(slot).isEmpty())
                {
                    break;
                }
            }
        }
        return moved;
    }

    private static void putBack(final IItemHandler source, final int slot, final ItemStack stack, final ServerLevel level, @Nullable final BlockPos dropAt)
    {
        ItemStack remaining = source.insertItem(slot, stack, false);
        if (!remaining.isEmpty())
        {
            remaining = ItemHandlerHelper.insertItemStacked(source, remaining, false);
        }
        if (remaining.isEmpty())
        {
            return;
        }
        if (dropAt == null)
        {
            LOGGER.warn("Could not return or put back {}; nowhere to drop it", remaining);
            return;
        }
        LOGGER.warn("Could not return or put back {}; dropping it at {}", remaining, dropAt);
        Block.popResource(level, dropAt.above(), remaining);
    }

    /**
     * Whether the building asked for this item recently: it has an open request for it now, a
     * delivered one its workers have not dealt with yet, or had either within
     * {@link #PROTECT_AFTER_REQUEST_TICKS}, so a delivery is not taken back before it is used.
     */
    private boolean isProtected(final IColony colony, final IBuilding building, final Item item, final long now)
    {
        final Wanted key = new Wanted(colony.getID(), building.getPosition(), item);
        if (hasOpenRequest(colony, building, item))
        {
            lastWanted.put(key, now);
            return true;
        }
        return lastWanted.containsKey(key);
    }

    private static boolean hasOpenRequest(final IColony colony, final IBuilding building, final Item item)
    {
        final ItemStack probe = new ItemStack(item);
        for (final Collection<IToken<?>> tokens : building.getOpenRequestsByRequestableType().values())
        {
            for (final IToken<?> token : tokens)
            {
                if (asks(colony.getRequestManager().getRequestForToken(token), probe))
                {
                    return true;
                }
            }
        }

        // Delivered between two passes: the request is completed but still waiting for its worker.
        if (building.getCompletedRequestsOfCitizenOrBuilding(null).stream().anyMatch(request -> asks(request, probe)))
        {
            return true;
        }
        for (final ICitizenData worker : building.getAllAssignedCitizen())
        {
            if (building.getCompletedRequestsOfCitizenOrBuilding(worker).stream().anyMatch(request -> asks(request, probe)))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean asks(@Nullable final IRequest<?> request, final ItemStack probe)
    {
        return request != null && request.getRequest() instanceof IDeliverable deliverable && deliverable.matches(probe);
    }

    private static int count(final IItemHandler handler, final Item item)
    {
        int count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
        {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item))
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    static final class Destination
    {
        private final IItemHandler handler;
        private int                room;

        Destination(final IItemHandler handler, final int room)
        {
            this.handler = handler;
            this.room = room;
        }
    }
}

package org.coloniesplus.coloniesplus.conversion;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.player.IPlayerRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.retrying.IRetryingRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.coloniesplus.coloniesplus.Config;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Drives builder conversions on the server: attaches the {@link ConversionResolver} to builder
 * huts placed before this mod was installed, finishes accepted conversions once their delay has
 * passed, offers stuck requests to the resolver again when spare materials turn up, and keeps
 * the hut tab's spare-materials list current.
 */
public final class ConversionTicker
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private int tickCounter;

    /**
     * Lets go of the stopped server's recipes, e.g. after quitting a single player world.
     */
    @SubscribeEvent
    public void onServerStopped(final ServerStoppedEvent event)
    {
        ConversionPlanner.clearCaches();
    }

    @SubscribeEvent
    public void onServerTick(final ServerTickEvent.Post event)
    {
        if (++tickCounter < Config.intervalTicks)
        {
            return;
        }
        tickCounter = 0;

        for (final ServerLevel level : event.getServer().getAllLevels())
        {
            for (final IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getColonies(level))
            {
                if (colony.getRequestManager() instanceof IStandardRequestManager manager)
                {
                    tickColony(colony, manager);
                }
            }
        }
    }

    private void tickColony(final IColony colony, final IStandardRequestManager manager)
    {
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (!(building instanceof AbstractBuilding hut) || !hut.hasModule(ConversionModule.producer()))
            {
                continue;
            }

            final ConversionModule module = hut.getModule(ConversionModule.producer());
            final ConversionResolver resolver = ensureResolver(colony, manager, hut, module);
            if (resolver == null)
            {
                continue;
            }

            try
            {
                if (Config.enabled)
                {
                    finishAccepted(manager, resolver, hut, module);
                    offerStuckRequests(manager, resolver, hut);
                }
                final List<ItemStack> spare = ConversionPlanner.spareMaterials(hut);
                final CrafterProgress progress = CrafterProgress.of(colony);
                module.updateStatus(
                    spare,
                    new ArrayList<>(resolver.getPending().values()),
                    neededMaterials(hut, module, spare, progress),
                    ConversionModule.CrafterStatus.of(progress));
            }
            catch (final RuntimeException e)
            {
                LOGGER.error("Material conversion failed for builder at {}", hut.getPosition(), e);
            }
        }
    }

    /**
     * Raw materials for what the build is missing, including what converting them would waste
     * at the colony's crafter levels, each with how much of it the builder has spare.
     */
    private static List<ConversionModule.NeededMaterial> neededMaterials(
        final AbstractBuilding building,
        final ConversionModule module,
        final List<ItemStack> spare,
        final CrafterProgress progress)
    {
        final ServerLevel level = (ServerLevel) building.getColony().getWorld();
        final List<ItemStack> missing = MaterialBreakdown.missing(building);
        final List<ItemStack> raw = module.rawMaterialsFor(missing, () -> MaterialBreakdown.rawMaterials(missing, building, level));

        final List<ConversionModule.NeededMaterial> needed = new ArrayList<>(raw.size());
        for (final ItemStack material : raw)
        {
            final int count = material.getCount() + progress.wasteFor(CrafterProgress.Trade.of(material), material.getCount());
            needed.add(new ConversionModule.NeededMaterial(material.copyWithCount(count), MaterialBreakdown.spareFor(material, spare, module.options(), level)));
        }
        return needed;
    }

    /**
     * New builder huts get the resolver from {@link ConversionModule#createResolvers()}. Huts that
     * already existed have their resolvers saved without it, so it is registered alongside them here.
     */
    @Nullable
    private ConversionResolver ensureResolver(final IColony colony, final IStandardRequestManager manager, final AbstractBuilding building, final ConversionModule module)
    {
        final Collection<IToken<?>> registered = manager.getProviderHandler().getRegisteredResolvers(building);
        if (registered.isEmpty())
        {
            // The hut is not part of the request system (yet).
            return null;
        }

        for (final IToken<?> token : registered)
        {
            if (resolverOrNull(manager, token) instanceof ConversionResolver resolver)
            {
                return resolver;
            }
        }

        final ConversionResolver resolver = module.createResolver();
        manager.getResolverHandler().registerResolver(resolver);
        final List<IToken<?>> updated = new ArrayList<>(registered);
        updated.add(resolver.getId());
        manager.getProviderResolverAssignmentDataStore().getAssignments().put(building.getId(), updated);
        colony.markDirty();
        building.markDirty();
        LOGGER.info("Added material conversion to the builder at {}", building.getPosition());
        return resolver;
    }

    @Nullable
    private static IRequestResolver<?> resolverOrNull(final IStandardRequestManager manager, @Nullable final IToken<?> token)
    {
        if (token == null)
        {
            return null;
        }
        try
        {
            return manager.getResolverHandler().getResolver(token);
        }
        catch (final IllegalArgumentException e)
        {
            return null;
        }
    }

    private void finishAccepted(final IStandardRequestManager manager, final ConversionResolver resolver, final AbstractBuilding building, final ConversionModule module)
    {
        final Collection<IToken<?>> assigned = manager.getRequestResolverRequestAssignmentDataStore().getAssignments().get(resolver.getId());
        final List<IToken<?>> tokens = assigned == null ? List.of() : List.copyOf(assigned);
        resolver.forgetUnassigned(tokens);

        for (final IToken<?> token : tokens)
        {
            resolver.tick(manager, token, building, module);
        }
    }

    /**
     * A request is only offered to resolvers when it is created or retried, so one that is
     * already waiting on the player is offered again once spare materials can cover it.
     */
    private void offerStuckRequests(final IStandardRequestManager manager, final ConversionResolver resolver, final AbstractBuilding building)
    {
        // Copy first: reassigning a request can change the building's open request map.
        final List<IToken<?>> tokens = building.getOpenRequestsByRequestableType().values().stream()
            .flatMap(Collection::stream)
            .toList();

        for (final IToken<?> token : tokens)
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            if (request == null || !(request.getRequest() instanceof Stack) || request.hasChildren())
            {
                continue;
            }

            final IRequestResolver<?> current = resolverOrNull(manager, manager.getRequestResolverRequestAssignmentDataStore().getAssignmentForValue(token));
            if (!(current instanceof IPlayerRequestResolver) && !(current instanceof IRetryingRequestResolver))
            {
                continue;
            }

            @SuppressWarnings("unchecked")
            final IRequest<? extends Stack> stackRequest = (IRequest<? extends Stack>) request;
            if (resolver.canResolveForBuilding(manager, stackRequest, building))
            {
                manager.reassignRequest(token, List.of());
            }
        }
    }
}

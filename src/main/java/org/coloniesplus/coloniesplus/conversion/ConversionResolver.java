package org.coloniesplus.coloniesplus.conversion;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requester.IRequester;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractBuildingDependentRequestResolver;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.coloniesplus.coloniesplus.Config;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lets a builder fill its own stuck requests from materials its build does not need. It ranks
 * after the hut itself, the warehouse and crafters, but before the retrying and player
 * resolvers, so it only steps in when nobody else in the colony can supply the item.
 * <p>
 * Accepted requests stay in progress for a short delay, so players can see in the request
 * window what is being converted, and are then converted and resolved by {@link ConversionTicker}.
 */
public class ConversionResolver extends AbstractBuildingDependentRequestResolver<Stack>
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Between the retrying resolver (50) and the default resolver priority (100).
     */
    private static final int PRIORITY = 75;

    /**
     * What each accepted request will be made from. Saved and synced, so clients can show it.
     */
    private final Map<IToken<?>, Conversion> pending = new LinkedHashMap<>();

    /**
     * Game time each accepted request started waiting. Not saved: after a reload the delay restarts.
     */
    private final Map<IToken<?>, Long> acceptedAt = new HashMap<>();

    public ConversionResolver(@NotNull final ILocation location, @NotNull final IToken<?> token)
    {
        super(location, token);
    }

    @Override
    public int getPriority()
    {
        return PRIORITY;
    }

    @Override
    public TypeToken<? extends Stack> getRequestType()
    {
        return TypeToken.of(Stack.class);
    }

    @Override
    public boolean canResolveForBuilding(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request, @NotNull final AbstractBuilding building)
    {
        return plan(request, building) != null;
    }

    @Nullable
    @Override
    public List<IToken<?>> attemptResolveForBuilding(
        @NotNull final IRequestManager manager,
        @NotNull final IRequest<? extends Stack> request,
        @NotNull final AbstractBuilding building)
    {
        final ConversionPlanner.Plan plan = plan(request, building);
        if (plan == null)
        {
            return null;
        }

        pending.put(request.getId(), new Conversion(plan.inputs(), plan.delivery()));
        return new ArrayList<>();
    }

    @Override
    public void resolveForBuilding(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request, @NotNull final AbstractBuilding building)
    {
        // Converted after a delay by the ticker, so the request is visible as in progress.
        acceptedAt.putIfAbsent(request.getId(), building.getColony().getWorld().getGameTime());
    }

    /**
     * Converts an accepted request once its delay has passed. If the materials are gone by then,
     * the request is handed back to the rest of the colony.
     */
    void tick(final IRequestManager manager, final IToken<?> token, final AbstractBuilding building, final ConversionModule module)
    {
        final IRequest<?> request = manager.getRequestForToken(token);
        if (request == null || !(request.getRequest() instanceof Stack requested))
        {
            forget(token);
            return;
        }
        if (request.getState() != RequestState.IN_PROGRESS)
        {
            return;
        }

        final long now = building.getColony().getWorld().getGameTime();
        final long since = acceptedAt.computeIfAbsent(token, t -> now);
        if (now - since < Config.conversionDelayTicks)
        {
            return;
        }

        final ServerLevel level = (ServerLevel) building.getColony().getWorld();
        final ConversionPlanner.Plan plan = ConversionPlanner.plan(building, requested.getStack(), requested.getCount(), module.options(), level);
        forget(token);

        if (plan != null && ConversionPlanner.execute(plan, building, level))
        {
            module.recordConversion(plan.inputs(), plan.delivery());
            request.addDelivery(plan.delivery());
            manager.updateRequestState(token, RequestState.RESOLVED);
            return;
        }

        LOGGER.debug("Builder at {}: spare materials for {} are gone, handing the request back", building.getPosition(), requested.getStack());
        try
        {
            manager.reassignRequest(token, List.of(getId()));
        }
        catch (final IllegalArgumentException e)
        {
            LOGGER.warn("Could not hand request {} back to the colony", token, e);
        }
    }

    /**
     * Drops bookkeeping for requests that are no longer assigned to this resolver.
     */
    void forgetUnassigned(final Collection<IToken<?>> assigned)
    {
        pending.keySet().removeIf(token -> !assigned.contains(token));
        acceptedAt.keySet().removeIf(token -> !assigned.contains(token));
    }

    @Nullable
    private ConversionPlanner.Plan plan(final IRequest<? extends Stack> request, final AbstractBuilding building)
    {
        if (!Config.enabled || !building.hasModule(ConversionModule.producer()))
        {
            return null;
        }

        final ConversionModule module = building.getModule(ConversionModule.producer());
        final Stack requested = request.getRequest();
        return ConversionPlanner.plan(building, requested.getStack(), requested.getCount(), module.options(), (ServerLevel) building.getColony().getWorld());
    }

    private void forget(final IToken<?> token)
    {
        pending.remove(token);
        acceptedAt.remove(token);
    }

    @Override
    public void onAssignedRequestBeingCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request)
    {
        forget(request.getId());
    }

    @Override
    public void onAssignedRequestCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request)
    {
        forget(request.getId());
    }

    @Nullable
    @Override
    public List<IRequest<?>> getFollowupRequestForCompletion(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> completedRequest)
    {
        return null;
    }

    @Override
    public void onRequestedRequestComplete(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
    }

    @Override
    public void onRequestedRequestCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
    }

    @NotNull
    @Override
    public MutableComponent getRequesterDisplayName(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        final Conversion conversion = pending.get(request.getId());
        if (conversion == null)
        {
            return Component.translatable("coloniesplus.resolver.name");
        }
        return Component.translatable("coloniesplus.resolver.converting", Conversion.describe(conversion.inputs()), Conversion.describe(List.of(conversion.output())));
    }

    @Override
    public Optional<IRequester> getBuilding(@NotNull final IRequestManager manager, @NotNull final IToken<?> token)
    {
        if (!manager.getColony().getWorld().isClientSide)
        {
            return Optional.ofNullable(manager.getColony().getRequesterBuildingForPosition(getLocation().getInDimensionLocation()));
        }
        return Optional.empty();
    }

    Map<IToken<?>, Conversion> getPending()
    {
        return pending;
    }

    /**
     * Materials consumed and the item produced by one conversion.
     */
    public record Conversion(List<ItemStack> inputs, ItemStack output)
    {
        /**
         * @return e.g. "64 Spruce Planks, 2 Birch Planks".
         */
        public static MutableComponent describe(final List<ItemStack> stacks)
        {
            final MutableComponent text = Component.empty();
            for (int i = 0; i < stacks.size(); i++)
            {
                if (i > 0)
                {
                    text.append(", ");
                }
                text.append(Component.translatable("coloniesplus.gui.conversion.amount", stacks.get(i).getCount(), stacks.get(i).getHoverName()));
            }
            return text;
        }
    }
}

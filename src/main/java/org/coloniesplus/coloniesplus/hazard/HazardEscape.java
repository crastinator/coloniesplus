package org.coloniesplus.coloniesplus.hazard;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.coloniesplus.coloniesplus.Config;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gets citizens out of cactus, fire, berry bushes, magma and lava when they keep taking damage
 * from it, e.g. while working or sitting next to it. A citizen hurt by the same kind of hazard
 * for {@link Config#hazardEscapeTicks} walks to the nearest safe spot, standing up or waking up
 * first if needed. If walking has not got it out a few seconds later, it is moved there directly.
 */
public final class HazardEscape
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A gap this long between hazard hits means the citizen got out by itself.
     */
    private static final int RESET_TICKS = 30;

    /**
     * Still being hurt this long after starting to walk away: move the citizen instead.
     */
    private static final int TELEPORT_AFTER_TICKS = 60;

    /**
     * Give up steering the citizen after this long, whatever happened.
     */
    private static final int ESCAPE_TIMEOUT_TICKS = 200;

    private static final int ARRIVED_RANGE   = 1;
    /**
     * How far "walk away from here" goes when no safe spot was found.
     */
    private static final int WALK_AWAY_DISTANCE = 8;

    /**
     * Server thread only. Weak, so citizens that unload or die are dropped.
     */
    private final Map<AbstractEntityCitizen, State> states = new WeakHashMap<>();

    /**
     * @return true while the citizen is being walked (or about to be moved) out of a hazard.
     */
    public boolean isEscaping(final AbstractEntityCitizen citizen)
    {
        final State state = states.get(citizen);
        return state != null && (state.escapeTo != null || citizen.level().getGameTime() < state.walkingAwayUntil);
    }

    @SubscribeEvent
    public void onDamage(final LivingDamageEvent.Post event)
    {
        if (!Config.escapeHazards
            || !(event.getEntity() instanceof AbstractEntityCitizen citizen)
            || citizen.level().isClientSide
            || !isHazardDamage(event.getSource()))
        {
            return;
        }

        final long now = citizen.level().getGameTime();
        final State state = states.computeIfAbsent(citizen, c -> new State(now));
        if (now - state.lastHit > RESET_TICKS)
        {
            state.firstHit = now;
            state.escapeTo = null;
        }
        state.lastHit = now;
        state.burning |= isBurningDamage(event.getSource());

        if (state.escapeTo == null)
        {
            if (now - state.firstHit >= Config.hazardEscapeTicks)
            {
                startEscape(citizen, state, now);
            }
        }
        else if (now - state.escapeStart >= TELEPORT_AFTER_TICKS)
        {
            LOGGER.debug("Citizen {} could not walk out of a hazard at {}; moving it to {}", citizen.getName().getString(), citizen.blockPosition(), state.escapeTo);
            citizen.getNavigation().stop();
            citizen.teleportTo(state.escapeTo.getX() + 0.5, state.escapeTo.getY(), state.escapeTo.getZ() + 0.5);
            finish(citizen, state);
        }
    }

    @SubscribeEvent
    public void onEntityTick(final EntityTickEvent.Post event)
    {
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen) || citizen.level().isClientSide)
        {
            return;
        }

        final State state = states.get(citizen);
        if (state == null)
        {
            return;
        }

        final long now = citizen.level().getGameTime();
        if (state.escapeTo == null)
        {
            if (now - state.lastHit > RESET_TICKS && now >= state.walkingAwayUntil)
            {
                states.remove(citizen);
            }
            return;
        }

        // Re-issued every tick so the citizen's job does not walk it straight back in.
        if (EntityNavigationUtils.walkToPos(citizen, state.escapeTo, ARRIVED_RANGE, true) || now - state.escapeStart > ESCAPE_TIMEOUT_TICKS)
        {
            finish(citizen, state);
        }
    }

    private void startEscape(final AbstractEntityCitizen citizen, final State state, final long now)
    {
        final BlockPos safe = SafeSpots.find(citizen.level(), citizen.blockPosition());
        if (safe == null)
        {
            LOGGER.debug("Citizen {} is stuck in a hazard at {} with no safe spot nearby", citizen.getName().getString(), citizen.blockPosition());
            EntityNavigationUtils.walkAwayFrom(citizen, citizen.blockPosition(), WALK_AWAY_DISTANCE, 1.0);
            state.firstHit = now;
            state.walkingAwayUntil = now + TELEPORT_AFTER_TICKS;
            return;
        }

        if (citizen.isPassenger())
        {
            citizen.stopRiding();
        }
        if (citizen.isSleeping())
        {
            citizen.getCitizenSleepHandler().onWakeUp();
        }

        state.escapeTo = safe;
        state.escapeStart = now;
        EntityNavigationUtils.walkToPos(citizen, safe, ARRIVED_RANGE, true);
    }

    private void finish(final AbstractEntityCitizen citizen, final State state)
    {
        // Out of the fire, so put the flames out rather than let them burn on for seconds.
        if (state.burning && !citizen.isInLava() && !SafeSpots.isHazard(citizen.level(), citizen.blockPosition()))
        {
            citizen.clearFire();
        }
        states.remove(citizen);
    }

    private static boolean isHazardDamage(final DamageSource source)
    {
        return source.is(DamageTypes.CACTUS)
            || source.is(DamageTypes.SWEET_BERRY_BUSH)
            || source.is(DamageTypes.HOT_FLOOR)
            || isBurningDamage(source);
    }

    private static boolean isBurningDamage(final DamageSource source)
    {
        return source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.LAVA);
    }

    private static final class State
    {
        private long     firstHit;
        private long     lastHit;
        private long     escapeStart;
        private boolean  burning;
        /**
         * Walking away with no safe spot found counts as escaping until then.
         */
        private long     walkingAwayUntil;
        @Nullable
        private BlockPos escapeTo;

        private State(final long now)
        {
            this.firstHit = now;
            this.lastHit = now;
        }
    }
}

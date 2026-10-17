package org.coloniesplus.coloniesplus.couriers;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.jobs.guard.JobCavalry;
import com.minecolonies.core.entity.other.cavalry.CavalryHorseEntity;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.coloniesplus.coloniesplus.Coloniesplus;
import org.coloniesplus.coloniesplus.Config;
import org.coloniesplus.coloniesplus.hazard.HazardEscape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lends the colony's cavalry horses to couriers. Each courier with a horse has it follow along,
 * rides it on long trips (faster than walking), and gets off near where it is going to walk in,
 * since horses do not fit through doors. MineColonies already steers a horse ridden by a citizen
 * along the citizen's path, so riding needs no pathing of its own. Cavalry guards come first: a
 * horse is only lent while there are more free horses than guards without one.
 */
public final class CourierHorses
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CHECK_INTERVAL_TICKS = 10;

    /**
     * How far from a courier a free horse may be to be lent to it.
     */
    private static final double ASSIGN_RADIUS = 64;

    /**
     * An unridden horse this far from its courier is given back (e.g. after the courier was teleported).
     */
    private static final double LOST_DISTANCE_SQ = 96 * 96;

    /**
     * How close the horse must be for the courier to get on.
     */
    private static final double MOUNT_REACH_SQ = 4 * 4;

    /**
     * The courier gets off this close to where it is going, to walk the rest.
     */
    private static final double DISMOUNT_DISTANCE_SQ = 6 * 6;

    /**
     * An unridden horse walks after its courier once it is this far behind.
     */
    private static final double FOLLOW_DISTANCE_SQ = 6 * 6;

    /**
     * The horse picks a new spot to walk to once its courier has moved this far from the old one.
     */
    private static final double FOLLOW_REPATH_SQ = 4 * 4;

    private static final double FOLLOW_SPEED = 1.2;

    /**
     * A ridden horse that has not moved this long is left, so a bad path cannot trap the courier.
     */
    private static final int STUCK_RIDING_TICKS = 200;

    private static final double MOVED_DISTANCE_SQ = 1;

    /**
     * Checks in a row without a path before the courier gets off; a path is missing for a moment
     * while a new one is worked out, longer for long trips.
     */
    private static final int NO_PATH_CHECKS = 4;

    /**
     * How long after getting off before the courier gets on again, so it does not hop on and off.
     */
    private static final int REMOUNT_COOLDOWN_TICKS = 100;

    private static final ResourceLocation SPEED_ID = ResourceLocation.fromNamespaceAndPath(Coloniesplus.MODID, "courier_ride");

    /**
     * Courier UUID to its ride. Not saved: after a restart MineColonies drops the reservations on
     * its own within seconds and horses are lent out again. Server thread only.
     */
    private final Map<UUID, Ride> rides = new HashMap<>();

    /**
     * Citizens it is walking out of a hazard are left to it.
     */
    private final HazardEscape hazardEscape;

    private int tickCounter;

    public CourierHorses(final HazardEscape hazardEscape)
    {
        this.hazardEscape = hazardEscape;
    }

    @SubscribeEvent
    public void onServerTick(final ServerTickEvent.Post event)
    {
        if (++tickCounter < CHECK_INTERVAL_TICKS)
        {
            return;
        }
        tickCounter = 0;

        final Set<UUID> seen = new HashSet<>();
        for (final ServerLevel level : event.getServer().getAllLevels())
        {
            for (final IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getColonies(level))
            {
                try
                {
                    tickColony(colony, level, seen);
                }
                catch (final RuntimeException e)
                {
                    LOGGER.error("Courier horses failed for colony {}", colony.getID(), e);
                }
            }
        }

        // Couriers that unloaded, died or changed jobs give their horse back.
        for (final Iterator<Map.Entry<UUID, Ride>> it = rides.entrySet().iterator(); it.hasNext(); )
        {
            final Map.Entry<UUID, Ride> entry = it.next();
            if (!seen.contains(entry.getKey()))
            {
                entry.getValue().release(entry.getKey());
                it.remove();
            }
        }
    }

    private void tickColony(final IColony colony, final ServerLevel level, final Set<UUID> seen)
    {
        final List<AbstractEntityCitizen> couriers = new ArrayList<>();
        final Set<UUID> guardHorses = new HashSet<>();
        int guardsWithoutHorse = 0;
        AABB area = null;
        for (final ICitizenData data : colony.getCitizenManager().getCitizens())
        {
            final AbstractEntityCitizen citizen = data.getEntity().orElse(null);
            if (citizen == null || citizen.level() != level)
            {
                continue;
            }
            if (data.getJob() instanceof JobDeliveryman)
            {
                couriers.add(citizen);
            }
            else if (data.getJob() instanceof JobCavalry cavalry)
            {
                // A guard resting at its stable still has its own horse; only guards with none need one.
                final CavalryHorseEntity own = citizen.getVehicle() instanceof CavalryHorseEntity riding ? riding : ownHorse(cavalry, citizen, level);
                if (own != null)
                {
                    guardHorses.add(own.getUUID());
                }
                else
                {
                    guardsWithoutHorse++;
                }
            }
            else
            {
                continue;
            }
            final AABB around = citizen.getBoundingBox().inflate(ASSIGN_RADIUS, 24, ASSIGN_RADIUS);
            area = area == null ? around : area.minmax(around);
        }

        // A courier on a horse it was not lent (rides are not saved over a restart or unload, or the
        // feature was turned off) gets off, or nothing would ever take it off again.
        for (final AbstractEntityCitizen courier : couriers)
        {
            if ((!Config.courierHorses || !rides.containsKey(courier.getUUID())) && courier.getVehicle() instanceof CavalryHorseEntity horse)
            {
                courier.stopRiding();
                courier.getNavigation().stop();
                setSpeed(horse, null);
                if (courier.getUUID().equals(horse.reservedBy()))
                {
                    horse.clearReservation();
                }
            }
        }

        if (!Config.courierHorses || couriers.isEmpty())
        {
            return;
        }

        final List<CavalryHorseEntity> freeHorses = new ArrayList<>(level.getEntitiesOfClass(
            CavalryHorseEntity.class, area, horse -> horse.getColonyId() == colony.getID() && isFree(horse) && !guardHorses.contains(horse.getUUID())));
        final long now = level.getGameTime();

        // Give a horse back when a guard needs one and none is free; unridden ones first.
        if (guardsWithoutHorse > freeHorses.size())
        {
            couriers.stream()
                .filter(courier -> rides.containsKey(courier.getUUID()))
                .min(Comparator.comparing(Entity::isPassenger))
                .ifPresent(courier -> {
                    rides.remove(courier.getUUID()).release(courier.getUUID());
                    LOGGER.debug("Courier {} gave its horse back for a cavalry guard", courier.getName().getString());
                });
        }

        for (final AbstractEntityCitizen courier : couriers)
        {
            seen.add(courier.getUUID());
            Ride ride = rides.get(courier.getUUID());
            if (ride != null && !ride.tick(courier, now, hazardEscape))
            {
                rides.remove(courier.getUUID()).release(courier.getUUID());
                ride = null;
            }

            // Lend a horse only while more are free than guards need.
            if (ride == null && !courier.isSleeping() && freeHorses.size() > guardsWithoutHorse)
            {
                final CavalryHorseEntity horse = freeHorses.stream()
                    .filter(h -> h.distanceToSqr(courier) <= ASSIGN_RADIUS * ASSIGN_RADIUS)
                    .min(Comparator.comparingDouble(h -> h.distanceToSqr(courier)))
                    .orElse(null);
                if (horse != null)
                {
                    freeHorses.remove(horse);
                    horse.reserve(courier);
                    rides.put(courier.getUUID(), new Ride(level, horse.getUUID(), now));
                    LOGGER.debug("Lent horse {} to courier {}", horse.getUUID(), courier.getName().getString());
                }
            }
        }
    }

    /**
     * The horse a cavalry guard rides, if it is still there for it: alive and not taken by anyone else.
     */
    @Nullable
    private static CavalryHorseEntity ownHorse(final JobCavalry job, final AbstractEntityCitizen guard, final ServerLevel level)
    {
        final UUID mount = job.getMount();
        if (mount == null || !(level.getEntity(mount) instanceof CavalryHorseEntity horse) || !horse.isAlive())
        {
            return null;
        }
        final UUID reservedBy = horse.reservedBy();
        if ((reservedBy != null && !reservedBy.equals(guard.getUUID())) || !horse.getPassengers().isEmpty())
        {
            return null;
        }
        return horse;
    }

    /**
     * The same test cavalry guards use for a horse they may take.
     */
    private static boolean isFree(final CavalryHorseEntity horse)
    {
        return horse.isAlive()
            && !horse.isBaby()
            && horse.getPassengers().isEmpty()
            && !horse.hasReservation()
            && horse.isReadyForCombat();
    }

    private static void setSpeed(final CavalryHorseEntity horse, @Nullable final AbstractEntityCitizen rider)
    {
        final AttributeInstance speed = horse.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null)
        {
            return;
        }
        speed.removeModifier(SPEED_ID);
        if (rider == null)
        {
            return;
        }
        // Ridden speed follows the courier's own, so its speed research still counts.
        final double boost = rider.getAttributeValue(Attributes.MOVEMENT_SPEED) * Config.courierHorseSpeed - speed.getValue();
        if (boost > 0)
        {
            speed.addTransientModifier(new AttributeModifier(SPEED_ID, boost, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static double distanceToTargetSq(final AbstractEntityCitizen courier, @Nullable final Path path)
    {
        if (path == null || path.isDone())
        {
            return -1;
        }
        final BlockPos target = path.getTarget();
        return courier.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
    }

    private static final class Ride
    {
        private final ServerLevel level;
        private final UUID        horse;
        private long       lastDismount;
        private Vec3       lastPosition = Vec3.ZERO;
        private long       stillSince;
        private int        noPathChecks;
        @Nullable
        private BlockPos   followTarget;

        private Ride(final ServerLevel level, final UUID horse, final long now)
        {
            this.level = level;
            this.horse = horse;
            this.lastDismount = now - REMOUNT_COOLDOWN_TICKS;
            this.stillSince = now;
        }

        /**
         * @return false if the horse should be given back.
         */
        private boolean tick(final AbstractEntityCitizen courier, final long now, final HazardEscape hazardEscape)
        {
            final Entity entity = level.getEntity(horse);
            if (!(entity instanceof CavalryHorseEntity mount) || !mount.isAlive() || courier.isSleeping())
            {
                return false;
            }
            final boolean riding = courier.getVehicle() == mount;
            if (!riding && (!mount.getPassengers().isEmpty() || mount.distanceToSqr(courier) > LOST_DISTANCE_SQ))
            {
                return false;
            }

            // Kept reserved so it does not wander home or get taken; MineColonies drops it on its own if this stops.
            mount.reserve(courier);

            if (riding)
            {
                rideTick(courier, mount, now);
            }
            else
            {
                setSpeed(mount, null);
                walkTick(courier, mount, now, hazardEscape);
            }
            return true;
        }

        private void rideTick(final AbstractEntityCitizen courier, final CavalryHorseEntity mount, final long now)
        {
            setSpeed(mount, courier);

            final Path path = courier.getNavigation().getPath();
            final double toTarget = distanceToTargetSq(courier, path);
            noPathChecks = toTarget < 0 ? noPathChecks + 1 : 0;

            if (courier.position().distanceToSqr(lastPosition) >= MOVED_DISTANCE_SQ)
            {
                lastPosition = courier.position();
                stillSince = now;
            }

            final String reason;
            if (noPathChecks >= NO_PATH_CHECKS)
            {
                reason = "arrived";
            }
            else if (toTarget >= 0 && toTarget <= DISMOUNT_DISTANCE_SQ)
            {
                reason = "close to its destination";
            }
            else if (now - stillSince >= STUCK_RIDING_TICKS)
            {
                reason = "stuck";
            }
            else
            {
                return;
            }

            LOGGER.debug("Courier {} got off its horse ({})", courier.getName().getString(), reason);
            dismount(courier, mount, now);
        }

        private void walkTick(final AbstractEntityCitizen courier, final CavalryHorseEntity mount, final long now, final HazardEscape hazardEscape)
        {
            final double toTarget = distanceToTargetSq(courier, courier.getNavigation().getPath());
            final double tripDistance = Config.courierMountDistance;
            if (toTarget >= tripDistance * tripDistance
                && mount.distanceToSqr(courier) <= MOUNT_REACH_SQ
                && now - lastDismount >= REMOUNT_COOLDOWN_TICKS
                && !courier.isPassenger()
                && !hazardEscape.isEscaping(courier)
                && courier.startRiding(mount, true))
            {
                mount.getNavigation().stop();
                setSpeed(mount, courier);
                // Path again with the horse's rules: it cannot fit through doors.
                courier.getNavigation().stop();
                lastPosition = courier.position();
                stillSince = now;
                noPathChecks = 0;
                followTarget = null;
                return;
            }

            // Follow along, waiting outside when the courier goes indoors.
            final BlockPos courierPos = courier.blockPosition();
            if (mount.distanceToSqr(courier) <= FOLLOW_DISTANCE_SQ)
            {
                followTarget = null;
                return;
            }
            if (followTarget == null || followTarget.distSqr(courierPos) > FOLLOW_REPATH_SQ || mount.getNavigation().isDone())
            {
                followTarget = courierPos;
                EntityNavigationUtils.walkToPos(mount, courierPos, 3, false, FOLLOW_SPEED);
            }
        }

        private void dismount(final AbstractEntityCitizen courier, final CavalryHorseEntity mount, final long now)
        {
            courier.stopRiding();
            setSpeed(mount, null);
            mount.getNavigation().stop();
            courier.getNavigation().stop();
            lastDismount = now;
            followTarget = null;
        }

        /**
         * Gives the horse back: the courier gets off, the boost goes, and the reservation is cleared
         * so the horse walks home to its stable.
         */
        private void release(final UUID courierId)
        {
            if (!(level.getEntity(horse) instanceof CavalryHorseEntity mount))
            {
                // Unloaded or gone; its reservation runs out on its own.
                return;
            }
            for (final Entity passenger : List.copyOf(mount.getPassengers()))
            {
                if (passenger.getUUID().equals(courierId) && passenger instanceof AbstractEntityCitizen courier)
                {
                    courier.stopRiding();
                    courier.getNavigation().stop();
                }
            }
            setSpeed(mount, null);
            if (courierId.equals(mount.reservedBy()))
            {
                mount.clearReservation();
            }
        }
    }
}

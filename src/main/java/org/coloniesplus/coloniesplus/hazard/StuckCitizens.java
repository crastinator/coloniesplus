package org.coloniesplus.coloniesplus.hazard;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.coloniesplus.coloniesplus.Config;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Frees citizens that get stuck: inside a block (often their own workstation, after
 * MineColonies teleports them to work), or trying to walk somewhere without moving at all.
 * Walking cannot get them out, so they are moved to the nearest safe spot, and their job
 * then finds its way again from there.
 */
public final class StuckCitizens
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CHECK_INTERVAL_TICKS = 20;

    /**
     * Checks in a row a citizen must be inside a block before it is moved, so a brief overlap
     * while walking through a door does not count.
     */
    private static final int INSIDE_BLOCK_CHECKS = 2;

    /**
     * Moving less than this far (squared, in blocks) counts as not moving.
     */
    private static final double MOVED_DISTANCE_SQ = 0.5 * 0.5;

    /**
     * Each citizen is freed (or a free attempted) at most once in this long, so a bad spot cannot
     * make it bounce around and an enclosed citizen is not searched around every check.
     */
    private static final int COOLDOWN_TICKS = 600;

    /**
     * How far along its path a citizen that is not moving is put, at most.
     */
    private static final int PATH_SKIP_NODES = 4;

    /**
     * Server thread only. Weak, so citizens that unload or die are dropped.
     */
    private final Map<AbstractEntityCitizen, Track> tracks = new WeakHashMap<>();

    /**
     * Citizens it is walking out of a hazard are left to it, so the two never pull one citizen
     * in different directions.
     */
    private final HazardEscape hazardEscape;

    private int tickCounter;

    public StuckCitizens(final HazardEscape hazardEscape)
    {
        this.hazardEscape = hazardEscape;
    }

    @SubscribeEvent
    public void onServerTick(final ServerTickEvent.Post event)
    {
        if (!Config.unstickCitizens || ++tickCounter < CHECK_INTERVAL_TICKS)
        {
            return;
        }
        tickCounter = 0;

        for (final ServerLevel level : event.getServer().getAllLevels())
        {
            for (final IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getColonies(level))
            {
                for (final ICitizenData data : colony.getCitizenManager().getCitizens())
                {
                    data.getEntity().ifPresent(citizen -> check(citizen, level.getGameTime()));
                }
            }
        }
    }

    private void check(final AbstractEntityCitizen citizen, final long now)
    {
        if (!citizen.isAlive() || citizen.isPassenger() || citizen.isSleeping() || hazardEscape.isEscaping(citizen))
        {
            tracks.remove(citizen);
            return;
        }

        final Track track = tracks.computeIfAbsent(citizen, c -> new Track(c.position(), now));
        if (now - track.lastAttempt < COOLDOWN_TICKS)
        {
            return;
        }

        // Shrunk a little so standing against a wall or on a slab edge does not count.
        final boolean inside = !citizen.level().noCollision(citizen, citizen.getBoundingBox().deflate(0.1));
        track.insideChecks = inside ? track.insideChecks + 1 : 0;
        if (track.insideChecks >= INSIDE_BLOCK_CHECKS)
        {
            free(citizen, track, now, "inside a block");
            return;
        }

        if (citizen.getNavigation().isDone() || citizen.position().distanceToSqr(track.position) >= MOVED_DISTANCE_SQ)
        {
            // Standing still on purpose (working, idle), or getting somewhere.
            track.position = citizen.position();
            track.stillSince = now;
            return;
        }

        if (now - track.stillSince >= Config.stuckTicks)
        {
            free(citizen, track, now, "not moving");
        }
    }

    /**
     * Moves the citizen on: a few steps along its own path if it has one (the pathfinder already
     * knows it can walk from there), otherwise to the nearest safe, open spot around itself.
     * Never straight to where it was going, which could be behind a wall or on a roof.
     */
    private void free(final AbstractEntityCitizen citizen, final Track track, final long now, final String reason)
    {
        BlockPos safe = alongPath(citizen);
        if (safe == null)
        {
            safe = SafeSpots.find(citizen.level(), citizen.blockPosition());
        }
        track.insideChecks = 0;
        track.stillSince = now;
        track.position = citizen.position();
        track.lastAttempt = now;
        if (safe == null)
        {
            LOGGER.debug("Citizen {} is stuck ({}) at {} with no safe spot nearby", citizen.getName().getString(), reason, citizen.blockPosition());
            return;
        }

        LOGGER.debug("Citizen {} was stuck ({}) at {}; moving it to {}", citizen.getName().getString(), reason, citizen.blockPosition(), safe);
        citizen.getNavigation().stop();
        citizen.teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
        track.position = citizen.position();
    }

    /**
     * A safe node a few steps ahead on the citizen's current path, past whatever is blocking it.
     */
    @Nullable
    private static BlockPos alongPath(final AbstractEntityCitizen citizen)
    {
        final Path path = citizen.getNavigation().getPath();
        if (path == null || path.isDone())
        {
            return null;
        }
        final int last = Math.min(path.getNodeCount() - 1, path.getNextNodeIndex() + PATH_SKIP_NODES);
        for (int i = last; i > path.getNextNodeIndex(); i--)
        {
            final BlockPos node = path.getNodePos(i);
            if (node.distSqr(citizen.blockPosition()) >= 2 && SafeSpots.isSafe(citizen.level(), node))
            {
                return node;
            }
        }
        return null;
    }

    private static final class Track
    {
        private Vec3 position;
        private long stillSince;
        private long lastAttempt = Long.MIN_VALUE / 2;
        private int  insideChecks;

        private Track(final Vec3 position, final long now)
        {
            this.position = position;
            this.stillSince = now;
        }
    }
}

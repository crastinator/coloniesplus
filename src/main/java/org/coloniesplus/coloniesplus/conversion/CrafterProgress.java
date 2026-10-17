package org.coloniesplus.coloniesplus.conversion;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.coloniesplus.coloniesplus.Config;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Ties conversion to the colony's crafters: working a kind of material needs the hut whose
 * worker knows it (a Sawmill for wood, a Stonemason for stone, a Dyer for wool, a Blacksmith
 * for metal), and early conversions waste some of it, less as that hut is upgraded.
 */
public final class CrafterProgress
{
    /**
     * A kind of material and the crafter hut that works it.
     */
    public enum Trade
    {
        WOOD("wood"),
        STONE("stone"),
        WOOL("wool"),
        METAL("metal");

        private final String key;

        Trade(final String key)
        {
            this.key = key;
        }

        /**
         * @return the lang key suffix, e.g. "wood".
         */
        public String key()
        {
            return key;
        }

        /**
         * @return the crafter hut's display name, e.g. "Sawmill".
         */
        public MutableComponent hutName()
        {
            return Component.translatable(hut().get().getTranslationKey());
        }

        DeferredHolder<BuildingEntry, BuildingEntry> hut()
        {
            return switch (this)
            {
                case WOOD -> ModBuildings.sawmill;
                case STONE -> ModBuildings.stoneMason;
                case WOOL -> ModBuildings.dyer;
                case METAL -> ModBuildings.blacksmith;
            };
        }

        /**
         * @return the trade an item belongs to, or null for materials no crafter is needed for.
         */
        @Nullable
        public static Trade of(final ItemStack stack)
        {
            if (stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LOGS) || stack.is(Tags.Items.RODS_WOODEN))
            {
                return WOOD;
            }
            if (stack.is(ItemTags.STONE_CRAFTING_MATERIALS) || stack.is(ItemTags.STONE_TOOL_MATERIALS)
                || stack.is(Tags.Items.STONES) || stack.is(Tags.Items.COBBLESTONES))
            {
                return STONE;
            }
            if (stack.is(ItemTags.WOOL) || stack.is(ItemTags.WOOL_CARPETS))
            {
                return WOOL;
            }
            if (stack.is(Tags.Items.INGOTS) || stack.is(Tags.Items.NUGGETS))
            {
                return METAL;
            }
            return null;
        }
    }

    /**
     * Highest built level of each trade's hut in the colony, 0 if there is none.
     */
    private final int[] levels;

    private CrafterProgress(final int[] levels)
    {
        this.levels = levels;
    }

    public static CrafterProgress of(final IColony colony)
    {
        final int[] levels = new int[Trade.values().length];
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            for (final Trade trade : Trade.values())
            {
                if (building.getBuildingType() == trade.hut().get())
                {
                    levels[trade.ordinal()] = Math.max(levels[trade.ordinal()], building.getBuildingLevel());
                }
            }
        }
        return new CrafterProgress(levels);
    }

    /**
     * Rebuilds what {@link #levels()} returned; used by the client view.
     */
    public static CrafterProgress fromLevels(final int[] levels)
    {
        return new CrafterProgress(levels.clone());
    }

    public int[] levels()
    {
        return levels.clone();
    }

    public int level(final Trade trade)
    {
        return levels[trade.ordinal()];
    }

    public boolean allows(final Trade trade)
    {
        return !Config.requireCrafterHuts || level(trade) > 0;
    }

    /**
     * @return the share of a trade's material lost when converting, from the config's per-level
     * list. Without the hut (when huts are not required) the first entry applies.
     */
    public double waste(final Trade trade)
    {
        final List<Integer> perLevel = Config.wastePercentByLevel;
        if (perLevel.isEmpty())
        {
            return 0;
        }
        final int index = Math.min(Math.max(level(trade), 1), perLevel.size()) - 1;
        return perLevel.get(index) / 100.0;
    }

    /**
     * @return how many of {@code count} units of a trade's material a conversion loses on top.
     * Rounded down, so small conversions early on may lose nothing.
     */
    public int wasteFor(@Nullable final Trade trade, final int count)
    {
        return trade == null ? 0 : (int) Math.floor(count * waste(trade));
    }

    @Override
    public String toString()
    {
        return Arrays.toString(levels);
    }
}

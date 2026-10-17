package org.coloniesplus.coloniesplus.conversion;

import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.ICreatesResolversModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.util.constant.TypeConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.coloniesplus.coloniesplus.Config;
import org.coloniesplus.coloniesplus.client.ConversionModuleView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * Added to every builder's hut. Supplies the {@link ConversionResolver}, holds the builder's
 * conversion settings and history, and feeds the "Material Conversion" tab in the hut window.
 */
public class ConversionModule extends AbstractBuildingModule implements IPersistentModule, ICreatesResolversModule
{
    public static final int TOGGLE_SUBSTITUTION = 0;
    public static final int TOGGLE_CRAFTING     = 1;

    private static final int HISTORY_SIZE = 8;

    private static final String TAG_SUBSTITUTION = "substitution";
    private static final String TAG_CRAFTING     = "crafting";
    private static final String TAG_HISTORY      = "history";
    private static final String TAG_INPUTS       = "inputs";
    private static final String TAG_OUTPUT       = "output";

    private static BuildingEntry.ModuleProducer<ConversionModule, ConversionModuleView> producer;

    private boolean allowSubstitution = true;
    private boolean allowCrafting     = true;

    /**
     * Most recent first.
     */
    private final Deque<ConversionResolver.Conversion> history = new ArrayDeque<>();

    /**
     * Refreshed by {@link ConversionTicker}; only sent to clients, never saved.
     */
    private List<ItemStack>                         spare   = List.of();
    private List<ConversionResolver.Conversion> pending = List.of();
    private List<NeededMaterial>                    needed  = List.of();
    private List<CrafterStatus>                     crafters = List.of();

    /**
     * What the build was missing when {@link #rawMaterials} was worked out, so the recipe walk
     * only reruns when that changes.
     */
    @Nullable
    private List<ItemStack> missing;
    private List<ItemStack> rawMaterials = List.of();

    /**
     * Adds this module to MineColonies' builder hut. Must run once, at the same point on the
     * client and the server, because module ids are handed out in creation order.
     */
    public static void register()
    {
        producer = new BuildingEntry.ModuleProducer<>("coloniesplus_conversion", ConversionModule::new, () -> ConversionModuleView::new);
        ModBuildings.builder.get().getModuleProducers().add(producer);
    }

    public static BuildingEntry.ModuleProducer<ConversionModule, ConversionModuleView> producer()
    {
        return producer;
    }

    /**
     * @return the conversions this builder may use, combining its own toggles with the server config.
     */
    public ConversionPlanner.Options options()
    {
        return new ConversionPlanner.Options(
            Config.allowMaterialSubstitution && allowSubstitution,
            Config.allowRecipeConversion && allowCrafting);
    }

    public void setToggle(final int toggle, final boolean value)
    {
        switch (toggle)
        {
            case TOGGLE_SUBSTITUTION -> allowSubstitution = value;
            case TOGGLE_CRAFTING -> allowCrafting = value;
            default -> {
                return;
            }
        }
        markDirty();
    }

    void recordConversion(final List<ItemStack> inputs, final ItemStack output)
    {
        history.addFirst(new ConversionResolver.Conversion(inputs, output));
        while (history.size() > HISTORY_SIZE)
        {
            history.removeLast();
        }
        markDirty();
    }

    /**
     * A raw material the build would need if what it is missing were converted, and how much of
     * it the builder has spare.
     */
    public record NeededMaterial(ItemStack material, int spare)
    {
    }

    /**
     * Raw materials for what the build is missing; only broken down again when that changes.
     */
    List<ItemStack> rawMaterialsFor(final List<ItemStack> missing, final Supplier<List<ItemStack>> breakdown)
    {
        if (this.missing == null || !CountedStacks.same(this.missing, missing))
        {
            this.missing = missing;
            this.rawMaterials = breakdown.get();
        }
        return rawMaterials;
    }

    /**
     * Whether the colony has the crafter hut for a kind of material, its level, and how much of
     * that material conversions waste at that level.
     */
    public record CrafterStatus(CrafterProgress.Trade trade, int level, boolean allowed, int wastePercent)
    {
        static List<CrafterStatus> of(final CrafterProgress progress)
        {
            final List<CrafterStatus> statuses = new ArrayList<>();
            for (final CrafterProgress.Trade trade : CrafterProgress.Trade.values())
            {
                statuses.add(new CrafterStatus(trade, progress.level(trade), progress.allows(trade), (int) Math.round(progress.waste(trade) * 100)));
            }
            return statuses;
        }
    }

    /**
     * Updates what the hut tab shows, resyncing only when something changed.
     */
    void updateStatus(
        final List<ItemStack> spare,
        final List<ConversionResolver.Conversion> pending,
        final List<NeededMaterial> needed,
        final List<CrafterStatus> crafters)
    {
        if (CountedStacks.same(this.spare, spare) && samePending(this.pending, pending) && sameNeeded(this.needed, needed) && crafters.equals(this.crafters))
        {
            return;
        }
        this.spare = spare;
        this.pending = pending;
        this.needed = needed;
        this.crafters = crafters;
        markDirty();
    }

    private static boolean sameNeeded(final List<NeededMaterial> first, final List<NeededMaterial> second)
    {
        if (first.size() != second.size())
        {
            return false;
        }
        for (int i = 0; i < first.size(); i++)
        {
            if (first.get(i).spare() != second.get(i).spare()
                || !CountedStacks.same(List.of(first.get(i).material()), List.of(second.get(i).material())))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean samePending(final List<ConversionResolver.Conversion> first, final List<ConversionResolver.Conversion> second)
    {
        if (first.size() != second.size())
        {
            return false;
        }
        for (int i = 0; i < first.size(); i++)
        {
            if (!CountedStacks.same(first.get(i).inputs(), second.get(i).inputs())
                || !CountedStacks.same(List.of(first.get(i).output()), List.of(second.get(i).output())))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<IRequestResolver<?>> createResolvers()
    {
        return List.of(createResolver());
    }

    ConversionResolver createResolver()
    {
        return new ConversionResolver(building.getRequester().getLocation(), StandardFactoryController.getInstance().getNewInstance(TypeConstants.ITOKEN));
    }

    @Override
    public void serializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag compound)
    {
        compound.putBoolean(TAG_SUBSTITUTION, allowSubstitution);
        compound.putBoolean(TAG_CRAFTING, allowCrafting);

        final ListTag list = new ListTag();
        for (final ConversionResolver.Conversion conversion : history)
        {
            final CompoundTag entry = new CompoundTag();
            entry.put(TAG_INPUTS, CountedStacks.writeList(provider, conversion.inputs()));
            entry.put(TAG_OUTPUT, CountedStacks.write(provider, conversion.output()));
            list.add(entry);
        }
        compound.put(TAG_HISTORY, list);
    }

    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag compound)
    {
        allowSubstitution = !compound.contains(TAG_SUBSTITUTION) || compound.getBoolean(TAG_SUBSTITUTION);
        allowCrafting = !compound.contains(TAG_CRAFTING) || compound.getBoolean(TAG_CRAFTING);

        history.clear();
        final ListTag list = compound.getList(TAG_HISTORY, CountedStacks.LIST_TYPE);
        for (int i = 0; i < list.size() && i < HISTORY_SIZE; i++)
        {
            final CompoundTag entry = list.getCompound(i);
            history.addLast(new ConversionResolver.Conversion(
                CountedStacks.readList(provider, entry.getList(TAG_INPUTS, CountedStacks.LIST_TYPE)),
                CountedStacks.read(provider, entry.getCompound(TAG_OUTPUT))));
        }
    }

    @Override
    public void serializeToView(@NotNull final RegistryFriendlyByteBuf buf)
    {
        buf.writeBoolean(Config.enabled);
        buf.writeBoolean(Config.allowMaterialSubstitution);
        buf.writeBoolean(Config.allowRecipeConversion);
        buf.writeBoolean(allowSubstitution);
        buf.writeBoolean(allowCrafting);
        CountedStacks.writeList(buf, spare);
        writeConversions(buf, pending);
        writeConversions(buf, new ArrayList<>(history));
        buf.writeVarInt(needed.size());
        for (final NeededMaterial material : needed)
        {
            CountedStacks.write(buf, material.material());
            buf.writeVarInt(material.spare());
        }
        buf.writeVarInt(crafters.size());
        for (final CrafterStatus crafter : crafters)
        {
            buf.writeEnum(crafter.trade());
            buf.writeVarInt(crafter.level());
            buf.writeBoolean(crafter.allowed());
            buf.writeVarInt(crafter.wastePercent());
        }
    }

    /**
     * Reads the crafter statuses written by {@link #serializeToView}; used by the client view.
     */
    public static List<CrafterStatus> readCrafters(final RegistryFriendlyByteBuf buf)
    {
        final int size = buf.readVarInt();
        final List<CrafterStatus> crafters = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
        {
            crafters.add(new CrafterStatus(buf.readEnum(CrafterProgress.Trade.class), buf.readVarInt(), buf.readBoolean(), buf.readVarInt()));
        }
        return crafters;
    }

    /**
     * Reads the needed raw materials written by {@link #serializeToView}; used by the client view.
     */
    public static List<NeededMaterial> readNeeded(final RegistryFriendlyByteBuf buf)
    {
        final int size = buf.readVarInt();
        final List<NeededMaterial> needed = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
        {
            needed.add(new NeededMaterial(CountedStacks.read(buf), buf.readVarInt()));
        }
        return needed;
    }

    private static void writeConversions(final RegistryFriendlyByteBuf buf, final List<ConversionResolver.Conversion> conversions)
    {
        buf.writeVarInt(conversions.size());
        for (final ConversionResolver.Conversion conversion : conversions)
        {
            CountedStacks.writeList(buf, conversion.inputs());
            CountedStacks.write(buf, conversion.output());
        }
    }

    /**
     * Reads what {@link #writeConversions} wrote; used by the client view.
     */
    public static List<ConversionResolver.Conversion> readConversions(final RegistryFriendlyByteBuf buf)
    {
        final int size = buf.readVarInt();
        final List<ConversionResolver.Conversion> conversions = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
        {
            conversions.add(new ConversionResolver.Conversion(CountedStacks.readList(buf), CountedStacks.read(buf)));
        }
        return conversions;
    }

    /**
     * Reads a list written with {@link CountedStacks#writeList(RegistryFriendlyByteBuf, List)}; used by the client view.
     */
    public static List<ItemStack> readStacks(final RegistryFriendlyByteBuf buf)
    {
        return CountedStacks.readList(buf);
    }
}

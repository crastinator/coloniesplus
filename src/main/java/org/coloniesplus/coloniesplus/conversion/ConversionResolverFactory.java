package org.coloniesplus.coloniesplus.conversion;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolverFactory;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.constant.TypeConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Creates, saves and syncs {@link ConversionResolver}s for MineColonies' request system.
 */
public class ConversionResolverFactory implements IRequestResolverFactory<ConversionResolver>
{
    /**
     * Must not collide with MineColonies' ids (0-60 at the time of writing) or other add-ons'.
     */
    private static final short SERIALIZATION_ID = 31_501;

    private static final String NBT_TOKEN    = "Token";
    private static final String NBT_LOCATION = "Location";
    private static final String NBT_PENDING  = "Pending";
    private static final String NBT_REQUEST  = "Request";
    private static final String NBT_INPUTS   = "Inputs";
    private static final String NBT_OUTPUT   = "Output";

    @NotNull
    @Override
    public TypeToken<? extends ConversionResolver> getFactoryOutputType()
    {
        return TypeToken.of(ConversionResolver.class);
    }

    @NotNull
    @Override
    public TypeToken<? extends ILocation> getFactoryInputType()
    {
        return TypeConstants.ILOCATION;
    }

    @NotNull
    @Override
    public ConversionResolver getNewInstance(@NotNull final IFactoryController factoryController, @NotNull final ILocation location, @NotNull final Object... context)
    {
        return new ConversionResolver(location, factoryController.getNewInstance(TypeConstants.ITOKEN));
    }

    @NotNull
    @Override
    public CompoundTag serialize(@NotNull final HolderLookup.Provider provider, @NotNull final IFactoryController controller, @NotNull final ConversionResolver resolver)
    {
        final CompoundTag compound = new CompoundTag();
        compound.put(NBT_TOKEN, controller.serializeTag(provider, resolver.getId()));
        compound.put(NBT_LOCATION, controller.serializeTag(provider, resolver.getLocation()));

        final ListTag pending = new ListTag();
        for (final Map.Entry<IToken<?>, ConversionResolver.Conversion> entry : resolver.getPending().entrySet())
        {
            final CompoundTag conversion = new CompoundTag();
            conversion.put(NBT_REQUEST, controller.serializeTag(provider, entry.getKey()));
            conversion.put(NBT_INPUTS, CountedStacks.writeList(provider, entry.getValue().inputs()));
            conversion.put(NBT_OUTPUT, CountedStacks.write(provider, entry.getValue().output()));
            pending.add(conversion);
        }
        compound.put(NBT_PENDING, pending);
        return compound;
    }

    @NotNull
    @Override
    public ConversionResolver deserialize(@NotNull final HolderLookup.Provider provider, @NotNull final IFactoryController controller, @NotNull final CompoundTag nbt)
    {
        final IToken<?> token = controller.deserializeTag(provider, nbt.getCompound(NBT_TOKEN));
        final ILocation location = controller.deserializeTag(provider, nbt.getCompound(NBT_LOCATION));
        final ConversionResolver resolver = new ConversionResolver(location, token);

        final ListTag pending = nbt.getList(NBT_PENDING, CountedStacks.LIST_TYPE);
        for (int i = 0; i < pending.size(); i++)
        {
            final CompoundTag conversion = pending.getCompound(i);
            final IToken<?> request = controller.deserializeTag(provider, conversion.getCompound(NBT_REQUEST));
            final List<ItemStack> inputs = CountedStacks.readList(provider, conversion.getList(NBT_INPUTS, CountedStacks.LIST_TYPE));
            final ItemStack output = CountedStacks.read(provider, conversion.getCompound(NBT_OUTPUT));
            resolver.getPending().put(request, new ConversionResolver.Conversion(inputs, output));
        }
        return resolver;
    }

    @Override
    public void serialize(final IFactoryController controller, final ConversionResolver resolver, final RegistryFriendlyByteBuf buffer)
    {
        controller.serialize(buffer, resolver.getId());
        controller.serialize(buffer, resolver.getLocation());
        buffer.writeVarInt(resolver.getPending().size());
        for (final Map.Entry<IToken<?>, ConversionResolver.Conversion> entry : resolver.getPending().entrySet())
        {
            controller.serialize(buffer, entry.getKey());
            CountedStacks.writeList(buffer, entry.getValue().inputs());
            CountedStacks.write(buffer, entry.getValue().output());
        }
    }

    @Override
    public ConversionResolver deserialize(final IFactoryController controller, final RegistryFriendlyByteBuf buffer) throws Throwable
    {
        final IToken<?> token = controller.deserialize(buffer);
        final ILocation location = controller.deserialize(buffer);
        final ConversionResolver resolver = new ConversionResolver(location, token);

        final int size = buffer.readVarInt();
        for (int i = 0; i < size; i++)
        {
            final IToken<?> request = controller.deserialize(buffer);
            final List<ItemStack> inputs = CountedStacks.readList(buffer);
            final ItemStack output = CountedStacks.read(buffer);
            resolver.getPending().put(request, new ConversionResolver.Conversion(inputs, output));
        }
        return resolver;
    }

    @Override
    public short getSerializationId()
    {
        return SERIALIZATION_ID;
    }
}

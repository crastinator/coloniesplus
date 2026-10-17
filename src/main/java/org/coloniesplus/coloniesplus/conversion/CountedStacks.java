package org.coloniesplus.coloniesplus.conversion;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Saves and syncs item stacks whose count can exceed a normal stack (e.g. "128 oak planks").
 * The vanilla item codec only accepts counts up to 99, so the count is stored separately.
 */
final class CountedStacks
{
    /**
     * NBT type of the entries in lists written by {@link #writeList(HolderLookup.Provider, List)}.
     */
    static final int LIST_TYPE = Tag.TAG_COMPOUND;

    private static final String TAG_ITEM  = "item";
    private static final String TAG_COUNT = "count";

    private CountedStacks()
    {
    }

    static CompoundTag write(final HolderLookup.Provider provider, final ItemStack stack)
    {
        final CompoundTag tag = new CompoundTag();
        tag.put(TAG_ITEM, stack.copyWithCount(1).save(provider));
        tag.putInt(TAG_COUNT, stack.getCount());
        return tag;
    }

    static ItemStack read(final HolderLookup.Provider provider, final CompoundTag tag)
    {
        final ItemStack stack = ItemStack.parseOptional(provider, tag.getCompound(TAG_ITEM));
        stack.setCount(tag.getInt(TAG_COUNT));
        return stack;
    }

    static ListTag writeList(final HolderLookup.Provider provider, final List<ItemStack> stacks)
    {
        final ListTag list = new ListTag();
        for (final ItemStack stack : stacks)
        {
            list.add(write(provider, stack));
        }
        return list;
    }

    static List<ItemStack> readList(final HolderLookup.Provider provider, final ListTag list)
    {
        final List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
        {
            final ItemStack stack = read(provider, list.getCompound(i));
            if (!stack.isEmpty())
            {
                stacks.add(stack);
            }
        }
        return stacks;
    }

    static void write(final RegistryFriendlyByteBuf buf, final ItemStack stack)
    {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack.copyWithCount(1));
        buf.writeVarInt(stack.getCount());
    }

    static ItemStack read(final RegistryFriendlyByteBuf buf)
    {
        final ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        stack.setCount(buf.readVarInt());
        return stack;
    }

    static void writeList(final RegistryFriendlyByteBuf buf, final List<ItemStack> stacks)
    {
        buf.writeVarInt(stacks.size());
        for (final ItemStack stack : stacks)
        {
            write(buf, stack);
        }
    }

    static List<ItemStack> readList(final RegistryFriendlyByteBuf buf)
    {
        final int size = buf.readVarInt();
        final List<ItemStack> stacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
        {
            stacks.add(read(buf));
        }
        return stacks;
    }

    /**
     * @return true if both lists hold the same items in the same amounts and order.
     */
    static boolean same(final List<ItemStack> first, final List<ItemStack> second)
    {
        if (first.size() != second.size())
        {
            return false;
        }
        for (int i = 0; i < first.size(); i++)
        {
            if (first.get(i).getCount() != second.get(i).getCount() || !ItemStack.isSameItemSameComponents(first.get(i), second.get(i)))
            {
                return false;
            }
        }
        return true;
    }
}

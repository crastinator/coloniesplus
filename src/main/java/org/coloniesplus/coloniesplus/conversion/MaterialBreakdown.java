package org.coloniesplus.coloniesplus.conversion;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.BuildingResourcesModule;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out which raw materials (planks, stone, ingots, ...) a builder would need to convert
 * everything its build is still missing, following the same recipes conversion uses. It is an
 * estimate: amounts are summed before rounding up, while real conversions round up per request.
 */
final class MaterialBreakdown
{
    /**
     * Recipe chains deeper than this are cut off and the item is listed as it is.
     */
    private static final int MAX_DEPTH = 6;

    private final ServerLevel                                    level;
    private final List<ItemStack>                                held;
    private final Map<Item, List<RecipeHolder<CraftingRecipe>>> recipes = new HashMap<>();
    private final List<ItemStack>                                materials = new ArrayList<>();
    private final List<Double>                                   amounts   = new ArrayList<>();

    private MaterialBreakdown(final ServerLevel level, final List<ItemStack> held)
    {
        this.level = level;
        this.held = held;
        for (final RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING))
        {
            final ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (!result.isEmpty())
            {
                recipes.computeIfAbsent(result.getItem(), item -> new ArrayList<>()).add(holder);
            }
        }
    }

    /**
     * @return what the build still needs beyond what the builder holds, per item.
     */
    static List<ItemStack> missing(final IBuilding building)
    {
        if (!building.hasModule(BuildingModules.BUILDING_RESOURCES))
        {
            return List.of();
        }

        final List<IItemHandler> handlers = building.getHandlers();
        final List<ItemStack> missing = new ArrayList<>();
        for (final BuildingBuilderResource resource : building.getModule(BuildingModules.BUILDING_RESOURCES).getNeededResources().values())
        {
            final int lacking = resource.getAmount() - count(handlers, resource.getItemStack());
            if (lacking > 0)
            {
                missing.add(resource.getItemStack().copyWithCount(lacking));
            }
        }
        return missing;
    }

    /**
     * Breaks the missing items down into raw materials, largest amount first.
     */
    static List<ItemStack> rawMaterials(final List<ItemStack> missing, final IBuilding building, final ServerLevel level)
    {
        if (missing.isEmpty())
        {
            return List.of();
        }

        final MaterialBreakdown breakdown = new MaterialBreakdown(level, heldItems(building.getHandlers()));
        for (final ItemStack stack : missing)
        {
            breakdown.expand(stack, stack.getCount(), new ArrayList<>());
        }

        final List<ItemStack> raw = new ArrayList<>();
        for (int i = 0; i < breakdown.materials.size(); i++)
        {
            // Sums of fractions such as 2/9 of an ingot can land a hair above a whole number.
            final int count = (int) Math.ceil(breakdown.amounts.get(i) - 1e-6);
            if (count > 0)
            {
                raw.add(breakdown.materials.get(i).copyWithCount(count));
            }
        }
        raw.sort(Comparator.comparingInt(ItemStack::getCount).reversed());
        return raw;
    }

    /**
     * How much of a raw material the builder has spare. With substitution on, spare planks or
     * stone of another type in the same family count too, and planks count what spare logs would saw into.
     */
    static int spareFor(final ItemStack material, final List<ItemStack> spare, final ConversionPlanner.Options options, final ServerLevel level)
    {
        final boolean family = options.substitution() && ConversionPlanner.isSubstitutableMaterial(material);
        int count = 0;
        for (final ItemStack stack : spare)
        {
            if (ItemStack.isSameItemSameComponents(stack, material)
                || (family && ConversionPlanner.sharesMaterialFamily(material, stack))
                || (material.is(Items.DIRT) && ConversionPlanner.isDirtSource(stack)))
            {
                count += stack.getCount();
            }
        }
        return count + ConversionPlanner.planksFromSpare(material, spare, options, level);
    }

    private void expand(final ItemStack item, final double amount, final List<ItemStack> chain)
    {
        if (chain.size() >= MAX_DEPTH || isRaw(item))
        {
            add(item, amount);
            return;
        }

        chain.add(item);
        try
        {
            final ConversionPlanner.Cut cut = ConversionPlanner.findCut(item, level);
            if (cut != null)
            {
                final double cuts = amount / cut.result().getCount();
                for (final ItemStack material : cut.materials())
                {
                    expand(material, cuts, chain);
                }
                return;
            }

            final CraftingRecipe recipe = recipeFor(item, chain);
            if (recipe == null)
            {
                add(item, amount);
                return;
            }

            final double crafts = amount / recipe.getResultItem(level.registryAccess()).getCount();
            for (final Ingredient ingredient : recipe.getIngredients())
            {
                if (ingredient.isEmpty() || ConversionPlanner.isBuildTool(ingredient))
                {
                    continue;
                }
                final ItemStack choice = choose(ingredient);
                if (!choice.isEmpty())
                {
                    expand(choice, crafts, chain);
                }
            }
        }
        finally
        {
            chain.remove(chain.size() - 1);
        }
    }

    /**
     * The first crafting recipe for the item that does not lead back to something already being
     * broken down, such as nuggets into an ingot into nuggets. Recipes that only re-dye an item
     * (a red carpet from any carpet and red dye) are a last resort, so a red carpet breaks down
     * into wool rather than another carpet.
     */
    @Nullable
    private CraftingRecipe recipeFor(final ItemStack item, final List<ItemStack> chain)
    {
        CraftingRecipe dyeing = null;
        for (final RecipeHolder<CraftingRecipe> holder : recipes.getOrDefault(item.getItem(), List.of()))
        {
            final CraftingRecipe recipe = holder.value();
            if (!ItemStack.isSameItemSameComponents(recipe.getResultItem(level.registryAccess()), item))
            {
                continue;
            }

            final boolean cyclic = recipe.getIngredients().stream()
                .anyMatch(ingredient -> !ingredient.isEmpty() && chain.stream().anyMatch(ingredient::test));
            if (cyclic)
            {
                continue;
            }

            final boolean usesDye = recipe.getIngredients().stream()
                .anyMatch(ingredient -> Arrays.stream(ingredient.getItems()).anyMatch(stack -> stack.is(Tags.Items.DYES)));
            if (!usesDye)
            {
                return recipe;
            }
            if (dyeing == null)
            {
                dyeing = recipe;
            }
        }
        return dyeing;
    }

    /**
     * For an ingredient that accepts several items, prefers one the builder already holds.
     */
    private ItemStack choose(final Ingredient ingredient)
    {
        final ItemStack[] options = ingredient.getItems();
        if (options.length == 0)
        {
            return ItemStack.EMPTY;
        }
        for (final ItemStack stack : held)
        {
            if (ingredient.test(stack))
            {
                return stack.copyWithCount(1);
            }
        }
        return options[0].copyWithCount(1);
    }

    private void add(final ItemStack item, final double amount)
    {
        for (int i = 0; i < materials.size(); i++)
        {
            if (ItemStack.isSameItemSameComponents(materials.get(i), item))
            {
                amounts.set(i, amounts.get(i) + amount);
                return;
            }
        }
        materials.add(item.copyWithCount(1));
        amounts.add(amount);
    }

    /**
     * Materials players gather rather than craft, where breaking down stops even if a recipe
     * exists (planks from logs, ingots from nuggets).
     */
    private static boolean isRaw(final ItemStack stack)
    {
        return stack.is(ItemTags.PLANKS)
            || stack.is(ItemTags.LOGS)
            || stack.is(ItemTags.STONE_CRAFTING_MATERIALS)
            || stack.is(ItemTags.STONE_TOOL_MATERIALS)
            || stack.is(ItemTags.COALS)
            || stack.is(ItemTags.WOOL)
            || stack.is(Tags.Items.INGOTS)
            || stack.is(Tags.Items.GEMS)
            || stack.is(Tags.Items.DUSTS)
            || stack.is(Tags.Items.DYES);
    }

    private static int count(final List<IItemHandler> handlers, final ItemStack wanted)
    {
        int count = 0;
        for (final IItemHandler handler : handlers)
        {
            for (int slot = 0; slot < handler.getSlots(); slot++)
            {
                final ItemStack stack = handler.getStackInSlot(slot);
                if (ItemStack.isSameItemSameComponents(stack, wanted))
                {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private static List<ItemStack> heldItems(final List<IItemHandler> handlers)
    {
        final List<ItemStack> held = new ArrayList<>();
        for (final IItemHandler handler : handlers)
        {
            for (int slot = 0; slot < handler.getSlots(); slot++)
            {
                final ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty())
                {
                    held.add(stack);
                }
            }
        }
        return held;
    }
}

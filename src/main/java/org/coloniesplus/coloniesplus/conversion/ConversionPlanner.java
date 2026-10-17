package org.coloniesplus.coloniesplus.conversion;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockManager;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import com.ldtteam.domumornamentum.recipe.ModRecipeTypes;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipe;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipeInput;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.BuildingResourcesModule;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out how a builder can turn materials its build does not need into an item it is
 * waiting for, and applies that plan to its inventories. Conversion uses the server's normal
 * crafting recipes, so modded plank and stone recipes work without a hard-coded material list.
 * Domum Ornamentum blocks are planned like the Architect's Cutter makes them.
 */
public final class ConversionPlanner
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Material types that can stand in for one another, e.g. spruce planks for oak planks or
     * white wool for red wool.
     */
    private static final List<TagKey<Item>> FAMILIES = List.of(
        ItemTags.PLANKS,
        ItemTags.STONE_CRAFTING_MATERIALS,
        ItemTags.STONE_TOOL_MATERIALS,
        ItemTags.WOOL,
        ItemTags.WOOL_CARPETS);

    /**
     * Families that also stand in for each other inside recipes, e.g. any wool for a red carpet.
     */
    private static final List<TagKey<Item>> RECIPE_FAMILIES = List.of(
        ItemTags.PLANKS,
        ItemTags.WOOL,
        ItemTags.WOOL_CARPETS);

    /**
     * Structurize's build tool, which some recipes (e.g. racks) ask for. It is handed back after
     * crafting, and builders are the ones who use it, so they never need to hold one to convert.
     */
    private static final ResourceLocation BUILD_TOOL = ResourceLocation.fromNamespaceAndPath("structurize", "sceptergold");

    private ConversionPlanner()
    {
    }

    /**
     * Which kinds of conversion a builder may use.
     *
     * @param substitution swap one plank, stone, wool or carpet type for another, and planks, wool or carpets inside recipes.
     * @param crafting     craft the requested item from spare materials, at a crafting table or the Architect's Cutter.
     */
    public record Options(boolean substitution, boolean crafting)
    {
        public boolean any()
        {
            return substitution || crafting;
        }
    }

    /**
     * Plans how to produce {@code needed} of {@code target} from the builder's spare materials.
     *
     * @return the plan, or null if the spare materials cannot cover the whole amount.
     */
    @Nullable
    public static Plan plan(final IBuilding building, final ItemStack target, final int needed, final Options options, final ServerLevel level)
    {
        if (target.isEmpty() || needed <= 0 || !options.any())
        {
            return null;
        }

        final List<IItemHandler> handlers = building.getHandlers();
        if (handlers.isEmpty())
        {
            return null;
        }

        final List<SurplusSlot> surplus = findSurplus(building, handlers, target);
        if (surplus.isEmpty())
        {
            return null;
        }

        final CrafterProgress progress = CrafterProgress.of(building.getColony());
        Plan plan = options.substitution() ? applyProgress(planSubstitution(target, needed, surplus), surplus, progress, false) : null;
        if (plan == null && options.crafting())
        {
            plan = applyProgress(planCrafting(target, needed, surplus, options, level), surplus, progress, false);
        }
        if (plan == null && options.crafting())
        {
            plan = applyProgress(planCutting(target, needed, surplus, options, level), surplus, progress, true);
        }
        return plan;
    }

    /**
     * Checks the colony has the crafter huts for every kind of material a plan uses, then adds
     * the material those crafters would waste at their level. Waste is taken from the same items
     * the plan uses where possible, then from other spare material of the same kind.
     *
     * @param cutter whether the plan uses the Architect's Cutter, which needs a Sawmill or Stonemason.
     * @return the plan, or null if a crafter is missing or the spare materials cannot cover the waste.
     */
    @Nullable
    private static Plan applyProgress(@Nullable final Plan plan, final List<SurplusSlot> surplus, final CrafterProgress progress, final boolean cutter)
    {
        if (plan == null)
        {
            return null;
        }
        if (cutter && !progress.allows(CrafterProgress.Trade.WOOD) && !progress.allows(CrafterProgress.Trade.STONE))
        {
            return null;
        }

        final Map<CrafterProgress.Trade, Integer> used = new EnumMap<>(CrafterProgress.Trade.class);
        for (final Map.Entry<SurplusSlot, Integer> entry : plan.takes.entrySet())
        {
            final CrafterProgress.Trade trade = CrafterProgress.Trade.of(entry.getKey().stack);
            if (trade != null)
            {
                used.merge(trade, entry.getValue(), Integer::sum);
            }
        }

        for (final Map.Entry<CrafterProgress.Trade, Integer> entry : used.entrySet())
        {
            final CrafterProgress.Trade trade = entry.getKey();
            if (!progress.allows(trade))
            {
                return null;
            }

            final List<SurplusSlot> usedSlots = List.copyOf(plan.takes.keySet());
            for (int i = progress.wasteFor(trade, entry.getValue()); i > 0; i--)
            {
                SurplusSlot slot = findSlot(usedSlots, plan, stack -> CrafterProgress.Trade.of(stack) == trade);
                if (slot == null)
                {
                    slot = findSlot(surplus, plan, stack -> CrafterProgress.Trade.of(stack) == trade);
                }
                if (slot == null)
                {
                    return null;
                }
                plan.take(slot, 1);
            }
        }
        return plan;
    }

    /**
     * Sums up what the builder holds beyond what its current build needs.
     */
    public static List<ItemStack> spareMaterials(final IBuilding building)
    {
        final List<IItemHandler> handlers = building.getHandlers();
        if (handlers.isEmpty())
        {
            return List.of();
        }
        return summarize(findSurplus(building, handlers, ItemStack.EMPTY));
    }

    /**
     * Applies a plan in two passes: every extraction and insertion is simulated first, and
     * nothing is changed unless all of them would succeed. Anything that still does not fit
     * afterwards is dropped at the hut rather than destroyed.
     */
    public static boolean execute(final Plan plan, final IBuilding building, final ServerLevel level)
    {
        for (final Map.Entry<SurplusSlot, Integer> entry : plan.takes.entrySet())
        {
            final SurplusSlot slot = entry.getKey();
            final ItemStack simulated = slot.handler.extractItem(slot.slot, entry.getValue(), true);
            if (simulated.getCount() < entry.getValue() || !ItemStack.isSameItemSameComponents(simulated, slot.stack))
            {
                return false;
            }
        }

        final List<IItemHandler> destinations = insertionOrder(building);
        final List<ItemStack> outputs = splitIntoStacks(plan.outputs);
        for (final ItemStack output : outputs)
        {
            if (!insert(destinations, output, true).isEmpty())
            {
                return false;
            }
        }

        for (final Map.Entry<SurplusSlot, Integer> entry : plan.takes.entrySet())
        {
            entry.getKey().handler.extractItem(entry.getKey().slot, entry.getValue(), false);
        }

        for (final ItemStack output : outputs)
        {
            final ItemStack leftover = insert(destinations, output, false);
            if (!leftover.isEmpty())
            {
                LOGGER.warn("Builder at {} had no room for {}; dropping it at the hut", building.getPosition(), leftover);
                Block.popResource(level, building.getPosition().above(), leftover);
            }
        }
        return true;
    }

    /**
     * Lists every slot holding items beyond what the current build still needs. Items matching
     * {@code excluded}, and the builder's own gear, are never treated as surplus.
     */
    private static List<SurplusSlot> findSurplus(final IBuilding building, final List<IItemHandler> handlers, final ItemStack excluded)
    {
        final List<Reservation> reservations = new ArrayList<>();
        if (building.hasModule(BuildingModules.BUILDING_RESOURCES))
        {
            final BuildingResourcesModule resources = building.getModule(BuildingModules.BUILDING_RESOURCES);
            for (final BuildingBuilderResource resource : resources.getNeededResources().values())
            {
                reservations.add(new Reservation(resource.getItemStack(), resource.getAmount()));
            }
        }

        final List<SurplusSlot> surplus = new ArrayList<>();
        for (final IItemHandler handler : handlers)
        {
            for (int slot = 0; slot < handler.getSlots(); slot++)
            {
                final ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, excluded) || isKept(stack))
                {
                    continue;
                }

                int spare = stack.getCount();
                for (final Reservation reservation : reservations)
                {
                    if (spare > 0 && reservation.remaining > 0 && ItemStack.isSameItemSameComponents(stack, reservation.stack))
                    {
                        final int reserved = Math.min(spare, reservation.remaining);
                        reservation.remaining -= reserved;
                        spare -= reserved;
                    }
                }

                if (spare > 0)
                {
                    surplus.add(new SurplusSlot(handler, slot, stack.copy(), spare));
                }
            }
        }
        return surplus;
    }

    /**
     * Tools, armour and food are the builder's own gear, never building material, even though
     * the hut and the builder's inventory hold them alongside it.
     */
    private static boolean isKept(final ItemStack stack)
    {
        return stack.isDamageableItem() || stack.has(DataComponents.FOOD);
    }

    private static List<ItemStack> summarize(final List<SurplusSlot> surplus)
    {
        final List<ItemStack> totals = new ArrayList<>();
        for (final SurplusSlot slot : surplus)
        {
            addCounted(totals, slot.stack, slot.spare);
        }
        return totals;
    }

    private static void addCounted(final List<ItemStack> totals, final ItemStack stack, final int count)
    {
        for (final ItemStack total : totals)
        {
            if (ItemStack.isSameItemSameComponents(total, stack))
            {
                total.grow(count);
                return;
            }
        }
        totals.add(stack.copyWithCount(count));
    }

    @Nullable
    private static Plan planSubstitution(final ItemStack target, final int needed, final List<SurplusSlot> surplus)
    {
        if (!isSubstitutableMaterial(target))
        {
            return null;
        }

        final Plan plan = new Plan(target, needed);
        int remaining = needed;
        for (final SurplusSlot slot : surplus)
        {
            if (remaining <= 0)
            {
                break;
            }
            if (!sharesMaterialFamily(target, slot.stack))
            {
                continue;
            }

            final int taken = Math.min(remaining, slot.spare);
            plan.take(slot, taken);
            remaining -= taken;
        }

        if (remaining > 0)
        {
            return null;
        }
        plan.outputs.add(target.copyWithCount(needed));
        return plan;
    }

    /**
     * Finds the first crafting recipe for the target whose ingredients the surplus covers for
     * enough crafts to fill the whole request. The same plan is used to check and to consume,
     * so the two can never disagree.
     */
    @Nullable
    private static Plan planCrafting(
        final ItemStack target,
        final int needed,
        final List<SurplusSlot> surplus,
        final Options options,
        final ServerLevel level)
    {
        for (final RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING))
        {
            final CraftingRecipe recipe = holder.value();
            final ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty() || !ItemStack.isSameItemSameComponents(result, target))
            {
                continue;
            }

            final List<Ingredient> ingredients = recipe.getIngredients().stream()
                .filter(ingredient -> !ingredient.isEmpty())
                .toList();
            if (ingredients.isEmpty())
            {
                continue;
            }

            final int crafts = (int) Math.ceil((double) needed / result.getCount());
            final Plan plan = planIngredients(target, needed, ingredients, crafts, surplus, options, level);
            if (plan != null)
            {
                plan.outputs.add(0, result.copyWithCount(crafts * result.getCount()));
                return plan;
            }
        }
        return null;
    }

    /**
     * Plans a Domum Ornamentum block the way the Architect's Cutter makes it: each cut uses one
     * block per texture the request names.
     */
    @Nullable
    private static Plan planCutting(
        final ItemStack target,
        final int needed,
        final List<SurplusSlot> surplus,
        final Options options,
        final ServerLevel level)
    {
        final Cut cut = findCut(target, level);
        if (cut == null)
        {
            return null;
        }

        final int cuts = (int) Math.ceil((double) needed / cut.result().getCount());
        final Plan plan = planMaterials(target, needed, cut.materials(), cuts, surplus, options);
        if (plan != null)
        {
            plan.outputs.add(cut.result().copyWithCount(cuts * cut.result().getCount()));
        }
        return plan;
    }

    /**
     * One Architect's Cutter cut of a Domum Ornamentum block: the texture blocks it takes, one
     * each, and what it makes.
     */
    record Cut(List<ItemStack> materials, ItemStack result)
    {
    }

    /**
     * Finds how the Architect's Cutter makes {@code target}. The cutter's own recipe assembles the
     * result, so a cut is only returned if that comes out exactly as {@code target}.
     */
    @Nullable
    static Cut findCut(final ItemStack target, final ServerLevel level)
    {
        if (!(target.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof IMateriallyTexturedBlock textured))
        {
            return null;
        }

        final Map<ResourceLocation, Block> textures = MaterialTextureData.readFromItemStack(target).getTexturedComponents();
        final SimpleContainer input = new SimpleContainer(IMateriallyTexturedBlockManager.getInstance().getMaxTexturableComponentCount());
        final List<ItemStack> materials = new ArrayList<>();
        int slot = 0;
        for (final IMateriallyTexturedBlockComponent component : textured.getComponents())
        {
            final Block texture = textures.get(component.getId());
            if (texture != null && slot < input.getContainerSize())
            {
                final ItemStack material = new ItemStack(texture);
                input.setItem(slot, material);
                materials.add(material);
            }
            slot++;
        }
        if (materials.isEmpty())
        {
            return null;
        }

        final ArchitectsCutterRecipeInput cutterInput = new ArchitectsCutterRecipeInput(input);
        for (final RecipeHolder<ArchitectsCutterRecipe> holder : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.ARCHITECTS_CUTTER.get()))
        {
            final ArchitectsCutterRecipe recipe = holder.value();
            if (recipe.getBlock() != blockItem.getBlock())
            {
                continue;
            }

            final ItemStack result = recipe.assemble(cutterInput, level.registryAccess());
            if (!result.isEmpty() && ItemStack.isSameItemSameComponents(result, target))
            {
                return new Cut(materials, result);
            }
        }
        return null;
    }

    /**
     * Takes one of each material per cut, preferring the exact block. With substitution on, a
     * plank or stone material can come from another type of the same family, as if swapped first.
     */
    @Nullable
    private static Plan planMaterials(
        final ItemStack target,
        final int needed,
        final List<ItemStack> materials,
        final int cuts,
        final List<SurplusSlot> surplus,
        final Options options)
    {
        final Plan plan = new Plan(target, needed);
        for (int cut = 0; cut < cuts; cut++)
        {
            for (final ItemStack material : materials)
            {
                SurplusSlot slot = findSlot(surplus, plan, stack -> ItemStack.isSameItem(stack, material));
                if (slot == null && options.substitution() && isSubstitutableMaterial(material))
                {
                    slot = findSlot(surplus, plan, stack -> sharesMaterialFamily(material, stack));
                }
                if (slot == null)
                {
                    return null;
                }
                plan.take(slot, 1);
            }
        }
        return plan;
    }

    @Nullable
    private static Plan planIngredients(
        final ItemStack target,
        final int needed,
        final List<Ingredient> ingredients,
        final int crafts,
        final List<SurplusSlot> surplus,
        final Options options,
        final ServerLevel level)
    {
        final List<IngredientRule> rules = ingredients.stream().map(ingredient -> new IngredientRule(ingredient, options, level)).toList();
        final Plan plan = new Plan(target, needed);
        for (int craft = 0; craft < crafts; craft++)
        {
            for (final IngredientRule rule : rules)
            {
                if (!takeIngredient(rule, surplus, plan))
                {
                    return null;
                }
            }
        }

        for (final ItemStack leftover : plan.leftovers)
        {
            if (!leftover.isEmpty())
            {
                plan.outputs.add(leftover);
            }
        }
        return plan;
    }

    /**
     * Takes one unit of an ingredient, preferring the exact item the recipe asks for. Failing
     * that, a plank, wool or carpet ingredient accepts any of its kind, and otherwise the ingredient is crafted in one
     * step from spare materials, e.g. nuggets from an ingot or sticks from planks. What such a
     * craft makes beyond one unit is used for later units first, and the rest goes to the hut.
     */
    private static boolean takeIngredient(final IngredientRule rule, final List<SurplusSlot> surplus, final Plan plan)
    {
        if (rule.buildTool)
        {
            return true;
        }

        final SurplusSlot exact = findSlot(surplus, plan, rule.ingredient::test);
        if (exact != null)
        {
            takeOne(exact, plan);
            return true;
        }

        if (rule.family != null)
        {
            final SurplusSlot member = findSlot(surplus, plan, stack -> stack.is(rule.family));
            if (member != null)
            {
                takeOne(member, plan);
                return true;
            }
        }

        for (final ItemStack leftover : plan.leftovers)
        {
            if (!leftover.isEmpty() && rule.ingredient.test(leftover))
            {
                leftover.shrink(1);
                return true;
            }
        }

        for (final SubCraft craft : rule.subCrafts())
        {
            if (available(surplus, plan, craft.input::test) < craft.inputCount)
            {
                continue;
            }
            for (int i = 0; i < craft.inputCount; i++)
            {
                takeOne(findSlot(surplus, plan, craft.input::test), plan);
            }
            if (craft.result.getCount() > 1)
            {
                addCounted(plan.leftovers, craft.result, craft.result.getCount() - 1);
            }
            return true;
        }
        return false;
    }

    private static int available(final List<SurplusSlot> surplus, final Plan plan, final java.util.function.Predicate<ItemStack> matches)
    {
        int count = 0;
        for (final SurplusSlot slot : surplus)
        {
            if (matches.test(slot.stack))
            {
                count += slot.spare - plan.taken(slot);
            }
        }
        return count;
    }

    /**
     * Crafting recipes that make the ingredient from a single kind of material, such as an ingot
     * into nuggets or planks into sticks. Recipes whose material the ingredient itself accepts
     * (e.g. re-dyeing) are left out, since they would not turn anything spare into it.
     */
    private static List<SubCraft> findSubCrafts(final Ingredient ingredient, final ServerLevel level)
    {
        final List<SubCraft> crafts = new ArrayList<>();
        for (final RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING))
        {
            final ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (result.isEmpty() || !ingredient.test(result))
            {
                continue;
            }

            final List<Ingredient> inputs = holder.value().getIngredients().stream()
                .filter(input -> !input.isEmpty())
                .toList();
            if (inputs.isEmpty() || !inputs.stream().allMatch(input -> sameIngredient(input, inputs.get(0))))
            {
                continue;
            }

            final Ingredient input = inputs.get(0);
            if (Arrays.stream(input.getItems()).anyMatch(ingredient::test))
            {
                continue;
            }
            crafts.add(new SubCraft(input, inputs.size(), result.copy()));
        }
        return crafts;
    }

    private static boolean sameIngredient(final Ingredient first, final Ingredient second)
    {
        if (first == second)
        {
            return true;
        }
        final ItemStack[] firstItems = first.getItems();
        final ItemStack[] secondItems = second.getItems();
        if (firstItems.length != secondItems.length)
        {
            return false;
        }
        for (int i = 0; i < firstItems.length; i++)
        {
            if (!ItemStack.isSameItemSameComponents(firstItems[i], secondItems[i]))
            {
                return false;
            }
        }
        return true;
    }

    static boolean isBuildTool(final Ingredient ingredient)
    {
        final ItemStack tool = new ItemStack(BuiltInRegistries.ITEM.get(BUILD_TOOL));
        return !tool.isEmpty() && ingredient.test(tool);
    }

    private static void takeOne(final SurplusSlot slot, final Plan plan)
    {
        plan.take(slot, 1);
        final ItemStack remainder = slot.stack.getCraftingRemainingItem();
        if (!remainder.isEmpty())
        {
            plan.outputs.add(remainder.copy());
        }
    }

    @Nullable
    private static SurplusSlot findSlot(final List<SurplusSlot> surplus, final Plan plan, final java.util.function.Predicate<ItemStack> matches)
    {
        for (final SurplusSlot slot : surplus)
        {
            if (slot.spare > plan.taken(slot) && matches.test(slot.stack))
            {
                return slot;
            }
        }
        return null;
    }

    /**
     * The hut inventory comes first, since that is where the builder looks for materials.
     */
    private static List<IItemHandler> insertionOrder(final IBuilding building)
    {
        final List<IItemHandler> ordered = new ArrayList<>(building.getHandlers());
        final IItemHandler hut = building.getItemHandlerCap();
        if (hut != null && ordered.remove(hut))
        {
            ordered.add(0, hut);
        }
        return ordered;
    }

    private static ItemStack insert(final List<IItemHandler> destinations, final ItemStack stack, final boolean simulate)
    {
        ItemStack remaining = stack.copy();
        for (final IItemHandler handler : destinations)
        {
            remaining = ItemHandlerHelper.insertItemStacked(handler, remaining, simulate);
            if (remaining.isEmpty())
            {
                break;
            }
        }
        return remaining;
    }

    private static List<ItemStack> splitIntoStacks(final List<ItemStack> stacks)
    {
        final List<ItemStack> split = new ArrayList<>();
        for (final ItemStack stack : stacks)
        {
            int remaining = stack.getCount();
            while (remaining > 0)
            {
                final int count = Math.min(remaining, stack.getMaxStackSize());
                split.add(stack.copyWithCount(count));
                remaining -= count;
            }
        }
        return split;
    }

    static boolean isSubstitutableMaterial(final ItemStack stack)
    {
        return FAMILIES.stream().anyMatch(stack::is);
    }

    static boolean sharesMaterialFamily(final ItemStack first, final ItemStack second)
    {
        return FAMILIES.stream().anyMatch(family -> first.is(family) && second.is(family));
    }

    /**
     * An ingredient plus the fallbacks allowed for it.
     */
    private static final class IngredientRule
    {
        private final Ingredient ingredient;
        @Nullable
        private final TagKey<Item> family;
        private final boolean    buildTool;
        private final ServerLevel level;
        @Nullable
        private List<SubCraft> subCrafts;

        private IngredientRule(final Ingredient ingredient, final Options options, final ServerLevel level)
        {
            this.ingredient = ingredient;
            this.family = options.substitution() ? RECIPE_FAMILIES.stream()
                .filter(tag -> Arrays.stream(ingredient.getItems()).anyMatch(stack -> stack.is(tag)))
                .findFirst()
                .orElse(null) : null;
            this.buildTool = isBuildTool(ingredient);
            this.level = level;
        }

        /**
         * Looked up on first use, since most ingredients are found as they are.
         */
        private List<SubCraft> subCrafts()
        {
            if (subCrafts == null)
            {
                subCrafts = findSubCrafts(ingredient, level);
            }
            return subCrafts;
        }
    }

    /**
     * A one-step craft of an ingredient: {@code inputCount} units of {@code input} make {@code result}.
     */
    private record SubCraft(Ingredient input, int inputCount, ItemStack result)
    {
    }

    /**
     * Items in one slot that the current build does not need.
     */
    private static final class SurplusSlot
    {
        private final IItemHandler handler;
        private final int          slot;
        private final ItemStack    stack;
        private final int          spare;

        private SurplusSlot(final IItemHandler handler, final int slot, final ItemStack stack, final int spare)
        {
            this.handler = handler;
            this.slot = slot;
            this.stack = stack;
            this.spare = spare;
        }
    }

    /**
     * How many of an item the current build still needs, counted down as held items are matched to it.
     */
    private static final class Reservation
    {
        private final ItemStack stack;
        private int             remaining;

        private Reservation(final ItemStack stack, final int remaining)
        {
            this.stack = stack;
            this.remaining = remaining;
        }
    }

    /**
     * What a conversion takes from which slots, and what it produces.
     */
    public static final class Plan
    {
        private final ItemStack                 target;
        private final int                       needed;
        private final Map<SurplusSlot, Integer> takes   = new IdentityHashMap<>();
        private final List<ItemStack>           outputs = new ArrayList<>();
        private final List<ItemStack>           leftovers = new ArrayList<>();

        private Plan(final ItemStack target, final int needed)
        {
            this.target = target;
            this.needed = needed;
        }

        private void take(final SurplusSlot slot, final int count)
        {
            takes.merge(slot, count, Integer::sum);
        }

        private int taken(final SurplusSlot slot)
        {
            return takes.getOrDefault(slot, 0);
        }

        /**
         * @return the requested item and amount this plan produces.
         */
        public ItemStack delivery()
        {
            return target.copyWithCount(needed);
        }

        /**
         * @return the materials this plan consumes, summed per item.
         */
        public List<ItemStack> inputs()
        {
            final List<ItemStack> totals = new ArrayList<>();
            for (final Map.Entry<SurplusSlot, Integer> entry : takes.entrySet())
            {
                addCounted(totals, entry.getKey().stack, entry.getValue());
            }
            return totals;
        }
    }
}

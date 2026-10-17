package org.coloniesplus.coloniesplus;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Settings in two files. Features ({@code coloniesplus-common.toml}) turns each feature on or off
 * for the whole game and can be changed from the Mods screen at any time. World settings
 * ({@code serverconfig/coloniesplus-server.toml}) tune the features per world and can be changed
 * from the Mods screen while playing single player.
 */
@EventBusSubscriber(modid = Coloniesplus.MODID)
public class Config {
    private static final ModConfigSpec.Builder FEATURES = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue MATERIAL_CONVERSION = FEATURES
        .comment("Whether builders convert spare materials into blocks they are stuck waiting for.")
        .define("materialConversion", true);

    private static final ModConfigSpec.BooleanValue MATERIAL_SUBSTITUTION = FEATURES
        .comment("Allow swapping one plank, log, stone, wool or carpet type for another of the same family, e.g. spruce planks",
                 "for oak planks, spruce logs for oak logs (stripped for stripped, wood for wood) or white wool for red wool. Planks, wool and carpets also swap inside recipes, e.g. spruce",
                 "planks for oak stairs or white wool for a red carpet. Builders can also turn this off in their hut.")
        .define("materialSubstitution", true);

    private static final ModConfigSpec.BooleanValue RECIPE_CONVERSION = FEATURES
        .comment("Allow crafting requested blocks from spare materials using the server's crafting recipes,",
                 "and cutting Domum Ornamentum blocks from their texture materials as the Architect's Cutter does.",
                 "Builders can also turn this off in their hut.")
        .define("recipeConversion", true);

    private static final ModConfigSpec.BooleanValue LOGS_TO_PLANKS = FEATURES
        .comment("Allow sawing spare logs into planks, of any wood when material swapping is on.")
        .define("logsToPlanks", true);

    private static final ModConfigSpec.BooleanValue REQUIRE_CRAFTER_HUTS = FEATURES
        .comment("Whether converting a kind of material needs the colony's crafter for it: a Sawmill for wood,",
                 "a Stonemason for stone, a Dyer for wool and carpets, a Blacksmith for ingots and nuggets.",
                 "Architect's Cutter blocks need a Sawmill or a Stonemason.")
        .define("requireCrafterHuts", true);

    private static final ModConfigSpec.BooleanValue ESCAPE_HAZARDS = FEATURES
        .comment("Whether citizens walk away from cactus, fire, berry bushes, magma and lava that keep hurting them,",
                 "standing up or waking up first if needed. If walking does not get them out, they are moved to safety.")
        .define("escapeHazards", true);

    private static final ModConfigSpec.BooleanValue UNSTICK_CITIZENS = FEATURES
        .comment("Whether citizens stuck inside a block (often their workstation) or unable to move toward where",
                 "they are going are moved to the nearest safe spot.")
        .define("unstickCitizens", true);

    private static final ModConfigSpec.BooleanValue RETURN_CONTAINERS = FEATURES
        .comment("Whether empty bowls, glass bottles and buckets are taken from citizens and huts that do not use them,",
                 "and moved to restaurants and kitchens (bowls), the cowhand (buckets) or the warehouse. They move instantly, not by courier.")
        .define("returnContainers", true);

    private static final ModConfigSpec.BooleanValue KEEP_RESTAURANT_FOOD = FEATURES
        .comment("Whether restaurants keep food that is not on their menu, and extra menu food, instead of couriers",
                 "hauling it to the warehouse while citizens starve. Up to a few stacks per restaurant level are kept.")
        .define("keepRestaurantFood", true);

    private static final ModConfigSpec.BooleanValue HUNGRY_WORKERS_EAT = FEATURES
        .comment("Whether starving citizens eat the food they carry, and waiters and cooks eat from their restaurant,",
                 "so a busy waiter does not starve and stop the restaurant serving.")
        .define("hungryWorkersEat", true);

    private static final ModConfigSpec.BooleanValue COURIER_HORSES = FEATURES
        .comment("Whether couriers borrow the colony's cavalry horses (from the Stablemaster) and ride them on long trips.",
                 "Cavalry guards come first: a horse is only lent while there are more free horses than guards without one.")
        .define("courierHorses", true);

    static final ModConfigSpec FEATURES_SPEC = FEATURES.build();

    private static final ModConfigSpec.Builder WORLD = new ModConfigSpec.Builder();

    static {
        WORLD.comment("Builder material conversion.").push("conversion");
    }

    private static final ModConfigSpec.IntValue INTERVAL_TICKS = WORLD
        .comment("How often, in ticks, builder requests are checked.")
        .defineInRange("intervalTicks", 20, 1, 1200);

    private static final ModConfigSpec.IntValue CONVERSION_DELAY_TICKS = WORLD
        .comment("How long, in ticks, an accepted request shows as being converted before the materials are swapped.")
        .defineInRange("conversionDelayTicks", 60, 0, 6000);

    private static final ModConfigSpec.ConfigValue<List<? extends Integer>> WASTE_PERCENT_BY_LEVEL = WORLD
        .comment("Percent of a material lost when converting it, by the level of its crafter hut: the first entry is",
                 "for level 1, the next for level 2, and so on; the last entry also covers higher levels.",
                 "Rounded down, so small conversions may lose nothing. Set all to 0 to turn waste off.")
        .defineListAllowEmpty("wastePercentByLevel", List.of(40, 30, 20, 10, 0), () -> 0,
            value -> value instanceof Number number && number.intValue() >= 0 && number.intValue() <= 100);

    static {
        WORLD.pop().comment("Hazard escape and stuck citizens.").push("citizens");
    }

    private static final ModConfigSpec.IntValue HAZARD_ESCAPE_TICKS = WORLD
        .comment("How long, in ticks, a citizen keeps getting hurt by a hazard before it moves away. Hazards hurt every 10 ticks.")
        .defineInRange("hazardEscapeTicks", 20, 0, 1200);

    private static final ModConfigSpec.IntValue STUCK_TICKS = WORLD
        .comment("How long, in ticks, a citizen trying to walk somewhere can stay in place before it counts as stuck.",
                 "Kept long so citizens waiting at a door or behind another citizen are not moved.")
        .defineInRange("stuckTicks", 600, 100, 12000);

    private static final ModConfigSpec.IntValue EAT_BELOW_SATURATION = WORLD
        .comment("A citizen eats the food it carries once its saturation (0 to 20) is at or below this.",
                 "Kept well below the point (6) where citizens normally go to eat, so restaurant trips stay the usual way.")
        .defineInRange("eatBelowSaturation", 2, 0, 10);

    static {
        WORLD.pop().comment("Returning bowls and bottles.").push("containers");
    }

    private static final ModConfigSpec.IntValue CONTAINER_RETURN_INTERVAL_TICKS = WORLD
        .comment("How often, in ticks, bowls and bottles are returned.")
        .defineInRange("returnIntervalTicks", 200, 20, 24000);

    static {
        WORLD.pop().comment("Restaurants keeping food.").push("restaurant");
    }

    private static final ModConfigSpec.IntValue RESTAURANT_FOOD_STACKS_PER_LEVEL = WORLD
        .comment("How many stacks of food in total a restaurant keeps per level, on top of its menu.")
        .defineInRange("foodStacksPerLevel", 4, 1, 27);

    static {
        WORLD.pop().comment("Couriers riding horses.").push("couriers");
    }

    private static final ModConfigSpec.IntValue COURIER_MOUNT_DISTANCE = WORLD
        .comment("The shortest trip, in blocks, a courier rides its horse for. Shorter trips are walked.")
        .defineInRange("mountDistance", 24, 8, 256);

    private static final ModConfigSpec.DoubleValue COURIER_HORSE_SPEED = WORLD
        .comment("How fast a courier rides, as a multiple of its walking speed.")
        .defineInRange("horseSpeed", 1.75, 1.0, 3.0);

    static {
        WORLD.pop();
    }

    static final ModConfigSpec WORLD_SPEC = WORLD.build();

    public static boolean enabled = true;
    public static boolean allowMaterialSubstitution = true;
    public static boolean allowRecipeConversion = true;
    public static boolean logsToPlanks = true;
    public static boolean requireCrafterHuts = true;
    public static boolean escapeHazards = true;
    public static boolean unstickCitizens = true;
    public static boolean returnContainers = true;
    public static boolean courierHorses = true;
    public static boolean keepRestaurantFood = true;
    public static boolean hungryWorkersEat = true;

    public static int intervalTicks = 20;
    public static int conversionDelayTicks = 60;
    public static List<Integer> wastePercentByLevel = List.of(40, 30, 20, 10, 0);
    public static int hazardEscapeTicks = 20;
    public static int stuckTicks = 600;
    public static int eatBelowSaturation = 2;
    public static int restaurantFoodStacksPerLevel = 4;
    public static int containerReturnIntervalTicks = 200;
    public static int courierMountDistance = 24;
    public static double courierHorseSpeed = 1.75;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
        refresh(event.getConfig());
    }

    @SubscribeEvent
    static void onReload(final ModConfigEvent.Reloading event) {
        refresh(event.getConfig());
    }

    private static void refresh(final ModConfig config) {
        if (config.getSpec() == FEATURES_SPEC) {
            enabled = MATERIAL_CONVERSION.get();
            allowMaterialSubstitution = MATERIAL_SUBSTITUTION.get();
            allowRecipeConversion = RECIPE_CONVERSION.get();
            logsToPlanks = LOGS_TO_PLANKS.get();
            requireCrafterHuts = REQUIRE_CRAFTER_HUTS.get();
            escapeHazards = ESCAPE_HAZARDS.get();
            unstickCitizens = UNSTICK_CITIZENS.get();
            returnContainers = RETURN_CONTAINERS.get();
            courierHorses = COURIER_HORSES.get();
            keepRestaurantFood = KEEP_RESTAURANT_FOOD.get();
            hungryWorkersEat = HUNGRY_WORKERS_EAT.get();
        } else if (config.getSpec() == WORLD_SPEC) {
            intervalTicks = INTERVAL_TICKS.get();
            conversionDelayTicks = CONVERSION_DELAY_TICKS.get();
            wastePercentByLevel = WASTE_PERCENT_BY_LEVEL.get().stream().map(Number::intValue).toList();
            hazardEscapeTicks = HAZARD_ESCAPE_TICKS.get();
            stuckTicks = STUCK_TICKS.get();
            eatBelowSaturation = EAT_BELOW_SATURATION.get();
            restaurantFoodStacksPerLevel = RESTAURANT_FOOD_STACKS_PER_LEVEL.get();
            containerReturnIntervalTicks = CONTAINER_RETURN_INTERVAL_TICKS.get();
            courierMountDistance = COURIER_MOUNT_DISTANCE.get();
            courierHorseSpeed = COURIER_HORSE_SPEED.get();
        }
    }
}

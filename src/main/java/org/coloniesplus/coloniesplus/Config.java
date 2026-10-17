package org.coloniesplus.coloniesplus;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Server-side settings for builder material conversion and citizens escaping hazards.
 */
@EventBusSubscriber(modid = Coloniesplus.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Whether builders convert surplus materials into blocks they are stuck waiting for.")
        .define("enabled", true);

    private static final ModConfigSpec.IntValue INTERVAL_TICKS = BUILDER
        .comment("How often, in ticks, builder requests are checked.")
        .defineInRange("intervalTicks", 20, 1, 1200);

    private static final ModConfigSpec.IntValue CONVERSION_DELAY_TICKS = BUILDER
        .comment("How long, in ticks, an accepted request shows as being converted before the materials are swapped.")
        .defineInRange("conversionDelayTicks", 60, 0, 6000);

    private static final ModConfigSpec.BooleanValue ALLOW_MATERIAL_SUBSTITUTION = BUILDER
        .comment("Allow swapping one plank, stone, wool or carpet type for another of the same family, e.g. spruce planks",
                 "for oak planks or white wool for red wool. Planks, wool and carpets also swap inside recipes, e.g. spruce",
                 "planks for oak stairs or white wool for a red carpet. Builders can also turn this off in their hut.")
        .define("allowMaterialSubstitution", true);

    private static final ModConfigSpec.BooleanValue ALLOW_RECIPE_CONVERSION = BUILDER
        .comment("Allow crafting requested blocks from surplus materials using the server's crafting recipes,",
                 "and cutting Domum Ornamentum blocks from their texture materials as the Architect's Cutter does.",
                 "Builders can also turn this off in their hut.")
        .define("allowRecipeConversion", true);

    private static final ModConfigSpec.BooleanValue REQUIRE_CRAFTER_HUTS = BUILDER
        .comment("Whether converting a kind of material needs the colony's crafter for it: a Sawmill for wood,",
                 "a Stonemason for stone, a Dyer for wool and carpets, a Blacksmith for ingots and nuggets.",
                 "Architect's Cutter blocks need a Sawmill or a Stonemason.")
        .define("requireCrafterHuts", true);

    private static final ModConfigSpec.ConfigValue<List<? extends Integer>> WASTE_PERCENT_BY_LEVEL = BUILDER
        .comment("Percent of a material lost when converting it, by the level of its crafter hut: the first entry is",
                 "for level 1, the next for level 2, and so on; the last entry also covers higher levels.",
                 "Rounded down, so small conversions may lose nothing. Set all to 0 to turn waste off.")
        .defineListAllowEmpty("wastePercentByLevel", List.of(40, 30, 20, 10, 0), () -> 0,
            value -> value instanceof Number number && number.intValue() >= 0 && number.intValue() <= 100);

    private static final ModConfigSpec.BooleanValue ESCAPE_HAZARDS = BUILDER
        .comment("Whether citizens walk away from cactus, fire, berry bushes, magma and lava that keep hurting them,",
                 "standing up or waking up first if needed. If walking does not get them out, they are moved to safety.")
        .define("escapeHazards", true);

    private static final ModConfigSpec.IntValue HAZARD_ESCAPE_TICKS = BUILDER
        .comment("How long, in ticks, a citizen keeps getting hurt by a hazard before it moves away. Hazards hurt every 10 ticks.")
        .defineInRange("hazardEscapeTicks", 20, 0, 1200);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enabled = true;
    public static int intervalTicks = 20;
    public static int conversionDelayTicks = 60;
    public static boolean allowMaterialSubstitution = true;
    public static boolean allowRecipeConversion = true;
    public static boolean requireCrafterHuts = true;
    public static List<Integer> wastePercentByLevel = List.of(40, 30, 20, 10, 0);
    public static boolean escapeHazards = true;
    public static int hazardEscapeTicks = 20;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
        refresh(event.getConfig());
    }

    @SubscribeEvent
    static void onReload(final ModConfigEvent.Reloading event) {
        refresh(event.getConfig());
    }

    private static void refresh(final ModConfig config) {
        if (config.getSpec() != SPEC) {
            return;
        }
        enabled = ENABLED.get();
        intervalTicks = INTERVAL_TICKS.get();
        conversionDelayTicks = CONVERSION_DELAY_TICKS.get();
        allowMaterialSubstitution = ALLOW_MATERIAL_SUBSTITUTION.get();
        allowRecipeConversion = ALLOW_RECIPE_CONVERSION.get();
        requireCrafterHuts = REQUIRE_CRAFTER_HUTS.get();
        wastePercentByLevel = WASTE_PERCENT_BY_LEVEL.get().stream().map(Number::intValue).toList();
        escapeHazards = ESCAPE_HAZARDS.get();
        hazardEscapeTicks = HAZARD_ESCAPE_TICKS.get();
    }
}

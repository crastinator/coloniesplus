package org.coloniesplus.coloniesplus.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * NeoForge's config page with on/off switches shown as a green "ON" or a red "OFF", so it is
 * clear at a glance which features are running.
 */
public class ColoredConfigScreen extends ConfigurationScreen.ConfigurationSectionScreen
{
    public ColoredConfigScreen(final Screen parent, final ModConfig.Type type, final ModConfig modConfig, final Component title)
    {
        super(parent, type, modConfig, title);
    }

    @Override
    protected Element createBooleanValue(final String key, final ModConfigSpec.ValueSpec spec, final Supplier<Boolean> source, final Consumer<Boolean> target)
    {
        return new Element(getTranslationComponent(key), getTooltipComponent(key, null),
            new OptionInstance<>(getTranslationKey(key), getTooltip(key, null),
                (caption, value) -> value
                    ? CommonComponents.OPTION_ON.copy().withStyle(ChatFormatting.GREEN)
                    : CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.RED),
                Custom.BOOLEAN_VALUES_NO_PREFIX, source.get(), newValue -> {
                    // Same undo handling as NeoForge's own switches.
                    undoManager.add(v -> {
                        target.accept(v);
                        onChanged(key);
                    }, newValue, v -> {
                        target.accept(v);
                        onChanged(key);
                    }, source.get());
                }));
    }
}

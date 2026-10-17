package org.coloniesplus.coloniesplus.client;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.PaneBuilders;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.ItemIcon;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.coloniesplus.coloniesplus.Coloniesplus;
import org.coloniesplus.coloniesplus.conversion.ConversionModule;
import org.coloniesplus.coloniesplus.conversion.CrafterProgress;
import org.coloniesplus.coloniesplus.conversion.ConversionResolver;
import org.coloniesplus.coloniesplus.network.SetConversionTogglePayload;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The "Material Conversion" tab in a builder's hut window: per-builder switches, the raw
 * materials the build would need if what it is missing were converted, the materials the
 * builder holds that its build does not need, and its recent conversions.
 */
public class ConversionModuleWindow extends AbstractModuleWindow<ConversionModuleView>
{
    private static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(Coloniesplus.MODID, "gui/layouthuts/layoutconversion.xml");

    private static final ResourceLocation CHECK_ON           = ResourceLocation.parse("minecolonies:textures/gui/builderhut/builder_button_mini_check.png");
    private static final ResourceLocation CHECK_ON_DISABLED  = ResourceLocation.parse("minecolonies:textures/gui/builderhut/builder_button_mini_disabled_check.png");
    private static final ResourceLocation CHECK_OFF          = ResourceLocation.parse("minecolonies:textures/gui/builderhut/builder_button_mini.png");
    private static final ResourceLocation CHECK_OFF_DISABLED = ResourceLocation.parse("minecolonies:textures/gui/builderhut/builder_button_mini_disabled.png");

    private static final String BUTTON_SUBSTITUTION = "substitution";
    private static final String BUTTON_CRAFTING     = "crafting";
    private static final String STATUS              = "status";
    private static final String LIST_SPARE          = "spare";
    private static final String LIST_NEEDED         = "needed";
    private static final String LABEL_SUBSTITUTION  = "substitutionlabel";
    private static final String LABEL_CRAFTING      = "craftinglabel";
    private static final String BUTTON_SHOW_NEEDED  = "showneeded";
    private static final String BUTTON_SHOW_SPARE   = "showspare";

    private static final ResourceLocation TAB_SHOWN  = ResourceLocation.parse("minecolonies:textures/gui/builderhut/builder_button_small.png");
    private static final ResourceLocation TAB_HIDDEN = ResourceLocation.parse("minecolonies:textures/gui/builderhut/builder_button_small_disabled.png");

    private static final String ELLIPSIS = "…";

    private static final int COLOR_TAB_SHOWN  = 0x000000;
    private static final int COLOR_TAB_HIDDEN = 0xFFFFFF;
    private static final int COLOR_ENOUGH     = 0x000000;
    private static final int COLOR_SHORT      = 0xAA0000;
    private static final String LIST_HISTORY        = "history";

    private int shownVersion = -1;

    public ConversionModuleWindow(final ConversionModuleView moduleView)
    {
        super(moduleView, LAYOUT);
        registerButton(BUTTON_SUBSTITUTION, button -> toggle(ConversionModule.TOGGLE_SUBSTITUTION));
        registerButton(BUTTON_CRAFTING, button -> toggle(ConversionModule.TOGGLE_CRAFTING));
        registerButton(BUTTON_SHOW_NEEDED, button -> show(true));
        registerButton(BUTTON_SHOW_SPARE, button -> show(false));
        PaneBuilders.tooltipBuilder()
            .hoverPane(findPaneByID(BUTTON_SHOW_NEEDED))
            .build()
            .setText(Component.translatable("coloniesplus.gui.conversion.needed.tooltip"));
    }

    @Override
    public void onOpened()
    {
        super.onOpened();
        refresh();
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();
        if (shownVersion != moduleView.getVersion())
        {
            refresh();
        }
    }

    private void refresh()
    {
        shownVersion = moduleView.getVersion();
        updateToggle(BUTTON_SUBSTITUTION, ConversionModule.TOGGLE_SUBSTITUTION);
        updateToggle(BUTTON_CRAFTING, ConversionModule.TOGGLE_CRAFTING);
        addCrafterTooltip(LABEL_SUBSTITUTION);
        addCrafterTooltip(LABEL_CRAFTING);
        findPaneOfTypeByID(STATUS, Text.class).setText(status());
        updateListSwitch();
        fillNeeded();
        fillSpare();
        fillHistory();
    }

    /**
     * The status line only fits two short lines, so one missing crafter is named with its
     * material, and several are counted, pointing to the switches' tooltip for which.
     *
     * @return the message, or null if every kind of material can be converted.
     */
    @Nullable
    private MutableComponent missingCrafters()
    {
        final List<ConversionModule.CrafterStatus> missing = moduleView.getCrafters().stream()
            .filter(crafter -> !crafter.allowed())
            .toList();
        if (missing.isEmpty())
        {
            return null;
        }
        if (missing.size() == 1)
        {
            final CrafterProgress.Trade trade = missing.get(0).trade();
            return Component.translatable("coloniesplus.gui.conversion.crafters.missing.one",
                trade.hutName(),
                Component.translatable("coloniesplus.gui.conversion.trade." + trade.key() + ".lower"));
        }
        return Component.translatable("coloniesplus.gui.conversion.crafters.missing.many", missing.size());
    }

    /**
     * Hovering a switch lists which crafter huts the colony has and how much each wastes.
     */
    private void addCrafterTooltip(final String labelId)
    {
        final var tooltip = PaneBuilders.tooltipBuilder()
            .hoverPane(findPaneByID(labelId))
            .append(Component.translatable("coloniesplus.gui.conversion.crafters").withStyle(ChatFormatting.BOLD));
        for (final ConversionModule.CrafterStatus crafter : moduleView.getCrafters())
        {
            final Component material = Component.translatable("coloniesplus.gui.conversion.trade." + crafter.trade().key());
            final MutableComponent line;
            if (!crafter.allowed())
            {
                line = Component.translatable("coloniesplus.gui.conversion.crafters.locked", material, crafter.trade().hutName())
                    .withStyle(ChatFormatting.DARK_RED);
            }
            else if (crafter.level() == 0)
            {
                line = Component.translatable("coloniesplus.gui.conversion.crafters.none", material, crafter.trade().hutName(), crafter.wastePercent());
            }
            else
            {
                line = Component.translatable("coloniesplus.gui.conversion.crafters.level", material, crafter.trade().hutName(), crafter.level(), crafter.wastePercent());
            }
            tooltip.newLine().append(line);
        }
        tooltip.build();
    }

    private void show(final boolean needed)
    {
        moduleView.setShowingNeeded(needed);
        updateListSwitch();
    }

    /**
     * The needed and spare lists share one spot, switched with two tabs: the shown one is a
     * light button with black text, the other a dark one with white text.
     */
    private void updateListSwitch()
    {
        final boolean needed = moduleView.isShowingNeeded();
        styleTab(BUTTON_SHOW_NEEDED, needed);
        styleTab(BUTTON_SHOW_SPARE, !needed);
        findPaneByID(LIST_NEEDED).setVisible(needed);
        findPaneByID(LIST_SPARE).setVisible(!needed);
    }

    private void styleTab(final String id, final boolean shown)
    {
        final ButtonImage tab = findPaneOfTypeByID(id, ButtonImage.class);
        tab.setImage(shown ? TAB_SHOWN : TAB_HIDDEN);
        final int color = shown ? COLOR_TAB_SHOWN : COLOR_TAB_HIDDEN;
        tab.setColors(color, color, color);
    }

    private void fillNeeded()
    {
        final List<ConversionModule.NeededMaterial> needed = moduleView.getNeeded();
        findPaneOfTypeByID(LIST_NEEDED, ScrollingList.class).setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                return needed.size();
            }

            @Override
            public void updateElement(final int index, @NotNull final Pane rowPane)
            {
                final ConversionModule.NeededMaterial material = needed.get(index);
                final int amount = material.material().getCount();
                final Text countLabel = rowPane.findPaneOfTypeByID("count", Text.class);
                Component count = Component.translatable("coloniesplus.gui.conversion.needed.count", compact(material.spare()), compact(amount));
                if (Minecraft.getInstance().font.width(count) > countLabel.getWidth())
                {
                    // Very large numbers: just what is needed, with the full counts on hover.
                    count = Component.literal(compact(amount));
                }
                fillRow(rowPane, material.material(), count);
                countLabel.setColors(material.spare() < amount ? COLOR_SHORT : COLOR_ENOUGH);
                PaneBuilders.tooltipBuilder().hoverPane(countLabel).build().setText(Component.translatable(
                    "coloniesplus.gui.conversion.needed.count.tooltip", String.format("%,d", material.spare()), String.format("%,d", amount)));
            }
        });
    }

    private void toggle(final int toggle)
    {
        if (!moduleView.isToggleAllowedByServer(toggle))
        {
            return;
        }

        final boolean value = !moduleView.getToggle(toggle);
        moduleView.setToggleLocally(toggle, value);
        PacketDistributor.sendToServer(new SetConversionTogglePayload(
            buildingView.getColony().getDimension(),
            buildingView.getColony().getID(),
            buildingView.getID(),
            toggle,
            value));
        refresh();
    }

    private void updateToggle(final String id, final int toggle)
    {
        final ButtonImage button = findPaneOfTypeByID(id, ButtonImage.class);
        final boolean on = moduleView.getToggle(toggle);
        button.setImage(on ? CHECK_ON : CHECK_OFF);
        button.setImageDisabled(on ? CHECK_ON_DISABLED : CHECK_OFF_DISABLED);
        button.setEnabled(moduleView.isToggleAllowedByServer(toggle));
        if (!moduleView.isToggleAllowedByServer(toggle))
        {
            PaneBuilders.tooltipBuilder().hoverPane(button).build().setText(Component.translatable("coloniesplus.gui.conversion.server_disabled"));
        }
    }

    private MutableComponent status()
    {
        if (!moduleView.isServerEnabled())
        {
            return Component.translatable("coloniesplus.gui.conversion.server_disabled");
        }

        final List<ConversionResolver.Conversion> pending = moduleView.getPending();
        if (pending.isEmpty())
        {
            final MutableComponent missing = missingCrafters();
            return missing == null ? Component.translatable("coloniesplus.gui.conversion.idle") : missing;
        }

        final ConversionResolver.Conversion next = pending.get(0);
        return Component.translatable("coloniesplus.gui.conversion.converting",
            ConversionResolver.Conversion.describe(next.inputs()),
            ConversionResolver.Conversion.describe(List.of(next.output())));
    }

    private void fillSpare()
    {
        final List<ItemStack> spare = moduleView.getSpare();
        findPaneOfTypeByID(LIST_SPARE, ScrollingList.class).setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                return spare.size();
            }

            @Override
            public void updateElement(final int index, @NotNull final Pane rowPane)
            {
                final ItemStack stack = spare.get(index);
                fillRow(rowPane, stack, Component.literal(compact(stack.getCount())));
            }
        });
    }

    /**
     * Item name on the left, a number on the right. A name too long for its space is shortened
     * with "…" so it never runs into the number; the full name shows on hover.
     */
    private static void fillRow(final Pane rowPane, final ItemStack stack, final Component count)
    {
        rowPane.findPaneOfTypeByID("icon", ItemIcon.class).setItem(stack.copyWithCount(1));
        final Text label = rowPane.findPaneOfTypeByID("label", Text.class);
        label.setText(fitWidth(stack.getHoverName(), label.getWidth()));
        PaneBuilders.tooltipBuilder().hoverPane(label).build().setText(stack.getHoverName());
        rowPane.findPaneOfTypeByID("count", Text.class).setText(count);
    }

    /**
     * Shortens a name to fit, keeping its colours and styles.
     */
    private static Component fitWidth(final Component text, final int width)
    {
        final Font font = Minecraft.getInstance().font;
        if (font.width(text) <= width)
        {
            return text;
        }
        final MutableComponent shortened = Component.empty();
        font.substrByWidth(text, width - font.width(ELLIPSIS)).visit((style, part) -> {
            shortened.append(Component.literal(part).withStyle(style));
            return Optional.empty();
        }, text.getStyle());
        return shortened.append(Component.literal(ELLIPSIS).withStyle(text.getStyle()));
    }

    /**
     * Keeps counts short enough for their column: 950, 1.9k, 15k, 1.5M. Always rounded down, so
     * a smaller count never shows as more than a bigger one (1960 of 2000 reads "1.9k/2k").
     */
    private static String compact(final int count)
    {
        if (count < 1_000)
        {
            return String.valueOf(count);
        }
        if (count < 10_000)
        {
            return tenths(count / 100) + "k";
        }
        if (count < 1_000_000)
        {
            return count / 1_000 + "k";
        }
        return tenths(count / 100_000) + "M";
    }

    /**
     * @return e.g. 19 as "1.9" and 20 as "2".
     */
    private static String tenths(final int tenths)
    {
        return tenths % 10 == 0 ? String.valueOf(tenths / 10) : tenths / 10 + "." + tenths % 10;
    }

    private void fillHistory()
    {
        final List<ConversionResolver.Conversion> history = moduleView.getHistory();
        findPaneOfTypeByID(LIST_HISTORY, ScrollingList.class).setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                return history.size();
            }

            @Override
            public void updateElement(final int index, @NotNull final Pane rowPane)
            {
                final ConversionResolver.Conversion conversion = history.get(index);
                rowPane.findPaneOfTypeByID("icon", ItemIcon.class).setItem(conversion.output().copyWithCount(1));
                final Text label = rowPane.findPaneOfTypeByID("label", Text.class);
                label.setText(Component.translatable("coloniesplus.gui.conversion.entry",
                    ConversionResolver.Conversion.describe(conversion.inputs()),
                    ConversionResolver.Conversion.describe(List.of(conversion.output()))));
                PaneBuilders.tooltipBuilder().hoverPane(label).build().setText(label.getText());
            }
        });
    }
}

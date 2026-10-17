package org.coloniesplus.coloniesplus.client;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.coloniesplus.coloniesplus.conversion.ConversionModule;
import org.coloniesplus.coloniesplus.conversion.ConversionResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Client copy of a builder's {@link ConversionModule}, shown as the "Material Conversion" tab.
 */
public class ConversionModuleView extends AbstractBuildingModuleView
{
    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/crafting.png");

    private boolean serverEnabled;
    private boolean serverSubstitution;
    private boolean serverCrafting;
    private boolean allowSubstitution;
    private boolean allowCrafting;

    private List<ItemStack>                     spare   = List.of();
    private List<ConversionResolver.Conversion> pending = List.of();
    private List<ConversionResolver.Conversion> history = List.of();
    private List<ConversionModule.NeededMaterial> needed = List.of();
    private List<ConversionModule.CrafterStatus>  crafters = List.of();

    /**
     * Whether the tab lists the raw materials needed if converted, rather than the spare ones.
     * Kept here so it survives the window being reopened.
     */
    private boolean showingNeeded = true;

    /**
     * Bumped on every sync so an open window knows to redraw.
     */
    private int version;

    @Override
    public void deserialize(@NotNull final RegistryFriendlyByteBuf buf)
    {
        serverEnabled = buf.readBoolean();
        serverSubstitution = buf.readBoolean();
        serverCrafting = buf.readBoolean();
        allowSubstitution = buf.readBoolean();
        allowCrafting = buf.readBoolean();
        spare = ConversionModule.readStacks(buf);
        pending = ConversionModule.readConversions(buf);
        history = ConversionModule.readConversions(buf);
        needed = ConversionModule.readNeeded(buf);
        crafters = ConversionModule.readCrafters(buf);
        version++;
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public BOWindow getWindow()
    {
        return new ConversionModuleWindow(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation()
    {
        return ICON;
    }

    @Override
    public Component getDesc()
    {
        return Component.translatable("coloniesplus.gui.conversion");
    }

    public boolean isServerEnabled()
    {
        return serverEnabled;
    }

    public boolean isToggleAllowedByServer(final int toggle)
    {
        return serverEnabled && (toggle == ConversionModule.TOGGLE_SUBSTITUTION ? serverSubstitution : serverCrafting);
    }

    public boolean getToggle(final int toggle)
    {
        return toggle == ConversionModule.TOGGLE_SUBSTITUTION ? allowSubstitution : allowCrafting;
    }

    /**
     * Applied right away so the button flips without waiting for the server's resync.
     */
    void setToggleLocally(final int toggle, final boolean value)
    {
        if (toggle == ConversionModule.TOGGLE_SUBSTITUTION)
        {
            allowSubstitution = value;
        }
        else
        {
            allowCrafting = value;
        }
    }

    public List<ItemStack> getSpare()
    {
        return spare;
    }

    public List<ConversionResolver.Conversion> getPending()
    {
        return pending;
    }

    public List<ConversionResolver.Conversion> getHistory()
    {
        return history;
    }

    public List<ConversionModule.NeededMaterial> getNeeded()
    {
        return needed;
    }

    public List<ConversionModule.CrafterStatus> getCrafters()
    {
        return crafters;
    }

    boolean isShowingNeeded()
    {
        return showingNeeded;
    }

    void setShowingNeeded(final boolean showingNeeded)
    {
        this.showingNeeded = showingNeeded;
    }

    int getVersion()
    {
        return version;
    }
}

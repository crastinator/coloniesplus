package org.coloniesplus.coloniesplus.network;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.coloniesplus.coloniesplus.Coloniesplus;
import org.coloniesplus.coloniesplus.conversion.ConversionModule;

/**
 * Sent when a player flips one of the switches in a builder hut's "Material Conversion" tab.
 */
public record SetConversionTogglePayload(ResourceKey<Level> dimension, int colonyId, BlockPos building, int toggle, boolean value)
    implements CustomPacketPayload
{
    public static final Type<SetConversionTogglePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Coloniesplus.MODID, "set_conversion_toggle"));

    public static final StreamCodec<ByteBuf, SetConversionTogglePayload> STREAM_CODEC = StreamCodec.composite(
        ResourceKey.streamCodec(Registries.DIMENSION), SetConversionTogglePayload::dimension,
        ByteBufCodecs.VAR_INT, SetConversionTogglePayload::colonyId,
        BlockPos.STREAM_CODEC, SetConversionTogglePayload::building,
        ByteBufCodecs.VAR_INT, SetConversionTogglePayload::toggle,
        ByteBufCodecs.BOOL, SetConversionTogglePayload::value,
        SetConversionTogglePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public static void handle(final SetConversionTogglePayload payload, final IPayloadContext context)
    {
        if (!(context.player() instanceof ServerPlayer player))
        {
            return;
        }

        final IColony colony = IMinecoloniesAPI.getInstance().getColonyManager().getColonyByDimension(payload.colonyId(), payload.dimension());
        if (colony == null || !colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS))
        {
            return;
        }

        final IBuilding building = colony.getServerBuildingManager().getBuilding(payload.building());
        if (building != null && building.hasModule(ConversionModule.producer()))
        {
            building.getModule(ConversionModule.producer()).setToggle(payload.toggle(), payload.value());
        }
    }
}

package org.coloniesplus.coloniesplus;

import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.coloniesplus.coloniesplus.conversion.ConversionModule;
import org.coloniesplus.coloniesplus.conversion.ConversionResolverFactory;
import org.coloniesplus.coloniesplus.conversion.ConversionTicker;
import org.coloniesplus.coloniesplus.hazard.HazardEscape;
import org.coloniesplus.coloniesplus.network.SetConversionTogglePayload;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Coloniesplus.MODID)
public class Coloniesplus {
    public static final String MODID = "coloniesplus";

    public Coloniesplus(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.register(new ConversionTicker());
        NeoForge.EVENT_BUS.register(new HazardEscape());
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Queued so it runs on the main thread in mod order, giving the module the same id on client and server.
        event.enqueueWork(() -> {
            StandardFactoryController.getInstance().registerNewFactory(new ConversionResolverFactory());
            ConversionModule.register();
        });
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(SetConversionTogglePayload.TYPE, SetConversionTogglePayload.STREAM_CODEC, SetConversionTogglePayload::handle);
    }
}

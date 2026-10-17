package org.coloniesplus.coloniesplus.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.coloniesplus.coloniesplus.Coloniesplus;

/**
 * Client-only setup: a Config button for the mod in NeoForge's Mods screen, which mod list
 * mods such as Catalogue open too.
 */
@Mod(value = Coloniesplus.MODID, dist = Dist.CLIENT)
public class ColoniesplusClient {
    public ColoniesplusClient(final ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
            (container, parent) -> new ConfigurationScreen(container, parent, ColoredConfigScreen::new));
    }
}

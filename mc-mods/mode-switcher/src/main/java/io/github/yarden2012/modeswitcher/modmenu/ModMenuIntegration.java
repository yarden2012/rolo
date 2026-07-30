package io.github.yarden2012.modeswitcher.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import io.github.yarden2012.modeswitcher.gui.ModeSwitcherConfigScreen;

public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ModeSwitcherConfigScreen::new;
	}
}

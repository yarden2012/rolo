package io.github.yarden2012.fightrecap;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

/** A one-line live score right of the hotbar while a fight is running: hits landed - hits taken, current combo. */
public class FightRecapHud implements HudElement {
	private final FightRecapConfig config;
	private final FightTracker tracker;

	public FightRecapHud(FightRecapConfig config, FightTracker tracker) {
		this.config = config;
		this.tracker = tracker;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		Fight fight = tracker.current();

		if (!config.liveHud || fight == null || client.options.hideGui || fight.exchanges() == 0) {
			return;
		}

		int color = fight.combo >= 3 ? 0xFF55FF55 : fight.theirCombo >= 3 ? 0xFFFF5555 : 0xDDFFFFFF;
		Component line = Component.translatable("fightrecap.live", fight.hitsLanded, fight.hitsTaken, fight.combo);
		// The hotbar is 182 pixels wide and 22 tall, centered at the bottom of the screen.
		graphics.text(client.font, line, graphics.guiWidth() / 2 + 91 + 8, graphics.guiHeight() - 15, color);
	}
}

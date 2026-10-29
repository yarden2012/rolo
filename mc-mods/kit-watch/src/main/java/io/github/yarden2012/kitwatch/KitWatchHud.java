package io.github.yarden2012.kitwatch;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

/** The supply/armor/effect column on the screen edge, plus the center-screen alert banners. */
public class KitWatchHud implements HudElement {
	private static final int ROW_HEIGHT = 18;
	private static final int COLUMN_WIDTH = 44;

	private final KitWatchConfig config;
	private final KitMonitor monitor;

	public KitWatchHud(KitWatchConfig config, KitMonitor monitor) {
		this.config = config;
		this.monitor = monitor;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null || client.options.hideGui) {
			return;
		}

		if (config.panel) {
			drawColumn(graphics, client.font);
		}

		drawBanners(graphics, client.font);
	}

	private void drawColumn(GuiGraphicsExtractor graphics, Font font) {
		int rows = 0;

		for (Supply supply : Supply.values()) {
			if (config.showEmpty || monitor.count(supply) > 0) {
				rows++;
			}
		}

		rows += monitor.armor().size();
		int effectLines = monitor.expiringEffects().size();
		int height = rows * ROW_HEIGHT + effectLines * (font.lineHeight + 2);

		if (height == 0) {
			return;
		}

		int x = config.onRight() ? graphics.guiWidth() - COLUMN_WIDTH - 2 : 4;
		int y = (graphics.guiHeight() - height) / 2;
		long now = System.currentTimeMillis();

		for (Supply supply : Supply.values()) {
			int count = monitor.count(supply);

			if (!config.showEmpty && count == 0) {
				continue;
			}

			if (monitor.isFlashing(supply) && (now / 250) % 2 == 0) {
				graphics.fill(x - 2, y - 1, x + COLUMN_WIDTH, y + ROW_HEIGHT - 1, 0x80FF2020);
			}

			int color = count == 0 ? 0xFFFF5555 : count <= config.lowStock(supply) ? 0xFFFFAA00 : 0xFFFFFFFF;
			graphics.item(monitor.icon(supply), x, y);
			graphics.text(font, String.valueOf(count), x + 19, y + 4, color);
			y += ROW_HEIGHT;
		}

		int warn = config.armorWarnPercent;

		for (KitMonitor.ArmorPiece piece : monitor.armor()) {
			int color = piece.percent() <= Math.max(1, warn / 4) ? 0xFFFF5555
					: piece.percent() <= warn ? 0xFFFFAA00
					: piece.percent() <= 50 ? 0xFFFFFF55
					: 0xFF55FF55;
			graphics.item(piece.stack(), x, y);
			graphics.text(font, piece.percent() + "%", x + 19, y + 4, color);
			y += ROW_HEIGHT;
		}

		for (KitMonitor.EffectTimer timer : monitor.expiringEffects()) {
			int seconds = (timer.ticksLeft() + 19) / 20;
			int color = seconds <= 3 && (now / 250) % 2 == 0 ? 0xFFFF5555 : 0xFFFFAA00;
			graphics.text(font, Component.empty().append(timer.name()).append(" " + seconds + "s"), x, y + 1, color);
			y += font.lineHeight + 2;
		}
	}

	private void drawBanners(GuiGraphicsExtractor graphics, Font font) {
		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;

		if (monitor.retotemPending() && (System.currentTimeMillis() / 200) % 2 == 0) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(cx, cy - 110);
			graphics.pose().scale(2.0F);
			graphics.centeredText(font, Component.translatable("kitwatch.alert.retotem"), 0, 0, 0xFFFFDD33);
			graphics.pose().popMatrix();
		}

		Component banner = monitor.banner();

		if (banner != null) {
			graphics.centeredText(font, banner, cx, cy - 84, monitor.bannerColor());
		}
	}
}

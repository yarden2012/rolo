package io.github.yarden2012.hitrhythm;

import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.ARGB;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

/** Draws the charge meter, the per-hit grade popup and the stats panel. */
public class HitRhythmHud implements HudElement {
	private static final long POPUP_MS = 900;
	private static final int METER_WIDTH = 40;
	private static final int METER_HEIGHT = 3;

	private final HitRhythmConfig config;
	private final HitTracker tracker;

	public HitRhythmHud(HitRhythmConfig config, HitTracker tracker) {
		this.config = config;
		this.tracker = tracker;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;

		if (player == null || client.options.hideGui) {
			return;
		}

		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;

		if (config.meter && tracker.inCombat()) {
			drawMeter(graphics, cx, cy, player.getAttackStrengthScale(deltaTracker.getGameTimeDeltaPartialTick(false)));
		}

		if (config.gradePopup) {
			drawGrade(graphics, client.font, cx, cy);
		}

		if (config.panel) {
			drawPanel(graphics, client.font);
		}
	}

	private void drawMeter(GuiGraphicsExtractor graphics, int cx, int cy, float charge) {
		int x0 = cx - METER_WIDTH / 2;
		int y0 = cy + 16;
		int color = charge >= 1.0F ? 0xFF55FF55 : charge > HitTracker.CLEAN_THRESHOLD ? 0xFFFFFF55 : 0xFFFF5555;

		graphics.fill(x0 - 1, y0 - 1, x0 + METER_WIDTH + 1, y0 + METER_HEIGHT + 1, 0x90000000);
		graphics.fill(x0, y0, x0 + Math.round(METER_WIDTH * charge), y0 + METER_HEIGHT, color);

		// Notch at the 90% mark: past it, hits can crit and sprint-hits deal extra knockback.
		int notch = x0 + Math.round(METER_WIDTH * HitTracker.CLEAN_THRESHOLD);
		graphics.fill(notch, y0 - 2, notch + 1, y0 + METER_HEIGHT + 2, 0xFFFFFFFF);
	}

	private void drawGrade(GuiGraphicsExtractor graphics, Font font, int cx, int cy) {
		HitTracker.GradedHit hit = tracker.lastHit();

		if (hit == null) {
			return;
		}

		long age = System.currentTimeMillis() - hit.timeMs();

		if (age >= POPUP_MS) {
			return;
		}

		MutableComponent text = Component.translatable(hit.grade().key());

		if (hit.grade() == HitTracker.Grade.EARLY) {
			text.append(" ").append(Component.translatable("hitrhythm.tag.damage", Math.round(hit.damageFactor() * 100)));
		}

		if (hit.crit()) {
			text.append(" +").append(Component.translatable("hitrhythm.tag.crit"));
		}

		if (hit.knockback()) {
			text.append(" +").append(Component.translatable("hitrhythm.tag.knockback"));
		}

		float fade = 1.0F - (float) age / POPUP_MS;
		int rise = (int) (age / 90);
		graphics.centeredText(font, text, cx, cy - 26 - rise, ARGB.color(Math.max(fade, 0.05F), hit.grade().rgb()));
	}

	private void drawPanel(GuiGraphicsExtractor graphics, Font font) {
		List<Component> lines = List.of(
				Component.translatable("hitrhythm.panel.title"),
				Component.translatable("hitrhythm.panel.hits", tracker.hits(), tracker.averageChargePercent()),
				Component.translatable("hitrhythm.panel.clean", tracker.cleanPercent(), tracker.perfectPercent()),
				Component.translatable("hitrhythm.panel.bonus", tracker.crits(), tracker.knockbackHits()),
				Component.translatable("hitrhythm.panel.streak", tracker.cleanStreak(), tracker.bestCleanStreak()));

		int width = 0;

		for (Component line : lines) {
			width = Math.max(width, font.width(line));
		}

		int x = 4;
		int y = 4;
		int lineHeight = font.lineHeight + 1;
		graphics.fill(x - 2, y - 2, x + width + 2, y + lines.size() * lineHeight + 1, 0x80000000);

		for (int i = 0; i < lines.size(); i++) {
			graphics.text(font, lines.get(i), x, y + i * lineHeight, i == 0 ? 0xFFFFAA00 : 0xFFFFFFFF);
		}
	}
}

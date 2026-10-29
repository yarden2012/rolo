package io.github.yarden2012.ledgesense;

import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

/** Draws one arrow per hazard on a ring around the crosshair, pointing the way you'd fall. */
public class LedgeSenseHud implements HudElement {
	private final LedgeSenseConfig config;
	private final LedgeScanner scanner;
	private final LedgeAlarm alarm;

	public LedgeSenseHud(LedgeSenseConfig config, LedgeScanner scanner, LedgeAlarm alarm) {
		this.config = config;
		this.scanner = scanner;
		this.alarm = alarm;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		List<Hazard> hazards = scanner.hazards();

		if (!config.enabled || player == null || client.options.hideGui || hazards.isEmpty()) {
			return;
		}

		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;
		float opacity = config.dimWhileSneaking && player.isShiftKeyDown() ? 0.35F : 1.0F;
		Hazard primary = hazards.getFirst();

		for (Hazard hazard : hazards) {
			if (hazard.threat() > primary.threat()) {
				primary = hazard;
			}
		}

		for (Hazard hazard : hazards) {
			float angle = (float) Math.toRadians(Mth.wrapDegrees(hazard.yaw() - player.getYRot()));
			float x = cx + Mth.sin(angle) * config.ringRadius;
			float y = cy - Mth.cos(angle) * config.ringRadius;
			float strength = hazard == primary ? 1.0F : 0.7F;
			drawArrow(graphics, x, y, angle, hazard.distance() <= 1 ? 4 : 3, ARGB.multiplyAlpha(hazard.severity().color(), opacity * strength));
		}

		float primaryAngle = (float) Math.toRadians(Mth.wrapDegrees(primary.yaw() - player.getYRot()));

		if (config.labels) {
			int labelX = cx + Math.round(Mth.sin(primaryAngle) * (config.ringRadius + 14));
			int labelY = cy - Math.round(Mth.cos(primaryAngle) * (config.ringRadius + 14)) - client.font.lineHeight / 2;
			graphics.centeredText(client.font, label(primary), labelX, labelY, ARGB.multiplyAlpha(primary.severity().color(), opacity));
		}

		// Right after a hit next to a deadly edge, spell out where it is if you can't see it.
		boolean behind = Math.abs(Math.toDegrees(primaryAngle)) > 110;

		if (alarm.isFlashing() && behind && primary.severity() == Hazard.Severity.DEADLY && (alarm.flashTicks() / 3) % 2 == 0) {
			graphics.centeredText(client.font, Component.translatable("ledgesense.warning.behind"), cx, cy + config.ringRadius + 16, 0xFFFF3333);
		}
	}

	private static Component label(Hazard hazard) {
		return switch (hazard.kind()) {
			case VOID -> Component.translatable("ledgesense.hazard.void", hazard.distance());
			case LAVA -> Component.translatable("ledgesense.hazard.lava", hazard.distance());
			case DROP -> Component.translatable("ledgesense.hazard.drop", hazard.depth());
		};
	}

	/** A small solid triangle centered on (x, y), pointing away from the crosshair. */
	private static void drawArrow(GuiGraphicsExtractor graphics, float x, float y, float angle, int size, int color) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().rotate(angle);

		for (int row = 0; row < size; row++) {
			int top = row - size / 2;
			graphics.fill(-row, top, row + 1, top + 1, color);
		}

		graphics.pose().popMatrix();
	}
}

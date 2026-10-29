package io.github.yarden2012.threatpings;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

/**
 * Draws each ping as a dot on a ring around the crosshair, at the angle of the
 * sound relative to where you're looking (top = in front, bottom = behind),
 * with its label pushed outward so close-together pings don't overlap.
 */
public class ThreatPingsHud implements HudElement {
	/** Pings closer together than this on the ring get their labels stacked. */
	private static final float STACK_DEGREES = 14.0F;
	private static final int LABEL_STEP = 10;
	private static final double VERTICAL_HINT = 3.0;

	private final ThreatPingsConfig config;
	private final PingTracker tracker;

	public ThreatPingsHud(ThreatPingsConfig config, PingTracker tracker) {
		this.config = config;
		this.tracker = tracker;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;

		if (!config.enabled || player == null || client.options.hideGui) {
			return;
		}

		List<PingTracker.Ping> pings = tracker.pings();

		if (pings.isEmpty()) {
			return;
		}

		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;
		long now = System.currentTimeMillis();
		Vec3 eye = player.getEyePosition();
		List<Float> placedAngles = new ArrayList<>();

		// Newest first, so the freshest ping gets the spot closest to the ring.
		for (int i = pings.size() - 1; i >= 0; i--) {
			PingTracker.Ping ping = pings.get(i);
			Vec3 offset = ping.position.subtract(eye);
			float soundYaw = (float) Math.toDegrees(Math.atan2(-offset.x, offset.z));
			float relative = Mth.wrapDegrees(soundYaw - player.getYRot());
			float angle = (float) Math.toRadians(relative);
			float fade = 1.0F - (float) (now - ping.timeMs) / tracker.durationMs();
			int color = ARGB.multiplyAlpha(ping.kind.color(), Math.max(fade, 0.1F));

			float sin = Mth.sin(angle);
			float cos = Mth.cos(angle);
			int dotX = cx + Math.round(sin * config.ringRadius);
			int dotY = cy - Math.round(cos * config.ringRadius);
			graphics.fill(dotX - 2, dotY - 2, dotX + 2, dotY + 2, color);

			int stacked = 0;

			for (float placed : placedAngles) {
				if (Math.abs(Mth.wrapDegrees(placed - relative)) < STACK_DEGREES) {
					stacked++;
				}
			}

			placedAngles.add(relative);
			int labelRadius = config.ringRadius + 10 + stacked * LABEL_STEP;
			int labelX = cx + Math.round(sin * labelRadius);
			int labelY = cy - Math.round(cos * labelRadius) - client.font.lineHeight / 2;
			graphics.centeredText(client.font, label(ping, offset), labelX, labelY, color);
		}
	}

	private MutableComponent label(PingTracker.Ping ping, Vec3 offset) {
		MutableComponent label = Component.translatable(ping.kind.translationKey());

		if (config.showDistance) {
			label.append(" " + Math.round(offset.length()) + "m");
		}

		if (offset.y > VERTICAL_HINT) {
			label.append(" ▲");
		} else if (offset.y < -VERTICAL_HINT) {
			label.append(" ▼");
		}

		return label;
	}
}

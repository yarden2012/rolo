package io.github.yarden2012.modeswitcher;

import java.util.List;

import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.particle.ParticlesMode;

/**
 * Every client option the mod knows how to switch between profiles.
 */
public final class ManagedSettings {
	public static final List<ManagedSetting<?>> ALL = List.of(
			ManagedSetting.ofInt("fov", GameOptions::getFov),
			ManagedSetting.ofDouble("sensitivity", GameOptions::getMouseSensitivity),
			ManagedSetting.ofDouble("gamma", GameOptions::getGamma),
			ManagedSetting.ofInt("render_distance", GameOptions::getViewDistance),
			ManagedSetting.ofInt("max_fps", GameOptions::getMaxFps),
			ManagedSetting.ofBoolean("auto_jump", GameOptions::getAutoJump),
			ManagedSetting.ofBoolean("view_bobbing", GameOptions::getBobView),
			ManagedSetting.ofDouble("fov_effects", GameOptions::getFovEffectScale),
			ManagedSetting.ofDouble("distortion_effects", GameOptions::getDistortionEffectScale),
			ManagedSetting.ofEnum("particles", ParticlesMode.class, GameOptions::getParticles),
			ManagedSetting.ofEnum("clouds", CloudRenderMode.class, GameOptions::getCloudRenderMode));

	private ManagedSettings() {
	}
}

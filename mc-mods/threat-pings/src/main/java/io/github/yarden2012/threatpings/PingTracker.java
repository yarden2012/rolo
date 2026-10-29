package io.github.yarden2012.threatpings;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.world.phys.Vec3;

/**
 * Listens to every sound the client plays and keeps a short-lived ping for each
 * combat sound. Like vanilla subtitles, only sounds within their own hearing
 * range are picked up.
 */
public class PingTracker implements SoundEventListener {
	/** Sounds this close to you are your own actions. */
	private static final double SELF_RADIUS_SQR = 2.0 * 2.0;
	/** Repeated sounds (eating, drinking) from about the same spot refresh one ping instead of stacking. */
	private static final double MERGE_RADIUS_SQR = 3.0 * 3.0;
	private static final long MERGE_MS = 1500;
	private static final int MAX_PINGS = 16;

	public static final class Ping {
		public final ThreatKind kind;
		public Vec3 position;
		public long timeMs;

		Ping(ThreatKind kind, Vec3 position, long timeMs) {
			this.kind = kind;
			this.position = position;
			this.timeMs = timeMs;
		}
	}

	private final ThreatPingsConfig config;
	private final List<Ping> pings = new ArrayList<>();

	public PingTracker(ThreatPingsConfig config) {
		this.config = config;
	}

	@Override
	public void onPlaySound(SoundInstance sound, WeighedSoundEvents soundEvent, float range) {
		LocalPlayer player = Minecraft.getInstance().player;

		if (!config.enabled || player == null || sound.isRelative() || sound.getAttenuation() == SoundInstance.Attenuation.NONE) {
			return;
		}

		ThreatKind kind = ThreatKind.bySound(sound.getIdentifier());

		if (kind == null || !config.isEnabled(kind)) {
			return;
		}

		Vec3 position = new Vec3(sound.getX(), sound.getY(), sound.getZ());
		double distanceSqr = player.distanceToSqr(position);
		double maxDistance = Math.min(range, config.maxDistance);

		if (distanceSqr < SELF_RADIUS_SQR || distanceSqr > maxDistance * maxDistance) {
			return;
		}

		long now = System.currentTimeMillis();

		for (Ping ping : pings) {
			if (ping.kind == kind && now - ping.timeMs < MERGE_MS && ping.position.distanceToSqr(position) < MERGE_RADIUS_SQR) {
				ping.position = position;
				ping.timeMs = now;
				return;
			}
		}

		if (pings.size() >= MAX_PINGS) {
			pings.removeFirst();
		}

		pings.add(new Ping(kind, position, now));
	}

	/** Live pings, oldest first. Expired ones are dropped as a side effect. */
	public List<Ping> pings() {
		long cutoff = System.currentTimeMillis() - durationMs();
		Iterator<Ping> iterator = pings.iterator();

		while (iterator.hasNext()) {
			if (iterator.next().timeMs < cutoff) {
				iterator.remove();
			}
		}

		return pings;
	}

	public long durationMs() {
		return (long) (Math.max(0.5F, config.durationSeconds) * 1000);
	}

	public void clear() {
		pings.clear();
	}
}

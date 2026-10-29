package io.github.yarden2012.threatpings;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.Identifier;

/** The combat sounds Threat Pings reacts to, grouped into the labels shown on screen. */
public enum ThreatKind {
	PEARL("pearl", 0xFFC77DFF, "entity.ender_pearl.throw"),
	BOW("bow", 0xFFFFDD55, "entity.arrow.shoot"),
	CROSSBOW("crossbow", 0xFFFFAA33, "item.crossbow.shoot", "item.crossbow.loading_end"),
	TRIDENT("trident", 0xFF55DDDD, "item.trident.throw"),
	POTION("potion", 0xFFFF77CC, "entity.splash_potion.throw", "entity.lingering_potion.throw"),
	DRINK("drink", 0xFFFFAADD, "entity.generic.drink"),
	EAT("eat", 0xFF77FF77, "entity.generic.eat"),
	TOTEM("totem", 0xFFFFC233, "item.totem.use"),
	SHIELD("shield", 0xFFCCCCCC, "item.shield.block"),
	SHIELD_BREAK("shield_break", 0xFFFFFFFF, "item.shield.break"),
	WIND("wind", 0xFFAADDFF, "entity.wind_charge.throw"),
	MACE("mace", 0xFFFF6644, "item.mace.smash_ground", "item.mace.smash_ground_heavy"),
	EXPLOSION("explosion", 0xFFFF4444, "entity.generic.explode"),
	TNT("tnt", 0xFFFF5555, "entity.tnt.primed"),
	ANCHOR("anchor", 0xFFDD66FF, "block.respawn_anchor.charge"),
	ROCKET("rocket", 0xFFFFEE88, "entity.firework_rocket.launch");

	private static final Map<Identifier, ThreatKind> BY_SOUND = new HashMap<>();

	static {
		for (ThreatKind kind : values()) {
			for (String sound : kind.sounds) {
				BY_SOUND.put(Identifier.withDefaultNamespace(sound), kind);
			}
		}
	}

	private final String id;
	private final int color;
	private final String[] sounds;

	ThreatKind(String id, int color, String... sounds) {
		this.id = id;
		this.color = color;
		this.sounds = sounds;
	}

	public String id() {
		return id;
	}

	public int color() {
		return color;
	}

	public String translationKey() {
		return "threatpings.kind." + id;
	}

	/** The kind a sound event belongs to, or null if it isn't a combat sound. */
	public static ThreatKind bySound(Identifier sound) {
		return BY_SOUND.get(sound);
	}

	public static ThreatKind byId(String id) {
		for (ThreatKind kind : values()) {
			if (kind.id.equalsIgnoreCase(id)) {
				return kind;
			}
		}

		return null;
	}
}

package io.github.yarden2012.modeswitcher;

import net.minecraft.text.Text;

/**
 * The two settings profiles the player can switch between.
 */
public enum Mode {
	VANILLA("vanilla"),
	PVP("pvp");

	private final String id;

	Mode(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public Text label() {
		return Text.translatable("modeswitcher.mode." + id);
	}

	public Mode opposite() {
		return this == VANILLA ? PVP : VANILLA;
	}

	public static Mode byId(String id) {
		return PVP.id.equalsIgnoreCase(id) ? PVP : VANILLA;
	}
}

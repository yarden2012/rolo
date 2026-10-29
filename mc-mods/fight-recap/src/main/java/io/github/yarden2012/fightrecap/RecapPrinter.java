package io.github.yarden2012.fightrecap;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Turns a finished fight into chat lines. */
public final class RecapPrinter {
	private RecapPrinter() {
	}

	public static List<Component> lines(Fight fight) {
		Component outcome = Component.translatable(fight.outcome.key()).withColor(fight.outcome.color()).withStyle(ChatFormatting.BOLD);
		Component opponents = fight.opponents.isEmpty()
				? Component.translatable("fightrecap.recap.unknown_opponent")
				: Component.literal(fight.opponentNames());

		return List.of(
				Component.translatable("fightrecap.recap.header", outcome, opponents, fight.durationText()).withStyle(ChatFormatting.GOLD),
				Component.translatable("fightrecap.recap.offense",
						fight.hitsLanded, fight.swings, fight.accuracyPercent(), fight.crits, fight.bestCombo).withStyle(ChatFormatting.GREEN),
				Component.translatable("fightrecap.recap.defense",
						fight.hitsTaken, hearts(fight.damageTaken), fight.theirBestCombo).withStyle(ChatFormatting.RED),
				Component.translatable("fightrecap.recap.resources",
						hearts(fight.healed), fight.gapples, fight.pearls, fight.potions).withStyle(ChatFormatting.AQUA),
				Component.translatable("fightrecap.recap.totems", fight.totemsPopped, fight.opponentTotemsPopped).withStyle(ChatFormatting.YELLOW));
	}

	public static Component historyLine(int number, Fight fight) {
		return Component.translatable("fightrecap.history.line",
				number,
				Component.translatable(fight.outcome.key()).withColor(fight.outcome.color()),
				fight.opponents.isEmpty() ? Component.translatable("fightrecap.recap.unknown_opponent") : Component.literal(fight.opponentNames()),
				fight.hitsLanded,
				fight.hitsTaken,
				fight.durationText());
	}

	/** Health points as hearts, e.g. 7 -> "3.5❤". */
	private static String hearts(float health) {
		return String.format(Locale.ROOT, "%.1f❤", health / 2.0F);
	}
}

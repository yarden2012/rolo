package io.github.yarden2012.kitwatch;

import java.util.function.Predicate;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** The consumables Kit Watch counts, in the order they're shown. */
public enum Supply {
	TOTEMS("totems", Items.TOTEM_OF_UNDYING, stack -> stack.is(Items.TOTEM_OF_UNDYING)),
	GAPPLES("gapples", Items.GOLDEN_APPLE, stack -> stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)),
	PEARLS("pearls", Items.ENDER_PEARL, stack -> stack.is(Items.ENDER_PEARL)),
	WIND_CHARGES("wind_charges", Items.WIND_CHARGE, stack -> stack.is(Items.WIND_CHARGE)),
	ARROWS("arrows", Items.ARROW, stack -> stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW)),
	HEALING("healing", Items.SPLASH_POTION, Supply::isHealingPotion);

	private final String id;
	private final Item fallbackIcon;
	private final Predicate<ItemStack> matcher;

	Supply(String id, Item fallbackIcon, Predicate<ItemStack> matcher) {
		this.id = id;
		this.fallbackIcon = fallbackIcon;
		this.matcher = matcher;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "kitwatch.supply." + id;
	}

	public ItemStack fallbackIcon() {
		return new ItemStack(fallbackIcon);
	}

	public boolean matches(ItemStack stack) {
		return matcher.test(stack);
	}

	public static Supply byId(String id) {
		for (Supply supply : values()) {
			if (supply.id.equalsIgnoreCase(id)) {
				return supply;
			}
		}

		return null;
	}

	private static boolean isHealingPotion(ItemStack stack) {
		if (!stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION) && !stack.is(Items.LINGERING_POTION)) {
			return false;
		}

		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);

		if (contents == null) {
			return false;
		}

		for (MobEffectInstance effect : contents.getAllEffects()) {
			if (effect.getEffect().is(MobEffects.INSTANT_HEALTH)) {
				return true;
			}
		}

		return false;
	}
}

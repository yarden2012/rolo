package io.github.yarden2012.kitwatch;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Takes stock of the player's kit every tick and raises alerts when something
 * runs low. Alerts fire only when a value crosses a threshold, never repeatedly
 * while it stays below it.
 */
public class KitMonitor {
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final long FLASH_MS = 3000;
	private static final long BANNER_MS = 2500;
	/** An effect that vanishes with this little time left ran out, rather than being cleared by milk. */
	private static final int NATURAL_EXPIRY_TICKS = 40;

	public record ArmorPiece(ItemStack stack, int percent) {
	}

	public record EffectTimer(Component name, int ticksLeft) {
	}

	private final KitWatchConfig config;

	private final Map<Supply, Integer> counts = new EnumMap<>(Supply.class);
	private final Map<Supply, ItemStack> icons = new EnumMap<>(Supply.class);
	private final Map<Supply, Long> flashUntil = new EnumMap<>(Supply.class);
	private final Map<EquipmentSlot, Integer> armorPercents = new EnumMap<>(EquipmentSlot.class);
	private final Map<Holder<MobEffect>, Integer> effectTicks = new HashMap<>();
	private List<ArmorPiece> armor = List.of();
	private List<EffectTimer> expiringEffects = List.of();

	private LocalPlayer trackedPlayer;
	private boolean hadOffhandTotem;
	private boolean retotemPending;
	private Component banner;
	private int bannerColor;
	private long bannerUntil;

	public KitMonitor(KitWatchConfig config) {
		this.config = config;
	}

	public void tick(Minecraft client) {
		LocalPlayer player = client.player;

		if (player == null) {
			trackedPlayer = null;
			return;
		}

		// A new player object means a new world or a respawn: take a fresh baseline without alerting.
		boolean fresh = player != trackedPlayer;
		trackedPlayer = player;

		tickSupplies(client, player.getInventory(), fresh);
		tickArmor(client, player, fresh);
		tickEffects(client, player, fresh);
		tickTotemGuard(client, player, fresh);
	}

	private void tickSupplies(Minecraft client, Inventory inventory, boolean fresh) {
		Map<Supply, Integer> now = new EnumMap<>(Supply.class);
		icons.clear();

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);

			if (stack.isEmpty()) {
				continue;
			}

			for (Supply supply : Supply.values()) {
				if (supply.matches(stack)) {
					now.merge(supply, stack.getCount(), Integer::sum);
					icons.putIfAbsent(supply, stack.copyWithCount(1));
				}
			}
		}

		for (Supply supply : Supply.values()) {
			int count = now.getOrDefault(supply, 0);
			int before = counts.getOrDefault(supply, 0);
			int low = config.lowStock(supply);
			counts.put(supply, count);

			if (fresh || count >= before) {
				continue;
			}

			Component name = Component.translatable(supply.translationKey());

			if (count == 0) {
				alert(client, supply, Component.translatable("kitwatch.alert.out", name), 0xFFFF5555, 0.6F);
			} else if (before > low && count <= low) {
				alert(client, supply, Component.translatable("kitwatch.alert.low", name, count), 0xFFFFAA00, 0.9F);
			}
		}
	}

	private void alert(Minecraft client, Supply supply, Component message, int color, float pitch) {
		flashUntil.put(supply, System.currentTimeMillis() + FLASH_MS);
		showBanner(message, color);
		playSound(client, SoundEvents.NOTE_BLOCK_PLING.value(), pitch);
	}

	private void tickArmor(Minecraft client, LocalPlayer player, boolean fresh) {
		List<ArmorPiece> pieces = new ArrayList<>();
		int warn = config.armorWarnPercent;
		int critical = Math.max(1, warn / 4);

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);

			if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
				armorPercents.remove(slot);
				continue;
			}

			int percent = (stack.getMaxDamage() - stack.getDamageValue()) * 100 / stack.getMaxDamage();
			Integer before = armorPercents.put(slot, percent);
			pieces.add(new ArmorPiece(stack.copy(), percent));

			if (fresh || before == null) {
				continue;
			}

			boolean crossedWarn = before > warn && percent <= warn;
			boolean crossedCritical = before > critical && percent <= critical;

			if (crossedWarn || crossedCritical) {
				showBanner(Component.translatable("kitwatch.alert.armor", stack.getHoverName(), percent), crossedCritical ? 0xFFFF5555 : 0xFFFFAA00);
				playSound(client, SoundEvents.NOTE_BLOCK_PLING.value(), crossedCritical ? 0.5F : 0.7F);
			}
		}

		armor = pieces;
	}

	private void tickEffects(Minecraft client, LocalPlayer player, boolean fresh) {
		int warnTicks = config.effectWarnSeconds * 20;
		Map<Holder<MobEffect>, Integer> now = new HashMap<>();
		List<EffectTimer> expiring = new ArrayList<>();

		for (MobEffectInstance instance : player.getActiveEffects()) {
			// Beacon and conduit effects are constantly topped up, so they never really run out.
			if (instance.isAmbient() || instance.isInfiniteDuration() || !instance.getEffect().value().isBeneficial()) {
				continue;
			}

			int ticks = instance.getDuration();
			now.put(instance.getEffect(), ticks);

			if (ticks <= warnTicks) {
				expiring.add(new EffectTimer(instance.getEffect().value().getDisplayName(), ticks));

				Integer before = effectTicks.get(instance.getEffect());

				if (!fresh && before != null && before > warnTicks) {
					playSound(client, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2F);
				}
			}
		}

		if (!fresh) {
			for (Map.Entry<Holder<MobEffect>, Integer> entry : effectTicks.entrySet()) {
				if (!now.containsKey(entry.getKey()) && entry.getValue() <= NATURAL_EXPIRY_TICKS) {
					showBanner(Component.translatable("kitwatch.alert.effect_ended", entry.getKey().value().getDisplayName()), 0xFFFFAA00);
					playSound(client, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6F);
				}
			}
		}

		effectTicks.clear();
		effectTicks.putAll(now);
		expiring.sort((a, b) -> Integer.compare(a.ticksLeft(), b.ticksLeft()));
		expiringEffects = expiring;
	}

	private void tickTotemGuard(Minecraft client, LocalPlayer player, boolean fresh) {
		boolean offhandTotem = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);

		if (!config.totemGuard || fresh) {
			hadOffhandTotem = offhandTotem;
			retotemPending = false;
			return;
		}

		if (hadOffhandTotem && !offhandTotem) {
			if (counts.getOrDefault(Supply.TOTEMS, 0) > 0) {
				retotemPending = true;
				playSound(client, SoundEvents.NOTE_BLOCK_BELL.value(), 1.5F);
			} else {
				showBanner(Component.translatable("kitwatch.alert.no_totems"), 0xFFFF5555);
				playSound(client, SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F);
			}
		}

		if (offhandTotem || counts.getOrDefault(Supply.TOTEMS, 0) == 0) {
			retotemPending = false;
		}

		hadOffhandTotem = offhandTotem;
	}

	private void showBanner(Component message, int color) {
		banner = message;
		bannerColor = color;
		bannerUntil = System.currentTimeMillis() + BANNER_MS;
	}

	private void playSound(Minecraft client, SoundEvent sound, float pitch) {
		if (config.sounds) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, 0.8F));
		}
	}

	public int count(Supply supply) {
		return counts.getOrDefault(supply, 0);
	}

	public ItemStack icon(Supply supply) {
		ItemStack icon = icons.get(supply);
		return icon != null ? icon : supply.fallbackIcon();
	}

	public boolean isFlashing(Supply supply) {
		return flashUntil.getOrDefault(supply, 0L) > System.currentTimeMillis();
	}

	public List<ArmorPiece> armor() {
		return armor;
	}

	public List<EffectTimer> expiringEffects() {
		return expiringEffects;
	}

	public boolean retotemPending() {
		return retotemPending;
	}

	/** The current alert banner, or null when none is showing. */
	public Component banner() {
		return bannerUntil > System.currentTimeMillis() ? banner : null;
	}

	public int bannerColor() {
		return bannerColor;
	}
}

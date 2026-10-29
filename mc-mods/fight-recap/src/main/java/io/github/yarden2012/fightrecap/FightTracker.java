package io.github.yarden2012.fightrecap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Follows the local player's fights.
 *
 * <p>A swing only counts as a hit once the server confirms it: the client never
 * sets a target's hurt timer itself, so a fresh hurt animation on the target
 * within a few ticks of our swing, with us as the damage source, means the hit
 * registered (and wasn't eaten by invulnerability frames). Each hurt animation
 * confirms at most one swing, so spam-clicking can't inflate the count.
 */
public class FightTracker {
	/** Long enough to cover a laggy round trip to the server. */
	private static final int HIT_CONFIRM_TICKS = 12;
	private static final double ENGAGE_RANGE = 8.0;
	private static final long OPPONENT_ACTIVE_MS = 4000;
	private static final Identifier TOTEM_SOUND = Identifier.withDefaultNamespace("item.totem.use");

	private static final class PendingHit {
		final int entityId;
		final int hurtTimeBefore;
		final boolean crit;
		int age;

		PendingHit(int entityId, int hurtTimeBefore, boolean crit) {
			this.entityId = entityId;
			this.hurtTimeBefore = hurtTimeBefore;
			this.crit = crit;
		}
	}

	private final FightRecapConfig config;
	private final Consumer<Fight> onFinished;

	private Fight current;
	private long lastActionMs;
	private final List<PendingHit> pending = new ArrayList<>();
	/** Hurt timer of each swung-at target as of last tick, to spot when a new hurt animation starts. */
	private final Map<Integer, Integer> lastTargetHurtTime = new HashMap<>();
	/** Entity id of each opponent, mapped to when they last swung at us or got swung at. */
	private final Map<Integer, Long> opponents = new HashMap<>();

	private LocalPlayer trackedPlayer;
	private float lastHealth;
	private int lastHurtTime;
	private int lastGapples;
	private int lastPearls;
	private int lastPotions;

	public FightTracker(FightRecapConfig config, Consumer<Fight> onFinished) {
		this.config = config;
		this.onFinished = onFinished;
	}

	public Fight current() {
		return current;
	}

	public void onAttack(Player player, Entity target) {
		if (!(target instanceof LivingEntity living) || !isOpponent(target)) {
			return;
		}

		Fight fight = ensureFight();
		fight.swings++;
		fight.opponents.add(target.getName().getString());
		opponents.put(target.getId(), System.currentTimeMillis());
		lastActionMs = System.currentTimeMillis();

		// Same rule vanilla uses in Player.attack, checked before the swing resets the charge.
		boolean crit = player.getAttackStrengthScale(0.5F) > 0.9F
				&& player.fallDistance > 0.0
				&& !player.onGround()
				&& !player.onClimbable()
				&& !player.isInWater()
				&& !player.isMobilityRestricted()
				&& !player.isPassenger()
				&& !player.isSprinting();
		pending.add(new PendingHit(target.getId(), living.hurtTime, crit));
	}

	/**
	 * Counts totem pops. The client plays the totem sound right where the entity that popped
	 * one is standing, so the pop belongs to whoever is closest to the sound: us or an opponent.
	 */
	public void onSound(Identifier sound, double x, double y, double z) {
		Minecraft client = Minecraft.getInstance();

		if (current == null || client.player == null || client.level == null || !TOTEM_SOUND.equals(sound)) {
			return;
		}

		double ownDistance = client.player.distanceToSqr(x, y, z);
		double opponentDistance = Double.MAX_VALUE;

		for (int id : opponents.keySet()) {
			Entity opponent = client.level.getEntity(id);

			if (opponent != null) {
				opponentDistance = Math.min(opponentDistance, opponent.distanceToSqr(x, y, z));
			}
		}

		if (ownDistance <= opponentDistance && ownDistance < 1.0) {
			current.totemsPopped++;
		} else if (opponentDistance < 4.0) {
			current.opponentTotemsPopped++;
		}
	}

	public void tick(Minecraft client) {
		LocalPlayer player = client.player;
		ClientLevel level = client.level;

		// Left the world: wrap up whatever fight was running.
		if (player == null || level == null) {
			finish(Fight.Outcome.ENDED);
			trackedPlayer = null;
			return;
		}

		// A new player object means a respawn or a new world; dying already closed the fight.
		if (player != trackedPlayer) {
			finish(Fight.Outcome.ENDED);
			trackedPlayer = player;
			lastHealth = totalHealth(player);
			lastHurtTime = player.hurtTime;
			countConsumables(player.getInventory());
			return;
		}

		confirmHits(player, level);
		trackHealth(player, level);
		trackConsumables(player.getInventory());
		checkEnd(player, level);
	}

	/** Ends the current fight (if any) and reports it. */
	public void finish(Fight.Outcome outcome) {
		Fight fight = current;
		current = null;
		pending.clear();
		lastTargetHurtTime.clear();
		opponents.clear();

		if (fight == null) {
			return;
		}

		fight.endMs = System.currentTimeMillis();
		fight.outcome = outcome;

		if (fight.exchanges() >= config.minExchanges) {
			onFinished.accept(fight);
		}
	}

	private Fight ensureFight() {
		if (current == null) {
			current = new Fight();
			lastActionMs = System.currentTimeMillis();
		}

		return current;
	}

	private boolean isOpponent(Entity entity) {
		return config.playersOnly ? entity instanceof Player : entity instanceof LivingEntity;
	}

	private void confirmHits(LocalPlayer player, ClientLevel level) {
		Set<Integer> hurtThisTick = new HashSet<>();
		Iterator<PendingHit> iterator = pending.iterator();

		// Oldest swing first: a new hurt animation is credited to the earliest swing still waiting.
		while (iterator.hasNext()) {
			PendingHit hit = iterator.next();

			if (current != null && level.getEntity(hit.entityId) instanceof LivingEntity target && !hurtThisTick.contains(hit.entityId)) {
				int before = lastTargetHurtTime.getOrDefault(hit.entityId, hit.hurtTimeBefore);

				if (target.hurtTime > before) {
					hurtThisTick.add(hit.entityId);

					if (causedBy(target.getLastDamageSource(), player)) {
						current.landed(hit.crit);
						lastActionMs = System.currentTimeMillis();
						iterator.remove();
						continue;
					}
				}
			}

			if (++hit.age > HIT_CONFIRM_TICKS) {
				iterator.remove();
			}
		}

		lastTargetHurtTime.clear();

		for (PendingHit hit : pending) {
			if (level.getEntity(hit.entityId) instanceof LivingEntity target) {
				lastTargetHurtTime.put(hit.entityId, target.hurtTime);
			}
		}
	}

	/** Whether the damage came from this player. An unknown source gets the benefit of the doubt. */
	private static boolean causedBy(DamageSource source, Player player) {
		return source == null || source.getEntity() == null || source.getEntity() == player;
	}

	private void trackHealth(LocalPlayer player, ClientLevel level) {
		float health = totalHealth(player);

		// A hit restarts the hurt timer at 10. The server sends us two packets per hit that both
		// restart it, so a 9 -> 10 bump a tick later is the same hit, not a new one.
		if (player.hurtTime > lastHurtTime + 1) {
			LivingEntity attacker = attackerOf(player, level);

			// Only hits from an opponent count; fall, fire and poison damage are still added to the damage total below.
			if (attacker != null) {
				ensureFight().tookHit();
				lastActionMs = System.currentTimeMillis();
				current.opponents.add(attacker.getName().getString());
				opponents.put(attacker.getId(), System.currentTimeMillis());
			}
		}

		if (current != null) {
			float delta = health - lastHealth;

			if (delta < 0) {
				current.damageTaken -= delta;
			} else {
				current.healed += delta;
			}
		}

		lastHealth = health;
		lastHurtTime = player.hurtTime;
	}

	/**
	 * Who just hurt us. The server tells the client the damage source (for arrows and
	 * other projectiles, the shooter), so we use that; without one, fall back to the
	 * nearest opponent, and with a source that has no attacker (fall, fire) it's nobody.
	 */
	private LivingEntity attackerOf(LocalPlayer player, ClientLevel level) {
		DamageSource source = player.getLastDamageSource();

		if (source == null) {
			return nearestOpponent(player, level);
		}

		return source.getEntity() instanceof LivingEntity attacker && attacker != player && isOpponent(attacker) ? attacker : null;
	}

	private LivingEntity nearestOpponent(LocalPlayer player, ClientLevel level) {
		List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(ENGAGE_RANGE),
				entity -> entity != player && entity.isAlive() && isOpponent(entity));
		LivingEntity nearest = null;

		for (LivingEntity entity : nearby) {
			if (nearest == null || player.distanceToSqr(entity) < player.distanceToSqr(nearest)) {
				nearest = entity;
			}
		}

		return nearest;
	}

	private void trackConsumables(Inventory inventory) {
		int gapples = lastGapples;
		int pearls = lastPearls;
		int potions = lastPotions;
		countConsumables(inventory);

		if (current != null) {
			current.gapples += Math.max(0, gapples - lastGapples);
			current.pearls += Math.max(0, pearls - lastPearls);
			current.potions += Math.max(0, potions - lastPotions);
		}
	}

	private void countConsumables(Inventory inventory) {
		lastGapples = 0;
		lastPearls = 0;
		lastPotions = 0;

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);

			if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
				lastGapples += stack.getCount();
			} else if (stack.is(Items.ENDER_PEARL)) {
				lastPearls += stack.getCount();
			} else if (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)) {
				lastPotions += stack.getCount();
			}
		}
	}

	private void checkEnd(LocalPlayer player, ClientLevel level) {
		if (current == null) {
			return;
		}

		if (player.isDeadOrDying()) {
			finish(Fight.Outcome.LOSS);
			return;
		}

		long now = System.currentTimeMillis();
		boolean killed = false;
		Iterator<Map.Entry<Integer, Long>> iterator = opponents.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<Integer, Long> entry = iterator.next();

			if (level.getEntity(entry.getKey()) instanceof LivingEntity opponent && opponent.isDeadOrDying()) {
				current.kills++;
				killed = true;
				iterator.remove();
			}
		}

		boolean othersActive = opponents.values().stream().anyMatch(time -> now - time < OPPONENT_ACTIVE_MS);

		if (killed && !othersActive) {
			finish(Fight.Outcome.WIN);
		} else if (now - lastActionMs > config.timeoutSeconds * 1000L) {
			finish(current.kills > 0 ? Fight.Outcome.WIN : Fight.Outcome.ENDED);
		}
	}

	private static float totalHealth(LocalPlayer player) {
		return player.getHealth() + player.getAbsorptionAmount();
	}
}

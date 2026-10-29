package io.github.yarden2012.ledgesense;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Walks outward from the player in eight directions and reports the first
 * dangerous edge in each one. A direction stops being scanned once it hits a
 * two-block-tall wall, since knockback can't carry you through that.
 */
public class LedgeScanner {
	private static final int[][] DIRECTIONS = {
			{0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}
	};

	private final LedgeSenseConfig config;
	private List<Hazard> hazards = List.of();

	public LedgeScanner(LedgeSenseConfig config) {
		this.config = config;
	}

	public List<Hazard> hazards() {
		return hazards;
	}

	public void clear() {
		hazards = List.of();
	}

	public void scan(LocalPlayer player, Level level) {
		List<Hazard> found = new ArrayList<>();
		int feetY = Mth.floor(player.getY() + 0.01);
		int radius = config.clampedScanRadius();
		float health = player.getHealth() + player.getAbsorptionAmount();
		double safeFall = player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
		double fallMultiplier = player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER);

		for (int[] dir : DIRECTIONS) {
			double length = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]);
			double stepX = dir[0] / length;
			double stepZ = dir[1] / length;
			float yaw = (float) Math.toDegrees(Math.atan2(-dir[0], dir[1]));

			for (int distance = 1; distance <= radius; distance++) {
				int x = Mth.floor(player.getX() + stepX * distance);
				int z = Mth.floor(player.getZ() + stepZ * distance);

				if (isSolid(level, new BlockPos(x, feetY + 1, z))) {
					break;
				}

				Hazard hazard = checkColumn(level, x, feetY, z, yaw, distance, health, safeFall, fallMultiplier);

				if (hazard != null) {
					found.add(hazard);
					break;
				}
			}
		}

		hazards = found;
	}

	private Hazard checkColumn(Level level, int x, int feetY, int z, float yaw, int distance, float health, double safeFall, double fallMultiplier) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int y = feetY; y >= level.getMinY(); y--) {
			pos.set(x, y, z);
			BlockState state = level.getBlockState(pos);

			if (state.is(Blocks.LAVA)) {
				return new Hazard(Hazard.Kind.LAVA, Hazard.Severity.DEADLY, yaw, distance, feetY - y);
			}

			if (!state.getFluidState().isEmpty()) {
				return null; // Landing in water: no fall damage.
			}

			if (!state.getCollisionShape(level, pos).isEmpty()) {
				// You land on top of this block, so the fall ends one block above it.
				int depth = feetY - y - 1;

				if (depth < config.minDrop) {
					return null;
				}

				double damage = Math.ceil((depth - safeFall) * fallMultiplier);

				if (damage <= 0) {
					return null;
				}

				Hazard.Severity severity = damage >= health ? Hazard.Severity.DEADLY
						: damage >= health / 2 ? Hazard.Severity.HIGH
						: Hazard.Severity.LOW;
				return new Hazard(Hazard.Kind.DROP, severity, yaw, distance, depth);
			}
		}

		return new Hazard(Hazard.Kind.VOID, Hazard.Severity.DEADLY, yaw, distance, 0);
	}

	private static boolean isSolid(Level level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}

package io.github.yarden2012.ledgesense;

/**
 * Something dangerous in one of the eight compass directions around the player.
 *
 * @param yaw      Minecraft yaw pointing at the hazard (0 = south, 90 = west)
 * @param distance horizontal distance to the edge, in blocks
 * @param depth    how far the fall is, in blocks (unused for void)
 */
public record Hazard(Kind kind, Severity severity, float yaw, int distance, int depth) {
	public enum Kind {
		VOID,
		LAVA,
		DROP
	}

	public enum Severity {
		/** A drop that hurts. */
		LOW(0xFFFFDD33),
		/** A drop that would take half your health or more. */
		HIGH(0xFFFF8C1A),
		/** Void, lava, or a fall that would kill you outright. */
		DEADLY(0xFFFF3333);

		private final int color;

		Severity(int color) {
			this.color = color;
		}

		public int color() {
			return color;
		}
	}

	/** Higher is worse: deadlier first, then closer. */
	public int threat() {
		return severity.ordinal() * 100 - distance;
	}
}

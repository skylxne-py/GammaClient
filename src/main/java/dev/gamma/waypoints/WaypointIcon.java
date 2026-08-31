package dev.gamma.waypoints;

/**
 * An 8x8 pixel mask, filled via {@code Renderer2D.fill()} one cell at a time (same generative
 * approach as the ClickGUI's rounded rects — no bundled texture asset, per the project's Blaze3D-only
 * rendering split not needing a texture-upload path we'd otherwise need to build for one small icon
 * set). Rows read top to bottom, {@code '#'} = filled, anything else = empty.
 */
public enum WaypointIcon {

	CIRCLE(
			"..####..",
			".######.",
			"########",
			"########",
			"########",
			"########",
			".######.",
			"..####.."),

	SQUARE(
			"########",
			"########",
			"##....##",
			"##....##",
			"##....##",
			"##....##",
			"########",
			"########"),

	DIAMOND(
			"...##...",
			"..####..",
			".######.",
			"########",
			"########",
			".######.",
			"..####..",
			"...##..."),

	TRIANGLE(
			"...##...",
			"...##...",
			"..####..",
			"..####..",
			".######.",
			".######.",
			"########",
			"########"),

	STAR(
			"...##...",
			"..####..",
			"########",
			".######.",
			"..####..",
			".######.",
			"##....##",
			"#......#"),

	CROSS(
			"##....##",
			"###..###",
			".######.",
			"..####..",
			"..####..",
			".######.",
			"###..###",
			"##....##"),

	SKULL(
			".######.",
			"########",
			"##....##",
			"########",
			".######.",
			".#.##.#.",
			".#.##.#.",
			"........"),

	BEACON(
			"...##...",
			"..####..",
			".######.",
			"########",
			"...##...",
			"...##...",
			"...##...",
			"..####..");

	public static final int SIZE = 8;

	private final boolean[][] mask;

	WaypointIcon(String... rows) {
		this.mask = new boolean[SIZE][SIZE];
		for (int row = 0; row < SIZE; row++) {
			for (int col = 0; col < SIZE; col++) {
				mask[row][col] = rows[row].charAt(col) == '#';
			}
		}
	}

	public boolean filled(int row, int col) {
		return mask[row][col];
	}
}

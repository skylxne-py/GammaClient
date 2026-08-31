package dev.gamma.util;

/**
 * ARGB packing/unpacking and HSV conversion. Per the project conventions, colors are {@code int} ARGB
 * everywhere; this is the one place that reasons about their components. Used by the ClickGUI
 * theme (accent hover/active shades, derived rather than hardcoded) and the color picker
 * (HSV square + hue strip).
 */
public final class ColorUtil {

	private ColorUtil() {
	}

	public static int argb(int a, int r, int g, int b) {
		return (clampByte(a) << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
	}

	public static int alpha(int argb) {
		return (argb >>> 24) & 0xFF;
	}

	public static int red(int argb) {
		return (argb >>> 16) & 0xFF;
	}

	public static int green(int argb) {
		return (argb >>> 8) & 0xFF;
	}

	public static int blue(int argb) {
		return argb & 0xFF;
	}

	public static int withAlpha(int argb, int alpha) {
		return (clampByte(alpha) << 24) | (argb & 0x00FFFFFF);
	}

	/** Scales alpha by a [0,1] factor — for fade animations and disabled states. */
	public static int scaleAlpha(int argb, double scale) {
		return withAlpha(argb, (int) Math.round(alpha(argb) * clamp01(scale)));
	}

	/** Blends toward white in HSV value/saturation space — visually even lightening for hover states. */
	public static int lighten(int argb, double amount) {
		double[] hsv = toHsv(argb);
		hsv[1] = hsv[1] * (1.0 - amount * 0.6);
		hsv[2] = clamp01(hsv[2] + (1.0 - hsv[2]) * amount + amount * 0.15);
		return fromHsv(hsv[0], hsv[1], clamp01(hsv[2]), alpha(argb));
	}

	/** Blends toward black in HSV value space — for pressed/active states. */
	public static int darken(int argb, double amount) {
		double[] hsv = toHsv(argb);
		hsv[2] = clamp01(hsv[2] * (1.0 - amount));
		return fromHsv(hsv[0], hsv[1], hsv[2], alpha(argb));
	}

	public static int lerp(int from, int to, double t) {
		double clamped = clamp01(t);
		int a = (int) Math.round(alpha(from) + (alpha(to) - alpha(from)) * clamped);
		int r = (int) Math.round(red(from) + (red(to) - red(from)) * clamped);
		int g = (int) Math.round(green(from) + (green(to) - green(from)) * clamped);
		int b = (int) Math.round(blue(from) + (blue(to) - blue(from)) * clamped);
		return argb(a, r, g, b);
	}

	/** @return {hue in [0,360), saturation in [0,1], value in [0,1]} */
	public static double[] toHsv(int argb) {
		double r = red(argb) / 255.0;
		double g = green(argb) / 255.0;
		double b = blue(argb) / 255.0;
		double max = Math.max(r, Math.max(g, b));
		double min = Math.min(r, Math.min(g, b));
		double delta = max - min;

		double hue;
		if (delta < 1.0e-9) {
			hue = 0;
		} else if (max == r) {
			hue = 60 * (((g - b) / delta) % 6);
		} else if (max == g) {
			hue = 60 * (((b - r) / delta) + 2);
		} else {
			hue = 60 * (((r - g) / delta) + 4);
		}
		if (hue < 0) {
			hue += 360;
		}
		double saturation = max < 1.0e-9 ? 0 : delta / max;
		return new double[] {hue, saturation, max};
	}

	public static int fromHsv(double hue, double saturation, double value, int alpha) {
		double h = ((hue % 360) + 360) % 360;
		double s = clamp01(saturation);
		double v = clamp01(value);

		double c = v * s;
		double x = c * (1 - Math.abs((h / 60) % 2 - 1));
		double m = v - c;

		double r;
		double g;
		double b;
		if (h < 60) {
			r = c; g = x; b = 0;
		} else if (h < 120) {
			r = x; g = c; b = 0;
		} else if (h < 180) {
			r = 0; g = c; b = x;
		} else if (h < 240) {
			r = 0; g = x; b = c;
		} else if (h < 300) {
			r = x; g = 0; b = c;
		} else {
			r = c; g = 0; b = x;
		}
		return argb(alpha, (int) Math.round((r + m) * 255), (int) Math.round((g + m) * 255), (int) Math.round((b + m) * 255));
	}

	public static String toHex(int argb, boolean includeAlpha) {
		return includeAlpha
				? String.format("%08X", argb)
				: String.format("%06X", argb & 0x00FFFFFF);
	}

	/** Parses a {@code #RRGGBB}/{@code RRGGBB} or {@code #AARRGGBB}/{@code AARRGGBB} hex string, keeping {@code alpha} if not given. */
	public static int parseHex(String text, int alpha) {
		String cleaned = text.startsWith("#") ? text.substring(1) : text;
		if (cleaned.length() == 8) {
			return (int) Long.parseLong(cleaned, 16);
		}
		int rgb = Integer.parseInt(cleaned, 16);
		return withAlpha(rgb, alpha);
	}

	private static int clampByte(int value) {
		return Math.max(0, Math.min(255, value));
	}

	private static double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}

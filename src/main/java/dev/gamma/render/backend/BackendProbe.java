package dev.gamma.render.backend;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Reports which Blaze3D render backend (OpenGL or Vulkan) is currently active.
 * This is the only file in Gamma permitted to know that distinction — it may only
 * report, never branch rendering behavior on it.
 */
public final class BackendProbe {

	private BackendProbe() {
	}

	public static String activeBackend() {
		String deviceClass = RenderSystem.getDevice().getClass().getName().toLowerCase();
		if (deviceClass.contains("vulkan")) {
			return "Vulkan";
		}
		if (deviceClass.contains("gl")) {
			return "OpenGL";
		}
		return "Unknown (" + deviceClass + ")";
	}
}

package dev.gamma.gui.clickgui.anim;

/** Named easing curves. Everything in the ClickGUI/HUD animates through one of these — nothing snaps. */
public enum Easing {

	LINEAR {
		@Override
		public double apply(double t) {
			return t;
		}
	},

	/** The house style per project convention: cubic ease-out, used for panel/row expand and hover states. */
	CUBIC_OUT {
		@Override
		public double apply(double t) {
			double inverse = 1.0 - t;
			return 1.0 - inverse * inverse * inverse;
		}
	},

	CUBIC_IN_OUT {
		@Override
		public double apply(double t) {
			return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
		}
	},

	/**
	 * Near-instant departure, then a long settle — the curve interface work has largely converged on,
	 * and the house curve here (see {@link Animated#of}). Steeper at the start than
	 * {@link #CUBIC_OUT}, so a slower duration still feels immediate on the press.
	 */
	EXPO_OUT {
		@Override
		public double apply(double t) {
			return t >= 1.0 ? 1.0 : 1.0 - Math.pow(2.0, -10.0 * t);
		}
	};

	public abstract double apply(double t);
}

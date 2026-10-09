package ru.deelter.vrcamera.client.compat;

/**
 * how a text of {@link Gizmos} looks. Named like what newer versions of the game have for it
 */
public final class TextGizmo {
	private TextGizmo() {
	}

	/**
	 * @param scale 1 is half a block tall
	 */
	public record Style(int color, float scale) {
		public static Style forColorAndCentered(int color) {
			return new Style(color, 1.0F);
		}

		public Style withScale(float scale) {
			return new Style(this.color, scale);
		}
	}
}

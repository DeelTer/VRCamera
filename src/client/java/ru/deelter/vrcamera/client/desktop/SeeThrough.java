package ru.deelter.vrcamera.client.desktop;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import ru.deelter.vrcamera.Vrcamera;

/**
 * Shows a player who is behind blocks as the camera sees them: through a round hole in what is in the way, with a
 * dithered edge.
 * <p>
 * The world is drawn a second time for that, without everything nearer to the camera than the player, and that
 * picture is put over the first one where the hole is. No block is drawn any differently, and nothing is seen that
 * is not right around the player: what is far behind them was in the first picture already.
 * <p>
 * Drawn in the OpenGL context of the camera window, which nothing else draws in.
 */
final class SeeThrough {
	private static final String VERTEX = """
			#version 330 core
			out vec2 uv;
			void main() {
				uv = vec2(gl_VertexID & 1, gl_VertexID >> 1);
				gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
			}
			""";
	private static final String FRAGMENT = """
			#version 330 core
			uniform sampler2D cut;
			uniform vec2 center;
			uniform float radius;
			uniform float aspect;
			uniform float amount;
			uniform float feet;
			in vec2 uv;
			out vec4 color;
			const float bayer[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0,
					13.0, 5.0);
			void main() {
				float away = length((uv - center) * vec2(aspect, 1.0)) / radius;
				float open = (1.0 - smoothstep(0.55, 1.0, away)) * amount * smoothstep(feet - 0.01, feet + 0.03, uv.y);
				ivec2 cell = ivec2(gl_FragCoord.xy / 2.0) & 3;
				if (open <= (bayer[cell.y * 4 + cell.x] + 0.5) / 16.0) {
					discard;
				}
				vec4 seen = texture(cut, uv);
				if (seen.a < 0.5) {
					discard;
				}
				color = vec4(seen.rgb, 1.0);
			}
			""";
	private static Hole hole;
	private static int program;
	private static int vertices;
	private static boolean broken;

	private SeeThrough() {
	}

	static void set(Hole wanted) {
		hole = wanted;
	}

	/**
	 * @param shown where in the window the picture is
	 */
	static void draw(Box shown) {
		final Hole open = hole;
		if (open == null || broken) {
			return;
		}
		try {
			if (program == 0) {
				program = link();
				vertices = GL30.glGenVertexArrays();
			}
			GL11.glViewport(shown.x(), shown.y(), shown.width(), shown.height());
			GL20.glUseProgram(program);
			GL13.glActiveTexture(GL13.GL_TEXTURE0);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, open.texture());
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "cut"), 0);
			GL20.glUniform2f(GL20.glGetUniformLocation(program, "center"), open.x(), open.y());
			GL20.glUniform1f(GL20.glGetUniformLocation(program, "radius"), open.radius());
			GL20.glUniform1f(GL20.glGetUniformLocation(program, "aspect"), open.aspect());
			GL20.glUniform1f(GL20.glGetUniformLocation(program, "amount"), open.amount());
			GL20.glUniform1f(GL20.glGetUniformLocation(program, "feet"), open.floor());
			GL30.glBindVertexArray(vertices);
			GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
			GL30.glBindVertexArray(0);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
			GL20.glUseProgram(0);
		} catch (RuntimeException e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: can't show the player through blocks, that is off until the game restarts", e);
		}
	}

	private static int link() {
		final int linked = GL20.glCreateProgram();
		for (final int[] stage : new int[][]{{GL20.GL_VERTEX_SHADER, 0}, {GL20.GL_FRAGMENT_SHADER, 1}}) {
			final int shader = GL20.glCreateShader(stage[0]);
			GL20.glShaderSource(shader, stage[1] == 0 ? VERTEX : FRAGMENT);
			GL20.glCompileShader(shader);
			if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
				throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
			}
			GL20.glAttachShader(linked, shader);
			GL20.glDeleteShader(shader);
		}
		GL20.glLinkProgram(linked);
		if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
			throw new IllegalStateException(GL20.glGetProgramInfoLog(linked));
		}
		return linked;
	}

	/**
	 * @param texture what OpenGL calls the picture without what is in the way
	 * @param x       where the player is in the picture from its left, from 0 to 1, and {@code y} from its bottom
	 * @param radius  of the hole, in heights of the picture
	 * @param aspect  how many times wider than high the picture is
	 * @param amount  how far the hole is open, from 0 to 1
	 * @param floor   where the feet of the player are in the picture, from its bottom. The hole ends there: below
	 *                it is the ground in front of the player, which a picture that begins behind what is in the way
	 *                has holes in
	 */
	record Hole(int texture, float x, float y, float radius, float aspect, float amount, float floor) {
	}
}

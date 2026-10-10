package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import ru.deelter.vrcamera.client.photo.Palettes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The palettes of the photos as buttons, each with its colours on it, and a photo in those of the one the mouse
 * is over: picking one, getting one from lospec.com by
 * its link, looking for one there, and opening the folder they are in. The same as the command /vrcam palette does.
 */
public class PalettesScreen extends Screen {
	private static final int WIDTH = 240;
	private static final int MOST_SAMPLE = 220;
	private static final int LEAST_SAMPLE = 90;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int ROWS = 5;
	private static final int MOST_SWATCHES = 16;
	private static final String SWATCH = "█";
	private static final Component HELP = Component.translatable("vrcamera.gui.palettes.help");

	private final Screen parent;
	private final List<String> names = new ArrayList<>();
	private final Map<Button, String> rows = new HashMap<>();
	private EditBox link;
	private StringWidget status;
	private Component said = HELP;
	private String typed = "";
	private int page = -1;
	private String sampled;
	private boolean open = true;

	public PalettesScreen(Screen parent) {
		super(Component.translatable("vrcamera.gui.palettes"));
		this.parent = parent;
	}

	private static Component name(String name) {
		return name.isEmpty() ? Component.translatable("vrcamera.option.photoPalette.map") : Component.literal(name);
	}

	@Override
	protected void init() {
		names.clear();
		names.add(Palettes.MAP_COLORS);
		names.addAll(Palettes.names());
		final int pages = (names.size() + ROWS - 1) / ROWS;
		if (page < 0) {
			page = Math.max(0, names.indexOf(Palettes.selected())) / ROWS;
		}
		page = Math.min(page, pages - 1);

		final int room = width - WIDTH - GAP * 6;
		final int sampleWidth = room < LEAST_SAMPLE ? 0 : Math.min(MOST_SAMPLE, room);
		final int x = (width - WIDTH - (sampleWidth > 0 ? sampleWidth + GAP * 2 : 0)) / 2;
		final int row = BUTTON_HEIGHT + GAP;
		int y = Math.max(4, height / 2 - 122);
		addRenderableWidget(new StringWidget(x, y, WIDTH, BUTTON_HEIGHT, title, font));
		y += row;
		rows.clear();
		for (int index = page * ROWS; index < Math.min(names.size(), (page + 1) * ROWS); index++) {
			final String name = names.get(index);
			rows.put(addRenderableWidget(Button.builder(label(name), button -> {
				Palettes.select(name);
				say(Component.translatable("vrcamera.gui.palettes.picked", name(name)));
			}).bounds(x, y + (index - page * ROWS) * row, WIDTH, BUTTON_HEIGHT).build()), name);
		}
		sampled = sampleWidth > 0 && PaletteSample.show(minecraft, Palettes.selected()) ? Palettes.selected() : null;
		if (sampled != null) {
			final int sampleHeight = Math.round(sampleWidth * PaletteSample.ASPECT);
			addRenderableWidget(ImageWidget.texture(sampleWidth, sampleHeight, PaletteSample.TEXTURE, sampleWidth,
					sampleHeight)).setPosition(x + WIDTH + GAP * 2, y);
		}
		y += ROWS * row;
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("←"), button -> turn(pages - 1, pages))
					.bounds(x, y, BUTTON_HEIGHT * 2, BUTTON_HEIGHT).build());
			addRenderableWidget(new StringWidget(x + BUTTON_HEIGHT * 2, y, WIDTH - BUTTON_HEIGHT * 4, BUTTON_HEIGHT,
					Component.translatable("vrcamera.gui.palettes.page", page + 1, pages), font));
			addRenderableWidget(Button.builder(Component.literal("→"), button -> turn(1, pages))
					.bounds(x + WIDTH - BUTTON_HEIGHT * 2, y, BUTTON_HEIGHT * 2, BUTTON_HEIGHT).build());
			y += row;
		}
		y += GAP * 2;
		link = addRenderableWidget(new EditBox(font, x, y, WIDTH, BUTTON_HEIGHT,
				Component.translatable("vrcamera.gui.palettes.link")));
		link.setMaxLength(200);
		link.setHint(Component.translatable("vrcamera.gui.palettes.link"));
		link.setValue(typed);
		y += row;
		final int third = (WIDTH - GAP * 2) / 3;
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.palettes.find"),
						button -> Palettes.openSite())
				.bounds(x, y, third, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.palettes.get"), button -> get())
				.bounds(x + third + GAP, y, third, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.palettes.folder"),
						button -> Palettes.openFolder())
				.bounds(x + WIDTH - third, y, third, BUTTON_HEIGHT).build());
		y += row;
		status = addRenderableWidget(new StringWidget(x, y, WIDTH, BUTTON_HEIGHT, said, font));
		y += row;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(x, Math.min(height - BUTTON_HEIGHT - GAP, y), WIDTH, BUTTON_HEIGHT).build());
	}

	/**
	 * the picture shows the palette the mouse is over, the picked one otherwise
	 */
	@Override
	public void tick() {
		super.tick();
		if (sampled == null) {
			return;
		}
		String looked = Palettes.selected();
		for (final Map.Entry<Button, String> row : rows.entrySet()) {
			looked = row.getKey().isHovered() ? row.getValue() : looked;
		}
		if (!looked.equals(sampled) && PaletteSample.show(minecraft, looked)) {
			sampled = looked;
		}
	}

	@Override
	public void removed() {
		super.removed();
		PaletteSample.close(minecraft);
	}

	@Override
	public void onClose() {
		open = false;
		minecraft.gui.setScreen(parent);
	}

	private void turn(int by, int pages) {
		page = (page + by) % pages;
		say(HELP);
	}

	private void get() {
		final String address = link.getValue().trim();
		if (address.isEmpty()) {
			say(Component.translatable("vrcamera.gui.palettes.link.none"));
			return;
		}
		status.setMessage(Component.translatable("vrcamera.command.palette.loading"));
		Palettes.fetch(address).whenComplete((name, error) -> minecraft.execute(() -> {
			if (error == null) {
				Palettes.select(name);
			}
			if (!open) {
				return;
			}
			if (error != null) {
				say(Component.translatable("vrcamera.command.palette.failed"));
				return;
			}
			link.setValue("");
			page = -1;
			say(Component.translatable("vrcamera.gui.palettes.picked", name(name)));
		}));
	}

	/**
	 * says something under the buttons and shows them again, with what changed
	 */
	private void say(Component said) {
		this.said = said;
		typed = link.getValue();
		rebuildWidgets();
	}

	private Component label(String name) {
		final MutableComponent label = Component.literal(name.equals(Palettes.selected()) ? "✔ " : "");
		label.append(name(name));
		final int[] colors = Palettes.colors(name);
		if (colors != null) {
			label.append("  ");
			final int step = (colors.length + MOST_SWATCHES - 1) / MOST_SWATCHES;
			for (int color = 0; color < colors.length; color += step) {
				label.append(Component.literal(SWATCH).withColor(colors[color]));
			}
		}
		return label;
	}
}

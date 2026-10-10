package ru.deelter.vrcamera.sync.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * /vrcamphotos: the photos a player has pinned, a page at a time, to find the ones they forgot about and take
 * them off. Each with the place it hangs at, which offers the command to go there when it is clicked.
 */
final class PhotoList {
	static final String COMMAND = "vrcamphotos";

	private static final TextColor ACCENT = TextColor.color(0xFFD700);
	private static final TextColor TEXT = TextColor.color(0xC7B9A6);
	static final TextColor ERROR = TextColor.color(0xEA4B3C);
	static final TextColor ERROR_TEXT = TextColor.color(0xC7A6AB);
	private static final int PER_PAGE = 8;

	private final SyncPlugin plugin;

	PhotoList(SyncPlugin plugin) {
		this.plugin = plugin;
	}

	private static int number(String text, int otherwise) {
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return otherwise;
		}
	}

	private static Component button(String label, TextColor color, String hover, ClickEvent click) {
		return Component.text("[" + label + "]", color)
				.hoverEvent(HoverEvent.showText(Component.text(hover, TEXT)))
				.clickEvent(click);
	}

	/**
	 * @param args nothing, a page, or "remove" with the id of a photo and the page to show afterwards
	 */
	boolean command(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("VRCameraSync: only a player has photos to list");
			return true;
		}
		if (args.length >= 2 && args[0].equalsIgnoreCase("remove")) {
			final long id = number(args[1], 0);
			if (!plugin.takeOff(player, id)) {
				player.sendMessage(Component.text("Photos: ", ERROR)
						.append(Component.text("that photo is not yours, or is gone already", ERROR_TEXT)));
			}
			show(player, args.length >= 3 ? number(args[2], 1) : 1);
			return true;
		}
		show(player, args.length >= 1 ? number(args[0], 1) : 1);
		return true;
	}

	/**
	 * a player tried to pin one more photo than they may have
	 */
	void tellFull(Player player, int most) {
		player.sendMessage(Component.text("Photos: ", ERROR)
				.append(Component.text(most + "/" + most + " pinned, take one off first. ", ERROR_TEXT))
				.append(button("list", ACCENT, "Show your pinned photos", ClickEvent.runCommand("/" + COMMAND))));
	}

	/**
	 * @param asked the page, counted from 1. One before the first is the last, one after the last the first
	 */
	private void show(Player player, int asked) {
		final List<StoredSheet> photos = plugin.photosOf(player.getUniqueId());
		final int pages = Math.max(1, (photos.size() + PER_PAGE - 1) / PER_PAGE);
		final int page = Math.floorMod(asked - 1, pages) + 1;

		final TextComponent.Builder list = Component.text();
		list.append(Component.text("Photos ", ACCENT))
				.append(Component.text(photos.size() + "/" + plugin.mostPhotos(), TEXT));
		if (pages > 1) {
			list.append(Component.text(" · page " + page + "/" + pages, TEXT));
		}
		if (photos.isEmpty()) {
			list.append(Component.newline()).append(Component.text("You have no pinned photos", TEXT));
		}
		final int first = (page - 1) * PER_PAGE;
		for (int index = first; index < Math.min(first + PER_PAGE, photos.size()); index++) {
			list.append(Component.newline()).append(line(index + 1, photos.get(index), page));
		}
		if (pages > 1) {
			list.append(Component.newline())
					.append(button("←", ACCENT, "Page before", ClickEvent.runCommand("/" + COMMAND + " " + (page - 1))))
					.append(Component.space())
					.append(button("→", ACCENT, "Next page", ClickEvent.runCommand("/" + COMMAND + " " + (page + 1))));
		}
		player.sendMessage(list.build());
	}

	private Component line(int number, StoredSheet photo, int page) {
		final World world = Bukkit.getWorld(photo.world());
		final String worldName = world == null ? "?" : world.getName();
		final String place = String.format(Locale.ROOT, "%s, %d, %d, %d", worldName, (int) Math.floor(photo.x()),
				(int) Math.floor(photo.y()), (int) Math.floor(photo.z()));
		Component where = Component.text("[" + place + "]", ACCENT);
		if (world != null) {
			final String teleport = String.format(Locale.ROOT, "/execute in %s run tp @s %.1f %.1f %.1f",
					world.getKey().asString(), photo.x(), photo.y(), photo.z());
			where = where.hoverEvent(HoverEvent.showText(Component.text("Click for the command to go there", TEXT)))
					.clickEvent(ClickEvent.suggestCommand(teleport));
		}
		return Component.text(number + ". " + (photo.custom() ? "Picture " : "Photo "), TEXT)
				.append(where)
				.append(Component.space())
				.append(button("✕", ERROR, "Take this photo off",
						ClickEvent.runCommand("/" + COMMAND + " remove " + photo.id() + " " + page)));
	}
}

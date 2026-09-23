package net.tfminecraft.thievery.utils;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

/**
 * Text colours for Thievery. See COLOUR-PALETTE.md at workspace root.
 */
public final class ThieveryTexts {

	// Display (chat, titles, action bar)
	public static final String ERROR = "§c";
	public static final String CRITICAL = "§4";
	public static final String SUCCESS = "§a";
	public static final String WARN = "§e";
	public static final String ACCENT = "§6";
	public static final String INFO = "§b";
	public static final String STAFF = "§7";
	public static final String MUTED = "§7";
	public static final String WHITE = "§f";
	public static final String DARK = "§8";

	// GUI (item lore, inventory titles)
	public static final String GUI_SUCCESS = "#87d65c";
	public static final String GUI_WARN = "#d6cf69";
	public static final String GUI_ACCENT = "#c9a24f";
	public static final String GUI_INFO = "#56ccf2";
	public static final String GUI_STAFF = "#c4b896";

	private ThieveryTexts() {}

	public static String formatDisplay(String raw) {
		if (raw == null || raw.isEmpty()) {
			return raw;
		}
		return raw.replace('&', '\u00A7');
	}

	public static String formatGui(String raw) {
		if (raw == null || raw.isEmpty()) {
			return raw;
		}
		return StringFormatter.formatHex(raw);
	}

	/** Display text for chat, titles, and action bar. */
	public static String msg(String raw) {
		return formatDisplay(raw);
	}

	/** GUI text for item lore, display names, and inventory titles. */
	public static String gui(String raw) {
		return formatGui(raw);
	}
}

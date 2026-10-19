package com.cluehelper.dev;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * B04: turns the probe's events.jsonl into one trial per spellbook change: the click that
 * started it, the click that committed it (a dialogue choice or sub-op), the tick the book var
 * changed, and the next spell cast with the tick it was accepted. B14 reuses it for S7.
 *
 * <p>Tick arithmetic: a click is logged with the tick count of the period it happened in, and a
 * server effect (var, animation) with the count of the period before its tick boundary, so
 * "ticks from click to effect" is {@code effectTick + 1 - clickTick}.
 */
final class SpellbookTimingParser
{
	private static final int MAGIC_SPELLBOOK_GROUP = 218;
	private static final int INVENTORY_GROUP = 149;
	private static final int EQUIPMENT_GROUP = 387;
	private static final int GIVE_UP_AFTER_TICKS = 40;
	private static final int ACCEPT_WITHIN_TICKS = 3;
	private static final int RECLICK_TICKS = 10;
	private static final Set<String> BOOK_OPTIONS = new HashSet<>(Arrays.asList("standard", "ancient", "lunar", "arceuus"));
	private static final Set<String> NOT_PART_OF_A_CHANGE = new HashSet<>(Arrays.asList("walk here", "inventory", "magic", "worn equipment", "cancel"));

	static final class Trial
	{
		String method;
		String firstClick;
		int firstClickTick;
		int commitClickTick;
		int clicks;
		int bookChangeTick = -1;
		String bookChange;
		String castClick;
		int castClickTick = -1;
		int acceptTick = -1;
		int castClicksBeforeAccept;
		String refusal;

		boolean changed()
		{
			return bookChangeTick >= 0;
		}

		/** Ticks from the committing click (sub-op, dialogue choice or the op itself) to the book var. */
		int commitToBook()
		{
			return bookChangeTick + 1 - commitClickTick;
		}

		/** Ticks from the first click of the change to the book var, menus and walking included. */
		int firstClickToBook()
		{
			return bookChangeTick + 1 - firstClickTick;
		}

		/** Ticks from the book var to the player's next spell click: human reaction, an upper bound on any lock. */
		int bookToCastClick()
		{
			return castClickTick < 0 ? -1 : castClickTick - bookChangeTick;
		}

		int castClickToAccept()
		{
			return acceptTick < 0 ? -1 : acceptTick + 1 - castClickTick;
		}

		@Override
		public String toString()
		{
			return method + " '" + firstClick + "' t" + firstClickTick + " commit t" + commitClickTick + " clicks=" + clicks
				+ (changed() ? " book t" + bookChangeTick + " (" + bookChange + ") commit->book=" + commitToBook()
				+ " first->book=" + firstClickToBook() : " NO CHANGE" + (refusal == null ? "" : " '" + refusal + "'"))
				+ (castClickTick < 0 ? "" : " cast '" + castClick + "' t" + castClickTick + " book->castClick=" + bookToCastClick()
				+ " castClick->accept=" + castClickToAccept() + " ignored=" + castClicksBeforeAccept);
		}
	}

	private SpellbookTimingParser()
	{
	}

	static List<Trial> parse(List<JsonObject> events)
	{
		List<Trial> trials = new ArrayList<>();
		Trial open = null;
		Trial awaitingCast = null;
		for (JsonObject e : events)
		{
			String kind = e.get("event").getAsString();
			int t = e.get("t").getAsInt();
			if (open != null && t - open.commitClickTick > GIVE_UP_AFTER_TICKS)
			{
				open = null;
			}
			if (awaitingCast != null && awaitingCast.acceptTick < 0 && awaitingCast.castClickTick >= 0
				&& t - awaitingCast.castClickTick > ACCEPT_WITHIN_TICKS)
			{
				awaitingCast.castClicksBeforeAccept++;
				awaitingCast.castClickTick = -1;
			}
			switch (kind)
			{
				case "click":
				{
					String option = e.get("option").getAsString();
					String method = method(e);
					boolean reclick = method != null && open != null && method.equals(open.method) && t - open.commitClickTick <= RECLICK_TICKS;
					if (method != null && !reclick)
					{
						open = new Trial();
						open.method = method;
						open.firstClick = option + " | " + e.get("target").getAsString();
						open.firstClickTick = t;
						open.commitClickTick = t;
						open.clicks = 1;
						trials.add(open);
						awaitingCast = null;
					}
					else if (open != null && !NOT_PART_OF_A_CHANGE.contains(option.toLowerCase()) && !isCast(e))
					{
						open.commitClickTick = t;
						open.clicks++;
					}
					else if (awaitingCast != null && isCast(e) && awaitingCast.acceptTick < 0)
					{
						if (awaitingCast.castClickTick < 0)
						{
							awaitingCast.castClick = option + " | " + e.get("target").getAsString();
							awaitingCast.castClickTick = t;
						}
					}
					break;
				}
				case "vars":
				{
					if (open != null && bookChanged(e))
					{
						open.bookChangeTick = t;
						open.bookChange = describeBookChange(e);
						awaitingCast = open;
						open = null;
					}
					break;
				}
				case "anim":
				{
					if (awaitingCast != null && awaitingCast.castClickTick >= 0 && awaitingCast.acceptTick < 0
						&& e.get("anim").getAsInt() != -1 && t >= awaitingCast.castClickTick)
					{
						awaitingCast.acceptTick = t;
					}
					break;
				}
				case "chat":
				{
					String message = e.get("message").getAsString();
					if (open != null && isRefusal(message))
					{
						open.refusal = message;
					}
					if (awaitingCast != null && awaitingCast.castClickTick >= 0 && awaitingCast.acceptTick < 0
						&& "GAMEMESSAGE".equals(e.get("type").getAsString()) && t >= awaitingCast.castClickTick)
					{
						awaitingCast.acceptTick = t;
					}
					break;
				}
				default:
					break;
			}
		}
		return trials;
	}

	static String method(JsonObject click)
	{
		String option = click.get("option").getAsString().toLowerCase();
		String target = click.get("target").getAsString().toLowerCase();
		int group = click.has("widgetGroup") ? click.get("widgetGroup").getAsInt() : -1;
		if (target.contains("spellbook swap"))
		{
			return "swap";
		}
		if (target.contains("mounted") && (option.equals("perks") || option.equals("spellbook")))
		{
			return "mountedCape";
		}
		if ((target.contains("magic cape") || target.contains("max cape")) && option.equals("spellbook"))
		{
			return "cape";
		}
		if ((group == INVENTORY_GROUP || group == EQUIPMENT_GROUP) && BOOK_OPTIONS.contains(option))
		{
			return "cape";
		}
		if (target.contains("altar of the occult"))
		{
			return option.equals("venerate") ? "occultVenerate" : BOOK_OPTIONS.contains(option) ? "occultOp" : null;
		}
		if (target.equals("altar") && option.equals("pray"))
		{
			return "astralAltar";
		}
		if (target.equals("altar") && option.equals("pray-at"))
		{
			return "pyramidAltar";
		}
		if (target.startsWith("tyss") && option.equals("spellbook"))
		{
			return "tyss";
		}
		if (target.endsWith("altar") && option.equals("venerate"))
		{
			return "singleAltar";
		}
		return null;
	}

	private static boolean isCast(JsonObject click)
	{
		return click.has("widgetGroup") && click.get("widgetGroup").getAsInt() == MAGIC_SPELLBOOK_GROUP
			&& !click.get("target").getAsString().toLowerCase().contains("spellbook swap");
	}

	private static boolean bookChanged(JsonObject vars)
	{
		JsonObject from = vars.getAsJsonObject("from");
		JsonObject to = vars.getAsJsonObject("to");
		if (!from.has("book4070"))
		{
			return false;
		}
		return !from.get("book4070").equals(to.get("book4070")) || !from.get("swap3617").equals(to.get("swap3617"));
	}

	private static String describeBookChange(JsonObject vars)
	{
		JsonObject from = vars.getAsJsonObject("from");
		JsonObject to = vars.getAsJsonObject("to");
		return "4070 " + from.get("book4070") + "->" + to.get("book4070") + ", 3617 " + from.get("swap3617") + "->" + to.get("swap3617");
	}

	private static boolean isRefusal(String message)
	{
		String m = message.toLowerCase();
		return m.contains("can't") || m.contains("cannot") || m.contains("unable") || m.contains("already") || m.contains("too deep")
			|| m.contains("not able") || m.contains("may only");
	}
}

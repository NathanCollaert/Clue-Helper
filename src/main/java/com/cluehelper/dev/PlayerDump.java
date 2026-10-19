package com.cluehelper.dev;

import com.cluehelper.player.Book;
import com.cluehelper.player.Charge;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.Position;
import com.cluehelper.player.RuneModel;
import com.cluehelper.player.Spell;
import com.cluehelper.player.SpellbookState;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.AvailabilitySet;
import com.cluehelper.transport.RowAvailability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

/**
 * {@code ::ch-player} and the {@code [CH-PLAYER]} log lines: the snapshot, the rune model's castable
 * spells (checked against the spellbook widget while the magic tab is open), and {@code A_carried}.
 * Client thread only.
 */
@Slf4j
final class PlayerDump
{
	static final String TAG = "[CH-PLAYER]";
	private static final int PARAM_SPELL_WIDGET = 596;
	private static final int PARAM_SPRITE_LIT = 597;
	private static final int PARAM_SPRITE_DIM = 598;
	private static final double NANOS_PER_MS = 1_000_000.0;

	private final Client client;
	private final PlayerModel model;
	private final AvailabilityService availability;
	private Set<String> lastAvailable = new TreeSet<>();

	PlayerDump(Client client, PlayerModel model, AvailabilityService availability)
	{
		this.client = client;
		this.model = model;
		this.availability = availability;
	}

	/** @param chat lines for the chat box and log; {@code logOnly} gets the unavailable catalog rows */
	void describe(Consumer<String> chat, Consumer<String> logOnly)
	{
		PlayerSnapshot s = model.getSnapshot();
		if (s == null)
		{
			chat.accept("No snapshot: not logged in yet");
			return;
		}
		snapshotLines(s).forEach(chat);
		RuneModel runes = model.getRuneModel();
		if (runes != null)
		{
			spellLines(s, runes).forEach(chat);
		}
		AvailabilitySet carried = availability.getCarried();
		chat.accept(String.format("A_carried: %d of %d rows (v%d, judged in %.2f ms); A_banked: empty until B15",
			carried.available().size(), carried.getRows().size(), carried.getSnapshotVersion(),
			availability.getLastJudgeNanos() / NANOS_PER_MS));
		for (RowAvailability row : carried.getRows())
		{
			(row.isAvailable() ? chat : logOnly).accept(rowLine(row));
		}
		chat.accept("Rebuilds: " + model.getStats().summary());
	}

	/** Logs what changed in {@code A_carried} since the last judgement. */
	void onJudged(AvailabilitySet carried)
	{
		PlayerSnapshot s = model.getSnapshot();
		Set<String> available = carried.available().stream().map(r -> r.getRow().getName()).collect(Collectors.toCollection(TreeSet::new));
		Set<String> gained = new TreeSet<>(available);
		gained.removeAll(lastAvailable);
		Set<String> lost = new TreeSet<>(lastAvailable);
		lost.removeAll(available);
		lastAvailable = available;
		StringBuilder line = new StringBuilder(TAG).append(" t").append(client.getTickCount())
			.append(" snapshot v").append(s == null ? 0 : s.getVersion())
			.append(" A_carried ").append(available.size()).append('/').append(carried.getRows().size());
		if (s != null)
		{
			line.append(" pouch ").append(s.isPouchHeld() ? names(s.getPouch()) : "not held")
				.append(" charges ").append(charges(s));
		}
		if (!gained.isEmpty())
		{
			line.append(" +").append(gained);
		}
		if (!lost.isEmpty())
		{
			line.append(" -").append(lost);
		}
		log.info(line.toString());
	}

	private List<String> snapshotLines(PlayerSnapshot s)
	{
		List<String> lines = new ArrayList<>();
		Position p = s.getPosition();
		Position now = model.getPosition();
		lines.add(String.format("Snapshot v%d (t%d): %s %s, now %s, wild %d, %s world%s, account type %d", s.getVersion(),
			s.getTick(), p.getKind(), tile(p.getTile()), tile(now.getTile()) + (now.getMapped() != null
				&& !now.getMapped().equals(now.getTile()) ? " (mapped " + tile(now.getMapped()) + ")" : ""),
			s.getWildernessLevel(), s.isMembersWorld() ? "members" : "F2P", s.isPvpWorld() ? ", PvP" : "",
			s.getAccountType()) + (s.getDormantReason() == null ? "" : ", dormant: " + s.getDormantReason()));
		SpellbookState b = s.getSpellbook();
		lines.add(String.format("Book %s%s, cape changes left %d, Arceuus %s, access ancient %s lunar %s swap %s",
			b.getBook(), b.isPendingRevert() ? " (pending revert)" : "", b.getCapeChangesLeft(), yes(b.isArceuusUnlocked()),
			yes(b.isAncientAccess()), yes(b.isLunarAccess()), yes(b.isSwapAccess())));
		lines.add("Inventory: " + names(s.getInventory()));
		lines.add("Worn: " + names(s.getWorn()));
		lines.add("Pouch: " + (s.isPouchHeld() ? names(s.getPouch()) : "not held"));
		lines.add("Charges: " + charges(s));
		long finished = s.getQuests().values().stream().filter(q -> q == QuestState.FINISHED).count();
		lines.add(String.format("Quests %s: %d/%d finished, %d QP; Magic %d, total %d, combat %d; vars %s / varps %s",
			s.isQuestsRead() ? "read" : "not read", finished, s.getQuests().size(), s.getQuestPoints(),
			s.getBoostedLevels().getOrDefault(Skill.MAGIC, 0), s.getTotalLevel(), s.getCombatLevel(),
			s.getVarbits().size(), s.getVarps().size()));
		return lines;
	}

	private List<String> spellLines(PlayerSnapshot s, RuneModel runes)
	{
		List<String> lines = new ArrayList<>();
		List<String> mismatches = new ArrayList<>();
		int checked = 0;
		for (Book book : Book.values())
		{
			List<String> castable = new ArrayList<>();
			for (Spell spell : runes.getSpells())
			{
				if (spell.getBook() != book)
				{
					continue;
				}
				boolean ours = runes.whyNotCastable(spell, s).isEmpty();
				if (ours && spell.getName().contains("Teleport"))
				{
					castable.add(spell.getName());
				}
				Boolean lit = book == s.getSpellbook().getBook() ? widgetLit(spell) : null;
				if (lit != null)
				{
					checked++;
					if (lit != ours)
					{
						mismatches.add(spell.getName() + " widget " + (lit ? "lit" : "dim") + ", ours " + (ours ? "castable" : runes.whyNotCastable(spell, s)));
					}
				}
			}
			lines.add(book + " teleports castable: " + castable.size() + " " + castable);
		}
		lines.add(checked == 0 ? "Spell widget check: open the magic tab and run ::ch-player again"
			: "Spell widget check: " + checked + " spells, " + mismatches.size() + " mismatches " + mismatches);
		return lines;
	}

	/** Null while the spell's widget is hidden: the widget is stale then (B04). */
	private Boolean widgetLit(Spell spell)
	{
		int widgetId = client.getItemDefinition(spell.getObj()).getIntValue(PARAM_SPELL_WIDGET);
		Widget widget = widgetId <= 0 ? null : client.getWidget(widgetId);
		if (widget == null || widget.isHidden())
		{
			return null;
		}
		int sprite = widget.getSpriteId();
		if (sprite == client.getItemDefinition(spell.getObj()).getIntValue(PARAM_SPRITE_LIT))
		{
			return true;
		}
		return sprite == client.getItemDefinition(spell.getObj()).getIntValue(PARAM_SPRITE_DIM) ? false : null;
	}

	private String rowLine(RowAvailability row)
	{
		String head = (row.isAvailable() ? "+ " : "- ") + row.getRow().getName()
			+ (row.getRow().getRequirements().getBook() == null ? "" : " [" + row.getRow().getRequirements().getBook() + "]");
		if (row.isAvailable())
		{
			return head + (row.getConfidence() == RowAvailability.Confidence.SURE ? "" : " (" + row.getConfidence() + ")")
				+ " via " + row.getWitness().stream().map(this::use).collect(Collectors.joining(", "));
		}
		return head + ": " + String.join("; ", row.getWhyNot());
	}

	private String use(ItemUse use)
	{
		return use.getQuantity() + " " + name(use.getItemId()) + " (" + use.getContainer().name().toLowerCase() + ")";
	}

	private String charges(PlayerSnapshot s)
	{
		return s.getCharges().entrySet().stream()
			.map(e -> name(e.getKey()) + "=" + e.getValue().getCharges() + " " + source(e.getValue()))
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private static String source(Charge charge)
	{
		return charge.getSource().name().toLowerCase().replace('_', ' ');
	}

	private String names(Map<Integer, Integer> items)
	{
		return items.entrySet().stream().map(e -> e.getValue() + " " + name(e.getKey())).collect(Collectors.joining(", ", "[", "]"));
	}

	private String name(int id)
	{
		return client.getItemDefinition(id).getName();
	}

	private static String tile(WorldPoint p)
	{
		return p == null ? "-" : p.getX() + "," + p.getY() + "," + p.getPlane();
	}

	private static String yes(boolean value)
	{
		return value ? "yes" : "no";
	}
}

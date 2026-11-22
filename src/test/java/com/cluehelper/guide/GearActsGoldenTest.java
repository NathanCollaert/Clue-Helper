package com.cluehelper.guide;

import com.cluehelper.campaign.CampaignReplay;
import com.cluehelper.memory.FakeProfileConfig;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.GearRequirement;
import com.cluehelper.route.StepNeeds;
import com.cluehelper.step.Step;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every gear act of the campaign's guides, played under a few loadouts: what the guide lists, outlines and asks to take
 * off each round, the player then doing what is outlined, until the act is done. The campaign recordings hold only the
 * built line, so this is what pins the gear acts' choices.
 */
public class GearActsGoldenTest
{
	private static final Path GOLDEN = Paths.get("src/test/resources/gear-acts/golden.txt");
	private static final Path ACTUAL = Paths.get("build/gear-acts/actual.txt");
	/** Item id, name, equipment slot (-1 for none) and inventory actions joined by {@code |}, from the game cache. */
	private static final Path ITEMS = Paths.get("src/test/resources/gear-acts/items.tsv");
	private static final Path NEEDED = Paths.get("build/gear-acts/needed-items.txt");
	/** By name, so the same golden holds across a change of the gear acts' kinds. */
	private static final Set<String> GEAR = Set.of("EQUIPPED", "WAY_IN_WORN", "GEAR_WORN", "SLOTS_EMPTY", "WAY_IN_SLOTS_EMPTY",
		"ITEM_WORN", "WORN");
	private static final int ROUNDS = 4;
	private static final int WORN_SLOTS = 14;

	private final Map<Integer, String[]> items = new HashMap<>();
	private final Set<Integer> needed = new TreeSet<>();

	@Test
	public void gearActsPickOutlineAndEndAsSignedOff() throws Exception
	{
		readItems();
		StringBuilder out = new StringBuilder();
		for (CampaignReplay.Guide guide : CampaignReplay.guides(sub -> GEAR.contains(sub.getDone().name())))
		{
			List<SubStep> subSteps = guide.getSubSteps();
			Set<Integer> candidates = subSteps.stream().filter(sub -> GEAR.contains(sub.getDone().name()))
				.flatMap(act -> candidates(act, guide).stream()).collect(Collectors.toCollection(TreeSet::new));
			for (int i = 0; i < subSteps.size(); i++)
			{
				if (GEAR.contains(subSteps.get(i).getDone().name()))
				{
					out.append("== ").append(guide.getKey()).append(" #").append(i).append(' ').append(subSteps.get(i).getText()).append('\n');
					play(out, guide, subSteps.get(i), candidates);
				}
			}
		}
		if (!needed.isEmpty())
		{
			write(NEEDED, needed.stream().map(String::valueOf).collect(Collectors.joining("\n")));
			throw new AssertionError(needed.size() + " items have no row in " + ITEMS + ": see " + NEEDED);
		}
		if (!Files.exists(GOLDEN))
		{
			write(GOLDEN, out.toString());
			throw new AssertionError(GOLDEN + " was missing and is now written: read it, then run again");
		}
		write(ACTUAL, out.toString());
		assertTrue("no gear acts replayed", out.length() > 0);
		assertEquals("gear acts differ from " + GOLDEN + ": see " + ACTUAL, Files.readString(GOLDEN).replace("\r\n", "\n"), out.toString());
	}

	private void play(StringBuilder out, CampaignReplay.Guide guide, SubStep act, Set<Integer> candidates) throws Exception
	{
		PlayerSnapshot recorded = guide.getPlayer();
		Map<String, Loadout> loadouts = new LinkedHashMap<>();
		loadouts.put("recorded", new Loadout(recorded.getWorn().keySet(), recorded.getInventory().keySet()));
		loadouts.put("bare", new Loadout(Set.of(), Set.of()));
		loadouts.put("carried", new Loadout(Set.of(), candidates));
		loadouts.put("worn", new Loadout(Set.of(), candidates).wearing(candidates));
		for (Map.Entry<String, Loadout> loadout : loadouts.entrySet())
		{
			out.append("-- ").append(loadout.getKey()).append('\n');
			Turn turn = new Turn(act, loadout.getValue());
			for (int round = 1; round <= ROUNDS && turn != null; round++)
			{
				turn = round(out, guide, turn, candidates);
			}
		}
	}

	/**
	 * One tick as the guide runs it: the act checked for done, then refreshed and outlined; the next turn after doing what
	 * is outlined, or null once done or stuck.
	 */
	private Turn round(StringBuilder out, CampaignReplay.Guide guide, Turn turn, Set<Integer> candidates) throws Exception
	{
		SubStep act = turn.act;
		Loadout loadout = turn.loadout;
		Set<Integer> known = new HashSet<>(candidates);
		known.addAll(loadout.worn);
		known.addAll(loadout.inventory);
		known.addAll(act.getActItems());
		Wiring wiring = new Wiring(guide, loadout, known);
		boolean done = wiring.gearActs.isDone(act, guide.getStep());
		out.append("  worn ").append(loadout.worn).append(" inv ").append(loadout.inventory).append(done ? " done\n" : "\n");
		if (done)
		{
			return null;
		}
		SubStep shown = wiring.gearActs.refresh(act, guide.getStep());
		out.append("   -> ").append(shown.getText()).append(' ').append(shown.getItems()).append('\n');
		Highlights outline = wiring.outline(shown, guide.getStep());
		List<String> menu = new ArrayList<>();
		MenuTarget target = outline.getMenuTarget();
		menu.add(target.getPath() + "@" + new TreeSet<>(target.getItemIds()));
		target.getAlternatives().forEach(alt -> menu.add(alt.getPath() + "@" + new TreeSet<>(alt.getItemIds())));
		out.append("   outline ").append(new TreeSet<>(outline.getItems())).append(" fill ").append(new TreeSet<>(outline.getFillItems()))
			.append(" tab ").append(outline.getTab()).append(" menu ").append(menu).append('\n');
		boolean removes = target.getPath().contains("Remove");
		Loadout next = removes ? loadout.removing(outline.getItems()) : loadout.wearing(outline.getItems());
		if (next.equals(loadout))
		{
			out.append("   stuck\n");
			return null;
		}
		return new Turn(shown, next);
	}

	/** Every item an act could want on: its requirements' ids, the way in's, the gear pieces recorded as owned. */
	private Set<Integer> candidates(SubStep act, CampaignReplay.Guide guide)
	{
		Set<Integer> ids = new TreeSet<>(act.getActItems());
		Step step = guide.getStep();
		step.getRequirements().getItems().forEach(requirement -> ids.addAll(ids(requirement)));
		if (step.getData() != null)
		{
			step.getData().wornRequiresFor(quest -> guide.getPlayer().finished(quest), guide.getPlayer().carriedIds())
				.forEach(need -> ids.addAll(need.getIds()));
		}
		guide.getNeeds().of(step).getNeeds().stream().filter(need -> act.getWornNeeds().contains(need.getName()))
			.forEach(need -> ids.addAll(ids(need.getRequirement())));
		return ids;
	}

	/** Core's requirement types keep their ids private, so they are read by field. */
	private static Set<Integer> ids(Object requirement)
	{
		Set<Integer> ids = new TreeSet<>();
		Integer start = null;
		for (Class<?> type = requirement.getClass(); type != Object.class; type = type.getSuperclass())
		{
			for (Field field : type.getDeclaredFields())
			{
				Object value = read(field, requirement);
				if (value instanceof ItemRequirement[])
				{
					Arrays.stream((ItemRequirement[]) value).forEach(inner -> ids.addAll(ids(inner)));
				}
				else if (value instanceof Collection)
				{
					((Collection<?>) value).forEach(inner -> ids.addAll(inner instanceof Integer ? Set.of((Integer) inner) : ids(inner)));
				}
				else if (requirement instanceof GearRequirement && value instanceof Map)
				{
					((Map<?, ?>) value).keySet().forEach(id -> ids.add((Integer) id));
				}
				else if (field.getName().equals("itemId"))
				{
					ids.add((Integer) value);
				}
				else if (field.getName().equals("startItemId"))
				{
					start = (Integer) value;
				}
				else if (field.getName().equals("endItemId"))
				{
					for (int id = start; id <= (Integer) value; id++)
					{
						ids.add(id);
					}
				}
			}
		}
		return ids;
	}

	private static Object read(Field field, Object owner)
	{
		if (java.lang.reflect.Modifier.isStatic(field.getModifiers()))
		{
			return null;
		}
		try
		{
			field.setAccessible(true);
			return field.get(owner);
		}
		catch (IllegalAccessException e)
		{
			throw new IllegalStateException("can't read " + field, e);
		}
	}

	private static void set(Object owner, String name, Object value) throws ReflectiveOperationException
	{
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}

	private void readItems() throws IOException
	{
		if (Files.exists(ITEMS))
		{
			Files.readAllLines(ITEMS).forEach(line -> items.put(Integer.parseInt(line.split("\t")[0]), line.split("\t", -1)));
		}
	}

	private String[] item(int id)
	{
		String[] row = items.get(id);
		if (row == null)
		{
			needed.add(id);
			return new String[]{String.valueOf(id), "item " + id, "-1", ""};
		}
		return row;
	}

	private int slot(int id)
	{
		return Integer.parseInt(item(id)[2]);
	}

	private String[] actions(int id)
	{
		String joined = item(id)[3];
		return joined.isEmpty() ? new String[0] : Arrays.stream(joined.split("\\|", -1)).map(a -> a.isEmpty() ? null : a).toArray(String[]::new);
	}

	private static void write(Path file, String text) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	/** The act as the guide shows it now, and the loadout it is shown for. */
	private static final class Turn
	{
		final SubStep act;
		final Loadout loadout;

		Turn(SubStep act, Loadout loadout)
		{
			this.act = act;
			this.loadout = loadout;
		}
	}

	/** What is worn and carried, one of each; putting a piece on takes off what its slot held. */
	private final class Loadout
	{
		final SortedSet<Integer> worn;
		final SortedSet<Integer> inventory;

		Loadout(Collection<Integer> worn, Collection<Integer> inventory)
		{
			this.worn = new TreeSet<>(worn);
			this.inventory = new TreeSet<>(inventory);
		}

		Loadout wearing(Collection<Integer> pieces)
		{
			Loadout next = new Loadout(worn, inventory);
			pieces.stream().sorted().filter(id -> slot(id) >= 0 && inventory.contains(id)).forEach(id ->
			{
				next.worn.stream().filter(on -> slot(on) == slot(id)).findFirst().ifPresent(off ->
				{
					next.worn.remove(off);
					next.inventory.add(off);
				});
				next.inventory.remove(id);
				next.worn.add(id);
			});
			return next;
		}

		Loadout removing(Collection<Integer> pieces)
		{
			Loadout next = new Loadout(worn, inventory);
			pieces.stream().filter(next.worn::remove).forEach(next.inventory::add);
			return next;
		}

		Item[] bySlot()
		{
			Item[] slots = new Item[WORN_SLOTS];
			Arrays.fill(slots, new Item(-1, 0));
			worn.stream().filter(id -> slot(id) >= 0).forEach(id -> slots[slot(id)] = new Item(id, 1));
			return slots;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Loadout && worn.equals(((Loadout) other).worn) && inventory.equals(((Loadout) other).inventory);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(worn, inventory);
		}
	}

	/** Today's classes, wired by hand to a client that knows only the loadout and the item table. */
	private final class Wiring
	{
		final GearActs gearActs = new GearActs();
		final StepHighlights highlights = new StepHighlights();

		/** @param known the items whose left clicks are learned already, so no swap is ever asked about */
		Wiring(CampaignReplay.Guide guide, Loadout loadout, Set<Integer> known) throws ReflectiveOperationException
		{
			Client client = client(loadout);
			PlayerModel player = new PlayerModel();
			set(player, "snapshot", guide.getPlayer().toBuilder()
				.worn(oneOfEach(loadout.worn)).inventory(oneOfEach(loadout.inventory)).build());
			LeftClicks leftClicks = new LeftClicks();
			set(leftClicks, "client", client);
			leftClicks.init(new FakeProfileConfig().newStore());
			LeftClicks.Learned learned = new LeftClicks.Learned();
			Map<String, String> clicks = new HashMap<>();
			for (int id : known)
			{
				clicks.put("item:" + id, Arrays.stream(actions(id)).filter(Objects::nonNull).findFirst().orElse("Use"));
				clicks.put("wornid:" + id, "Remove");
			}
			set(learned, "clicks", clicks);
			((com.cluehelper.memory.MemorySlot<LeftClicks.Learned>) read(LeftClicks.class.getDeclaredField("remembered"), leftClicks)).set(learned);
			RowTargets rowTargets = new RowTargets();
			set(rowTargets, "client", client);
			set(rowTargets, "player", player);
			set(gearActs, "client", client);
			set(gearActs, "player", player);
			set(gearActs, "stepNeeds", guide.getNeeds());
			set(gearActs, "leftClicks", leftClicks);
			set(highlights, "client", client);
			set(highlights, "player", player);
			set(highlights, "leftClicks", leftClicks);
			set(highlights, "rowTargets", rowTargets);
		}

		Highlights outline(SubStep act, Step step) throws ReflectiveOperationException
		{
			Method method = StepHighlights.class.getDeclaredMethod("actHighlights", Highlights.HighlightsBuilder.class, SubStep.class, Step.class);
			method.setAccessible(true);
			try
			{
				return (Highlights) method.invoke(highlights, Highlights.builder(), act, step);
			}
			catch (InvocationTargetException e)
			{
				throw new IllegalStateException("outlining " + act.getText(), e.getCause());
			}
		}

		private Map<Integer, Integer> oneOfEach(Set<Integer> ids)
		{
			return ids.stream().collect(Collectors.toMap(id -> id, id -> 1));
		}

		private Client client(Loadout loadout)
		{
			return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getItemDefinition":
						return definition((Integer) args[0]);
					case "getItemContainer":
						Item[] worn = loadout.bySlot();
						return Proxy.newProxyInstance(ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
							(container, read, none) -> worn);
					default:
						throw new UnsupportedOperationException("the gear golden's client has no " + method);
				}
			});
		}

		private ItemComposition definition(int id)
		{
			return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(), new Class<?>[]{ItemComposition.class},
				(proxy, method, args) ->
				{
					switch (method.getName())
					{
						case "getName":
							return item(id)[1];
						case "getInventoryActions":
							return actions(id);
						case "getId":
							return id;
						default:
							throw new UnsupportedOperationException("the gear golden's items have no " + method);
					}
				});
		}
	}
}

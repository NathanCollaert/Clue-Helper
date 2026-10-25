package com.cluehelper;

import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;

/**
 * Hands the Clue Scroll plugin a clue as if read: one at random, filtered by tier and type, or a list of step IDs walked
 * with next and previous. Core's own lists are the source, so a mocked clue is the object a real read gives.
 */
@Slf4j
final class MockClues
{
	static final String COMMAND = "ch-mock";
	static final String ANY = "any";
	static final List<String> TIERS = List.of(ANY, "beginner", "easy", "medium", "hard", "elite", "master");
	static final List<String> TYPES = List.of(ANY, ClueTypes.ANAGRAM, ClueTypes.CIPHER, ClueTypes.COORDINATE, ClueTypes.CRYPTIC,
		ClueTypes.EMOTE, ClueTypes.FAIRY_RING, ClueTypes.FALO, ClueTypes.MAP, ClueTypes.MUSIC, ClueTypes.SKILL);
	static final int RANDOM_LIST_LENGTH = 6;

	private static final String CLUES_PACKAGE = "net.runelite.client.plugins.cluescrolls.clues.";
	private static final List<String> LIST_TYPES = List.of("CrypticClue", "AnagramClue", "CipherClue", "CoordinateClue",
		"EmoteClue", "MapClue", "BeginnerMapClue", "FairyRingClue", "MusicClue", "FaloTheBardClue", "SkillChallengeClue");

	/** One core clue with what Clue Helper reads from it. */
	static final class Mock
	{
		final ClueScroll clue;
		final String key;
		final String type;
		final String tier;
		final String text;
		/** Null for a step without a tile. */
		final WorldPoint target;

		Mock(ClueScroll clue, Step step)
		{
			this.clue = clue;
			this.key = step.getKey();
			this.type = step.getType();
			this.tier = step.getTier();
			this.text = step.getText() == null ? step.getKey() : step.getText();
			this.target = step.getTargets().isEmpty() ? null : step.getTargets().get(0);
		}

		String describe()
		{
			return (tier == null ? "?" : tier) + " " + type + "\n" + key + "\n" + text;
		}
	}

	private final Client client;
	private final Supplier<ClueScrollPlugin> core;
	private final StepResolver resolver;
	private final Consumer<String> shown;
	private final Random random = new Random();
	private List<Mock> catalog;
	private List<String> listIds = List.of();
	private List<Mock> list = List.of();
	private int position = -1;

	private final Consumer<List<String>> listFilled;

	/**
	 * @param shown gets each mocked clue's description, or null once cleared
	 * @param listFilled gets the step IDs of a random list, for the list field
	 */
	MockClues(Client client, Supplier<ClueScrollPlugin> core, StepResolver resolver, Consumer<String> shown,
		Consumer<List<String>> listFilled)
	{
		this.client = client;
		this.core = core;
		this.resolver = resolver;
		this.shown = shown;
		this.listFilled = listFilled;
	}

	/** Client thread, from ::ch-mock [random [tier] [type] | next | prev | off | step ID]. */
	void run(String[] arguments)
	{
		String argument = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
		switch (argument)
		{
			case "":
				say("::" + COMMAND + " random [tier] [type], next, prev, off, or a step ID");
				return;
			case "random":
				random(arguments.length > 1 ? arguments[1] : ANY, arguments.length > 2 ? arguments[2] : ANY);
				return;
			case "next":
				walk(listIds, 1);
				return;
			case "prev":
				walk(listIds, -1);
				return;
			case "off":
				clear();
				return;
			default:
				walk(List.of(String.join(" ", arguments)), 1);
		}
	}

	/** Client thread. A single clue; the list keeps its place. */
	void random(String tier, String type)
	{
		List<Mock> pool = pool(tier, type);
		if (pool.isEmpty())
		{
			say("No " + tier + " " + type + " clue to mock");
			return;
		}
		show(pool.get(random.nextInt(pool.size())), "");
	}

	/** Client thread. Fills the list with random step IDs and starts it. */
	void randomList(String tier, String type)
	{
		List<Mock> pool = new ArrayList<>(pool(tier, type));
		if (pool.isEmpty())
		{
			say("No " + tier + " " + type + " clue to mock");
			return;
		}
		Collections.shuffle(pool, random);
		List<String> ids = pool.subList(0, Math.min(RANDOM_LIST_LENGTH, pool.size())).stream().map(mock -> mock.key)
			.collect(Collectors.toList());
		listFilled.accept(ids);
		walk(ids, 1);
	}

	/**
	 * Client thread. Steps through {@code ids}, one step ID per entry: from its first (or last, going back) when it differs
	 * from the list walked so far. An ID that matches no step whole is looked for as a piece of a step ID or text.
	 */
	void walk(List<String> ids, int by)
	{
		List<String> wanted = ids.stream().map(String::trim).filter(id -> !id.isEmpty()).collect(Collectors.toList());
		if (wanted.isEmpty())
		{
			say("No step IDs in the list");
			return;
		}
		if (!wanted.equals(listIds))
		{
			listIds = List.copyOf(wanted);
			list = resolve(wanted);
			position = by > 0 ? -1 : list.size();
		}
		if (list.isEmpty())
		{
			return;
		}
		position = Math.floorMod(position + by, list.size());
		show(list.get(position), (position + 1) + " / " + list.size());
	}

	/** Client thread. Every clue core's lists hold. */
	List<Mock> all()
	{
		return catalog();
	}

	/** Client thread. One clue, outside the list. */
	void mock(Mock mock, String where)
	{
		show(mock, where);
	}

	/** Client thread. */
	void clear()
	{
		invoke("resetClue", boolean.class, true);
		position = listIds.isEmpty() ? -1 : position;
		shown.accept(null);
		say("Mock clue cleared");
	}

	private List<Mock> resolve(List<String> ids)
	{
		List<Mock> found = new ArrayList<>();
		for (String id : ids)
		{
			Mock mock = find(id);
			if (mock == null)
			{
				say("No step matches " + id);
			}
			else
			{
				found.add(mock);
			}
		}
		return List.copyOf(found);
	}

	private void show(Mock mock, String where)
	{
		invoke("updateClue", ClueScroll.class, mock.clue);
		say("Mock clue " + (where.isEmpty() ? "" : where + " ") + mock.key);
		log.info("[CH-MOCK] {} {} {}", mock.key, mock.tier, where);
		shown.accept((where.isEmpty() ? "" : where + "  ") + mock.describe());
	}

	private List<Mock> pool(String tier, String type)
	{
		return catalog().stream()
			.filter(mock -> ANY.equalsIgnoreCase(tier) || tier.equalsIgnoreCase(mock.tier))
			.filter(mock -> ANY.equalsIgnoreCase(type) || type.equalsIgnoreCase(mock.type))
			.collect(Collectors.toList());
	}

	private Mock find(String id)
	{
		String lower = id.toLowerCase(Locale.ROOT);
		Mock whole = catalog().stream().filter(mock -> mock.key.equalsIgnoreCase(id)).findFirst().orElse(null);
		return whole != null ? whole : catalog().stream()
			.filter(mock -> mock.key.toLowerCase(Locale.ROOT).contains(lower) || mock.text.toLowerCase(Locale.ROOT).contains(lower))
			.findFirst().orElse(null);
	}

	/** Built on first use: resolving reads core's providers, which need the client thread. */
	private List<Mock> catalog()
	{
		if (catalog == null)
		{
			List<Mock> mocks = new ArrayList<>();
			for (String type : LIST_TYPES)
			{
				for (Object entry : coreList(type))
				{
					Step step = resolver.resolve((ClueScroll) entry, core.get());
					if (step != null && step.getKey() != null && !step.getKey().startsWith("unreadable"))
					{
						mocks.add(new Mock((ClueScroll) entry, step));
					}
				}
			}
			catalog = List.copyOf(mocks);
			log.info("[CH-MOCK] {} clues to mock", catalog.size());
		}
		return catalog;
	}

	private static Collection<?> coreList(String type)
	{
		try
		{
			Field field = Class.forName(CLUES_PACKAGE + type).getDeclaredField("CLUES");
			field.setAccessible(true);
			Object clues = field.get(null);
			return clues instanceof Map ? ((Map<?, ?>) clues).values() : (Collection<?>) clues;
		}
		catch (ReflectiveOperationException e)
		{
			log.warn("[CH-MOCK] can't read core's {} list", type, e);
			return List.of();
		}
	}

	private void invoke(String method, Class<?> parameter, Object argument)
	{
		try
		{
			Method m = ClueScrollPlugin.class.getDeclaredMethod(method, parameter);
			m.setAccessible(true);
			m.invoke(core.get(), argument);
		}
		catch (ReflectiveOperationException e)
		{
			log.warn("[CH-MOCK] core's {} failed", method, e);
		}
	}

	private void say(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
	}
}

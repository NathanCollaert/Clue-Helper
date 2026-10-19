package com.cluehelper.step;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.NPCComposition;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.HotColdClue;

/**
 * Offline access to core's clue data in the client jar this project builds against: the
 * package-private CLUES lists, read by reflection, and a ClueScrollPlugin whose fake client
 * answers every varbit and varp with one settable value, so each provider branch can be
 * evaluated. Test support only.
 */
final class CoreClues
{
	static final String CLUES_PACKAGE = "net.runelite.client.plugins.cluescrolls.clues.";
	static final int[] VAR_STATES = {0, 1, 2, 3, 4, 5, 10, 100};
	static final String[] LIST_TYPES = {
		"CrypticClue", "AnagramClue", "CipherClue", "CoordinateClue", "EmoteClue", "MapClue",
		"BeginnerMapClue", "FairyRingClue", "MusicClue", "FaloTheBardClue", "SkillChallengeClue",
	};
	static final String[] HOT_COLD_CLUES = {"BEGINNER_CLUE", "MASTER_CLUE", "LEAGUE_TUTOR_BEGINNER_CLUE", "LEAGUE_TUTOR_MASTER_CLUE"};

	private static int varState;
	private static ClueScrollPlugin plugin;

	private CoreClues()
	{
	}

	static synchronized ClueScrollPlugin plugin() throws ReflectiveOperationException
	{
		if (plugin == null)
		{
			plugin = pluginWithFakeClient();
		}
		return plugin;
	}

	static void setVarState(int state)
	{
		varState = state;
	}

	/** Every entry of one CLUES list, in core's order. */
	static List<Object> entries(String type) throws ReflectiveOperationException
	{
		Object clues = staticField(CLUES_PACKAGE + type, "CLUES");
		Collection<?> values = clues instanceof Map ? ((Map<?, ?>) clues).values() : (Collection<?>) clues;
		return new ArrayList<>(values);
	}

	/**
	 * Every step core can hand out, by source: "CrypticClue#0" and so on, and "HotColdClue#NAME".
	 * The hot/cold clues are reset first, as core does when it hands one out, so they have a solver.
	 */
	static Map<String, ClueScroll> steps() throws ReflectiveOperationException
	{
		Map<String, ClueScroll> steps = new LinkedHashMap<>();
		for (String type : LIST_TYPES)
		{
			List<Object> entries = entries(type);
			for (int i = 0; i < entries.size(); i++)
			{
				steps.put(type + "#" + i, (ClueScroll) entries.get(i));
			}
		}
		for (String name : HOT_COLD_CLUES)
		{
			HotColdClue clue = (HotColdClue) staticField(CLUES_PACKAGE + "HotColdClue", name);
			clue.reset();
			steps.put("HotColdClue#" + name, clue);
		}
		return steps;
	}

	static Object staticField(String className, String fieldName) throws ReflectiveOperationException
	{
		Field field = Class.forName(className).getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(null);
	}

	/** Cipher clues name their NPC through the client's NPC definitions. */
	private static NPCComposition npcNamed(String name)
	{
		return (NPCComposition) Proxy.newProxyInstance(NPCComposition.class.getClassLoader(), new Class<?>[]{NPCComposition.class},
			(proxy, method, methodArgs) -> method.getName().equals("getName") ? name : null);
	}

	private static ClueScrollPlugin pluginWithFakeClient() throws ReflectiveOperationException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, methodArgs) ->
			{
				switch (method.getName())
				{
					case "getVarbitValue":
					case "getVarpValue":
						return varState;
					case "getIntStack":
						return new int[]{varState, varState, varState};
					case "getNpcDefinition":
						return npcNamed("npc " + methodArgs[0]);
					case "toString":
						return "FakeClient";
					case "hashCode":
						return 0;
					case "equals":
						return proxy == methodArgs[0];
				}
				Class<?> type = method.getReturnType();
				if (type == boolean.class)
				{
					return false;
				}
				if (type.isPrimitive() && type != void.class)
				{
					return type == long.class ? 0L : type == float.class ? 0f : type == double.class ? 0d : 0;
				}
				return null;
			});
		ClueScrollPlugin fake = new ClueScrollPlugin();
		Field clientField = ClueScrollPlugin.class.getDeclaredField("client");
		clientField.setAccessible(true);
		clientField.set(fake, client);
		return fake;
	}
}

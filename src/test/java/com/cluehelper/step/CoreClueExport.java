package com.cluehelper.step;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;

/**
 * Writes core's clue data to JSON for Clue-Helper-Tools/tools/cache/c1_match.py and
 * Clue-Helper-Tools/tools/steps/generate.py. Provider lambdas run under every var state in
 * {@link CoreClues#VAR_STATES}, so varbit- or quest-dependent entries list all their branches. Run: gradlew coreClues.
 */
public class CoreClueExport
{
	private static final String CLUES_PACKAGE = CoreClues.CLUES_PACKAGE;

	private static ClueScrollPlugin fakePlugin;

	public static void main(String[] args) throws Exception
	{
		Path out = Paths.get(args.length > 0 ? args[0] : "Clue-Helper-Tools/tools/cache/out/core-clues.json");
		fakePlugin = CoreClues.plugin();

		JsonObject root = new JsonObject();
		for (String type : CoreClues.LIST_TYPES)
		{
			JsonArray array = new JsonArray();
			for (Object clue : CoreClues.entries(type))
			{
				array.add(withStepKey(describe(clue, 0).getAsJsonObject(), (ClueScroll) clue));
			}
			root.add(type, array);
			System.out.println(type + ": " + array.size());
		}
		root.add("HotColdLocation", enumValues(CLUES_PACKAGE + "hotcold.HotColdLocation"));
		root.add("STASHUnit", enumValues(CLUES_PACKAGE + "emote.STASHUnit"));
		root.add("HotColdClue", hotColdClues());

		Files.createDirectories(out.getParent());
		try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8))
		{
			new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(root, writer);
		}
		System.out.println("wrote " + out.toAbsolutePath());
	}

	private static JsonArray enumValues(String className) throws Exception
	{
		JsonArray array = new JsonArray();
		for (Object constant : Class.forName(className).getEnumConstants())
		{
			JsonObject described = describe(constant, 0).getAsJsonObject();
			described.addProperty("name", ((Enum<?>) constant).name());
			array.add(described);
		}
		return array;
	}

	private static JsonArray hotColdClues() throws Exception
	{
		JsonArray array = new JsonArray();
		for (String name : CoreClues.HOT_COLD_CLUES)
		{
			Object clue = CoreClues.staticField(CLUES_PACKAGE + "HotColdClue", name);
			JsonObject described = withStepKey(describe(clue, 0).getAsJsonObject(), (ClueScroll) clue);
			described.addProperty("name", name);
			array.add(described);
		}
		return array;
	}

	/** The step index generator joins on this key, taken under var state 0. */
	private static JsonObject withStepKey(JsonObject described, ClueScroll clue)
	{
		described.addProperty("stepKey", StepKey.of(CoreClueReader.facts(clue, fakePlugin)));
		return described;
	}

	private static JsonElement describe(Object value, int depth)
	{
		if (value == null)
		{
			return JsonNull.INSTANCE;
		}
		if (value instanceof Number)
		{
			return new JsonPrimitive((Number) value);
		}
		if (value instanceof Boolean)
		{
			return new JsonPrimitive((Boolean) value);
		}
		if (value instanceof CharSequence || value instanceof Character)
		{
			return new JsonPrimitive(value.toString());
		}
		if (value instanceof WorldPoint)
		{
			WorldPoint point = (WorldPoint) value;
			JsonArray xyz = new JsonArray();
			xyz.add(point.getX());
			xyz.add(point.getY());
			xyz.add(point.getPlane());
			return xyz;
		}
		if (value instanceof Enum && depth > 0)
		{
			return new JsonPrimitive(((Enum<?>) value).name());
		}
		if (value instanceof Function)
		{
			return evaluateProvider((Function<?, ?>) value, depth);
		}
		if (value instanceof Map)
		{
			JsonArray pairs = new JsonArray();
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
			{
				JsonArray pair = new JsonArray();
				pair.add(describe(entry.getKey(), depth + 1));
				pair.add(describe(entry.getValue(), depth + 1));
				pairs.add(pair);
			}
			return pairs;
		}
		if (value instanceof Collection)
		{
			JsonArray array = new JsonArray();
			for (Object element : (Collection<?>) value)
			{
				array.add(describe(element, depth + 1));
			}
			return array;
		}
		if (value.getClass().isArray())
		{
			JsonArray array = new JsonArray();
			for (int i = 0; i < Array.getLength(value); i++)
			{
				array.add(describe(Array.get(value, i), depth + 1));
			}
			return array;
		}
		if (isCoreClueType(value.getClass()) && depth < 6)
		{
			return describeFields(value, depth);
		}
		return new JsonPrimitive(value.toString());
	}

	private static boolean isCoreClueType(Class<?> type)
	{
		return type.getName().startsWith("net.runelite.client.plugins.cluescrolls");
	}

	private static JsonObject describeFields(Object value, int depth)
	{
		JsonObject object = new JsonObject();
		object.addProperty("class", value.getClass().getSimpleName());
		for (Class<?> type = value.getClass(); type != null && type != Object.class && type != Enum.class; type = type.getSuperclass())
		{
			for (Field field : type.getDeclaredFields())
			{
				if (Modifier.isStatic(field.getModifiers()) || object.has(field.getName()))
				{
					continue;
				}
				try
				{
					field.setAccessible(true);
					object.add(field.getName(), describe(field.get(value), depth + 1));
				}
				catch (ReflectiveOperationException | RuntimeException e)
				{
					object.addProperty(field.getName(), "<unreadable: " + e + ">");
				}
			}
		}
		return object;
	}

	@SuppressWarnings("unchecked")
	private static JsonElement evaluateProvider(Function<?, ?> provider, int depth)
	{
		Set<String> seen = new LinkedHashSet<>();
		List<JsonElement> branches = new ArrayList<>();
		for (int state : CoreClues.VAR_STATES)
		{
			CoreClues.setVarState(state);
			JsonElement result;
			try
			{
				result = describe(((Function<Object, Object>) provider).apply(fakePlugin), depth + 1);
			}
			catch (RuntimeException e)
			{
				result = new JsonPrimitive("<threw " + e.getClass().getSimpleName() + ">");
			}
			if (seen.add(result.toString()))
			{
				branches.add(result);
			}
		}
		CoreClues.setVarState(0);
		JsonObject object = new JsonObject();
		JsonArray array = new JsonArray();
		branches.forEach(array::add);
		object.add("branches", array);
		return object;
	}
}

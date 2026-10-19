package com.cluehelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * House rules checked on the source and class files, without loading any plugin class: what a
 * hub reviewer would flag, and the config-panel crash a nested config enum causes.
 */
public class ConventionsTest
{
	private static final Path MAIN_SOURCES = Paths.get("src/main/java");
	private static final Path MAIN_CLASSES = Paths.get("build/classes/java/main");
	private static final Path CONFIG_SOURCE = MAIN_SOURCES.resolve("com/cluehelper/ClueHelperConfig.java");

	private static final Map<String, Pattern> BANNED = new LinkedHashMap<>();

	static
	{
		BANNED.put("java.io file APIs (load data with getResourceAsStream)",
			Pattern.compile("\\bjava\\.io\\.(File|RandomAccessFile)\\w*|\\bnew\\s+File(InputStream|OutputStream|Reader|Writer)?\\s*\\("));
		BANNED.put("java.nio.file", Pattern.compile("\\bjava\\.nio\\.file\\b"));
		BANNED.put("getResource (use getResourceAsStream)", Pattern.compile("\\.getResource\\s*\\("));
		BANNED.put("Thread.sleep", Pattern.compile("\\bThread\\.sleep\\b"));
		BANNED.put("Thread interrupt", Pattern.compile("\\.interrupt\\s*\\("));
		BANNED.put("new Gson (inject the client's Gson)", Pattern.compile("\\bnew\\s+Gson(Builder)?\\s*\\("));
		BANNED.put("reflection",
			Pattern.compile("\\bjava\\.lang\\.reflect\\b|\\.getDeclared(Field|Method|Constructor)s?\\s*\\(|\\.setAccessible\\s*\\("));
	}

	private static final Set<String> M1_CONFIG_KEYS = new TreeSet<>(Arrays.asList(
		"routeCount", "consumablePenalty", "avoidWilderness",
		"highlightItems", "highlightSpells", "highlightMenus",
		"pathLine", "minimapMarker", "worldMapMarker",
		"spellbookChanges", "capeUsePenalty", "strandPenalty",
		"diggingDog", "xericsHonourUnlocked"));

	private static final Set<String> PLAIN_CONFIG_TYPES = new TreeSet<>(Arrays.asList("int", "boolean", "String", "double", "long"));

	@Test
	public void mainSourcesUseNoBannedApis() throws IOException
	{
		List<String> hits = new ArrayList<>();
		for (Path file : javaFiles(MAIN_SOURCES))
		{
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				for (Map.Entry<String, Pattern> rule : BANNED.entrySet())
				{
					if (rule.getValue().matcher(lines.get(i)).find())
					{
						hits.add(MAIN_SOURCES.relativize(file) + ":" + (i + 1) + " " + rule.getKey() + ": " + lines.get(i).trim());
					}
				}
			}
		}
		assertEquals("banned APIs in src/main:\n" + String.join("\n", hits), 0, hits.size());
	}

	/** These change core's shared state, which every overlay of core reads (B07). Safe only offline. */
	private static final Pattern MUTATING_CORE_CALL = Pattern.compile(
		"\\.(forText|forChatboxText|update|reset|resetClue|setChallengeCompleted|makeOverlayHint|makeWorldOverlayHint|makeSTASHOverlay)\\s*\\(");
	private static final String CORE_CLUE_PACKAGE = "import net.runelite.client.plugins.cluescrolls";

	@Test
	public void mainSourcesNeverCallMutatingCoreClueMethods() throws IOException
	{
		List<String> hits = new ArrayList<>();
		for (Path file : javaFiles(MAIN_SOURCES))
		{
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			if (lines.stream().noneMatch(line -> line.startsWith(CORE_CLUE_PACKAGE)))
			{
				continue;
			}
			for (int i = 0; i < lines.size(); i++)
			{
				if (MUTATING_CORE_CALL.matcher(lines.get(i)).find())
				{
					hits.add(MAIN_SOURCES.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).trim());
				}
			}
		}
		assertEquals("calls that change the Clue Scroll plugin's state:\n" + String.join("\n", hits), 0, hits.size());
	}

	@Test
	public void configHasEveryM1Key() throws IOException
	{
		Set<String> keys = new TreeSet<>();
		Matcher m = Pattern.compile("keyName\\s*=\\s*\"(\\w+)\"").matcher(configSource());
		while (m.find())
		{
			keys.add(m.group(1));
		}
		assertEquals(M1_CONFIG_KEYS, keys);
	}

	@Test
	public void configEnumsArePublicTopLevelTypes() throws IOException
	{
		String config = configSource();
		Matcher m = Pattern.compile("default\\s+(\\w+)\\s+\\w+\\s*\\(\\)").matcher(config);
		int enums = 0;
		while (m.find())
		{
			String type = m.group(1);
			if (PLAIN_CONFIG_TYPES.contains(type))
			{
				continue;
			}
			Path source = CONFIG_SOURCE.resolveSibling(type + ".java");
			assertTrue(type + " has its own source file next to the config", Files.exists(source));
			assertTrue(type + " is declared 'public enum'", Files.readString(source).contains("public enum " + type));
			enums++;
		}
		assertEquals("avoidWilderness, spellbookChanges, diggingDog", 3, enums);
	}

	@Test
	public void configCompilesToNoNestedClasses() throws IOException
	{
		Path dir = MAIN_CLASSES.resolve("com/cluehelper");
		assertTrue("compiled classes present", Files.isDirectory(dir));
		try (Stream<Path> files = Files.list(dir))
		{
			List<String> nested = files.map(p -> p.getFileName().toString())
				.filter(name -> name.startsWith("ClueHelperConfig$"))
				.collect(Collectors.toList());
			assertEquals(List.of(), nested);
		}
	}

	private static String configSource() throws IOException
	{
		return Files.readString(CONFIG_SOURCE);
	}

	private static List<Path> javaFiles(Path root) throws IOException
	{
		try (Stream<Path> files = Files.walk(root))
		{
			return files.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
	}
}

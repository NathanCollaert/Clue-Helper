package com.cluehelper.step;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Gson fills this from the generated step files (tools/steps/generate.py), one per clue type: field names are their
 * JSON keys. Fields the plugin doesn't use yet (outfits, requirement groups) stay in the files only. Keys iterate in
 * sorted order, which the golden routes' sample depends on.
 */
@Slf4j
public final class StepIndex
{
	static final String DIRECTORY = "steps/";
	static final List<String> FILES = List.of("anagram", "cipher", "coordinate", "cryptic", "emote", "fairyring", "falo",
		"hotcold", "map", "music", "skill");

	private String dump;
	private Map<String, Entry> steps = Map.of();
	private Map<String, Spot> hotColdSpots = Map.of();

	public static StepIndex read(Gson gson)
	{
		StepIndex all = new StepIndex();
		Map<String, Entry> steps = new HashMap<>();
		Map<String, Spot> spots = new HashMap<>();
		for (String file : FILES)
		{
			String resource = DIRECTORY + file + ".json";
			try (InputStream in = StepIndex.class.getResourceAsStream(resource))
			{
				if (in == null)
				{
					log.warn("{} is missing: its steps resolve as unknown", resource);
					continue;
				}
				StepIndex part = read(gson, new InputStreamReader(in, StandardCharsets.UTF_8));
				steps.putAll(part.getSteps());
				spots.putAll(part.getHotColdSpots());
				all.dump = part.dump;
			}
			catch (IOException | JsonParseException e)
			{
				log.warn("{} is unreadable: its steps resolve as unknown", resource, e);
			}
		}
		all.steps = Collections.unmodifiableMap(new TreeMap<>(steps));
		all.hotColdSpots = Collections.unmodifiableMap(new TreeMap<>(spots));
		return all;
	}

	static StepIndex read(Gson gson, Reader reader)
	{
		StepIndex index = gson.fromJson(reader, StepIndex.class);
		return index == null ? empty() : index;
	}

	public static StepIndex empty()
	{
		return new StepIndex();
	}

	public Entry entry(String key)
	{
		return steps == null ? null : steps.get(key);
	}

	public Map<String, Entry> getSteps()
	{
		return steps == null ? Map.of() : steps;
	}

	public Map<String, Spot> getHotColdSpots()
	{
		return hotColdSpots == null ? Map.of() : hotColdSpots;
	}

	/** The cache dump the data was generated from, e.g. "2026-10-18". */
	public String getDump()
	{
		return dump;
	}

	public static final class Entry
	{
		private String tier;
		private List<CacheRow> rows;
		private List<int[]> tiles;
		private List<Target> targets;
		private List<Combat> combat;
		private Challenge challenge;
		private Music music;
		private Stash stash;
		private Area area;
		private int[] stand;
		private List<Requires> requires;
		private WayIn entry;
		private Reach reach;
		private List<Place> places;
		private List<Place> sources;
		private String floor;
		private String note;
		private boolean hidden;
		private List<String> manual;

		static Entry of(String tier, List<CacheRow> rows)
		{
			Entry entry = new Entry();
			entry.tier = tier;
			entry.rows = List.copyOf(rows);
			entry.tiles = List.of();
			return entry;
		}

		public String getTier()
		{
			return tier;
		}

		public List<CacheRow> getRows()
		{
			return rows == null ? List.of() : rows;
		}

		/** Each tile as x, y, plane. */
		public List<int[]> getTiles()
		{
			return tiles == null ? List.of() : tiles;
		}

		public List<Target> getTargets()
		{
			return targets == null ? List.of() : targets;
		}

		public List<Combat> getCombat()
		{
			return combat == null ? List.of() : combat;
		}

		public Challenge getChallenge()
		{
			return challenge;
		}

		public Music getMusic()
		{
			return music;
		}

		public Stash getStash()
		{
			return stash;
		}

		/** Hand data: where a step with no tile (a kill task) is done. */
		public Area getArea()
		{
			return area;
		}

		/** Hand data: a walkable tile beside a blocked target tile; null when the target is walkable. */
		public WorldPoint getStand()
		{
			return point(stand);
		}

		public List<Requires> getRequires()
		{
			return requires == null ? List.of() : requires;
		}

		public WayIn getEntry()
		{
			return entry;
		}

		/** Hand data: how to get to a target no route reaches (a Sailing island); null for the rest. */
		public String getReach()
		{
			return reach == null ? null : reach.text;
		}

		public List<Place> getPlaces()
		{
			return places == null ? List.of() : places;
		}

		/** True when hand data says where the task is done; an empty list then means anywhere. */
		public boolean hasPlaces()
		{
			return places != null;
		}

		public List<Place> getSources()
		{
			return sources == null ? List.of() : sources;
		}

		public String getFloor()
		{
			return floor;
		}

		public String getNote()
		{
			return note;
		}

		/** Quest steps: never guided. */
		public boolean isHidden()
		{
			return hidden;
		}

		/** The fields hand data set; empty for a fully generated entry. */
		public List<String> getManual()
		{
			return manual == null ? List.of() : manual;
		}
	}

	/** A cache target: coord, npc, loc, key (kill an NPC for a key, then search) or kill. */
	public static final class Target
	{
		private String kind;
		private int[] tile;
		private List<Integer> npcs;
		private Integer loc;
		private Integer key;
		private int[] keyTile;
		private String text;

		public String getKind()
		{
			return kind;
		}

		public WorldPoint getTile()
		{
			return point(tile);
		}

		public List<Integer> getNpcs()
		{
			return npcs == null ? List.of() : npcs;
		}

		public int getLoc()
		{
			return loc == null ? -1 : loc;
		}

		public int getKey()
		{
			return key == null ? -1 : key;
		}

		/** Where the key's NPCs are killed. */
		public WorldPoint getKeyTile()
		{
			return point(keyTile);
		}

		public String getText()
		{
			return text;
		}
	}

	public static final class Combat
	{
		private String text;
		private List<Integer> npcs;

		public String getText()
		{
			return text;
		}

		public List<Integer> getNpcs()
		{
			return npcs == null ? List.of() : npcs;
		}
	}

	/** Either a question with its answer, or a puzzle or light box. */
	public static final class Challenge
	{
		private String question;
		private String box;

		public String getQuestion()
		{
			return question;
		}

		public String getBox()
		{
			return box;
		}
	}

	public static final class Music
	{
		private String track;
		private String unlock;

		public String getTrack()
		{
			return track;
		}

		/** Where the track unlocks. */
		public String getUnlock()
		{
			return unlock;
		}
	}

	public static final class Stash
	{
		private int object;
		private int[] tile;

		public int getObject()
		{
			return object;
		}

		public WorldPoint getTile()
		{
			return point(tile);
		}
	}

	public static final class Area
	{
		private int[] tile;
		private int radius;
		private String text;

		public WorldPoint getTile()
		{
			return point(tile);
		}

		public int getRadius()
		{
			return radius;
		}

		public String getText()
		{
			return text;
		}
	}

	/** Items the way in needs; any one of {@link #getIds()} does. */
	public static final class Requires
	{
		private List<Integer> ids;
		private String name;

		public List<Integer> getIds()
		{
			return ids == null ? List.of() : ids;
		}

		public String getName()
		{
			return name;
		}
	}

	/**
	 * The way into a target's area: the object to use (-1 for none) and the box that counts as inside. The walk data
	 * holds it as a walk row (tools/walk), so routes already pass through it.
	 */
	public static final class WayIn
	{
		private int object = -1;
		private String option;
		private int[] tile;
		private int[] to;
		private int[] inside;
		private String text;
		/** The chat options to pick, in order, when the way in is a conversation (Shantay's arrest). */
		private List<String> dialogue;

		/** Where the way in lands. */
		public WorldPoint getTo()
		{
			return point(to);
		}

		public int getObject()
		{
			return object;
		}

		public String getOption()
		{
			return option;
		}

		public WorldPoint getTile()
		{
			return point(tile);
		}

		public String getText()
		{
			return text;
		}

		public List<String> getDialogue()
		{
			return dialogue == null ? List.of() : dialogue;
		}

		public boolean isInside(WorldPoint at)
		{
			return inside != null && at != null && at.getPlane() == inside[4] && at.getX() >= inside[0] && at.getY() >= inside[1]
				&& at.getX() <= inside[2] && at.getY() <= inside[3];
		}
	}

	private static final class Reach
	{
		private String text;
	}

	public static final class Place
	{
		private int[] tile;
		private String text;
		private List<Requires> tools;

		/** For an item source: what gathering it takes. */
		public List<Requires> getTools()
		{
			return tools == null ? List.of() : tools;
		}

		public WorldPoint getTile()
		{
			return point(tile);
		}

		public String getText()
		{
			return text;
		}
	}

	public static final class Spot
	{
		private int row;
		private String name;
		private int[] tile;

		/** The row's centre as x, y, plane; null in data generated before B19. */
		public int[] getTile()
		{
			return tile;
		}

		public int getRow()
		{
			return row;
		}

		public String getName()
		{
			return name;
		}
	}

	private static WorldPoint point(int[] tile)
	{
		return tile == null || tile.length < 3 ? null : new WorldPoint(tile[0], tile[1], tile[2]);
	}
}

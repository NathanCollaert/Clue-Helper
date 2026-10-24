package com.cluehelper.poh;

import com.cluehelper.memory.MemorySlot;
import com.cluehelper.memory.MemoryStore;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.Position;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.transport.Catalog;
import com.cluehelper.transport.CatalogData;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.HouseNeed;
import com.cluehelper.transport.SpellbookChange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;

/** Client thread only, except {@link #getHouse()}. */
@Slf4j
@Singleton
public class PohService
{
	static final String PROFILE_KEY = "poh";
	/** Every piece has loaded two ticks after entering; later scans never found more. */
	static final int SCAN_DELAY_TICKS = 2;
	static final int[] NEXUS_SLOTS = {VarbitID.POH_NEXUS_TELE_1, VarbitID.POH_NEXUS_TELE_2, VarbitID.POH_NEXUS_TELE_3,
		VarbitID.POH_NEXUS_TELE_4, VarbitID.POH_NEXUS_TELE_5, VarbitID.POH_NEXUS_TELE_6, VarbitID.POH_NEXUS_TELE_7,
		VarbitID.POH_NEXUS_TELE_8, VarbitID.POH_NEXUS_TELE_9, VarbitID.POH_NEXUS_TELE_10, VarbitID.POH_NEXUS_TELE_11,
		VarbitID.POH_NEXUS_TELE_12, VarbitID.POH_NEXUS_TELE_13, VarbitID.POH_NEXUS_TELE_14, VarbitID.POH_NEXUS_TELE_15,
		VarbitID.POH_NEXUS_TELE_16, VarbitID.POH_NEXUS_TELE_17, VarbitID.POH_NEXUS_TELE_18, VarbitID.POH_NEXUS_TELE_19,
		VarbitID.POH_NEXUS_TELE_20, VarbitID.POH_NEXUS_TELE_21, VarbitID.POH_NEXUS_TELE_22, VarbitID.POH_NEXUS_TELE_23,
		VarbitID.POH_NEXUS_TELE_24, VarbitID.POH_NEXUS_TELE_25, VarbitID.POH_NEXUS_TELE_26, VarbitID.POH_NEXUS_TELE_27,
		VarbitID.POH_NEXUS_TELE_28, VarbitID.POH_NEXUS_TELE_29, VarbitID.POH_NEXUS_TELE_30, VarbitID.POH_NEXUS_TELE_31,
		VarbitID.POH_NEXUS_TELE_32, VarbitID.POH_NEXUS_TELE_33, VarbitID.POH_NEXUS_TELE_34, VarbitID.POH_NEXUS_TELE_35,
		VarbitID.POH_NEXUS_TELE_36, VarbitID.POH_NEXUS_TELE_37, VarbitID.POH_NEXUS_TELE_38, VarbitID.POH_NEXUS_TELE_39,
		VarbitID.POH_NEXUS_TELE_40, VarbitID.POH_NEXUS_TELE_41, VarbitID.POH_NEXUS_TELE_42, VarbitID.POH_NEXUS_TELE_43,
		VarbitID.POH_NEXUS_TELE_44, VarbitID.POH_NEXUS_TELE_45};
	static final int NEXUS_LINE_SCRIPT = 2675;
	/** The script's int arguments: [index, 10, y, struct id, line number, 1, parity, component]. */
	static final int NEXUS_LINE_ARGS = 8;
	private static final int NEXUS_ARG_STRUCT = 3;
	private static final int NEXUS_ARG_LINE_NUMBER = 4;
	static final int[] SETTINGS = {VarbitID.POH_NEXUS_XERIC, VarbitID.POH_NEXUS_DIGSITE, VarbitID.POH_JEWELLERYBOX_MULTI,
		VarbitID.POH_HOUSE_LOCATION, VarbitID.POH_TELE_TOGGLE};
	private static final int[] BOXES = {ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_2, ObjectID.POH_JEWELLERY_BOX_3};
	/** Box lines 1–9 are on every box, A–J from the fancy one, K–R only on the ornate one; varbit 2308 is 1-based. */
	private static final int FANCY_FIRST_LINE = 10;
	private static final int ORNATE_FIRST_LINE = 20;
	private static final int XERIC_FIRST = ObjectID.POH_AMULET_XERIC_LOOKOUT;
	private static final int DIGSITE_FIRST = ObjectID.POH_AMULET_DIG_DIGSITE;

	private final Client client;
	private final PlayerModel player;
	private final CatalogData catalogData;
	private final MemorySlot<Profile> stored;
	private final HouseOwnership ownership = new HouseOwnership(System::currentTimeMillis);
	private final Set<Integer> watched = new HashSet<>();
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

	private volatile Set<Integer> pieces = Set.of();
	private volatile HouseState house = HouseState.NONE;
	private boolean seenATick;
	private boolean wasInside;
	private int scanAtTick = -1;
	private boolean varsDirty = true;
	private boolean houseDirty = true;
	private boolean nexusLinesDirty;

	@Inject
	PohService(Client client, PlayerModel player, CatalogData catalogData, MemoryStore memory)
	{
		this.client = client;
		this.player = player;
		this.catalogData = catalogData;
		this.stored = memory.slot(PROFILE_KEY, Profile.class, Profile::new);
		for (int id : NEXUS_SLOTS)
		{
			watched.add(id);
		}
		for (int id : SETTINGS)
		{
			watched.add(id);
		}
		watched.add(VarbitID.POH_BUILDING_MODE);
	}

	public void start()
	{
		catalogData.get().thenAccept(this::learnPieces);
		seenATick = false;
		wasInside = false;
		varsDirty = true;
		houseDirty = true;
	}

	public void stop()
	{
		scanAtTick = -1;
	}

	/** Any thread. */
	public HouseState getHouse()
	{
		return house;
	}

	public HouseOwnership.Ownership getOwnership()
	{
		return ownership.getOwnership();
	}

	public boolean isInHouse()
	{
		return ownership.isInHouse();
	}

	/** Listeners get the log lines on the client thread. */
	public Runnable addListener(Consumer<String> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		ownership.observeClick(event.getMenuOption(), event.getMenuTarget());
	}

	/** Script 2675 draws each nexus line with its struct id and line number, which is its hotkey. */
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != NEXUS_LINE_SCRIPT || client.getIntStackSize() < NEXUS_LINE_ARGS)
		{
			return;
		}
		int[] stack = client.getIntStack();
		int base = client.getIntStackSize() - NEXUS_LINE_ARGS;
		int struct = stack[base + NEXUS_ARG_STRUCT];
		int line = stack[base + NEXUS_ARG_LINE_NUMBER];
		Profile profile = stored.get();
		if (!Integer.valueOf(line).equals(profile.nexusLines.get(struct)))
		{
			profile.nexusLines.put(struct, line);
			nexusLinesDirty = true;
		}
	}

	public void onVarbitChanged(VarbitChanged event)
	{
		if (watched.contains(event.getVarbitId()))
		{
			varsDirty = true;
		}
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			varsDirty = true;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			ownership.left();
			wasInside = false;
			scanAtTick = -1;
		}
	}

	/** Call on {@code RuneScapeProfileChanged}, after the store reloads. */
	public void onProfileChanged()
	{
		varsDirty = true;
		houseDirty = true;
	}

	public void forget()
	{
		stored.clear();
		varsDirty = true;
		houseDirty = true;
		tell("forgot the learned house");
	}

	/** Call from a GameTick handler that runs before the player model's. */
	public void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		followHouseEntry();
		scanWhenDue();
		readVarsWhenDirty();
		saveNexusLinesWhenLearned();
		publish();
	}

	/** Whose house the player is in follows the entry, the exit and build mode. */
	private void followHouseEntry()
	{
		boolean buildMode = client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 1;
		boolean inside = player.getPosition().getKind() == Position.Kind.POH;
		if (inside && !wasInside)
		{
			if (seenATick)
			{
				ownership.entered(buildMode);
			}
			else
			{
				ownership.startedInside(buildMode);
			}
			scanAtTick = client.getTickCount() + SCAN_DELAY_TICKS;
			tell("entered a house: " + ownership.getOwnership() + (ownership.getEntryClick() == null ? "" : " (" + ownership.getEntryClick() + ")"));
		}
		else if (!inside && wasInside)
		{
			ownership.left();
			scanAtTick = -1;
			tell("left the house");
		}
		else if (inside && buildMode && ownership.getOwnership() != HouseOwnership.Ownership.OWNED)
		{
			ownership.buildModeSeen();
			tell("build mode: the house is the player's");
		}
		wasInside = inside;
		seenATick = true;
	}

	/** The scan waits for the furniture to load, and only the player's own house is scanned. */
	private void scanWhenDue()
	{
		if (scanAtTick < 0 || client.getTickCount() < scanAtTick)
		{
			return;
		}
		scanAtTick = -1;
		if (ownership.getOwnership() == HouseOwnership.Ownership.OWNED)
		{
			scan();
		}
		else
		{
			tell("no scan: " + ownership.getOwnership() + " house");
		}
	}

	private void readVarsWhenDirty()
	{
		if (varsDirty)
		{
			varsDirty = false;
			readVars();
		}
	}

	private void saveNexusLinesWhenLearned()
	{
		if (nexusLinesDirty)
		{
			nexusLinesDirty = false;
			stored.set(stored.get());
			houseDirty = true;
			tell("nexus hotkeys learned: " + stored.get().nexusLines.size() + " lines");
		}
	}

	private void learnPieces(Catalog catalog)
	{
		Set<Integer> ids = new HashSet<>(HouseState.NEXUS_OBJECTS);
		for (CatalogRow row : catalog.getTeleports())
		{
			HouseNeed need = row.getRequirements().getHouse();
			if (need != null && need.getKind() == HouseNeed.Kind.FURNITURE)
			{
				ids.addAll(need.getIds());
			}
		}
		for (SpellbookChange change : catalog.getSpellbookChanges())
		{
			if (change.isInHouse() && change.getObjectId() != SpellbookChange.NO_ID)
			{
				ids.add(change.getObjectId());
			}
		}
		pieces = Set.copyOf(ids);
		houseDirty = true;
	}

	/** POH varbits read 0 during a world hop, so an all-zero read is ignored. */
	private void readVars()
	{
		Map<Integer, Integer> values = new HashMap<>();
		boolean any = false;
		for (int id : NEXUS_SLOTS)
		{
			int value = client.getVarbitValue(id);
			values.put(id, value);
			any |= value != 0;
		}
		for (int id : SETTINGS)
		{
			int value = client.getVarbitValue(id);
			values.put(id, value);
			any |= value != 0;
		}
		if (!any)
		{
			return;
		}
		Profile profile = stored.get();
		if (!values.equals(profile.vars))
		{
			profile.vars = values;
			stored.set(profile);
			tell("house varbits: " + nexusKeys(profile).size() + " nexus destinations, Xeric " + values.get(VarbitID.POH_NEXUS_XERIC)
				+ ", digsite " + values.get(VarbitID.POH_NEXUS_DIGSITE) + ", box " + values.get(VarbitID.POH_JEWELLERYBOX_MULTI));
		}
	}

	private void scan()
	{
		long started = System.nanoTime();
		WorldView view = client.getTopLevelWorldView();
		Player local = client.getLocalPlayer();
		if (view == null || local == null)
		{
			return;
		}
		Map<Integer, List<WorldPoint>> found = findPieces(view.getScene());
		SceneBfs.Result walk = walkFrom(view, local.getLocalLocation());
		Map<Integer, Integer> ticks = new HashMap<>();
		found.forEach((id, cells) ->
		{
			int tiles = cells.stream().mapToInt(cell -> walk == null ? SceneBfs.UNREACHED : walk.costNear(cell)).min()
				.orElse(SceneBfs.UNREACHED);
			if (tiles != SceneBfs.UNREACHED)
			{
				ticks.put(id, (tiles + 1) / 2);
			}
		});
		Profile profile = stored.get();
		Set<Integer> before = new HashSet<>(profile.furniture);
		profile.furniture = new ArrayList<>(found.keySet());
		profile.walkTicks = ticks;
		profile.scannedAt = System.currentTimeMillis();
		stored.set(profile);
		houseDirty = true;
		Set<Integer> added = new HashSet<>(found.keySet());
		added.removeAll(before);
		before.removeAll(found.keySet());
		tell(String.format("scan: %d pieces %s, walk ticks %s, added %s, gone %s, %.1f ms", found.size(), found.keySet(), ticks,
			added, before, (System.nanoTime() - started) / 1e6));
	}

	private Map<Integer, List<WorldPoint>> findPieces(Scene scene)
	{
		Map<Integer, List<WorldPoint>> found = new HashMap<>();
		Tile[][][] tiles = scene.getTiles();
		for (int z = 0; z < tiles.length; z++)
		{
			for (int x = 0; x < tiles[z].length; x++)
			{
				for (int y = 0; y < tiles[z][x].length; y++)
				{
					Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						continue;
					}
					for (GameObject object : tile.getGameObjects())
					{
						if (object != null && pieces.contains(object.getId()))
						{
							for (int ox = object.getSceneMinLocation().getX(); ox <= object.getSceneMaxLocation().getX(); ox++)
							{
								for (int oy = object.getSceneMinLocation().getY(); oy <= object.getSceneMaxLocation().getY(); oy++)
								{
									found.computeIfAbsent(object.getId(), k -> new ArrayList<>()).add(new WorldPoint(ox, oy, z));
								}
							}
						}
					}
					WallObject wall = tile.getWallObject();
					DecorativeObject decoration = tile.getDecorativeObject();
					GroundObject ground = tile.getGroundObject();
					for (TileObject object : new TileObject[]{wall, decoration, ground})
					{
						if (object != null && pieces.contains(object.getId()))
						{
							found.computeIfAbsent(object.getId(), k -> new ArrayList<>()).add(new WorldPoint(x, y, z));
						}
					}
				}
			}
		}
		return found;
	}

	/** Uses the instance's own collision flags; its points are scene coordinates, not world ones. */
	private static SceneBfs.Result walkFrom(WorldView view, LocalPoint at)
	{
		CollisionData[] maps = view.getCollisionMaps();
		if (maps == null || at == null)
		{
			return null;
		}
		int[][][] flags = new int[maps.length][][];
		for (int plane = 0; plane < maps.length; plane++)
		{
			flags[plane] = maps[plane] == null ? null : maps[plane].getFlags();
		}
		SceneBfs.Scene scene = new SceneBfs.Scene(0, 0, view.getSizeX(), view.getSizeY(), flags);
		return SceneBfs.run(scene, new WorldPoint(at.getSceneX(), at.getSceneY(), view.getPlane()), null, null);
	}

	private void publish()
	{
		if (!houseDirty && house.getNexusKeys().equals(nexusKeys(stored.get())))
		{
			return;
		}
		houseDirty = false;
		HouseState next = compose(stored.get());
		if (!next.equals(house))
		{
			house = next;
			player.setHouse(next);
			tell("house: " + next.getSource() + ", " + next.getFurniture().size() + " pieces, " + next.getNexusKeys().size()
				+ " nexus destinations");
		}
	}

	private HouseState compose(Profile profile)
	{
		Map<Integer, Integer> vars = profile.vars;
		int xeric = vars.getOrDefault(VarbitID.POH_NEXUS_XERIC, 0);
		int digsite = vars.getOrDefault(VarbitID.POH_NEXUS_DIGSITE, 0);
		int box = vars.getOrDefault(VarbitID.POH_JEWELLERYBOX_MULTI, 0);
		HouseState.HouseStateBuilder state = HouseState.builder().xericLeftClick(xeric).digsiteLeftClick(digsite).boxLeftClick(box)
			.walkTicks(Map.copyOf(profile.walkTicks)).scannedAt(profile.scannedAt);
		Set<Integer> furniture;
		if (profile.scannedAt > 0)
		{
			furniture = new HashSet<>(profile.furniture);
			state.source(HouseState.Source.SCAN);
		}
		else
		{
			furniture = fromVarbits(xeric, digsite, box);
			state.source(HouseState.Source.VARBITS);
		}
		Set<Integer> keys = nexusKeys(profile);
		boolean scannedWithoutNexus = profile.scannedAt > 0 && HouseState.NEXUS_OBJECTS.stream().noneMatch(furniture::contains);
		return state.furniture(Set.copyOf(furniture)).nexusKeys(scannedWithoutNexus ? Set.of() : keys)
			.nexusLines(Map.copyOf(profile.nexusLines)).build();
	}

	/**
	 * The mounted Xeric's talisman and digsite pendant follow from their left-click settings, and the jewellery box from
	 * its left-click line, which needs at least the tier that has that line.
	 */
	private static Set<Integer> fromVarbits(int xeric, int digsite, int boxLine)
	{
		Set<Integer> ids = new HashSet<>();
		if (xeric > 0)
		{
			ids.add(XERIC_FIRST + xeric - 1);
		}
		if (digsite > 0)
		{
			ids.add(DIGSITE_FIRST + digsite - 1);
		}
		if (boxLine > 0)
		{
			ids.add(BOXES[boxLine >= ORNATE_FIRST_LINE ? 2 : boxLine >= FANCY_FIRST_LINE ? 1 : 0]);
		}
		return ids;
	}

	private static Set<Integer> nexusKeys(Profile profile)
	{
		Set<Integer> keys = new HashSet<>();
		for (int id : NEXUS_SLOTS)
		{
			int key = profile.vars.getOrDefault(id, 0);
			if (key > 0)
			{
				keys.add(key);
			}
		}
		return Set.copyOf(keys);
	}

	private void tell(String line)
	{
		log.debug("poh: {}", line);
		listeners.forEach(listener -> listener.accept(line));
	}

	static final class Profile
	{
		/** By varbit id, as last read with a house present. */
		Map<Integer, Integer> vars = new HashMap<>();
		/** Base ids from the last own-house scan. */
		List<Integer> furniture = new ArrayList<>();
		Map<Integer, Integer> walkTicks = new HashMap<>();
		long scannedAt;
		/** Nexus struct id → line number, which is its hotkey. */
		Map<Integer, Integer> nexusLines = new HashMap<>();
	}
}

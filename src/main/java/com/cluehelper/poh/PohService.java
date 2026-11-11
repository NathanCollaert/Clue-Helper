package com.cluehelper.poh;

import com.cluehelper.Tables;
import com.cluehelper.guide.SceneObjects;
import com.cluehelper.memory.*;
import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.stream.*;
import javax.inject.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;

/** Client thread only, except {@link #getHouse()}. */
@Slf4j
@Singleton
public class PohService
{
	private static final String PROFILE_KEY = "poh";
	/** Every piece has loaded two ticks after entering; later scans never found more. */
	private static final int SCAN_DELAY_TICKS = 2;
	static final int[] NEXUS_SLOTS = Tables.ids("nexus slots");
	private static final int NEXUS_LINE_SCRIPT = 2675;
	/** The script's int arguments: [index, 10, y, struct id, line number, 1, parity, component]. */
	private static final int NEXUS_LINE_ARGS = 8;
	private static final int NEXUS_ARG_STRUCT = 3;
	private static final int NEXUS_ARG_LINE_NUMBER = 4;
	private static final int[] SETTINGS = Tables.ids("house settings");
	private static final int[] HOUSE_VARBITS = IntStream.concat(Arrays.stream(NEXUS_SLOTS), Arrays.stream(SETTINGS)).toArray();
	/** The varbits a house is read from. */
	private static final Set<Integer> WATCHED = IntStream.concat(Arrays.stream(HOUSE_VARBITS), IntStream.of(VarbitID.POH_BUILDING_MODE))
		.boxed().collect(Collectors.toUnmodifiableSet());
	private static final int[] BOXES = {ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_2, ObjectID.POH_JEWELLERY_BOX_3};
	/** Box lines 1–9 are on every box, A–J from the fancy one, K–R only on the ornate one; varbit 2308 is 1-based. */
	private static final int FANCY_FIRST_LINE = 10;
	private static final int ORNATE_FIRST_LINE = 20;
	private static final int XERIC_FIRST = ObjectID.POH_AMULET_XERIC_LOOKOUT;
	private static final int DIGSITE_FIRST = ObjectID.POH_AMULET_DIG_DIGSITE;

	@Inject
	private Client client;
	@Inject
	private PlayerModel player;
	@Inject
	private CatalogData catalogData;
	private MemorySlot<Profile> stored;
	private final HouseOwnership ownership = new HouseOwnership(System::currentTimeMillis);

	private volatile Set<Integer> pieces = Set.of();
	/** Any thread. */
	@Getter
	private volatile HouseState house = HouseState.NONE;
	private boolean seenATick;
	private boolean wasInside;
	private int scanAtTick = -1;
	private boolean varsDirty = true;
	private boolean houseDirty = true;
	private boolean nexusLinesDirty;

	@Inject
	void init(MemoryStore memory)
	{
		stored = memory.slot(PROFILE_KEY, Profile.class, Profile::new);
	}

	public void start()
	{
		catalogData.get().thenAccept(this::learnPieces);
		seenATick = false;
		wasInside = false;
		readHouseAgain();
	}

	public void stop()
	{
		scanAtTick = -1;
	}

	public HouseOwnership.Ownership getOwnership()
	{
		return ownership.getOwnership();
	}

	public boolean isInHouse()
	{
		return ownership.isInHouse();
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
		if (WATCHED.contains(event.getVarbitId()))
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
		readHouseAgain();
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

	private void readHouseAgain()
	{
		varsDirty = true;
		houseDirty = true;
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
		for (int id : HOUSE_VARBITS)
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
		WorldView view = client.getTopLevelWorldView();
		Player local = client.getLocalPlayer();
		if (view == null || local == null)
		{
			return;
		}
		Map<Integer, List<WorldPoint>> found = findPieces(view.getScene());
		WalkCosts walk = walkFrom(view, local.getLocalLocation());
		Map<Integer, Integer> ticks = new HashMap<>();
		found.forEach((id, cells) ->
		{
			int tiles = cells.stream().mapToInt(cell -> walk == null ? WalkCosts.UNREACHED : walk.costNear(cell)).min()
				.orElse(WalkCosts.UNREACHED);
			if (tiles != WalkCosts.UNREACHED)
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
		tell(String.format("scan: %d pieces %s, walk ticks %s, added %s, gone %s", found.size(), found.keySet(), ticks, added,
			before));
	}

	/** By piece, its scene tiles: a large piece is on each tile it covers. */
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
					for (TileObject object : tile == null ? List.<TileObject>of() : SceneObjects.objectsOn(tile))
					{
						if (pieces.contains(object.getId()))
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
	private static WalkCosts walkFrom(WorldView view, LocalPoint at)
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
		LoadedScene scene = new LoadedScene(0, 0, view.getSizeX(), view.getSizeY(), flags);
		return WalkCosts.from(Ground.sceneOnly(scene), new WorldPoint(at.getSceneX(), at.getSceneY(), view.getPlane()));
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
		return Arrays.stream(NEXUS_SLOTS).mapToObj(id -> profile.vars.getOrDefault(id, 0)).filter(key -> key > 0)
			.collect(Collectors.toUnmodifiableSet());
	}

	private void tell(String line)
	{
		log.debug("poh: {}", line);
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

package com.cluehelper;

import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.Loadouts;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.DriftSteps;
import com.cluehelper.route.GearPicker;
import com.cluehelper.route.GearRequirement;
import com.cluehelper.route.Ground;
import com.cluehelper.route.LoadedScene;
import com.cluehelper.route.NoTeleportAreas;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.Planner;
import com.cluehelper.route.RequiredItems;
import com.cluehelper.route.SceneFiles;
import com.cluehelper.route.WalkCosts;
import com.cluehelper.route.WalkGates;
import com.cluehelper.route.WalkTable;
import com.cluehelper.route.WorldMap;
import com.cluehelper.step.Stage;
import com.cluehelper.step.Step;
import com.cluehelper.step.TargetKind;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.Catalog;
import com.cluehelper.transport.CatalogLoader;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Plans each {@link PlanTrace} recording again, warm and as the live planner does, and prints its time and labels beside
 * the live ones:
 * {@code gradlew planReplay [-Pplans=<folder>]}.
 */
public final class PlanReplay
{
	private static final int WARM_UP = 2;
	private static final int TIMED = 3;
	private static final long NANOS_PER_MILLI = 1_000_000;

	private PlanReplay()
	{
	}

	public static void main(String[] args) throws IOException
	{
		File folder = new File(args.length > 0 ? args[0] : PlanTrace.PLANS.getPath());
		File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
		if (files == null || files.length == 0)
		{
			System.out.println("No recordings in " + folder);
			return;
		}
		Arrays.sort(files);
		WalkTable table = WalkTable.load();
		WorldMap map = WorldMap.load();
		Catalog catalog = CatalogLoader.load();
		NoTeleportAreas noTeleport = NoTeleportAreas.load();
		Map<String, Step> steps = DriftSteps.read();
		AvailabilityEngine engine = new AvailabilityEngine(Loadouts.runeModel(), id -> "item " + id);
		Gson gson = new Gson();
		for (File file : files)
		{
			PlanTrace.Recording recording;
			try (Reader in = new FileReader(file, StandardCharsets.UTF_8))
			{
				recording = gson.fromJson(in, PlanTrace.Recording.class);
			}
			PlanInput input = PlanInput.builder()
				.step(steps.getOrDefault(recording.stepKey, stepOf(recording)))
				.player(recording.player)
				.banked(recording.banked)
				.pinPending(recording.pinPending)
				.atOpenBank(recording.atOpenBank)
				.required(required(recording))
				.engine(engine)
				.nowMinutes(recording.nowMinutes)
				.catalog(catalog)
				.table(table)
				.noTeleport(noTeleport)
				.start(start(recording, folder, table, map, engine))
				.settings(recording.settings)
				.revertTicksLeft(recording.revertTicksLeft)
				.refusedChanges(recording.refusedChanges)
				.foreignHouseStart(recording.foreignHouseStart)
				.followedFirstTransport(recording.followedFirstTransport)
				.build();
			report(file.getName(), recording, input);
		}
	}

	private static void report(String name, PlanTrace.Recording recording, PlanInput input)
	{
		Plan plan = null;
		for (int i = 0; i < WARM_UP; i++)
		{
			plan = Planner.withoutAlternatives(input);
		}
		long best = Long.MAX_VALUE;
		for (int i = 0; i < TIMED; i++)
		{
			long started = System.nanoTime();
			plan = Planner.withoutAlternatives(input);
			best = Math.min(best, System.nanoTime() - started);
		}
		System.out.printf("%s: live %d ms %d labels | replay %d ms %d labels, %d searches | %s%n", name, recording.liveMillis,
			recording.liveLabels, best / NANOS_PER_MILLI, plan.getLabels(), plan.getSearches(),
			plan.getBest() == null ? "no route" : plan.getBest().describe());
	}

	/** A three-step or other key the step data lacks: its targets are enough to plan. */
	private static Step stepOf(PlanTrace.Recording recording)
	{
		List<WorldPoint> targets = recording.targets.stream().map(PlanReplay::point).collect(Collectors.toList());
		List<Stage> stages = recording.stages == null ? List.of() : recording.stages.stream().map(PlanReplay::stage).collect(Collectors.toList());
		return Step.builder().key(recording.stepKey).type(recording.stepType).tier(recording.tier).kind(TargetKind.valueOf(recording.kind))
			.targets(targets).stages(stages).regions(List.of()).build();
	}

	private static Stage stage(PlanTrace.RecordedStage recorded)
	{
		return Stage.builder().kind(Stage.Kind.valueOf(recorded.kind)).act(recorded.act)
			.tiles(recorded.tiles.stream().map(PlanReplay::point).collect(Collectors.toList())).worn(recorded.worn).build();
	}

	private static RequiredItems required(PlanTrace.Recording recording)
	{
		List<RequiredItems.Need> needs = recording.needs.stream()
			.map(need -> new RequiredItems.Need(need.name, requirement(need), need.stepItem, need.inCoreTag))
			.collect(Collectors.toList());
		return RequiredItems.builder().needs(needs).stash(StashMemory.State.valueOf(recording.stash)).build();
	}

	private static ItemRequirement requirement(PlanTrace.RecordedNeed need)
	{
		if (need.gearPieces == null)
		{
			return new AnyOf(Set.copyOf(need.ids));
		}
		Map<Integer, GearPicker.Piece> pieces = need.gearPieces.stream().collect(Collectors.toMap(GearPicker.Piece::getItemId, piece -> piece));
		return new GearRequirement(need.name, need.gearAtLeast, pieces);
	}

	private static PlanStart start(PlanTrace.Recording recording, File folder, WalkTable table, WorldMap map, AvailabilityEngine engine)
		throws IOException
	{
		WorldPoint tile = point(recording.startTile);
		boolean[] rowOpen = new WalkGates(table, recording.player, engine, recording.nowMinutes).rowOpen();
		LoadedScene scene = recording.sceneFile == null ? null : scene(new File(folder, recording.sceneFile));
		WalkCosts walk = WalkCosts.from(Ground.world(map, scene, table, row -> rowOpen[row]), tile);
		switch (PlanStart.Kind.valueOf(recording.startKind))
		{
			case SCENE:
				return PlanStart.scene(tile, walk);
			case AT_NODE:
				return PlanStart.atNode(table, table.node(tile));
			default:
				return PlanStart.teleportOnly(tile, walk);
		}
	}

	private static LoadedScene scene(File file) throws IOException
	{
		try (InputStream in = new FileInputStream(file))
		{
			return SceneFiles.read(in).getScene();
		}
	}

	private static WorldPoint point(int[] xyz)
	{
		return new WorldPoint(xyz[0], xyz[1], xyz[2]);
	}

	/** A recorded need: any one of the owned items that counted for it. */
	private static final class AnyOf implements ItemRequirement
	{
		private final Set<Integer> ids;

		AnyOf(Set<Integer> ids)
		{
			this.ids = ids;
		}

		@Override
		public boolean fulfilledBy(int itemId)
		{
			return ids.contains(itemId);
		}

		@Override
		public boolean fulfilledBy(Item[] items)
		{
			return Arrays.stream(items).anyMatch(item -> ids.contains(item.getId()));
		}

		@Override
		public String getCollectiveName(Client client)
		{
			return "recorded need";
		}
	}
}

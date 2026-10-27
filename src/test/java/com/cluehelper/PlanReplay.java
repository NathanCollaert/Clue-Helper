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
import com.cluehelper.route.PlanRecording;
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
import com.cluehelper.ui.RouteReport;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Plans each {@link PlanTrace} recording, and each route report a player pasted into a {@code .txt} file, again, warm
 * and as the live planner does, and prints its time, labels and best route beside the live ones:
 * {@code gradlew planReplay [-Pplans=<folder>]}.
 */
public final class PlanReplay
{
	private static final int WARM_UP = 2;
	private static final int TIMED = 3;
	private static final long NANOS_PER_MILLI = 1_000_000;
	private static final String REPORT = ".txt";

	private PlanReplay()
	{
	}

	public static void main(String[] args) throws IOException
	{
		File folder = new File(args.length > 0 ? args[0] : PlanTrace.PLANS.getPath());
		File[] files = folder.listFiles((dir, name) -> name.endsWith(".json") || name.endsWith(REPORT));
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
			PlanRecording recording = gson.fromJson(recordingJson(file), PlanRecording.class);
			PlanInput input = PlanInput.builder()
				.step(steps.getOrDefault(recording.getStepKey(), stepOf(recording)))
				.player(recording.getPlayer())
				.banked(recording.getBanked())
				.pinPending(recording.isPinPending())
				.atOpenBank(recording.isAtOpenBank())
				.required(required(recording))
				.engine(engine)
				.nowMinutes(recording.getNowMinutes())
				.catalog(catalog)
				.table(table)
				.noTeleport(noTeleport)
				.start(start(recording, folder, table, map, engine))
				.settings(recording.getSettings())
				.revertTicksLeft(recording.getRevertTicksLeft())
				.refusedChanges(recording.getRefusedChanges())
				.foreignHouseStart(recording.isForeignHouseStart())
				.followedFirstTransport(recording.getFollowedFirstTransport())
				.build();
			report(file.getName(), recording, input);
		}
	}

	private static String recordingJson(File file) throws IOException
	{
		String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
		if (!file.getName().endsWith(REPORT))
		{
			return text;
		}
		int start = text.indexOf(RouteReport.REPLAY_START);
		int end = text.indexOf(RouteReport.REPLAY_END);
		if (start < 0 || end < start)
		{
			throw new IOException(file + " has no replay data");
		}
		byte[] gzipped = Base64.getMimeDecoder().decode(text.substring(start + RouteReport.REPLAY_START.length(), end).trim());
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped)))
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void report(String name, PlanRecording recording, PlanInput input)
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
		System.out.printf("%s: live %d ms %d labels | replay %d ms %d labels, %d searches | %s%n", name, recording.getLiveMillis(),
			recording.getLiveLabels(), best / NANOS_PER_MILLI, plan.getLabels(), plan.getSearches(),
			plan.getBest() == null ? "no route" : plan.getBest().describe());
	}

	/** A three-step or other key the step data lacks: its targets are enough to plan. */
	private static Step stepOf(PlanRecording recording)
	{
		List<WorldPoint> targets = recording.getTargets().stream().map(PlanReplay::point).collect(Collectors.toList());
		List<Stage> stages = recording.getStages() == null ? List.of() : recording.getStages().stream().map(PlanReplay::stage).collect(Collectors.toList());
		return Step.builder().key(recording.getStepKey()).type(recording.getStepType()).tier(recording.getTier()).kind(TargetKind.valueOf(recording.getKind()))
			.targets(targets).stages(stages).regions(List.of()).build();
	}

	private static Stage stage(PlanRecording.RecordedStage recorded)
	{
		return Stage.builder().kind(Stage.Kind.valueOf(recorded.getKind())).act(recorded.getAct())
			.tiles(recorded.getTiles().stream().map(PlanReplay::point).collect(Collectors.toList())).worn(recorded.getWorn()).build();
	}

	private static RequiredItems required(PlanRecording recording)
	{
		List<RequiredItems.Need> needs = recording.getNeeds().stream()
			.map(need -> new RequiredItems.Need(need.getName(), requirement(need), need.isStepItem(), need.isInCoreTag(), need.isBlocksRoute()))
			.collect(Collectors.toList());
		return RequiredItems.builder().needs(needs).stash(StashMemory.State.valueOf(recording.getStash())).build();
	}

	private static ItemRequirement requirement(PlanRecording.RecordedNeed need)
	{
		if (need.getGearPieces() == null)
		{
			return new AnyOf(Set.copyOf(need.getIds()));
		}
		Map<Integer, GearPicker.Piece> pieces = need.getGearPieces().stream().collect(Collectors.toMap(GearPicker.Piece::getItemId, piece -> piece));
		return new GearRequirement(need.getName(), need.getGearAtLeast(), pieces);
	}

	private static PlanStart start(PlanRecording recording, File folder, WalkTable table, WorldMap map, AvailabilityEngine engine)
		throws IOException
	{
		WorldPoint tile = point(recording.getStartTile());
		boolean[] rowOpen = new WalkGates(table, recording.getPlayer(), engine, recording.getNowMinutes()).rowOpen();
		LoadedScene scene = recording.getSceneFile() == null ? null : scene(new File(folder, recording.getSceneFile()));
		WalkCosts walk = WalkCosts.from(Ground.world(map, scene, table, row -> rowOpen[row]), tile);
		switch (PlanStart.Kind.valueOf(recording.getStartKind()))
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

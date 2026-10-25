package com.cluehelper;

import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.GearPicker;
import com.cluehelper.route.GearRequirement;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.PlannerSettings;
import com.cluehelper.route.RequiredItems;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneFiles;
import com.cluehelper.route.WalkCosts;
import com.cluehelper.step.Stage;
import com.cluehelper.step.Step;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;

/**
 * Each plan slower than {@link #SLOW_MILLIS}, with what it was planned from, as a {@link Recording} in {@link #PLANS}
 * (its scene beside it), which {@code PlanReplay} plans again offline. Nothing while the bank PIN keypad is open.
 */
@Slf4j
final class PlanTrace
{
	static final String TAG = "[CH-PLANREC]";
	static final File PLANS = new File("build/plans");
	private static final long SLOW_MILLIS = 300;
	private static final long NANOS_PER_MILLI = 1_000_000;
	private static final int MOST_RECORDINGS = 40;

	private final Client client;
	private final Gson gson = new Gson();
	private final Runnable unregister;
	private int recorded;

	PlanTrace(Client client, RoutePlanner routes)
	{
		this.client = client;
		PLANS.mkdirs();
		unregister = routes.addInputListener(this::record);
	}

	void stop()
	{
		unregister.run();
	}

	private void record(Plan plan, PlanInput input)
	{
		long millis = plan.getSearchNanos() / NANOS_PER_MILLI;
		if (millis < SLOW_MILLIS || recorded >= MOST_RECORDINGS || pinOpen() || input.getStep() == null)
		{
			return;
		}
		String name = String.format("%03d-%s", ++recorded, slug(input.getStep().getKey()));
		Recording recording = recording(plan, input, millis);
		WalkCosts walk = input.getStart().getWalk();
		if (walk != null && walk.getScene() != null)
		{
			recording.sceneFile = name + ".scene.gz";
			SceneFiles.write(new File(PLANS, recording.sceneFile), walk.getScene(), input.getStart().getTile());
		}
		try (Writer out = new FileWriter(new File(PLANS, name + ".json"), StandardCharsets.UTF_8))
		{
			out.write(gson.toJson(recording));
			log.info("{} {} took {} ms, {} labels: recorded", TAG, name, millis, plan.getLabels());
		}
		catch (IOException e)
		{
			log.warn("{} could not record {}", TAG, name, e);
		}
	}

	private static Recording recording(Plan plan, PlanInput input, long millis)
	{
		Step step = input.getStep();
		Recording r = new Recording();
		r.stepKey = step.getKey();
		r.stepType = step.getType();
		r.tier = step.getTier();
		r.kind = step.getKind().name();
		r.targets = step.getTargets().stream().map(PlanTrace::xyz).collect(Collectors.toList());
		r.stages = step.getStages().stream().map(PlanTrace::stage).collect(Collectors.toList());
		r.player = input.getPlayer();
		r.banked = input.getBanked();
		r.pinPending = input.isPinPending();
		r.atOpenBank = input.isAtOpenBank();
		r.nowMinutes = input.getNowMinutes();
		r.startKind = input.getStart().getKind().name();
		r.startTile = xyz(input.getStart().getTile());
		r.settings = input.getSettings();
		r.revertTicksLeft = input.getRevertTicksLeft();
		r.refusedChanges = input.getRefusedChanges();
		r.foreignHouseStart = input.isForeignHouseStart();
		r.followedFirstTransport = input.getFollowedFirstTransport();
		r.stash = input.getRequired().getStash().name();
		r.needs = needs(input);
		r.liveMillis = millis;
		r.liveLabels = plan.getLabels();
		return r;
	}

	/** A need as the owned items that count for it: its "all of" sets are kept as "any of". */
	private static List<RecordedNeed> needs(PlanInput input)
	{
		Set<Integer> owned = new HashSet<>(input.getPlayer().getInventory().keySet());
		owned.addAll(input.getPlayer().getWorn().keySet());
		PlayerSnapshot banked = input.getBanked();
		if (banked != null)
		{
			owned.addAll(banked.getBank().keySet());
			owned.addAll(banked.getGroupStorage().keySet());
		}
		List<RecordedNeed> needs = new ArrayList<>();
		for (RequiredItems.Need need : input.getRequired().getNeeds())
		{
			RecordedNeed recorded = new RecordedNeed();
			recorded.name = need.getName();
			recorded.stepItem = need.isStepItem();
			recorded.inCoreTag = need.isInCoreTag();
			recorded.ids = owned.stream().filter(need.getRequirement()::fulfilledBy).sorted().collect(Collectors.toList());
			if (need.getRequirement() instanceof GearRequirement)
			{
				GearRequirement gear = (GearRequirement) need.getRequirement();
				recorded.gearAtLeast = gear.getAtLeast();
				recorded.gearPieces = List.copyOf(gear.getPieces().values());
			}
			needs.add(recorded);
		}
		return needs;
	}

	private static RecordedStage stage(Stage stage)
	{
		RecordedStage recorded = new RecordedStage();
		recorded.kind = stage.getKind().name();
		recorded.act = stage.getAct();
		recorded.tiles = stage.getTiles().stream().map(PlanTrace::xyz).collect(Collectors.toList());
		recorded.worn = stage.getWorn();
		return recorded;
	}

	private static int[] xyz(WorldPoint tile)
	{
		return tile == null ? null : new int[]{tile.getX(), tile.getY(), tile.getPlane()};
	}

	private static String slug(String key)
	{
		String slug = key.toLowerCase().replaceAll("[^a-z0-9]+", "-");
		return slug.substring(0, Math.min(40, slug.length()));
	}

	private boolean pinOpen()
	{
		return client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) != null;
	}

	/** One recorded plan; {@code PlanReplay} reads it back. */
	static final class Recording
	{
		String stepKey;
		String stepType;
		String tier;
		String kind;
		List<int[]> targets;
		List<RecordedStage> stages;
		PlayerSnapshot player;
		PlayerSnapshot banked;
		boolean pinPending;
		boolean atOpenBank;
		long nowMinutes;
		String startKind;
		int[] startTile;
		String sceneFile;
		PlannerSettings settings;
		int revertTicksLeft;
		Set<String> refusedChanges;
		boolean foreignHouseStart;
		String followedFirstTransport;
		String stash;
		List<RecordedNeed> needs;
		long liveMillis;
		int liveLabels;
	}

	static final class RecordedStage
	{
		String kind;
		String act;
		List<int[]> tiles;
		List<String> worn;
	}

	static final class RecordedNeed
	{
		String name;
		boolean stepItem;
		boolean inCoreTag;
		List<Integer> ids;
		/** Gear needs only: the bonus total and each owned piece. */
		int gearAtLeast;
		List<GearPicker.Piece> gearPieces;
	}
}

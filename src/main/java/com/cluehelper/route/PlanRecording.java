package com.cluehelper.route;

import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.step.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * One plan with everything it was planned from, as JSON: what a route report carries, and what the developer tools plan
 * again offline.
 */
@Getter
public final class PlanRecording
{
	private static final long NANOS_PER_MILLI = 1_000_000;

	private String stepKey;
	private String stepType;
	private String tier;
	private String kind;
	private List<int[]> targets;
	private List<RecordedStage> stages;
	private PlayerSnapshot player;
	private PlayerSnapshot banked;
	private boolean pinPending;
	private boolean atOpenBank;
	private long nowMinutes;
	private String startKind;
	private int[] startTile;
	/** Null when no scene was saved beside the recording. */
	private String sceneFile;
	private PlannerSettings settings;
	private int revertTicksLeft;
	private Set<String> refusedChanges;
	private boolean foreignHouseStart;
	private String followedFirstTransport;
	private String stash;
	private List<RecordedNeed> needs;
	private long liveMillis;
	private int liveLabels;

	private PlanRecording()
	{
	}

	public static PlanRecording of(Plan plan, PlanInput input, String sceneFile)
	{
		Step step = input.getStep();
		PlanRecording r = new PlanRecording();
		r.stepKey = step.getKey();
		r.stepType = step.getType();
		r.tier = step.getTier();
		r.kind = step.getKind().name();
		r.targets = step.getTargets().stream().map(PlanRecording::xyz).collect(Collectors.toList());
		r.stages = step.getStages().stream().map(RecordedStage::of).collect(Collectors.toList());
		r.player = input.getPlayer();
		r.banked = input.getBanked();
		r.pinPending = input.isPinPending();
		r.atOpenBank = input.isAtOpenBank();
		r.nowMinutes = input.getNowMinutes();
		r.startKind = input.getStart().getKind().name();
		r.startTile = xyz(input.getStart().getTile());
		r.sceneFile = sceneFile;
		r.settings = input.getSettings();
		r.revertTicksLeft = input.getRevertTicksLeft();
		r.refusedChanges = input.getRefusedChanges();
		r.foreignHouseStart = input.isForeignHouseStart();
		r.followedFirstTransport = input.getFollowedFirstTransport();
		r.stash = input.getRequired().getStash().name();
		r.needs = needs(input);
		r.liveMillis = plan.getSearchNanos() / NANOS_PER_MILLI;
		r.liveLabels = plan.getLabels();
		return r;
	}

	/** For a route report, which a player posts in public: the bank holds only the items a route could use. */
	public static PlanRecording forReport(Plan plan, PlanInput input)
	{
		Set<Integer> usable = UsableItems.of(input, input.getEngine().getRunes(), input.getRequired(), input.getStep());
		PlanInput cut = input.toBuilder()
			.player(UsableItems.cutDown(input.getPlayer(), usable))
			.banked(UsableItems.cutDown(input.getBanked(), usable))
			.build();
		return of(plan, cut, null);
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
			needs.add(RecordedNeed.of(need, owned));
		}
		return needs;
	}

	private static int[] xyz(WorldPoint tile)
	{
		return tile == null ? null : new int[]{tile.getX(), tile.getY(), tile.getPlane()};
	}

	@Getter
	public static final class RecordedStage
	{
		private String kind;
		private String act;
		private List<int[]> tiles;
		private List<String> worn;

		private RecordedStage()
		{
		}

		private static RecordedStage of(Stage stage)
		{
			RecordedStage recorded = new RecordedStage();
			recorded.kind = stage.getKind().name();
			recorded.act = stage.getAct();
			recorded.tiles = stage.getTiles().stream().map(PlanRecording::xyz).collect(Collectors.toList());
			recorded.worn = stage.getWorn();
			return recorded;
		}
	}

	@Getter
	public static final class RecordedNeed
	{
		private String name;
		private boolean stepItem;
		private boolean inCoreTag;
		private boolean blocksRoute;
		private List<Integer> ids;
		/** Gear needs only: the bonus total and each owned piece. */
		private int gearAtLeast;
		private List<GearPicker.Piece> gearPieces;

		private RecordedNeed()
		{
		}

		private static RecordedNeed of(RequiredItems.Need need, Set<Integer> owned)
		{
			RecordedNeed recorded = new RecordedNeed();
			recorded.name = need.getName();
			recorded.stepItem = need.isStepItem();
			recorded.inCoreTag = need.isInCoreTag();
			recorded.blocksRoute = need.isBlocksRoute();
			recorded.ids = owned.stream().filter(need.getRequirement()::fulfilledBy).sorted().collect(Collectors.toList());
			if (need.getRequirement() instanceof GearRequirement)
			{
				GearRequirement gear = (GearRequirement) need.getRequirement();
				recorded.gearAtLeast = gear.getAtLeast();
				recorded.gearPieces = List.copyOf(gear.getPieces().values());
			}
			return recorded;
		}
	}
}

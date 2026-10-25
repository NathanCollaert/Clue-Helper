package com.cluehelper.campaign;

import com.cluehelper.guide.SubStep;
import com.cluehelper.guide.SubSteps;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.RuneModel;
import com.cluehelper.route.GearPicker;
import com.cluehelper.route.GearPieces;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.RequiredItems;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.StepNeeds;
import com.cluehelper.step.CoreClues;
import com.cluehelper.step.Stage;
import com.cluehelper.step.StageTarget;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import com.cluehelper.step.StepResolver;
import com.cluehelper.transport.ItemClause;
import com.cluehelper.transport.Requirements;
import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Client thread. Turns a step's first live plan into a recording: the clue resolved, its needs judged and its guide
 * written again through a {@link ClientTape}, so every client read lands on the tape, and the snapshots cut down to the
 * items the planner could use. The outcome kept is the live plan's route with the guide those same inputs give.
 */
public final class CampaignRecorder
{
	private final Client client;
	private final StashMemory stash;
	private final GearPieces gearPieces;
	private final BankMemory bankMemory;
	private final Supplier<RuneModel> runes;
	private final StepIndex index = StepIndex.read(new Gson());

	/** @param runes the player model's, loaded from the client's cache after start */
	public CampaignRecorder(Client client, StashMemory stash, GearPieces gearPieces, BankMemory bankMemory, Supplier<RuneModel> runes)
	{
		this.client = client;
		this.stash = stash;
		this.gearPieces = gearPieces;
		this.bankMemory = bankMemory;
		this.runes = runes;
	}

	/** What Finish saves and Bug reports; {@link #problems} say why a replay can't give it back. */
	@Value
	static class Capture
	{
		CampaignRecording recording;
		SceneBfs.Scene scene;
		WorldPoint sceneTile;
		List<String> problems;
	}

	/** @param coreClue the mocked clue's place in core's lists */
	Capture capture(String coreClue, Plan plan, PlanInput input) throws ReflectiveOperationException
	{
		CampaignRecording recording = new CampaignRecording();
		ClientTape tape = ClientTape.recording(client);
		Step step = new StepResolver(() -> index).resolve(CoreClues.entry(coreClue), CoreClues.plugin(tape.client()));
		Step travel = StageTarget.travelStep(step, PlayerSnapshot.items(input.getPlayer().getInventory()));
		recording.stashStates = new TreeMap<>();
		recording.gearPieces = new TreeMap<>();
		recording.onlyInBankedKit = new TreeMap<>();
		StepNeeds needs = new StepNeeds(tape.client(), id -> recordStash(recording, id),
			(bonus, owned) -> recordGear(recording, bonus.name(), gearPieces.of(bonus, owned)),
			() -> StepNeeds.ownedItemIds(input.getPlayer(), input.getBanked()));
		RequiredItems required = needs.of(travel);
		SubSteps subSteps = new SubSteps(tape.client(), input::getPlayer, id -> recordKit(recording, id), needs, input::getStart,
			input::getTable, input.getSettings()::isScrollBookOutOfWilderness);
		List<SubStep> guide = plan.getBest() == null ? List.of() : subSteps.of(plan.getBest(), travel);

		Set<Integer> usable = usableItemIds(input, required, travel, recording);
		recording.key = step.getKey();
		recording.coreClue = coreClue;
		recording.tier = step.getTier();
		recording.type = step.getType();
		recording.text = step.getText();
		recording.recordedOn = LocalDate.now().toString();
		recording.player = cutDown(input.getPlayer(), usable);
		recording.banked = cutDown(input.getBanked(), usable);
		recording.pinPending = input.isPinPending();
		recording.atOpenBank = input.isAtOpenBank();
		recording.nowMinutes = input.getNowMinutes();
		recording.startKind = input.getStart().getKind().name();
		WorldPoint tile = input.getStart().getTile();
		recording.startTile = new int[]{tile.getX(), tile.getY(), tile.getPlane()};
		recording.settings = input.getSettings();
		recording.revertTicksLeft = input.getRevertTicksLeft();
		recording.refusedChanges = input.getRefusedChanges();
		recording.foreignHouseStart = input.isForeignHouseStart();
		recording.client = tape.answers();
		recording.expected = Outcome.of(plan, guide);
		SceneBfs.Result scene = input.getStart().getScene();
		return new Capture(recording, scene == null ? null : scene.getScene(), tile, problems(tape, travel, input.getStep()));
	}

	private static List<String> problems(ClientTape tape, Step travel, Step live)
	{
		List<String> problems = new ArrayList<>();
		tape.unreplayable().forEach(call -> problems.add("a client call a replay can't answer: " + call));
		if (!travel.getKey().equals(live.getKey()) || !travel.getTargets().equals(live.getTargets()))
		{
			problems.add("resolved again, the step differs from the live one: " + travel.getKey() + " " + travel.getTargets()
				+ " vs " + live.getKey() + " " + live.getTargets());
		}
		return List.copyOf(problems);
	}

	private StashMemory.State recordStash(CampaignRecording recording, int objectId)
	{
		StashMemory.State state = stash.state(objectId);
		recording.stashStates.put(objectId, state.name());
		return state;
	}

	private static Map<Integer, GearPicker.Piece> recordGear(CampaignRecording recording, String bonus, Map<Integer, GearPicker.Piece> pieces)
	{
		recording.gearPieces.computeIfAbsent(bonus, b -> new TreeMap<>()).putAll(pieces);
		return pieces;
	}

	private boolean recordKit(CampaignRecording recording, int itemId)
	{
		boolean only = bankMemory.onlyInBankedKit(itemId);
		recording.onlyInBankedKit.put(itemId, only);
		return only;
	}

	private static boolean meets(Stage stage, int itemId)
	{
		return stage.getItem() != null && stage.getItem().fulfilledBy(itemId);
	}

	/**
	 * Items a route could use: any a teleport, a spellbook change or a walk gate asks for, runes and what stands in for
	 * them, and stored items that meet one of the step's needs or stages.
	 */
	private Set<Integer> usableItemIds(PlanInput input, RequiredItems required, Step travel, CampaignRecording recording)
	{
		Set<Integer> usable = new HashSet<>(runes.get().sourceItemIds());
		input.getCatalog().getTeleports().forEach(row -> addItems(row.getRequirements(), usable));
		input.getCatalog().getSpellbookChanges().forEach(change -> addItems(change.getRequirements(), usable));
		input.getTable().getGates().forEach(gate -> addItems(gate, usable));
		usable.add(ItemID.HUNTSMANS_KIT);
		recording.gearPieces.values().forEach(pieces -> usable.addAll(pieces.keySet()));
		List<ItemRequirement> needed = new ArrayList<>();
		required.getNeeds().forEach(need -> needed.add(need.getRequirement()));
		Set<Integer> stored = new HashSet<>();
		if (input.getBanked() != null)
		{
			stored.addAll(input.getBanked().storedIds());
		}
		for (int id : stored)
		{
			if (needed.stream().anyMatch(need -> need.fulfilledBy(id)) || travel.getStages().stream().anyMatch(stage -> meets(stage, id)))
			{
				usable.add(id);
			}
		}
		return usable;
	}

	private static void addItems(Requirements requirements, Set<Integer> ids)
	{
		if (requirements == null)
		{
			return;
		}
		for (ItemClause clause : requirements.getItems())
		{
			clause.getBranches().forEach(branch -> ids.addAll(branch.getIds()));
		}
	}

	/** Carried and worn items stay whole; bank and group storage keep only usable items, charges only for kept ones. */
	private static PlayerSnapshot cutDown(PlayerSnapshot snapshot, Set<Integer> usable)
	{
		if (snapshot == null)
		{
			return null;
		}
		Map<Integer, Integer> bank = only(snapshot.getBank(), usable);
		Map<Integer, Integer> groupStorage = only(snapshot.getGroupStorage(), usable);
		Set<Integer> kept = new HashSet<>(snapshot.carriedIds());
		kept.addAll(snapshot.getPouch().keySet());
		kept.addAll(bank.keySet());
		kept.addAll(groupStorage.keySet());
		return snapshot.toBuilder()
			.bank(bank)
			.groupStorage(groupStorage)
			.charges(only(snapshot.getCharges(), kept))
			.build();
	}

	private static <V> Map<Integer, V> only(Map<Integer, V> items, Set<Integer> ids)
	{
		return items.entrySet().stream().filter(item -> ids.contains(item.getKey()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
	}
}

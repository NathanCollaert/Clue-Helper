package com.cluehelper.campaign;

import com.cluehelper.guide.SubStep;
import com.cluehelper.guide.SubSteps;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.Loadouts;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.RuneModel;
import com.cluehelper.route.GearPicker;
import com.cluehelper.route.NoTeleportAreas;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.Planner;
import com.cluehelper.route.Ground;
import com.cluehelper.route.LoadedScene;
import com.cluehelper.route.WalkCosts;
import com.cluehelper.route.WorldMap;
import com.cluehelper.route.SceneFiles;
import com.cluehelper.route.StepNeeds;
import com.cluehelper.route.WalkGates;
import com.cluehelper.route.WalkTable;
import com.cluehelper.step.CoreClues;
import com.cluehelper.step.StageTarget;
import com.cluehelper.step.Stages;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import com.cluehelper.step.StepResolver;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.Catalog;
import com.cluehelper.transport.CatalogLoader;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.SkillChallengeClue;

/**
 * Plans a recording again offline the way the plugin planned it live: the core clue resolved with today's step data,
 * the stages left, the needs, the plan and the guide's sub-steps, every client read answered from the recording's tape.
 */
public final class CampaignReplay
{
	private final WalkTable table;
	private final WorldMap map;
	private final Catalog catalog;
	private final NoTeleportAreas noTeleport;
	private final RuneModel runes;
	private final StepIndex index;
	private Map<String, String> sourcesByKey;

	private CampaignReplay(WalkTable table, WorldMap map, Catalog catalog, NoTeleportAreas noTeleport, RuneModel runes, StepIndex index)
	{
		this.table = table;
		this.map = map;
		this.catalog = catalog;
		this.noTeleport = noTeleport;
		this.runes = runes;
		this.index = index;
	}

	/** The bundled walk table and world map, catalog and step data, and the rune model slice the tests plan with. */
	public static CampaignReplay load() throws IOException
	{
		return new CampaignReplay(WalkTable.load(), WorldMap.load(), CatalogLoader.load(), NoTeleportAreas.load(), Loadouts.runeModel(),
			StepIndex.read(new Gson()));
	}

	/**
	 * A replay that throws (a read that isn't on the tape, core lost the clue) comes back as an outcome saying so: it
	 * differs from every signed-off one, so the step is played again.
	 *
	 * @param folder where the recording's scene file is
	 */
	Outcome replay(CampaignRecording recording, Path folder) throws IOException
	{
		try
		{
			return plan(recording, folder);
		}
		catch (RuntimeException e)
		{
			return new Outcome(List.of("replay failed: " + e), List.of());
		}
	}

	private Outcome plan(CampaignRecording recording, Path folder) throws IOException
	{
		ClientTape tape = ClientTape.replaying(recording.client);
		Client client = tape.client();
		PlayerSnapshot player = recording.player;
		Step travel = StageTarget.travelStep(asLive(resolve(recording, client), player, recording.banked),
			StageTarget.owned(player, recording.banked));
		StepNeeds needs = new StepNeeds(client, id -> stashState(recording, id), (bonus, owned) -> gearPieces(recording, bonus.name(), owned),
			() -> StepNeeds.ownedItemIds(player, recording.banked));
		AvailabilityEngine engine = new AvailabilityEngine(runes, id -> "item " + id);
		PlanStart start = start(recording, folder, engine);
		PlanInput input = PlanInput.builder()
			.step(travel)
			.player(player)
			.banked(recording.banked)
			.pinPending(recording.pinPending)
			.atOpenBank(recording.atOpenBank)
			.required(needs.of(travel))
			.engine(engine)
			.nowMinutes(recording.nowMinutes)
			.catalog(catalog)
			.table(table)
			.noTeleport(noTeleport)
			.start(start)
			.settings(recording.settings)
			.revertTicksLeft(recording.revertTicksLeft)
			.refusedChanges(recording.refusedChanges)
			.foreignHouseStart(recording.foreignHouseStart)
			.build();
		Plan plan = Planner.withoutAlternatives(input);
		SubSteps subSteps = new SubSteps(client, () -> player, id -> onlyInBankedKit(recording, id), needs, () -> start,
			recording.settings::isScrollBookOutOfWilderness, id -> false);
		List<SubStep> guide = plan.getBest() == null ? List.of() : subSteps.of(plan.getBest(), travel);
		return Outcome.of(plan, guide);
	}

	private Step resolve(CampaignRecording recording, Client client)
	{
		try
		{
			StepResolver resolver = new StepResolver(() -> index);
			Step step = resolveAsRecorded(resolver, CoreClues.entry(recording.coreClue), recording.challengeCompleted, client);
			if (step.getKey().equals(recording.key))
			{
				return step;
			}
			String moved = sourceOf(recording.key);
			if (moved == null)
			{
				throw new IllegalStateException("core has no clue with key " + recording.key + " any more");
			}
			return resolver.resolve(CoreClues.entry(moved), CoreClues.plugin(client));
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't read core's clue " + recording.coreClue + " for " + recording.key, e);
		}
	}

	/**
	 * Core's clues are shared objects: replayed in the live client on Finish, a Sherlock task done since the capture is
	 * done on the very clue the replay reads, so it is set back to its captured state for the resolve.
	 */
	private Step resolveAsRecorded(StepResolver resolver, ClueScroll clue, Boolean completed, Client client)
		throws ReflectiveOperationException
	{
		if (!(clue instanceof SkillChallengeClue) || completed == null)
		{
			return resolver.resolve(clue, CoreClues.plugin(client));
		}
		SkillChallengeClue challenge = (SkillChallengeClue) clue;
		boolean live = challenge.isChallengeCompleted();
		challenge.setChallengeCompleted(completed);
		try
		{
			return resolver.resolve(clue, CoreClues.plugin(client));
		}
		finally
		{
			challenge.setChallengeCompleted(live);
		}
	}

	/** Core's lists moved (a RuneLite update): each clue's place found again by its key, read with every var at 0. */
	private String sourceOf(String key) throws ReflectiveOperationException
	{
		if (sourcesByKey == null)
		{
			sourcesByKey = new HashMap<>();
			StepResolver resolver = new StepResolver(() -> index);
			for (Map.Entry<String, ClueScroll> clue : CoreClues.steps().entrySet())
			{
				sourcesByKey.putIfAbsent(resolver.resolve(clue.getValue(), CoreClues.plugin()).getKey(), clue.getKey());
			}
		}
		return sourcesByKey.get(key);
	}

	private PlanStart start(CampaignRecording recording, Path folder, AvailabilityEngine engine) throws IOException
	{
		WorldPoint tile = new WorldPoint(recording.startTile[0], recording.startTile[1], recording.startTile[2]);
		boolean[] rowOpen = new WalkGates(table, recording.player, engine, recording.nowMinutes).rowOpen();
		LoadedScene scene = recording.sceneFile == null ? null : scene(folder.resolve(recording.sceneFile));
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

	/** The step as the live client hands it on: a kill for a key the player owns, carried or banked, is dropped. */
	static Step asLive(Step resolved, PlayerSnapshot player, PlayerSnapshot banked)
	{
		return Stages.adjusted(resolved, id -> owns(player, banked, id), player.getPosition().getTile());
	}

	private static boolean owns(PlayerSnapshot player, PlayerSnapshot banked, int itemId)
	{
		return player.getInventory().containsKey(itemId) || player.getWorn().containsKey(itemId)
			|| banked != null && (banked.getBank().containsKey(itemId) || banked.getGroupStorage().containsKey(itemId));
	}

	private static LoadedScene scene(Path file) throws IOException
	{
		try (InputStream in = Files.newInputStream(file))
		{
			return SceneFiles.read(in).getScene();
		}
	}

	private static StashMemory.State stashState(CampaignRecording recording, int objectId)
	{
		String state = recording.stashStates.get(objectId);
		if (state == null)
		{
			throw new IllegalStateException("STASH unit " + objectId + " was not recorded");
		}
		return StashMemory.State.valueOf(state);
	}

	private static Map<Integer, GearPicker.Piece> gearPieces(CampaignRecording recording, String bonus, Collection<Integer> owned)
	{
		Map<Integer, GearPicker.Piece> pieces = recording.gearPieces.get(bonus);
		if (pieces == null)
		{
			throw new IllegalStateException("gear bonus " + bonus + " was not recorded");
		}
		return pieces.entrySet().stream().filter(piece -> owned.contains(piece.getKey()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private static boolean onlyInBankedKit(CampaignRecording recording, int itemId)
	{
		Boolean only = recording.onlyInBankedKit.get(itemId);
		if (only == null)
		{
			throw new IllegalStateException("whether item " + itemId + " is only in the banked kit was not recorded");
		}
		return only;
	}
}

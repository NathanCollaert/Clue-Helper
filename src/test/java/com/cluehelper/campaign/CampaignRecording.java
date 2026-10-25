package com.cluehelper.campaign;

import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.GearPicker;
import com.cluehelper.route.PlannerSettings;
import java.util.Map;
import java.util.Set;

/**
 * One finished campaign step: what its first plan was planned from, cut down to what the planner reads, and the route
 * and guide lines Nathan signed off. The step itself is not in it: a replay resolves the core clue again with today's
 * step data, so a step-data change is caught like a planner change.
 */
final class CampaignRecording
{
	String key;
	/** Core's list and the clue's place in it ({@code CrypticClue#12}); the key finds it when the list moved. */
	String coreClue;
	String tier;
	String type;
	String text;
	String recordedOn;

	/** Bank and group storage hold only the items a teleport, a rune, a need or a stage could use. */
	PlayerSnapshot player;
	PlayerSnapshot banked;
	boolean pinPending;
	boolean atOpenBank;
	long nowMinutes;
	String startKind;
	int[] startTile;
	/** Beside the recording, in the same folder. */
	String sceneFile;
	PlannerSettings settings;
	int revertTicksLeft;
	Set<String> refusedChanges;
	boolean foreignHouseStart;

	/** STASH unit object id to its remembered state, for each unit the needs asked about. */
	Map<Integer, String> stashStates;
	/** Gear bonus to what each owned piece adds, for each bonus the needs asked about. */
	Map<String, Map<Integer, GearPicker.Piece>> gearPieces;
	/** Item id to whether its only banked copy sat in the huntsman's kit, for each item the guide asked about. */
	Map<Integer, Boolean> onlyInBankedKit;
	/** The client's answers while resolving the clue, judging its needs and writing its guide: see {@link ClientTape}. */
	Map<String, Object> client;

	Outcome expected;
}

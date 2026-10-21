package com.cluehelper.guide;

import com.cluehelper.player.Book;
import com.cluehelper.route.Exit;
import com.cluehelper.step.Stage;
import com.cluehelper.step.StepIndex;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.SpellbookChange;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
@Builder(toBuilder = true)
public class SubStep
{
	/** What ends an act; any other sub-step ends by its kind's own check. */
	public enum Done
	{
		/** The step changes or a new plan comes: the last act of a step, or a stage's. */
		STEP_CHANGES,
		STASH_WITHDRAWN,
		STASH_DEPOSITED,
		/** Core's item requirements are met by what is worn. */
		EQUIPPED,
		URI_HERE,
		/** A challenge scroll, puzzle box or light box arrives in the inventory. */
		CHALLENGE_ITEM
	}

	public enum Kind
	{
		WALK,
		/** Out of a scene with no walk node, by a staircase, ladder or cave exit. */
		EXIT,
		/** Into the step's own area by hand data's way in: a guild door, a boat, a dungeon ladder. */
		ENTER,
		/** Core's own markers guide this walk. */
		WALK_TO_CLUE,
		WILDERNESS_EXIT,
		TELEPORT,
		SPELLBOOK_SWAP,
		FAIRY_RING,
		CHANGE_BOOK,
		BANK,
		WITHDRAW,
		/** Talk, search, dig or emote. */
		ACT
	}

	Kind kind;
	String text;
	String detail;
	/** Null on core's target and for travel that lands far away. */
	WorldPoint waypoint;
	/** Null when the sub-step completes some other way than arriving within {@link #radius}. */
	WorldPoint goal;
	int radius;
	Book book;
	/** The teleport to highlight, or a fairy ring's landing. */
	CatalogRow row;
	SpellbookChange change;
	Exit exit;
	StepIndex.WayIn wayIn;
	/** An act that ends one stage of several; null for the step's own act. */
	Stage stage;
	/** NPCs to outline and walk up to, from the stage: the Grand Exchange clerks. */
	@Builder.Default
	Set<Integer> npcIds = Set.of();
	@Builder.Default
	Done done = Done.STEP_CHANGES;
	/** An enemy may attack during this act (a dig, an emote): while one is near, the guide says to fight it. */
	boolean fights;
	/** The act's text once a fight is over: "Dig again". */
	String again;
	@Builder.Default
	Set<Integer> bankItems = Set.of();
	/** An act's item, used by its left click: the strange device for a feel. */
	@Builder.Default
	Set<Integer> actItems = Set.of();
	/** The side tab the act is done in: Emotes for an emote, Music for a track. */
	@Builder.Default
	StepGuide.Tab tab = StepGuide.Tab.NONE;

	public boolean isTravel()
	{
		return kind == Kind.TELEPORT || kind == Kind.FAIRY_RING;
	}
}

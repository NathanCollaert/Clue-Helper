package com.cluehelper.guide;

import com.cluehelper.player.Book;
import com.cluehelper.route.Exit;
import com.cluehelper.step.Stage;
import com.cluehelper.step.StepIndex;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.SpellbookChange;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

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
		CHALLENGE_ITEM,
		/** An item named {@code carriedWhenDone} is in the inventory (a scroll taken out of its book). */
		ITEM_CARRIED,
		/** No item named {@code actItemNamed} is in the inventory any more (a book deposited). */
		ITEM_GONE,
		/** The way in's worn requirements (diving gear) are all worn. */
		WAY_IN_WORN,
		/** The needs named in {@code wornNeeds} are met by worn items alone. */
		GEAR_WORN,
		/** Core's {@code emptied} slot requirements are met by what is worn. */
		SLOTS_EMPTY,
		/** One of {@code heldWhenDone} is carried or worn. */
		ITEM_HELD
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
	/** The option that does the act on its objects or NPCs, coloured in their menu and filled on a left click; or null. */
	String option;
	/** Objects to outline and walk up to, from the stage: an anvil. */
	@Builder.Default
	Set<Integer> objectIds = Set.of();
	/** NPCs to outline and walk up to, from the stage: the Grand Exchange clerks. */
	@Builder.Default
	Set<Integer> npcIds = Set.of();
	/** The step's own NPC by name, whose option is coloured only: core outlines it. */
	@Builder.Default
	Set<String> npcNames = Set.of();
	@Builder.Default
	Done done = Done.STEP_CHANGES;
	/** An enemy may attack during this act (a dig, an emote): while one is near, the guide says to fight it. */
	boolean fights;
	/** The act's text once a fight is over: "Dig again". */
	String again;
	@Builder.Default
	Set<Integer> bankItems = Set.of();
	/** Shown one per line under the text: the items to withdraw. */
	@Builder.Default
	List<String> items = List.of();
	/** An act's item, used by its left click: the strange device for a feel. */
	@Builder.Default
	Set<Integer> actItems = Set.of();
	/** A carried item to click, by the start of its name (puzzle boxes come in a form per tier); null for none. */
	String actItemNamed;
	/** The option the act takes on its item: filled when that is the item's left click, coloured in its menu. */
	String actOption;
	/** For {@link Done#GEAR_WORN}: the step's needs to wear, by name. */
	@Builder.Default
	List<String> wornNeeds = List.of();
	/** For {@link Done#SLOTS_EMPTY}: core's empty-slot requirements. */
	@Builder.Default
	List<ItemRequirement> emptied = List.of();
	/** For {@link Done#ITEM_HELD}: any of these. */
	@Builder.Default
	Set<Integer> heldWhenDone = Set.of();
	/** For a walk to or an act at a place that spans an area: anywhere in one is there, and it is outlined. */
	@Builder.Default
	List<StepIndex.Place> spans = List.of();
	/** A ground item to pick up, outlined where it lies, the nearest as the waypoint; -1 for none. */
	@Builder.Default
	int groundItem = -1;
	/** For {@link Done#ITEM_CARRIED}: the start of the item's name, lower case. */
	String carriedWhenDone;
	/** The side tab the act is done in: Emotes for an emote, Music for a track. */
	@Builder.Default
	StepGuide.Tab tab = StepGuide.Tab.NONE;

	/** Gear put on or taken off, or something fetched before the act: done where the player stands, no line to the place. */
	public boolean isPreparation()
	{
		return done == Done.GEAR_WORN || done == Done.WAY_IN_WORN || done == Done.SLOTS_EMPTY || done == Done.ITEM_HELD;
	}

	public boolean isTravel()
	{
		return kind == Kind.TELEPORT || kind == Kind.FAIRY_RING;
	}

	/** An act that is a skill challenge's task, at its place or anywhere. */
	public boolean isTask()
	{
		return kind == Kind.ACT && stage != null && stage.getKind() == Stage.Kind.TASK;
	}
}

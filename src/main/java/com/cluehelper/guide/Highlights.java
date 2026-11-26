package com.cluehelper.guide;

import com.cluehelper.player.ItemUse;
import java.util.*;
import lombok.*;

/** What the overlays mark for the current sub-step, as one value: {@link #NONE} marks nothing. */
@Value
@Builder(toBuilder = true)
public class Highlights
{
	static final Highlights NONE = Highlights.builder().build();

	/** Carried or worn items to outline. */
	@Builder.Default
	Set<Integer> items = Set.of();
	/** Outlined items whose left click does what the step needs. */
	@Builder.Default
	Set<Integer> fillItems = Set.of();
	/** Bank items to outline; the huntsman's kit stands for an item only it holds. */
	@Builder.Default
	Set<Integer> bankItems = Set.of();
	@Builder.Default
	int spellWidget = -1;
	/** The spell's teleport is its right-click option (a second landing set with Configure), so it is outlined, not filled. */
	boolean spellRightClick;
	/** A scroll whose book entry is outlined; null for none. */
	ScrollBook.Scroll scroll;
	/** The entry's left click is the step's click: a scroll that teleports with no choice after it, so it is filled. */
	boolean scrollLeftClick;
	@Builder.Default
	StepGuide.Tab tab = StepGuide.Tab.NONE;
	@Builder.Default
	MenuTarget menuTarget = MenuTarget.NONE;
	/** The current sub-step's own object or NPC with its option ("Search for traps"), for colouring its menu line. */
	@Builder.Default
	MenuTarget optionTarget = MenuTarget.NONE;
	/** The teleport's held items, for the fill once their left click is learned. */
	@Builder.Default
	List<ItemUse> heldUses = List.of();
	/** The menu label a one-click teleport goes to; null for none. */
	String oneClickLabel;
	/** An outlined object is filled when its left click is this option (a STASH unit's "Search"); null for teleports. */
	String objectOption;
	/** The carried item used on the outlined objects (a rope on the crevice), shown on them; -1 for none. */
	@Builder.Default
	int usedItem = -1;
}

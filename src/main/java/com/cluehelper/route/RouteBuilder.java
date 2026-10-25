package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.player.Container;
import com.cluehelper.player.ItemUse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

/** A searched goal label as the route the guide follows: its legs, withdraw list and what it spends. */
final class RouteBuilder
{
	private RouteBuilder()
	{
	}

	static Route build(PlanContext ctx, LabelSearch.Label goal, int rank)
	{
		List<Leg> legs = new ArrayList<>();
		WorldPoint at = ctx.in.getStart().getTile();
		boolean wilderness = false;
		boolean stageReached = false;
		for (LabelSearch.Label label : goal.chain())
		{
			if (label.kind == null)
			{
				stageReached |= label.parent != null && label.leg != label.parent.leg;
				continue;
			}
			Leg leg = leg(ctx, label, at);
			Leg last = legs.isEmpty() ? null : legs.get(legs.size() - 1);
			boolean merge = leg.getKind() == Leg.Kind.WALK && last != null && last.getKind() == Leg.Kind.WALK
				&& !leavesByExit(ctx, last) && !stageReached;
			stageReached = false;
			if (merge)
			{
				legs.set(legs.size() - 1, Leg.walk(last.getFrom(), leg.getTo(), last.getHalfTicks() + leg.getHalfTicks(), leg.getBookAfter()));
			}
			else
			{
				legs.add(leg);
			}
			at = leg.getTo();
			wilderness |= at != null && OriginClass.of(at, ctx.in.getNoTeleport()).isWilderness();
		}
		Map<String, Integer> spent = new LinkedHashMap<>();
		for (LabelSearch.Label label : goal.chain())
		{
			tally(ctx, label.ref, spent);
			tally(ctx, label.ref2, spent);
		}
		List<ItemUse> withdraw = withdraw(ctx, goal);
		boolean banked = goal.b == PlanContext.BANKED;
		boolean depositFirst = banked && slotsNeeded(ctx, withdraw) > ctx.in.getPlayer().getFreeInventorySlots();
		int extra = depositFirst ? PlanContext.DEPOSIT_FIRST_HALF_TICKS : 0;
		boolean bankLeg = legs.stream().anyMatch(leg -> leg.getKind() == Leg.Kind.BANK);
		return Route.builder()
			.legs(List.copyOf(legs))
			.halfTicks(goal.half + extra)
			.rankHalfTicks(rank + extra)
			.banked(banked)
			.withdraw(withdraw)
			.withdrawInCoreTag(inCoreTag(ctx, goal, withdraw))
			.depositFirst(depositFirst)
			.pinIncluded(bankLeg && ctx.in.isPinPending())
			.openBankStart(banked && !bankLeg)
			.changed(goal.c != LabelSearch.NONE)
			.startBook(ctx.homeBook)
			.endBook(goal.endBook != null ? goal.endBook : LabelSearch.endBook(goal.k))
			.spent(spent)
			.confidence(goal.confidence())
			.wilderness(wilderness)
			.build();
	}

	/** A start leg through an {@link Exit} stays a leg of its own, so the guide can name the exit. */
	private static boolean leavesByExit(PlanContext ctx, Leg leg)
	{
		PlanStart start = ctx.in.getStart();
		return start.getKind() == PlanStart.Kind.SCENE && leg.getFrom() != null && leg.getFrom().equals(start.getTile())
			&& start.getWalk().exitTo(leg.getTo()) != null;
	}

	private static List<ItemUse> withdraw(PlanContext ctx, LabelSearch.Label goal)
	{
		if (goal.b != PlanContext.BANKED)
		{
			return List.of();
		}
		Map<Integer, ItemUse> byItem = new LinkedHashMap<>(routeWithdraw(goal));
		ctx.required.getWithdraw().forEach(use -> byItem.putIfAbsent(use.getItemId(), use));
		for (int gateItem : goal.withdrawn)
		{
			byItem.putIfAbsent(gateItem, new ItemUse(Container.BANK, gateItem, 1));
		}
		return List.copyOf(byItem.values());
	}

	private static Map<Integer, ItemUse> routeWithdraw(LabelSearch.Label goal)
	{
		Map<Integer, ItemUse> byItem = new LinkedHashMap<>();
		for (LabelSearch.Label label : goal.chain())
		{
			for (Object ref : new Object[]{label.ref, label.ref2})
			{
				List<ItemUse> uses = ref instanceof TransportOptions.Teleport ? ((TransportOptions.Teleport) ref).withdrawUses
					: ref instanceof TransportOptions.Change ? ((TransportOptions.Change) ref).withdrawUses : null;
				if (label.b == PlanContext.BANKED && uses != null)
				{
					uses.forEach(use -> byItem.putIfAbsent(use.getItemId(), use));
				}
			}
		}
		return byItem;
	}

	private static Set<Integer> inCoreTag(PlanContext ctx, LabelSearch.Label goal, List<ItemUse> withdraw)
	{
		Set<Integer> routeItems = routeWithdraw(goal).keySet();
		Set<Integer> tagged = new HashSet<>();
		for (ItemUse use : withdraw)
		{
			List<RequiredItems.Need> helped = ctx.in.getRequired().getNeeds().stream()
				.filter(need -> need.getRequirement().fulfilledBy(use.getItemId())).collect(Collectors.toList());
			if (!routeItems.contains(use.getItemId()) && !helped.isEmpty() && helped.stream().allMatch(RequiredItems.Need::isInCoreTag))
			{
				tagged.add(use.getItemId());
			}
		}
		return Set.copyOf(tagged);
	}

	/** A stack of something already in the inventory takes no new slot. */
	private static int slotsNeeded(PlanContext ctx, List<ItemUse> withdraw)
	{
		return (int) withdraw.stream().filter(use -> ctx.in.getPlayer().count(Container.INVENTORY, use.getItemId()) == 0).count();
	}

	/** Every unit a leg uses, tracked by the search or not. */
	private static void tally(PlanContext ctx, Object ref, Map<String, Integer> spent)
	{
		int[] res = ref instanceof TransportOptions.Teleport ? ((TransportOptions.Teleport) ref).res
			: ref instanceof TransportOptions.Change ? ((TransportOptions.Change) ref).res : null;
		int[] amounts = ref instanceof TransportOptions.Teleport ? ((TransportOptions.Teleport) ref).amounts
			: ref instanceof TransportOptions.Change ? ((TransportOptions.Change) ref).amounts : null;
		for (int i = 0; res != null && i < res.length; i++)
		{
			spent.merge(ctx.resources.name(res[i]), amounts[i], Integer::sum);
		}
	}

	private static Leg leg(PlanContext ctx, LabelSearch.Label label, WorldPoint at)
	{
		Book book = LabelSearch.bookOf(label.k);
		switch (label.kind)
		{
			case TELEPORT:
				return Leg.teleport(at, ((TransportOptions.Teleport) label.ref).row, label.edgeHalf, book);
			case SWAP_TELEPORT:
				return Leg.swapTeleport(at, ((TransportOptions.Teleport) label.ref).row, ((TransportOptions.Change) label.ref2).change, label.edgeHalf, book);
			case FAIRY_RING:
				return Leg.fairyRing(at, ((TransportOptions.Teleport) label.ref).row, ((TransportOptions.Teleport) label.ref2).row, label.edgeHalf, book);
			case SPELLBOOK_CHANGE:
				return Leg.spellbookChange(at, ((TransportOptions.Change) label.ref).change, label.edgeHalf, book);
			case BANK:
				return Leg.bank(at, label.edgeHalf, book);
			case WILDERNESS_EXIT:
				return Leg.wildernessExit(at, label.edgeHalf, book);
			case WALK:
				return Leg.walk(at, ctx.tile(label.node), label.edgeHalf, book);
			default:
				throw new IllegalArgumentException("unhandled leg kind " + label.kind);
		}
	}
}

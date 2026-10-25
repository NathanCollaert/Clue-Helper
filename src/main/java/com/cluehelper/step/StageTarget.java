package com.cluehelper.step;

import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Client thread only. Drops the leading stages already done by what the player carries (the key, Charlie's item), so
 * the route and the guide start at the first stage left. Charlie's item comes from the bank when it is there, from
 * the Grand Exchange on a regular account, else from its source with that source's tools. Core's own changes (a
 * solved part, a completed task) reach the step through the resolver instead.
 */
@Singleton
public class StageTarget
{
	/** The Grand Exchange's bank node, a walk table node. */
	private static final WorldPoint GRAND_EXCHANGE = new WorldPoint(3162, 3489, 0);
	private static final String BUY_TEXT = "Buy it on the Grand Exchange";
	private static final Set<Integer> GE_CLERKS = Set.of(NpcID.GE_CLERK_1, NpcID.GE_CLERK_2, NpcID.GE_CLERK_3, NpcID.GE_CLERK_4);

	private final BankMemory bankMemory;
	private final PlayerModel player;
	private Step lastIn;
	private Step lastOut;

	@Inject
	StageTarget(BankMemory bankMemory, PlayerModel player)
	{
		this.bankMemory = bankMemory;
		this.player = player;
	}

	/** The same step object while the stages left don't change. */
	public Step travelStep(Step step)
	{
		BankMemory.Seen bank = bankMemory.getBank();
		Step out = travelStep(step, carried(), bank == null || bank.items == null ? Map.of() : bank.items, canTrade());
		if (out == step)
		{
			return step;
		}
		if (step == lastIn && out.equals(lastOut))
		{
			return lastOut;
		}
		lastIn = step;
		lastOut = out;
		return out;
	}

	/** {@code step} itself when no stage is dropped and none is fetched. */
	public static Step travelStep(Step step, Item[] carried, Map<Integer, Integer> bank, boolean canTrade)
	{
		if (step == null || step.getStages().isEmpty())
		{
			return step;
		}
		int dropped = 0;
		ItemRequirement fromBank = null;
		while (dropped < step.getStages().size() - 1)
		{
			Stage stage = step.getStages().get(dropped);
			if (stage.getItem() != null && stage.getItem().fulfilledBy(carried))
			{
				dropped++;
			}
			else if (stage.getKind() == Stage.Kind.FETCH && banked(stage.getItem(), bank))
			{
				fromBank = stage.getItem();
				dropped++;
			}
			else
			{
				break;
			}
		}
		Stage fetch = dropped < step.getStages().size() && step.getStages().get(dropped).getKind() == Stage.Kind.FETCH
			? step.getStages().get(dropped) : null;
		boolean buy = fetch != null && canTrade;
		if (dropped == 0 && fetch == null)
		{
			return step;
		}
		List<Stage> left = new ArrayList<>(step.getStages().subList(dropped, step.getStages().size()));
		StepRequirements needs = fromBank == null ? step.getRequirements() : step.getRequirements().plusOwn(fromBank);
		if (buy)
		{
			left.set(0, fetch.toBuilder().detail(BUY_TEXT).tiles(List.of(GRAND_EXCHANGE)).npcIds(GE_CLERKS).build());
		}
		else if (fetch != null)
		{
			for (ItemRequirement tool : fetch.getTools())
			{
				needs = needs.plusOwn(tool);
			}
		}
		boolean onlyFinal = left.size() == 1 && left.get(0).getKind() == Stage.Kind.FINAL;
		return step.toBuilder()
			.stages(onlyFinal ? List.of() : List.copyOf(left))
			.targets(Stages.tiles(left))
			.kind(onlyFinal ? TargetKind.ANY_OF : TargetKind.SEQUENCE)
			.requirements(needs)
			.build();
	}

	private boolean canTrade()
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		return snapshot != null && snapshot.getAccountType().canTrade();
	}

	/** Charlie's item already in the bank is withdrawn there, not fetched: a needed item brings a bank stop. */
	private static boolean banked(ItemRequirement item, Map<Integer, Integer> bank)
	{
		if (item == null)
		{
			return false;
		}
		for (Map.Entry<Integer, Integer> stack : bank.entrySet())
		{
			if (stack.getValue() > 0 && item.fulfilledBy(stack.getKey()))
			{
				return true;
			}
		}
		return false;
	}

	private Item[] carried()
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		return snapshot == null ? new Item[0] : PlayerSnapshot.items(snapshot.getInventory());
	}
}

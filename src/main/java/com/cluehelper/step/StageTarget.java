package com.cluehelper.step;

import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;

/**
 * Client thread only. Drops the leading stages already done by what the player carries (the key), so the route and the
 * guide start at the first stage left. Core's own changes (a solved part, a completed task) reach the step through the
 * resolver instead.
 */
@Singleton
public class StageTarget
{
	private final PlayerModel player;
	private Step lastIn;
	private Step lastOut;

	@Inject
	StageTarget(PlayerModel player)
	{
		this.player = player;
	}

	/** The same step object while the stages left don't change. */
	public Step travelStep(Step step)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		Step out = travelStep(step, snapshot == null ? new Item[0] : PlayerSnapshot.items(snapshot.getInventory()));
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

	/** {@code step} itself when no stage is dropped. */
	public static Step travelStep(Step step, Item[] carried)
	{
		if (step == null || step.getStages().isEmpty())
		{
			return step;
		}
		int dropped = 0;
		while (dropped < step.getStages().size() - 1)
		{
			Stage stage = step.getStages().get(dropped);
			if (stage.getItem() == null || !stage.getItem().fulfilledBy(carried))
			{
				break;
			}
			dropped++;
		}
		if (dropped == 0)
		{
			return step;
		}
		List<Stage> left = step.getStages().subList(dropped, step.getStages().size());
		boolean onlyFinal = left.size() == 1 && left.get(0).getKind() == Stage.Kind.FINAL;
		return step.toBuilder()
			.stages(onlyFinal ? List.of() : List.copyOf(left))
			.targets(Stages.tiles(left))
			.kind(onlyFinal ? TargetKind.ANY_OF : TargetKind.SEQUENCE)
			.build();
	}
}

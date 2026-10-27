package com.cluehelper.step;

import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.transport.AvailabilityService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;

/**
 * Client thread only. Drops the leading stages already done by what the player owns, carried or banked (the key, or the
 * steel key ring it hangs on), so the route and the guide start at the first stage left; the route brings a banked one. Core's own changes (a solved part, a completed task) reach the step through the
 * resolver instead.
 */
@Singleton
public class StageTarget
{
	private final PlayerModel player;
	private final AvailabilityService availability;
	private Step lastIn;
	private Step lastOut;

	@Inject
	StageTarget(PlayerModel player, AvailabilityService availability)
	{
		this.player = player;
		this.availability = availability;
	}

	/** The same step object while the stages left don't change. */
	public Step travelStep(Step step)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		Step out = travelStep(step, snapshot == null ? new Item[0] : owned(snapshot, availability.getBankedSnapshot()));
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

	/** What a stage's item can be found among: carried, worn, and in the bank of {@code banked} (null when unknown). */
	public static Item[] owned(PlayerSnapshot player, PlayerSnapshot banked)
	{
		Map<Integer, Integer> counts = new HashMap<>(player.getInventory());
		player.getWorn().forEach((id, count) -> counts.merge(id, count, Integer::sum));
		if (banked != null)
		{
			banked.storedIds().forEach(id -> counts.merge(id, banked.stored(id), Integer::sum));
		}
		return PlayerSnapshot.items(counts);
	}

	/** {@code step} itself when no stage is dropped. */
	public static Step travelStep(Step step, Item[] owned)
	{
		if (step == null || step.getStages().isEmpty())
		{
			return step;
		}
		int dropped = 0;
		while (dropped < step.getStages().size() - 1)
		{
			Stage stage = step.getStages().get(dropped);
			if (stage.getItem() == null || !stage.getItem().fulfilledBy(owned))
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

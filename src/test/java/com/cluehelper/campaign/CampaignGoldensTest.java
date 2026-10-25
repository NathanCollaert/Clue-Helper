package com.cluehelper.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * Every campaign step Nathan finished must replay to the outcome he signed off. A changed step fails the build: a
 * route or guide change is played again in the campaign, a tick-only one is accepted with {@code acceptTickChanges}
 * after reading {@code build/campaign/changed.html}.
 */
public class CampaignGoldensTest
{
	@Test
	public void finishedStepsReplayTheirSignedOffOutcome() throws IOException
	{
		assertTrue(CampaignFiles.GOLDENS + " is missing: the campaign's recordings must be there, even before the first",
			Files.isDirectory(CampaignFiles.GOLDENS));
		List<Path> files = CampaignFiles.goldenFiles();
		List<ChangeReport.Changed> changed = new ArrayList<>();
		CampaignReplay replay = files.isEmpty() ? null : CampaignReplay.load();
		for (Path file : files)
		{
			CampaignRecording recording = CampaignFiles.read(file);
			Outcome now = replay.replay(recording, file.getParent());
			Outcome.Change change = recording.expected.changeTo(now);
			if (change != Outcome.Change.NONE)
			{
				changed.add(new ChangeReport.Changed(file, recording, now, change));
			}
		}
		ChangeReport.write(changed);
		System.out.println(files.size() + " campaign steps replayed, " + changed.size() + " changed");
		assertTrue(changed.size() + " of " + files.size() + " finished steps changed (see " + CampaignFiles.CHANGED_PAGE + "):\n"
			+ changed.stream().map(step -> step.getChange() + " " + step.getRecording().key).collect(Collectors.joining("\n")),
			changed.isEmpty());
	}
}

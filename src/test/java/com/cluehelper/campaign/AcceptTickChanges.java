package com.cluehelper.campaign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Takes the new tick counts of the steps the last golden test marked {@code ticks}, and nothing else: a step whose
 * route or guide changed is refused, it needs playing again. Run after reading changed.html: gradlew acceptTickChanges.
 */
public final class AcceptTickChanges
{
	private AcceptTickChanges()
	{
	}

	public static void main(String[] args) throws IOException
	{
		if (!Files.exists(CampaignFiles.CHANGED))
		{
			System.out.println("No " + CampaignFiles.CHANGED + ": run the tests first");
			return;
		}
		CampaignReplay replay = CampaignReplay.load();
		int accepted = 0;
		for (String line : Files.readAllLines(CampaignFiles.CHANGED, StandardCharsets.UTF_8))
		{
			String[] parts = line.split("\t");
			if (parts.length == 3 && parts[0].equals("ticks") && accept(replay, Paths.get(parts[2])))
			{
				accepted++;
			}
		}
		System.out.println(accepted + " tick changes accepted");
	}

	private static boolean accept(CampaignReplay replay, Path file) throws IOException
	{
		CampaignRecording recording = CampaignFiles.read(file);
		Outcome now = replay.replay(recording, file.getParent());
		Outcome.Change change = recording.expected.changeTo(now);
		if (change != Outcome.Change.TICKS)
		{
			System.out.println("refused " + recording.key + ": " + change + " now, play it again in the campaign");
			return false;
		}
		System.out.println(recording.key + "\n  was " + bestLine(recording.expected) + "\n  now " + bestLine(now));
		recording.expected = now;
		CampaignFiles.write(file, recording);
		return true;
	}

	private static String bestLine(Outcome outcome)
	{
		List<String> route = outcome.getRoute();
		return route.isEmpty() ? "" : route.get(0);
	}
}

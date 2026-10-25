package com.cluehelper.campaign;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** One recording replayed, beside what was recorded: a bug report's, or a golden's. gradlew campaignReplay -Pfile=<path>. */
public final class ReplayRecording
{
	private ReplayRecording()
	{
	}

	public static void main(String[] args) throws IOException
	{
		Path file = Paths.get(args[0]);
		CampaignRecording recording = CampaignFiles.read(file);
		Outcome now = CampaignReplay.load().replay(recording, file.getParent());
		System.out.println(recording.key + " (" + recording.coreClue + "), recorded " + recording.recordedOn);
		print("recorded", recording.expected);
		print("replayed", now);
		System.out.println("change: " + recording.expected.changeTo(now));
	}

	private static void print(String title, Outcome outcome)
	{
		System.out.println(title + ":");
		outcome.getRoute().forEach(line -> System.out.println("  " + line));
		outcome.getGuide().forEach(line -> System.out.println("  - " + line));
	}
}

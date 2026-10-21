package com.cluehelper.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;

final class TsvTable
{
	private static final String COMMENT = "#";

	private TsvTable()
	{
	}

	static List<Line> read(InputStream in) throws IOException
	{
		List<Line> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String[] header = null;
			String text;
			int number = 0;
			while ((text = reader.readLine()) != null)
			{
				number++;
				if (text.startsWith(COMMENT) || text.trim().isEmpty())
				{
					continue;
				}
				String[] values = text.split("\t", -1);
				if (header == null)
				{
					header = values;
					continue;
				}
				Map<String, String> cells = new HashMap<>();
				for (int i = 0; i < header.length && i < values.length; i++)
				{
					cells.put(header[i], values[i].trim());
				}
				lines.add(new Line(number, Collections.unmodifiableMap(cells)));
			}
		}
		return lines;
	}

	@Value
	static class Line
	{
		int number;
		Map<String, String> cells;

		String get(String column)
		{
			return cells.getOrDefault(column, "");
		}
	}
}

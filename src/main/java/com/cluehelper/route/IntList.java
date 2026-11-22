package com.cluehelper.route;

import java.util.Arrays;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;

/** A growable array of ints, read through its fields. */
@FieldDefaults(level = AccessLevel.PACKAGE)
final class IntList
{
	int[] data = new int[256];
	int size;

	void add(int value)
	{
		if (size == data.length)
		{
			data = Arrays.copyOf(data, size * 2);
		}
		data[size++] = value;
	}

	int[] toArray()
	{
		return Arrays.copyOf(data, size);
	}
}

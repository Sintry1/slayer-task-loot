package com.slayertaskloot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses and updates the user-editable drop exclusion settings. */
final class DropExclusions
{
	private DropExclusions()
	{
	}

	static boolean isExcluded(String global, String perTask, String taskName, String itemName)
	{
		final String item = normalize(itemName);
		if (parseGlobal(global).contains(item))
		{
			return true;
		}

		final TaskGroup group = parseTaskGroups(perTask).get(normalize(taskName));
		return group != null && group.items.containsKey(item);
	}

	static String addGlobal(String value, String itemName)
	{
		final Set<String> entries = displayEntries(value);
		final String wanted = normalize(itemName);
		for (String entry : entries)
		{
			if (normalize(entry).equals(wanted))
			{
				return String.join("; ", entries);
			}
		}
		entries.add(clean(itemName));
		return String.join("; ", entries);
	}

	static String addForTask(String value, String taskName, String itemName)
	{
		final Map<String, TaskGroup> groups = parseTaskGroups(value);
		final String wantedTask = normalize(taskName);
		final TaskGroup group = groups.computeIfAbsent(wantedTask,
			ignored -> new TaskGroup(clean(taskName)));
		group.items.putIfAbsent(normalize(itemName), clean(itemName));
		return formatTaskGroups(groups);
	}

	static String removeGlobal(String value, String itemName)
	{
		final String wanted = normalize(itemName);
		final Set<String> entries = displayEntries(value);
		entries.removeIf(entry -> normalize(entry).equals(wanted));
		return String.join("; ", entries);
	}

	static String removeForTask(String value, String taskName, String itemName)
	{
		final Map<String, TaskGroup> groups = parseTaskGroups(value);
		final String wantedTask = normalize(taskName);
		final TaskGroup group = groups.get(wantedTask);
		if (group != null)
		{
			group.items.remove(normalize(itemName));
			if (group.items.isEmpty())
			{
				groups.remove(wantedTask);
			}
		}
		return formatTaskGroups(groups);
	}

	static List<String> globalEntries(String value)
	{
		return Collections.unmodifiableList(new ArrayList<>(displayEntries(value)));
	}

	static List<String> entriesForTask(String value, String taskName)
	{
		final TaskGroup group = parseTaskGroups(value).get(normalize(taskName));
		return group == null
			? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(group.items.values()));
	}

	static String formatPerTask(String value)
	{
		return formatTaskGroups(parseTaskGroups(value));
	}

	private static Map<String, TaskGroup> parseTaskGroups(String value)
	{
		final Map<String, TaskGroup> groups = new LinkedHashMap<>();
		for (String entry : split(value))
		{
			final int colon = entry.indexOf(':');
			final int equals = entry.indexOf('=');
			final int delimiter = colon > 0 ? colon : equals;
			if (delimiter <= 0)
			{
				continue;
			}

			final String taskName = clean(entry.substring(0, delimiter));
			final String taskKey = normalize(taskName);
			if (taskKey.isEmpty())
			{
				continue;
			}
			final TaskGroup group = groups.computeIfAbsent(taskKey,
				ignored -> new TaskGroup(taskName));
			// The new Task: Item, Item form groups items with commas. The legacy
			// Task = Item form treats everything after '=' as one item for compatibility.
			final String[] items = colon > 0
				? entry.substring(delimiter + 1).split(",")
				: new String[]{entry.substring(delimiter + 1)};
			for (String item : items)
			{
				final String itemName = clean(item);
				if (!itemName.isEmpty())
				{
					group.items.putIfAbsent(normalize(itemName), itemName);
				}
			}
		}
		return groups;
	}

	private static String formatTaskGroups(Map<String, TaskGroup> groups)
	{
		final List<String> entries = new ArrayList<>();
		for (TaskGroup group : groups.values())
		{
			if (!group.items.isEmpty())
			{
				entries.add(group.name + ": " + String.join(", ", group.items.values()));
			}
		}
		return String.join("; ", entries);
	}

	private static Set<String> parseGlobal(String value)
	{
		final Set<String> entries = new LinkedHashSet<>();
		for (String entry : split(value))
		{
			entries.add(normalize(entry));
		}
		return entries;
	}

	private static Set<String> displayEntries(String value)
	{
		final Set<String> entries = new LinkedHashSet<>();
		for (String entry : split(value))
		{
			entries.add(clean(entry));
		}
		return entries;
	}

	private static String[] split(String value)
	{
		return value == null || value.trim().isEmpty()
			? new String[0]
			: value.split("[;\\r\\n]+");
	}

	private static String clean(String value)
	{
		return value == null ? "" : value.trim().replaceAll("\\s+", " ");
	}

	private static String normalize(String value)
	{
		return clean(value).toLowerCase(Locale.ROOT);
	}

	private static final class TaskGroup
	{
		private final String name;
		private final Map<String, String> items = new LinkedHashMap<>();

		private TaskGroup(String name)
		{
			this.name = name;
		}
	}
}

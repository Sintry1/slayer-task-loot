package com.slayertaskloot;

import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * Immutable snapshot of a task, resolved on the client thread and handed to the panel.
 *
 * <p>Item names and prices are looked up before crossing to the Swing thread so the panel
 * never touches game state. It only calls {@code ItemManager.getImage}, which is safe off
 * the client thread.
 */
@Value
class TaskView
{
	String assignmentId;
	String taskName;

	@Nullable
	String taskLocation;

	int kills;
	int initialAmount;
	long startedAt;
	long endedAt;
	boolean completed;

	/** GE value of everything credited to the task. */
	long lootValue;

	/** GE value of supplies consumed during the task's sessions. */
	long supplyCost;

	int sessions;
	int onTaskTicks;
	boolean sessionOpen;
	boolean canResumeSession;

	/** Loot rows, highest total value first. */
	List<LootRow> rows;

	/** Consumed supply families, highest cost first. Empty when nothing was used. */
	List<LootRow> supplyRows;

	/** Sessions belonging to this assignment, in creation order. */
	List<SessionView> sessionViews;

	/** Exclusions affecting this task, managed from its Drops section. */
	List<ExcludedDrop> excludedDrops;

	@Value
	static class ExcludedDrop
	{
		String name;
		boolean global;

		@Override
		public String toString()
		{
			return name + (global ? " (all tasks)" : " (this task)");
		}
	}

	@Value
	static class LootRow
	{
		int itemId;
		String name;
		int quantity;
		/** Total GE value of this stack, not the per-item price. */
		long value;
		/**
		 * Unit for {@link #quantity} when it isn't a plain item count — "doses" for
		 * dose-based supply families. Empty otherwise.
		 */
		String unit;

		/**
		 * For a charged item, its components as already-formatted "Blood rune x812" lines,
		 * resolved on the client thread. Empty for an ordinary supply.
		 */
		List<String> breakdown;

		LootRow(int itemId, String name, int quantity, long value, String unit)
		{
			this(itemId, name, quantity, value, unit, java.util.Collections.emptyList());
		}

		LootRow(int itemId, String name, int quantity, long value, String unit,
			List<String> breakdown)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.value = value;
			this.unit = unit;
			this.breakdown = breakdown;
		}
	}

	@Value
	static class SessionView
	{
		String sessionId;
		int number;
		int onTaskTicks;
		boolean open;

		@Override
		public String toString()
		{
			return "Session " + number + " (" + formatDuration(onTaskTicks) + ")";
		}

		private static String formatDuration(int ticks)
		{
			final long seconds = (long) ticks * 600 / 1000;
			return String.format("%d:%02d", seconds / 60, seconds % 60);
		}
	}
}

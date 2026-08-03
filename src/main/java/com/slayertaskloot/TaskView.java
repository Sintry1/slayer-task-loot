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

	/** Loot rows, highest total value first. */
	List<LootRow> rows;

	/** Consumed supply families, highest cost first. Empty when nothing was used. */
	List<LootRow> supplyRows;

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
	}
}

package com.slayertaskloot;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;

/**
 * One slayer task assignment and everything credited to it.
 *
 * <p>Persisted to per-character config as JSON, so every field has to stay Gson-friendly
 * and the class needs a no-argument constructor. Fields may come back null from an older
 * stored snapshot, so readers defend against that rather than assuming initialisers ran.
 */
@Getter
class TaskLootRecord
{
	/** Task name as resolved from the slayer DB table, e.g. "Aberrant spectres". */
	private String taskName;

	/** Konar assignment area, or null for masters that don't assign one. */
	@Nullable
	private String taskLocation;

	/** Size of the assignment when it was handed out. */
	private int initialAmount;

	/** Kills credited by the slayer counter. Not the same as the number of drops seen. */
	private int kills;

	/** Epoch millis when this assignment was first seen. */
	private long startedAt;

	/** Epoch millis when the counter reached zero, or 0 while still in progress. */
	@Setter
	private long endedAt;

	/** itemId -> total quantity. Insertion-ordered so first-seen order stays stable. */
	private Map<Integer, Integer> items;

	/** GE value of supplies consumed during this task's sessions. */
	private long supplyCost;

	/**
	 * Breakdown of {@link #supplyCost}: representative itemId -> what that family cost.
	 * Always sums to {@code supplyCost}, since both come from the same per-family diff.
	 */
	private Map<Integer, SupplyEntry> supplies;

	/** Number of on-task sessions opened. Surfaced so the attribution is auditable. */
	private int sessions;

	/** Game ticks spent with a session open, i.e. time actually on task. */
	private int onTaskTicks;

	/** Gson. */
	TaskLootRecord()
	{
	}

	TaskLootRecord(String taskName, @Nullable String taskLocation, int initialAmount, long startedAt)
	{
		this.taskName = taskName;
		this.taskLocation = taskLocation;
		this.initialAmount = initialAmount;
		this.startedAt = startedAt;
		this.items = new LinkedHashMap<>();
		this.supplies = new LinkedHashMap<>();
	}

	Map<Integer, Integer> getItems()
	{
		if (items == null)
		{
			items = new LinkedHashMap<>();
		}
		return items;
	}

	Map<Integer, SupplyEntry> getSupplies()
	{
		if (supplies == null)
		{
			// Absent from a snapshot stored before the breakdown existed.
			supplies = new LinkedHashMap<>();
		}
		return supplies;
	}

	void addKills(int count)
	{
		kills += count;
	}

	void addItem(int itemId, int quantity)
	{
		getItems().merge(itemId, quantity, Integer::sum);
	}

	/** Applies one diff's cost and folds its breakdown into the running totals. */
	void addSupplyCharge(SupplyCharge charge)
	{
		supplyCost += charge.getTotal();

		for (SupplyCharge.Row row : charge.getRows())
		{
			final SupplyEntry existing = getSupplies().get(row.getItemId());
			if (existing == null)
			{
				getSupplies().put(row.getItemId(),
					new SupplyEntry(row.getQuantity(), row.getValue(), row.isDoseBased()));
			}
			else
			{
				existing.add(row.getQuantity(), row.getValue());
			}
		}
	}

	void openedSession()
	{
		sessions++;
	}

	void tickOnTask()
	{
		onTaskTicks++;
	}

	boolean isCompleted()
	{
		return endedAt > 0;
	}
}

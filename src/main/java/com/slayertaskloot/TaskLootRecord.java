package com.slayertaskloot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
	/** Stable identity for this assignment, including back-to-back assignments of the same type. */
	private String assignmentId;

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
	private Map<Integer, Long> itemValues;

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

	/** User-visible sessions. A manually resumed session may contain multiple active segments. */
	private List<TaskSession> taskSessions;

	/** Gson. */
	TaskLootRecord()
	{
	}

	TaskLootRecord(String taskName, @Nullable String taskLocation, int initialAmount, long startedAt)
	{
		this.assignmentId = UUID.randomUUID().toString();
		this.taskName = taskName;
		this.taskLocation = taskLocation;
		this.initialAmount = initialAmount;
		this.startedAt = startedAt;
		this.items = new LinkedHashMap<>();
		this.itemValues = new LinkedHashMap<>();
		this.supplies = new LinkedHashMap<>();
		this.taskSessions = new ArrayList<>();
	}

	String getAssignmentId()
	{
		if (assignmentId == null || assignmentId.isEmpty())
		{
			assignmentId = UUID.randomUUID().toString();
		}
		return assignmentId;
	}

	List<TaskSession> getTaskSessions()
	{
		if (taskSessions == null)
		{
			taskSessions = new ArrayList<>();
		}
		return taskSessions;
	}

	Map<Integer, Integer> getItems()
	{
		ensureLegacyItems();
		final Map<Integer, Integer> combined = new LinkedHashMap<>(items);
		for (TaskSession session : getTaskSessions())
		{
			session.getItems().forEach((itemId, quantity) ->
				combined.merge(itemId, quantity, Integer::sum));
		}
		return combined;
	}

	Map<Integer, SupplyEntry> getSupplies()
	{
		ensureLegacySupplies();
		final Map<Integer, SupplyEntry> combined = new LinkedHashMap<>();
		supplies.forEach((itemId, supply) -> combined.put(itemId,
			new SupplyEntry(supply.getQuantity(), supply.getValue(), supply.isDoseBased(),
						supply.getComponents())));
		for (TaskSession session : getTaskSessions())
		{
			session.getSupplies().forEach((itemId, supply) ->
			{
				final SupplyEntry existing = combined.get(itemId);
				if (existing == null)
				{
					combined.put(itemId,
						new SupplyEntry(supply.getQuantity(), supply.getValue(), supply.isDoseBased(),
						supply.getComponents()));
				}
				else
				{
					existing.add(supply.getQuantity(), supply.getValue(), supply.getComponents());
				}
			});
		}
		return combined;
	}

	Map<Integer, Long> getItemValues()
	{
		if (itemValues == null)
		{
			itemValues = new LinkedHashMap<>();
		}
		final Map<Integer, Long> combined = new LinkedHashMap<>(itemValues);
		for (TaskSession session : getTaskSessions())
		{
			session.getItemValues().forEach((itemId, value) ->
				combined.merge(itemId, value, Long::sum));
		}
		return combined;
	}

	void addKills(int count)
	{
		kills += count;
	}

	void addKills(String sessionId, int count)
	{
		final TaskSession session = findSession(sessionId);
		if (session != null)
		{
			session.addKills(count);
		}
		else
		{
			addKills(count);
		}
	}

	void addItem(int itemId, int quantity)
	{
		ensureLegacyItems();
		items.merge(itemId, quantity, Integer::sum);
	}

	void addItem(String sessionId, int itemId, int quantity)
	{
		final TaskSession session = findSession(sessionId);
		if (session != null)
		{
			session.addItem(itemId, quantity);
		}
		else
		{
			ensureLegacyItems();
			items.merge(itemId, quantity, Integer::sum);
		}
	}

	void addItem(String sessionId, int itemId, int quantity, long value)
	{
		final TaskSession session = findSession(sessionId);
		if (session != null)
		{
			session.addItem(itemId, quantity, value);
		}
		else
		{
			ensureLegacyItems();
			if (itemValues == null)
			{
				itemValues = new LinkedHashMap<>();
			}
			items.merge(itemId, quantity, Integer::sum);
			itemValues.merge(itemId, value, Long::sum);
		}
	}

	/** Applies one diff's cost and folds its breakdown into the running totals. */
	void addSupplyCharge(SupplyCharge charge)
	{
		ensureLegacySupplies();
		supplyCost += charge.getTotal();

		for (SupplyCharge.Row row : charge.getRows())
		{
			final SupplyEntry existing = supplies.get(row.getItemId());
			if (existing == null)
			{
				supplies.put(row.getItemId(),
					new SupplyEntry(row.getQuantity(), row.getValue(), row.isDoseBased(),
						row.getComponents()));
			}
			else
			{
				existing.add(row.getQuantity(), row.getValue(), row.getComponents());
			}
		}
	}

	void addSupplyCharge(String sessionId, SupplyCharge charge)
	{
		final TaskSession session = findSession(sessionId);
		if (session != null)
		{
			session.addSupplyCharge(charge);
		}
		else
		{
			addSupplyCharge(charge);
		}
	}

	long getSupplyCost()
	{
		return supplyCost
			+ getTaskSessions().stream().mapToLong(TaskSession::getSupplyCost).sum();
	}

	int getKills()
	{
		return kills + getTaskSessions().stream().mapToInt(TaskSession::getKills).sum();
	}

	TaskSession openNewSession(long now, boolean openedManually)
	{
		final TaskSession session = new TaskSession(UUID.randomUUID().toString(), now, openedManually);
		getTaskSessions().add(session);
		return session;
	}

	TaskSession resumeSession(String sessionId, long now)
	{
		for (TaskSession session : getTaskSessions())
		{
			if (session.getSessionId().equals(sessionId))
			{
				session.resume(now);
				return session;
			}
		}
		return null;
	}

	TaskSession latestClosedSession()
	{
		for (int i = getTaskSessions().size() - 1; i >= 0; i--)
		{
			final TaskSession session = getTaskSessions().get(i);
			if (!session.isOpen())
			{
				return session;
			}
		}
		return null;
	}

	void closeSession(String sessionId, long now, String reason)
	{
		for (TaskSession session : getTaskSessions())
		{
			if (session.getSessionId().equals(sessionId))
			{
				session.close(now, reason);
				return;
			}
		}
	}

	void closeOpenSessions(long now, String reason)
	{
		for (TaskSession session : getTaskSessions())
		{
			if (session.isOpen())
			{
				session.close(now, reason);
			}
		}
	}

	void tickSession(String sessionId)
	{
		for (TaskSession session : getTaskSessions())
		{
			if (session.getSessionId().equals(sessionId))
			{
				session.tick();
				return;
			}
		}
	}

	boolean mergeSessions(String targetId, String sourceId)
	{
		TaskSession target = null;
		TaskSession source = null;
		for (TaskSession session : getTaskSessions())
		{
			if (session.getSessionId().equals(targetId))
			{
				target = session;
			}
			else if (session.getSessionId().equals(sourceId))
			{
				source = session;
			}
		}

		if (target == null || source == null || target.isOpen() || source.isOpen())
		{
			return false;
		}

		target.mergeFrom(source);
		getTaskSessions().remove(source);
		return true;
	}

	/** Pools another assignment record into this one without changing this record's identity. */
	void mergeRecord(TaskLootRecord other)
	{
		final boolean thisWasCompleted = isCompleted();
		ensureLegacyItems();
		other.ensureLegacyItems();
		other.items.forEach((itemId, quantity) -> items.merge(itemId, quantity, Integer::sum));

		if (itemValues == null)
		{
			itemValues = new LinkedHashMap<>();
		}
		if (other.itemValues != null)
		{
			other.itemValues.forEach((itemId, value) ->
				itemValues.merge(itemId, value, Long::sum));
		}

		ensureLegacySupplies();
		other.ensureLegacySupplies();
		other.supplies.forEach((itemId, supply) ->
		{
			final SupplyEntry existing = supplies.get(itemId);
			if (existing == null)
			{
				supplies.put(itemId,
					new SupplyEntry(supply.getQuantity(), supply.getValue(), supply.isDoseBased(),
						supply.getComponents()));
			}
			else
			{
				existing.add(supply.getQuantity(), supply.getValue(), supply.getComponents());
			}
		});

		kills += other.kills;
		supplyCost += other.supplyCost;
		sessions += other.sessions;
		onTaskTicks += other.onTaskTicks;
		getTaskSessions().addAll(other.getTaskSessions());
		startedAt = Math.min(startedAt, other.startedAt);
		initialAmount += other.initialAmount;
		if (thisWasCompleted)
		{
			endedAt = Math.max(endedAt, other.endedAt);
		}
	}

	private TaskSession findSession(String sessionId)
	{
		if (sessionId == null)
		{
			return null;
		}
		for (TaskSession session : getTaskSessions())
		{
			if (sessionId.equals(session.getSessionId()))
			{
				return session;
			}
		}
		return null;
	}

	private void ensureLegacyItems()
	{
		if (items == null)
		{
			items = new LinkedHashMap<>();
		}
	}

	private void ensureLegacySupplies()
	{
		if (supplies == null)
		{
			supplies = new LinkedHashMap<>();
		}
	}

	int getSessions()
	{
		// The scalar is retained as a migration baseline for snapshots written before
		// sessions had identities and segments.
		return sessions + getTaskSessions().size();
	}

	int getOnTaskTicks()
	{
		return onTaskTicks
			+ getTaskSessions().stream().mapToInt(TaskSession::getOnTaskTicks).sum();
	}

	boolean isCompleted()
	{
		return endedAt > 0;
	}
}

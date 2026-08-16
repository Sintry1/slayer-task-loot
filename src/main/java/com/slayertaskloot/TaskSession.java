package com.slayertaskloot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * One user-visible session within a Slayer assignment.
 *
 * <p>A session may contain several active segments. Manually resuming a closed session adds
 * another segment to the same session rather than creating a second user-visible total.
 */
@Getter
class TaskSession
{
	private String sessionId;
	private long startedAt;
	private long endedAt;
	private List<SessionSegment> segments;
	private int kills;
	private Map<Integer, Integer> items;
	private Map<Integer, Long> itemValues;
	private long supplyCost;
	private Map<Integer, SupplyEntry> supplies;

	/** Gson. */
	TaskSession()
	{
	}

	TaskSession(String sessionId, long startedAt, boolean openedManually)
	{
		this.sessionId = sessionId;
		this.startedAt = startedAt;
		this.segments = new ArrayList<>();
		this.items = new LinkedHashMap<>();
		this.itemValues = new LinkedHashMap<>();
		this.supplies = new LinkedHashMap<>();
		openSegment(startedAt, openedManually);
	}

	List<SessionSegment> getSegments()
	{
		if (segments == null)
		{
			segments = new ArrayList<>();
		}
		return segments;
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
			supplies = new LinkedHashMap<>();
		}
		return supplies;
	}

	Map<Integer, Long> getItemValues()
	{
		if (itemValues == null)
		{
			itemValues = new LinkedHashMap<>();
		}
		return itemValues;
	}

	void addKills(int count)
	{
		kills += count;
	}

	void addItem(int itemId, int quantity)
	{
		getItems().merge(itemId, quantity, Integer::sum);
	}

	void addItem(int itemId, int quantity, long value)
	{
		addItem(itemId, quantity);
		getItemValues().merge(itemId, value, Long::sum);
	}

	void addSupplyCharge(SupplyCharge charge)
	{
		supplyCost += charge.getTotal();
		for (SupplyCharge.Row row : charge.getRows())
		{
			final SupplyEntry existing = getSupplies().get(row.getItemId());
			if (existing == null)
			{
				getSupplies().put(row.getItemId(),
					new SupplyEntry(row.getQuantity(), row.getValue(), row.isDoseBased(),
						row.getComponentHundredths()));
			}
			else
			{
				existing.add(row.getQuantity(), row.getValue(), row.getComponentHundredths());
			}
		}
	}

	boolean isOpen()
	{
		return !getSegments().isEmpty()
			&& getSegments().get(getSegments().size() - 1).getEndedAt() == 0;
	}

	void resume(long now)
	{
		if (!isOpen())
		{
			endedAt = 0;
			openSegment(now, true);
		}
	}

	void close(long now, String reason)
	{
		if (!isOpen())
		{
			return;
		}

		getSegments().get(getSegments().size() - 1).close(now, reason);
		endedAt = now;
	}

	void tick()
	{
		if (isOpen())
		{
			getSegments().get(getSegments().size() - 1).tick();
		}
	}

	int getOnTaskTicks()
	{
		return getSegments().stream().mapToInt(SessionSegment::getOnTaskTicks).sum();
	}

	void mergeFrom(TaskSession other)
	{
		getSegments().addAll(other.getSegments());
		getSegments().sort(Comparator.comparingLong(SessionSegment::getStartedAt));
		startedAt = Math.min(startedAt, other.startedAt);
		endedAt = Math.max(endedAt, other.endedAt);
		kills += other.kills;
		other.getItems().forEach((itemId, quantity) ->
			getItems().merge(itemId, quantity, Integer::sum));
		other.getItemValues().forEach((itemId, value) ->
			getItemValues().merge(itemId, value, Long::sum));
		supplyCost += other.supplyCost;
		other.getSupplies().forEach((itemId, supply) ->
		{
			final SupplyEntry existing = getSupplies().get(itemId);
			if (existing == null)
			{
				getSupplies().put(itemId,
					new SupplyEntry(supply.getQuantity(), supply.getValue(), supply.isDoseBased(),
						supply.getComponentHundredths()));
			}
			else
			{
				existing.add(supply.getQuantity(), supply.getValue(), supply.getComponentHundredths());
			}
		});
	}

	private void openSegment(long now, boolean openedManually)
	{
		getSegments().add(new SessionSegment(now, openedManually));
	}

	@Getter
	static class SessionSegment
	{
		private long startedAt;
		private long endedAt;
		private int onTaskTicks;
		private boolean openedManually;
		private String closeReason;

		/** Gson. */
		SessionSegment()
		{
		}

		SessionSegment(long startedAt, boolean openedManually)
		{
			this.startedAt = startedAt;
			this.openedManually = openedManually;
		}

		void tick()
		{
			onTaskTicks++;
		}

		void close(long now, String reason)
		{
			endedAt = now;
			closeReason = reason;
		}
	}
}

package com.slayertaskloot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

/**
 * Running total for one consumed item family on a task record.
 *
 * <p>Persisted as JSON, so it needs a no-argument constructor and plain fields.
 */
@Getter
class SupplyEntry
{
	/** Doses for dose-based families, plain item count otherwise. */
	private int quantity;

	/** Accumulated GE value consumed. */
	private long value;

	/** Whether {@link #quantity} counts doses rather than whole items. */
	private boolean doseBased;

	/**
	 * For a charged item, what its uses consumed as component itemId -> quantity. Null for an
	 * ordinary supply, and null on records written before charges were grouped.
	 */
	private Map<Integer, Integer> components;

	/** Gson. */
	SupplyEntry()
	{
	}

	SupplyEntry(int quantity, long value, boolean doseBased)
	{
		this(quantity, value, doseBased, null);
	}

	SupplyEntry(int quantity, long value, boolean doseBased, Map<Integer, Integer> components)
	{
		this.quantity = quantity;
		this.value = value;
		this.doseBased = doseBased;
		this.components = components == null || components.isEmpty()
			? null : new LinkedHashMap<>(components);
	}

	Map<Integer, Integer> getComponents()
	{
		return components == null ? Collections.emptyMap() : components;
	}

	boolean isCharged()
	{
		return components != null && !components.isEmpty();
	}

	void add(int quantity, long value)
	{
		this.quantity += quantity;
		this.value += value;
	}

	void add(int quantity, long value, Map<Integer, Integer> components)
	{
		add(quantity, value);
		if (components == null || components.isEmpty())
		{
			return;
		}
		if (this.components == null)
		{
			this.components = new LinkedHashMap<>();
		}
		components.forEach((itemId, count) -> this.components.merge(itemId, count, Integer::sum));
	}
}

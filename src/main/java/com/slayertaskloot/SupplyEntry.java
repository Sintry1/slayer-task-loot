package com.slayertaskloot;

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

	/** Gson. */
	SupplyEntry()
	{
	}

	SupplyEntry(int quantity, long value, boolean doseBased)
	{
		this.quantity = quantity;
		this.value = value;
		this.doseBased = doseBased;
	}

	void add(int quantity, long value)
	{
		this.quantity += quantity;
		this.value += value;
	}
}

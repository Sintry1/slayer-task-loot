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
	 * Legacy whole-item component quantities, from records written before a use could spend a
	 * fraction of a component. Read and migrated, never written.
	 */
	private Map<Integer, Integer> components;

	/**
	 * For a charged item, what its uses consumed as component itemId -> <em>hundredths</em> of an
	 * item. Null for an ordinary supply.
	 *
	 * <p>Hundredths because the quantity a single use spends is often a fraction: a scythe swing
	 * costs one hundredth of a vial of blood. Held as whole items, that fraction was only
	 * representable once a hundred swings had accumulated a whole one, so on any ordinary task the
	 * vial never appeared in the breakdown at all — while its cost was in the row's total the
	 * whole time. Tracked but invisible reads exactly like not tracked, and got reported as such.
	 */
	private Map<Integer, Integer> componentHundredths;

	/** Gson. */
	SupplyEntry()
	{
	}

	SupplyEntry(int quantity, long value, boolean doseBased)
	{
		this(quantity, value, doseBased, null);
	}

	SupplyEntry(int quantity, long value, boolean doseBased,
		Map<Integer, Integer> componentHundredths)
	{
		this.quantity = quantity;
		this.value = value;
		this.doseBased = doseBased;
		this.componentHundredths = componentHundredths == null || componentHundredths.isEmpty()
			? null : new LinkedHashMap<>(componentHundredths);
	}

	/** Components in hundredths of an item, migrating a legacy whole-item map on the way out. */
	Map<Integer, Integer> getComponentHundredths()
	{
		if (componentHundredths != null)
		{
			return componentHundredths;
		}
		if (components == null || components.isEmpty())
		{
			return Collections.emptyMap();
		}
		final Map<Integer, Integer> migrated = new LinkedHashMap<>();
		components.forEach((itemId, count) ->
			migrated.put(itemId, count * SupplyCharge.COMPONENT_SCALE));
		return migrated;
	}

	boolean isCharged()
	{
		return !getComponentHundredths().isEmpty();
	}

	void add(int quantity, long value)
	{
		this.quantity += quantity;
		this.value += value;
	}

	void add(int quantity, long value, Map<Integer, Integer> componentHundredths)
	{
		add(quantity, value);
		if (componentHundredths == null || componentHundredths.isEmpty())
		{
			return;
		}
		if (this.componentHundredths == null)
		{
			// Fold any legacy map in first, so a record part-written under either scheme keeps
			// the components it already had rather than restarting the count.
			this.componentHundredths = new LinkedHashMap<>(getComponentHundredths());
			this.components = null;
		}
		componentHundredths.forEach(
			(itemId, count) -> this.componentHundredths.merge(itemId, count, Integer::sum));
	}
}

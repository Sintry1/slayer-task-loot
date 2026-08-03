package com.slayertaskloot;

import java.util.Collections;
import java.util.List;
import lombok.Value;

/**
 * What one supply diff cost, plus the breakdown of what was actually used up.
 *
 * <p>{@link #total} is the sum of every row's value, so a breakdown always adds up to the
 * figure shown next to it.
 */
@Value
class SupplyCharge
{
	static final SupplyCharge EMPTY = new SupplyCharge(0, Collections.emptyList());

	long total;
	List<Row> rows;

	boolean isEmpty()
	{
		return total <= 0 && rows.isEmpty();
	}

	/**
	 * One consumed item family.
	 *
	 * @param itemId    representative id for the family — the largest dose variant for
	 *                  dose-based items, so the icon is the familiar one
	 * @param quantity  doses for dose-based families, plain item count otherwise
	 * @param doseBased whether {@link #quantity} counts doses rather than whole items
	 */
	@Value
	static class Row
	{
		int itemId;
		int quantity;
		long value;
		boolean doseBased;
	}
}

package com.slayertaskloot;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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

	/**
	 * Component quantities are held in hundredths of an item, so that a use spending a fraction
	 * of one — a scythe swing against a vial of blood — is representable at the moment it happens
	 * rather than only once a hundred of them have added up to a whole item.
	 */
	static final int COMPONENT_SCALE = 100;

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

		/**
		 * What one use of a charged item cost, as component itemId -> {@link #COMPONENT_SCALE}ths
		 * of an item, or empty for an ordinary supply.
		 *
		 * <p>When present, {@link #itemId} is the charged item itself and {@link #quantity} counts
		 * its uses, so the row reads "Scythe of Vitur, 400 attacks" with the blood runes and vials
		 * behind it rather than as separate supplies. Grouping them keeps a weapon's running cost
		 * as one line that can be compared against the drops, instead of scattering it across
		 * rune rows that look like spellcasting.
		 */
		Map<Integer, Integer> componentHundredths;

		Row(int itemId, int quantity, long value, boolean doseBased)
		{
			this(itemId, quantity, value, doseBased, Collections.emptyMap());
		}

		Row(int itemId, int quantity, long value, boolean doseBased,
			Map<Integer, Integer> componentHundredths)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.value = value;
			this.doseBased = doseBased;
			this.componentHundredths = componentHundredths;
		}
	}
}

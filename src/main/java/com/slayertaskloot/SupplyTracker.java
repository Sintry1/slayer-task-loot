package com.slayertaskloot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * Values supplies consumed by diffing everything the player is carrying.
 *
 * <p>Rather than matching consumption against a hardcoded table of food, potion, rune and
 * ammunition ids — which needs revisiting every time an item is added to the game — this
 * snapshots what the player carries and charges the loss in GE value between snapshots.
 * One rule covers food, potions, runes, ammunition, cannonballs and teleports at once,
 * and keeps working for items that don't exist yet.
 *
 * <p>Losses are totalled per <em>item family</em> rather than across the whole diff. That
 * matters for accuracy as much as for the breakdown: netting everything together would let
 * a drop picked up on the same tick as a potion sip cancel that sip out, silently losing
 * the cost. Judged per family, the drop's gain and the potion's loss don't meet.
 *
 * <p>A family is a base item name with its dose variants folded in, so a 4-dose leaving and
 * a 3-dose arriving is recognised as a single dose used — costing the difference between the
 * two, not the price of a whole potion. The vial returned by the last dose nets the same way.
 *
 * <p>Inventory, worn equipment and the rune pouch are diffed <em>jointly</em>, because
 * items move between them. Diffing containers separately would read wielding a stack of
 * bolts as consuming the whole stack, and casting from a rune pouch would be invisible
 * entirely (pouch contents live in varbits, not in an item container).
 *
 * <p>Known blind spots, all of which leave no trace in container state: item charges
 * (blowpipe scales, powered staves, tridents), Ruinous Powers, and cannon setup. Items
 * with no GE price — most untradeables, including crystal equipment — are valued at zero.
 */
class SupplyTracker
{
	/** Rune pouch slots. Contents live in varbits rather than an item container. */
	private static final int[] RUNE_POUCH_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1,
		VarbitID.RUNE_POUCH_TYPE_2,
		VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4,
		VarbitID.RUNE_POUCH_TYPE_5,
		VarbitID.RUNE_POUCH_TYPE_6,
	};

	private static final int[] RUNE_POUCH_QUANTITY_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1,
		VarbitID.RUNE_POUCH_QUANTITY_2,
		VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4,
		VarbitID.RUNE_POUCH_QUANTITY_5,
		VarbitID.RUNE_POUCH_QUANTITY_6,
	};

	private final Client client;
	private final ItemManager itemManager;

	/** itemId -> quantity across inventory, worn equipment and rune pouch. Null until baselined. */
	private Map<Integer, Integer> snapshot;

	@Inject
	SupplyTracker(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/** Drop the baseline entirely. The next {@link #charge()} re-baselines and charges nothing. */
	void clear()
	{
		snapshot = null;
	}

	/**
	 * Re-baseline without charging. Used when a container change can't be read as
	 * consumption — banking, trading, shops, the GE, dying, or an explicit drop.
	 */
	void resync()
	{
		snapshot = takeSnapshot();
	}

	/**
	 * Diffs the carried snapshot and reports what was consumed since the previous call.
	 *
	 * <p>Accounting is per item family, not across the whole diff. Only families that lost
	 * value are charged, so a drop picked up on the same tick as a potion sip can no longer
	 * cancel that sip out — each is judged on its own.
	 *
	 * @return the cost and its breakdown, or {@link SupplyCharge#EMPTY} when nothing was
	 * consumed or there was no baseline to compare against
	 */
	SupplyCharge charge()
	{
		final Map<Integer, Integer> current = takeSnapshot();

		if (snapshot == null)
		{
			snapshot = current;
			return SupplyCharge.EMPTY;
		}

		final Set<Integer> ids = new HashSet<>(snapshot.keySet());
		ids.addAll(current.keySet());

		// Grouped by base name so dose variants of one potion are accounted for together.
		final Map<String, Family> families = new LinkedHashMap<>();

		for (Integer id : ids)
		{
			final int delta = current.getOrDefault(id, 0) - snapshot.getOrDefault(id, 0);
			if (delta == 0)
			{
				continue;
			}

			final String name = itemName(id);
			final int doses = doseCount(name);
			final String base = doses > 0 ? name.substring(0, name.lastIndexOf('(')).trim() : name;

			final Family family = families.computeIfAbsent(base, k -> new Family());
			family.value += (long) delta * itemManager.getItemPrice(id);
			// Dose variants are commensurable only in doses: one 4-dose leaving and one
			// 3-dose arriving is a single dose used, not two items changing hands.
			family.units += (long) delta * Math.max(doses, 1);

			if (doses > 0)
			{
				family.doseBased = true;
			}
			if (doses >= family.bestDose)
			{
				family.bestDose = doses;
				family.representativeId = id;
			}
		}

		snapshot = current;

		long total = 0;
		final List<SupplyCharge.Row> rows = new ArrayList<>();

		for (Family family : families.values())
		{
			if (family.value >= 0)
			{
				// Gained value — loot, or a potion topped up. Not a cost.
				continue;
			}

			final long cost = -family.value;
			total += cost;

			if (family.units < 0)
			{
				rows.add(new SupplyCharge.Row(
					family.representativeId,
					(int) -family.units,
					cost,
					family.doseBased));
			}
		}

		return total <= 0 && rows.isEmpty() ? SupplyCharge.EMPTY : new SupplyCharge(total, rows);
	}

	private String itemName(int itemId)
	{
		try
		{
			final ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp != null && comp.getName() != null && !comp.getName().isEmpty())
			{
				return comp.getName();
			}
		}
		catch (Exception ex)
		{
			// Fall through — an unresolvable name just means this id is its own family.
		}
		return "Item #" + itemId;
	}

	/**
	 * Reads the dose count off a trailing "(n)" in an item name, e.g. "Prayer potion(4)".
	 *
	 * <p>Only digits qualify, so parenthesised suffixes that aren't doses — "(i)", "(tablet)",
	 * "(easy)" — are left alone.
	 *
	 * @return the dose count, or 0 when the name isn't dose-based
	 */
	private static int doseCount(String name)
	{
		if (name.isEmpty() || name.charAt(name.length() - 1) != ')')
		{
			return 0;
		}

		final int open = name.lastIndexOf('(');
		if (open <= 0 || open == name.length() - 2)
		{
			return 0;
		}

		int doses = 0;
		for (int i = open + 1; i < name.length() - 1; i++)
		{
			final char c = name.charAt(i);
			if (c < '0' || c > '9')
			{
				return 0;
			}
			doses = doses * 10 + (c - '0');
		}
		return doses;
	}

	/** Accumulator for one item family within a single diff. */
	private static final class Family
	{
		private long value;
		/** Doses for dose-based families, plain item count otherwise. Negative means consumed. */
		private long units;
		private boolean doseBased;
		private int bestDose = -1;
		private int representativeId;
	}

	private Map<Integer, Integer> takeSnapshot()
	{
		final Map<Integer, Integer> out = new HashMap<>();
		addContainer(out, InventoryID.INV);
		addContainer(out, InventoryID.WORN);
		addRunePouch(out);
		return out;
	}

	private void addContainer(Map<Integer, Integer> out, int containerId)
	{
		final ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return;
		}

		for (Item item : container.getItems())
		{
			final int id = item.getId();
			if (id <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			// Noted stacks price the same as the base item, and resolving them keeps a
			// withdraw-as-note from looking like a different item than what gets used.
			out.merge(itemManager.canonicalize(id), item.getQuantity(), Integer::sum);
		}
	}

	private void addRunePouch(Map<Integer, Integer> out)
	{
		final EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runes == null)
		{
			return;
		}

		for (int slot = 0; slot < RUNE_POUCH_TYPE_VARBITS.length; slot++)
		{
			final int type = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[slot]);
			final int quantity = client.getVarbitValue(RUNE_POUCH_QUANTITY_VARBITS[slot]);
			if (type == 0 || quantity <= 0)
			{
				continue;
			}

			final int itemId = runes.getIntValue(type);
			if (itemId > 0)
			{
				out.merge(itemId, quantity, Integer::sum);
			}
		}
	}
}

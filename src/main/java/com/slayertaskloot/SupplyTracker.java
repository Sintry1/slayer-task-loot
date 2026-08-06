package com.slayertaskloot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.http.api.item.ItemPrice;

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
	 * a 3-dose arriving is recognised as a single dose used. Drinkable families are priced from
	 * the full 4-dose container (or 2-dose mix), carrying fractional-GP remainders between uses.
 *
 * <p>Inventory, worn equipment and the rune pouch are diffed <em>jointly</em>, because
 * items move between them. Diffing containers separately would read wielding a stack of
 * bolts as consuming the whole stack, and casting from a rune pouch would be invisible
 * entirely (pouch contents live in varbits, not in an item container).
 *
	 * <p>Selected item charges are added as synthetic resource quantities from RuneLite varbits.
	 * Items with no GE price — most untradeables, including crystal equipment — value at zero.
	 * They're still listed at zero gp when consumed, so that a supply which is tracked but free
	 * doesn't look identical to one that isn't tracked at all.
 */
@Slf4j
class SupplyTracker
{
	/**
	 * Equipment deployed into the world rather than used up. A cannon's four parts leave the
	 * inventory on setup and come back on pickup, so diffing them reads building a cannon as
	 * ~450k of supplies spent and dismantling it as loot found. Neither happened, so they stay
	 * out of the snapshot entirely and no diff can ever see them move.
	 */
	private static final Set<Integer> DEPLOYABLE_ITEMS = new HashSet<>(Arrays.asList(
		ItemID.TWPART1, ItemID.TWPART2, ItemID.TWPART3, ItemID.TWPART4,
		ItemID.LEAGUE_3_MULTICANNON_BASE, ItemID.LEAGUE_3_MULTICANNON_STAND,
		ItemID.LEAGUE_3_MULTICANNON_BARRELS, ItemID.LEAGUE_3_MULTICANNON_FURNACE));


	/**
	 * Items that become a <em>different</em> item when spent, which the variation table has no
	 * reason to connect because they aren't variations of each other.
	 *
	 * <p>An abyssal tentacle reverts to a kraken tentacle: the whip inside it is what was consumed,
	 * and the tentacle is handed back. Left ungrouped, the abyssal tentacle disappearing reads as
	 * its whole GE price being used up in one tick. Mapped onto the form it becomes, the pair nets
	 * to zero items and the transformation guard skips it — the whip having already been billed
	 * per attack over the preceding 10,000 hits.
	 *
	 * <p>Includes the destination mapped to itself, so both sides of the change agree on a key.
	 */
	private static final Map<Integer, Integer> SPENT_FORMS = new HashMap<>();

	static
	{
		SPENT_FORMS.put(ItemID.ABYSSAL_TENTACLE, ItemID.KRAKEN_TENTACLE);
		SPENT_FORMS.put(ItemID.KRAKEN_TENTACLE, ItemID.KRAKEN_TENTACLE);
	}

	/**
	 * Ticks an unmatched drop stays outstanding. Long enough for the container update to land,
	 * short enough that it can't swallow a real use of the same item later in the trip.
	 */
	private static final int IGNORE_EXPIRY_TICKS = 3;

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
	private final Map<String, DosePrice> dosePrices = new HashMap<>();
	private final Map<String, Long> doseRemainders = new HashMap<>();

	/** Explicit drops awaiting the container update that carries them out. */
	private final Map<Integer, PendingIgnore> pendingIgnores = new HashMap<>();
	private boolean eyeUsesTears = true;

	/**
	 * Ammunition currently in the cannon's magazine. Only the load message says which of the
	 * two kinds it is; the count itself is a plain varp.
	 */
	private int loadedCannonballId = ItemID.MCANNONBALL;

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
		pendingIgnores.clear();
	}

	/**
	 * Switches which resource an eye of ayak is billed against, re-baselining so the swap
	 * between two different synthetic quantities isn't itself read as consumption. Silent when
	 * the answer hasn't changed — this is re-applied on every config change, and a resync there
	 * would throw away whatever that tick had used.
	 */
	boolean isEyeUsingTears()
	{
		return eyeUsesTears;
	}

	void setEyeUsesTears(boolean eyeUsesTears)
	{
		if (this.eyeUsesTears == eyeUsesTears)
		{
			return;
		}
		this.eyeUsesTears = eyeUsesTears;
		resync();
	}

	/**
	 * Records which ammunition the cannon is loaded with. Deliberately doesn't re-baseline:
	 * the type can only change across an unload, which empties the magazine first, so the
	 * ammunition counted under the old id has already returned to the inventory.
	 */
	void setLoadedCannonballId(int itemId)
	{
		if (itemId > 0)
		{
			loadedCannonballId = itemId;
		}
	}



	/**
	 * Re-baseline without charging. Used when a container change can't be read as
	 * consumption — banking, trading, shops, the GE, dying, or an explicit drop.
	 */
	void resync()
	{
		snapshot = takeSnapshot();
		// The new baseline already reflects whatever happened, so nothing is left to forgive.
		pendingIgnores.clear();
	}

	/**
	 * Marks an explicitly dropped stack as not consumption, to be honoured whenever the removal
	 * actually shows up.
	 *
	 * <p>A menu click arrives before the container update that carries it out, and how many ticks
	 * later that update lands isn't ours to decide. Adjusting the baseline on the spot only worked
	 * if the removal landed within the same tick's diff: if it landed later, the next diff
	 * overwrote the baseline with a snapshot that still held the item, and the removal after that
	 * was charged as consumption. That is why dropping an untradeable posted it as a 0 gp supply.
	 *
	 * <p>Held as an intention instead, and matched against the loss whenever it appears. Intentions
	 * expire so that a drop can't quietly absorb a genuine use of the same item minutes later.
	 */
	void ignoreRemoval(int itemId, int quantity)
	{
		if (itemId <= 0 || quantity <= 0)
		{
			return;
		}
		final PendingIgnore pending = pendingIgnores.computeIfAbsent(
			itemManager.canonicalize(itemId), k -> new PendingIgnore());
		pending.quantity += quantity;
		pending.ticksLeft = IGNORE_EXPIRY_TICKS;
	}

	/**
	 * Cancels a loss against an outstanding intention to drop that item.
	 *
	 * @return the delta with any ignored quantity absorbed, leaving 0 when the whole loss was the
	 * drop the player asked for
	 */
	static int applyIgnoredRemoval(Map<Integer, PendingIgnore> ignores, int itemId, int delta)
	{
		if (delta >= 0)
		{
			return delta;
		}
		final PendingIgnore pending = ignores.get(itemId);
		if (pending == null)
		{
			return delta;
		}

		final int absorbed = Math.min(pending.quantity, -delta);
		pending.quantity -= absorbed;
		if (pending.quantity <= 0)
		{
			ignores.remove(itemId);
		}
		return delta + absorbed;
	}

	private void expireIgnoredRemovals()
	{
		pendingIgnores.values().removeIf(pending -> --pending.ticksLeft <= 0);
	}

	/** An explicit drop waiting for the container update that performs it. */
	static final class PendingIgnore
	{
		private int quantity;
		private int ticksLeft;
	}

	/** Test seam: an outstanding drop of {@code quantity}, fresh enough not to have expired. */
	static PendingIgnore pendingIgnore(int quantity)
	{
		final PendingIgnore pending = new PendingIgnore();
		pending.quantity = quantity;
		pending.ticksLeft = IGNORE_EXPIRY_TICKS;
		return pending;
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
			final int delta = applyIgnoredRemoval(
				pendingIgnores, id, current.getOrDefault(id, 0) - snapshot.getOrDefault(id, 0));
			if (delta == 0)
			{
				continue;
			}
			if (delta < 0 && isLootOnlyItem(id))
			{
				// Prayer remains and ensouled heads are loot rather than task supplies.
				// Never let their removal create a row which netting cancels against loot.
				continue;
			}

			final String name = itemName(id);
			final int doses = doseCount(name);
			final String base = doses > 0
				? name.substring(0, name.lastIndexOf('(')).trim()
				: familyKey(id, name);

			final Family family = families.computeIfAbsent(base, k -> new Family());
			family.value += (long) delta * itemManager.getItemPrice(id);
			// Dose variants are commensurable only in doses: one 4-dose leaving and one
			// 3-dose arriving is a single dose used, not two items changing hands.
			family.units += (long) delta * Math.max(doses, 1);

			if (doses > 0)
			{
				family.doseBased = true;
			}
			// Represent the family by what was consumed, not what replaced it, so a row reads
			// "Prayer potion" and not the 3-dose it turned into.
			if (delta < 0 && doses >= family.bestDose)
			{
				family.bestDose = doses;
				family.representativeId = id;
			}
		}

		final Map<Integer, Integer> snapshotBefore = snapshot;
		snapshot = current;

		long total = 0;
		final List<SupplyCharge.Row> rows = new ArrayList<>();

		for (Map.Entry<String, Family> entry : families.entrySet())
		{
			final String base = entry.getKey();
			final Family family = entry.getValue();

			// One item out, one item in: a transformation, not consumption. A trident running dry
			// or being recharged, barrows gear degrading a step, a scythe emptying — the item count
			// is unchanged and nothing was used up, but the two forms can differ by the item's
			// entire GE price, because the charged form of most of them is untradeable and prices
			// at zero. Billing that difference posted the full cost of an uncharged trident for
			// recharging one. The runes and coins that did the recharging are still charged; they
			// really did leave the inventory.
			if (!family.doseBased && family.units == 0)
			{
				continue;
			}
			// A family that lost units but no value is an untradeable being consumed: ecto-tokens
			// off a bonecrusher, say. Reported as a zero-gp row rather than dropped, so that
			// "tracked, but genuinely free" is distinguishable from "not tracked at all" — the
			// silence was indistinguishable from a bug, and got reported as one.
			final boolean unpricedConsumption = family.value == 0 && family.units < 0;
			if (family.value >= 0
				&& !(family.doseBased && family.units < 0)
				&& !unpricedConsumption)
			{
				// Gained value — loot, or a potion topped up. Not a cost.
				continue;
			}

			long cost = -family.value;
			if (family.doseBased && family.units < 0 && isDrinkable(family.representativeId))
			{
				final DosePrice dosePrice = resolveDosePrice(base);
				if (dosePrice != null)
				{
					final long numerator = (long) -family.units * dosePrice.fullPrice
						+ doseRemainders.getOrDefault(base, 0L);
					cost = numerator / dosePrice.doses;
					doseRemainders.put(base, numerator % dosePrice.doses);
					family.representativeId = dosePrice.itemId;
				}
				else
				{
					family.doseBased = false;
				}
			}
			else if (family.doseBased)
			{
				// Teleport jewellery and other charged items also use numeric suffixes. Only an
				// item with a Drink action may use potion normalization.
				family.doseBased = false;
			}
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

		expireIgnoredRemovals();
		if (!rows.isEmpty() && log.isDebugEnabled())
		{
			// Every charge, with the raw per-id deltas behind it. A charge that shouldn't have
			// happened is otherwise indistinguishable from one that should: the panel shows the
			// same row either way, and netting hides it entirely once loot cancels it out.
			// Printing what the diff actually saw is what settled the CHARGES_* block, and it's
			// the only way to tell "the pouch wasn't in the snapshot this tick" apart from
			// "these runes really were spent".
			for (SupplyCharge.Row row : rows)
			{
				log.debug("charged {} x{} for {} gp", itemName(row.getItemId()), row.getQuantity(),
					row.getValue());
			}
			for (Integer id : ids)
			{
				final int before = snapshotBefore.getOrDefault(id, 0);
				final int after = current.getOrDefault(id, 0);
				if (before != after)
				{
					log.debug("  diff {} ({}) {} -> {}", itemName(id), id, before, after);
				}
			}
			logRunePouch();
		}
		return total <= 0 && rows.isEmpty() ? SupplyCharge.EMPTY : new SupplyCharge(total, rows);
	}

	SupplyCharge componentCharge(int... itemIdQuantityPairs)
	{
		long total = 0;
		final List<SupplyCharge.Row> rows = new ArrayList<>();
		for (int i = 0; i + 1 < itemIdQuantityPairs.length; i += 2)
		{
			final int itemId = itemIdQuantityPairs[i];
			final int quantity = itemIdQuantityPairs[i + 1];
			if (quantity <= 0)
			{
				continue;
			}
			final long value = (long) itemManager.getItemPrice(itemId) * quantity;
			total += value;
			rows.add(new SupplyCharge.Row(itemId, quantity, value, false));
		}
		return total == 0 ? SupplyCharge.EMPTY : new SupplyCharge(total, rows);
	}

	/**
	 * One use of a charged item, billed as a single row named after the item with its components
	 * behind it.
	 *
	 * @param chargedItemId the item being used up, which the row is named after
	 * @param extraValue    cost that isn't a whole component, such as a fraction of a vial
	 * @param itemIdQuantityPairs component item ids and how many of each one use consumes
	 */
	SupplyCharge chargedItemUse(int chargedItemId, long extraValue, int... itemIdQuantityPairs)
	{
		long total = extraValue;
		final Map<Integer, Integer> components = new LinkedHashMap<>();
		for (int i = 0; i + 1 < itemIdQuantityPairs.length; i += 2)
		{
			final int itemId = itemIdQuantityPairs[i];
			final int quantity = itemIdQuantityPairs[i + 1];
			if (quantity <= 0)
			{
				continue;
			}
			total += (long) itemManager.getItemPrice(itemId) * quantity;
			components.merge(itemId, quantity, Integer::sum);
		}

		if (chargedItemId <= 0)
		{
			return SupplyCharge.EMPTY;
		}
		return new SupplyCharge(total, Collections.singletonList(
			new SupplyCharge.Row(chargedItemId, 1, total, false, components)));
	}

	/** Resolves a dose family to its tradeable full container: (4), or (2) for mixes. */
	private DosePrice resolveDosePrice(String base)
	{
		if (dosePrices.containsKey(base))
		{
			return dosePrices.get(base);
		}

		DosePrice best = null;
		for (ItemPrice candidate : itemManager.search(base))
		{
			final String name = candidate.getName();
			if (name == null)
			{
				continue;
			}
			if (name.equalsIgnoreCase(base + "(4)"))
			{
				best = new DosePrice(candidate.getId(), 4, itemManager.getItemPrice(candidate.getId()));
				break;
			}
			if (name.equalsIgnoreCase(base + "(2)"))
			{
				best = new DosePrice(candidate.getId(), 2, itemManager.getItemPrice(candidate.getId()));
			}
		}

		if (best != null && best.fullPrice > 0)
		{
			dosePrices.put(base, best);
			return best;
		}
		return null;
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

	private boolean isDrinkable(int itemId)
	{
		try
		{
			final ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp != null && comp.getInventoryActions() != null)
			{
				for (String action : comp.getInventoryActions())
				{
					if ("Drink".equalsIgnoreCase(action))
					{
						return true;
					}
				}
			}
		}
		catch (Exception ex)
		{
			// Fall back to the observed variant-value delta if composition data is unavailable.
		}
		return false;
	}

	boolean isLootOnlyItem(int itemId)
	{
		try
		{
			final ItemComposition comp = itemManager.getItemComposition(itemId);
			return comp != null && (hasPrayerRemainsAction(comp.getInventoryActions())
				|| isLootOnlyName(comp.getName()));
		}
		catch (Exception ex)
		{
			return false;
		}
	}

	/** Testable classification shared by every kind of bone and demonic ash. */
	static boolean hasPrayerRemainsAction(String[] actions)
	{
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if ("Bury".equalsIgnoreCase(action) || "Scatter".equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	static boolean isLootOnlyName(String name)
	{
		if (name == null)
		{
			return false;
		}
		final String lower = name.toLowerCase(java.util.Locale.ROOT);
		return lower.endsWith(" ashes") || lower.endsWith(" bones")
			|| (lower.startsWith("ensouled ") && lower.endsWith(" head"));
	}

	Map<Integer, Integer> takeRunePouchSnapshot()
	{
		final Map<Integer, Integer> snapshot = new HashMap<>();
		addRunePouch(snapshot);
		return snapshot;
	}

	/**
	 * Reads the dose count off a trailing "(n)" in an item name, e.g. "Prayer potion(4)".
	 *
	 * <p>Only digits qualify, so parenthesised suffixes that aren't doses — "(i)", "(tablet)",
	 * "(easy)" — are left alone.
	 *
	 * @return the dose count, or 0 when the name isn't dose-based
	 */
	// Package-private rather than private so the parsing can be tested directly; it's a
	// heuristic over item names and the only part of this class testable without a client.
	static int doseCount(String name)
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

	/**
	 * Groups an item with every other form of itself, so an item that transforms rather than
	 * being consumed stays in one family.
	 *
	 * <p>Uses RuneLite's variation table, which already knows that "Trident of the seas (full)",
	 * "Trident of the seas" and "Uncharged trident" are one item in three states — a relationship
	 * no rule over their names could recover, since they share no stem. The name-suffix heuristic
	 * remains as the fallback for items the table doesn't cover.
	 *
	 * @return a key that is stable for one item across all of its forms
	 */
	static String familyKey(int itemId, String name)
	{
		final int variationBase = ItemVariationMapping.map(itemId);
		final Integer spentForm = SPENT_FORMS.get(variationBase);
		if (spentForm != null)
		{
			return "#" + spentForm;
		}
		if (variationBase != itemId || ItemVariationMapping.getVariations(itemId).size() > 1)
		{
			// Prefixed so a variation group can never collide with a plain item name.
			return "#" + variationBase;
		}
		return stripChargeState(name);
	}

	/** Charge-state suffixes that mark the spent form of an otherwise identical item. */
	private static final String[] SPENT_SUFFIXES = {
		" (uncharged)", " (empty)", " (deg)", " (broken)", " (inactive)",
	};

	/** Degradation steps Barrows-style equipment cycles through, as name suffixes. */
	private static final String[] DEGRADE_STEPS = { "100", "75", "50", "25", "0" };

	/**
	 * Strips charge or degradation state from an item name so an item that transforms into a
	 * spent version of itself stays in one family.
	 *
	 * <p>Without this, an item changing id looks like one item vanishing and an unrelated one
	 * appearing. The vanishing side is a value loss and gets billed at the item's <em>full</em>
	 * price — so a Scythe of Vitur running dry would be charged as if the scythe itself had been
	 * consumed. Grouped, the same event correctly costs only the difference between the charged
	 * and uncharged forms.
	 *
	 * <p>Doesn't catch renames that don't share a stem: "Trident of the swamp" becomes
	 * "Uncharged trident", which no suffix rule can connect.
	 */
	static String stripChargeState(String name)
	{
		for (String suffix : SPENT_SUFFIXES)
		{
			if (name.endsWith(suffix))
			{
				return name.substring(0, name.length() - suffix.length());
			}
		}

		final int lastSpace = name.lastIndexOf(' ');
		if (lastSpace > 0)
		{
			final String tail = name.substring(lastSpace + 1);
			for (String step : DEGRADE_STEPS)
			{
				if (tail.equals(step))
				{
					return name.substring(0, lastSpace);
				}
			}
		}

		return name;
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

	private static final class DosePrice
	{
		private final int itemId;
		private final int doses;
		private final int fullPrice;

		private DosePrice(int itemId, int doses, int fullPrice)
		{
			this.itemId = itemId;
			this.doses = doses;
			this.fullPrice = fullPrice;
		}
	}

	private Map<Integer, Integer> takeSnapshot()
	{
		final Map<Integer, Integer> out = new HashMap<>();
		addContainer(out, InventoryID.INV);
		addContainer(out, InventoryID.WORN);
		addRunePouch(out);
		addChargeResources(out);
		return out;
	}

	private void addChargeResources(Map<Integer, Integer> out)
	{
		// Blowpipe scales are billed per attack instead — see
		// SlayerTaskLootPlugin.chargeBlowpipeUse(). CHARGES_TOXIC_BLOWPIPE_QUANTITY never moved.
		// Zulrah's scales also charge the serpentine helm, toxic staff of the dead and trident of
		// the swamp, so they are attributed to the item that spent them rather than pooled into
		// one scales row that couldn't be traced back to anything.
		// The sanguinesti staff and both tridents are billed per cast from the powered-staff attack
		// animation instead — see SlayerTaskLootPlugin.POWERED_STAFF_COSTS. Their charge varbits
		// were read here and never moved.

		// Ammunition loaded into a cannon hasn't been spent — it's still the player's until it
		// is fired. Counting the magazine as carried makes loading net to zero against the
		// stack leaving the inventory, and each shot a loss of exactly one ball. Watching
		// cannonball projectiles fly, which is what this replaced, missed the shots entirely
		// whenever the cannon's position wasn't established.
		addSynthetic(out, loadedCannonballId, client.getVarpValue(VarPlayerID.ROCKTHROWER), 1);

		// The tome of fire is billed per cast from its spell graphic — see
		// SlayerTaskLootPlugin.onGraphicChanged().

		// The venator bow's ancient essence is charged per attack animation instead — see
		// SlayerTaskLootPlugin.processRepeatingWeaponUsage(). CHARGES_VENATOR_BOW_QUANTITY was
		// read here and never once moved in game.

		// Sunfire splinters are counted from the quiver's ammunition falling, and the eye of ayak
		// from its attack animation — see SlayerTaskLootPlugin. Every CHARGES_*_QUANTITY varbit
		// this class once read was measured at zero and never moved; none of them is populated,
		// so nothing is read from that block any more.

		final int quiverAmmoId = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
		final int quiverAmmo = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
		if (quiverAmmoId > 0 && quiverAmmo > 0)
		{
			out.merge(itemManager.canonicalize(quiverAmmoId), quiverAmmo, Integer::sum);
		}
	}

	private static void addSynthetic(Map<Integer, Integer> out, int itemId, int charges, int perCharge)
	{
		if (charges > 0)
		{
			out.merge(itemId, charges * perCharge, Integer::sum);
		}
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
			if (id <= 0 || item.getQuantity() <= 0 || DEPLOYABLE_ITEMS.contains(id))
			{
				continue;
			}
			// Noted stacks price the same as the base item, and resolving them keeps a
			// withdraw-as-note from looking like a different item than what gets used.
			out.merge(itemManager.canonicalize(id), item.getQuantity(), Integer::sum);
		}
	}

	/**
	 * The pouch as the snapshot sees it, slot by slot, including slots it decided to skip.
	 *
	 * <p>Runes moving from the inventory into the pouch should be invisible to a charge: both
	 * sides are in one snapshot keyed by item id, so the transfer nets to zero and no family is
	 * ever built. A charge for pouched runes therefore means this read came back missing them,
	 * and only the raw slot values say which of type, quantity or the enum lookup was the one
	 * that failed.
	 */
	private void logRunePouch()
	{
		final EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runes == null)
		{
			log.debug("  pouch enum RUNEPOUCH_RUNE unavailable, pouch contributed nothing");
			return;
		}
		for (int slot = 0; slot < RUNE_POUCH_TYPE_VARBITS.length; slot++)
		{
			final int type = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[slot]);
			final int quantity = client.getVarbitValue(RUNE_POUCH_QUANTITY_VARBITS[slot]);
			if (type == 0 && quantity == 0)
			{
				continue;
			}
			log.debug("  pouch slot {} type {} -> item {} x{}", slot + 1, type,
				runes.getIntValue(type), quantity);
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

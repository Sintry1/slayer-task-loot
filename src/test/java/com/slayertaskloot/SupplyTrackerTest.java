package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Covers the dose-suffix parsing that decides which items get folded into one family.
 *
 * <p>A false positive here would misprice a supply by up to 4x, so the parenthesised names
 * that <em>aren't</em> doses matter as much as the ones that are.
 */
public class SupplyTrackerTest
{
	@Test
	public void parsesDoseSuffixes()
	{
		assertEquals(4, SupplyTracker.doseCount("Prayer potion(4)"));
		assertEquals(3, SupplyTracker.doseCount("Prayer potion(3)"));
		assertEquals(1, SupplyTracker.doseCount("Super restore(1)"));
		assertEquals(4, SupplyTracker.doseCount("Saradomin brew(4)"));
		assertEquals(2, SupplyTracker.doseCount("Stamina potion(2)"));
	}

	@Test
	public void ignoresParenthesesThatAreNotDoses()
	{
		// Imbued rings, tablets and clue tiers all end in brackets without being dosed.
		assertEquals(0, SupplyTracker.doseCount("Ring of the gods (i)"));
		assertEquals(0, SupplyTracker.doseCount("Teleport to house (tablet)"));
		assertEquals(0, SupplyTracker.doseCount("Clue scroll (easy)"));
		assertEquals(0, SupplyTracker.doseCount("Prayer cape(t)"));
		assertEquals(0, SupplyTracker.doseCount("Crystal helm (inactive)"));
	}

	@Test
	public void ignoresNamesWithNoSuffix()
	{
		assertEquals(0, SupplyTracker.doseCount("Shark"));
		assertEquals(0, SupplyTracker.doseCount("Vial"));
		assertEquals(0, SupplyTracker.doseCount("Adamant bolts"));
		assertEquals(0, SupplyTracker.doseCount(""));
	}

	@Test
	public void groupsSpentFormsWithTheirChargedItem()
	{
		// Billing the full item price when a charged weapon runs dry would be catastrophic
		// on a Scythe, so these have to land in the same family as their charged form.
		assertEquals("Scythe of vitur", SupplyTracker.stripChargeState("Scythe of vitur (uncharged)"));
		assertEquals("Toxic blowpipe", SupplyTracker.stripChargeState("Toxic blowpipe (empty)"));
		assertEquals("Sanguinesti staff", SupplyTracker.stripChargeState("Sanguinesti staff (uncharged)"));
		assertEquals("Serpentine helm", SupplyTracker.stripChargeState("Serpentine helm (uncharged)"));
		assertEquals("Crystal helm", SupplyTracker.stripChargeState("Crystal helm (inactive)"));
	}

	@Test
	public void groupsDegradationStepsWithTheirIntactItem()
	{
		assertEquals("Dharok's greataxe", SupplyTracker.stripChargeState("Dharok's greataxe 100"));
		assertEquals("Dharok's greataxe", SupplyTracker.stripChargeState("Dharok's greataxe 75"));
		assertEquals("Karil's leathertop", SupplyTracker.stripChargeState("Karil's leathertop 0"));
	}

	@Test
	public void leavesOrdinaryNamesAlone()
	{
		assertEquals("Shark", SupplyTracker.stripChargeState("Shark"));
		assertEquals("Adamant bolts", SupplyTracker.stripChargeState("Adamant bolts"));
		assertEquals("Ring of the gods (i)", SupplyTracker.stripChargeState("Ring of the gods (i)"));
		// A trailing number that isn't a degradation step must not be stripped.
		assertEquals("Team-18 cape", SupplyTracker.stripChargeState("Team-18 cape"));
	}

	@Test
	public void handlesMalformedSuffixes()
	{
		// An empty or unbalanced bracket must not be read as a dose count.
		assertEquals(0, SupplyTracker.doseCount("Weird item()"));
		assertEquals(0, SupplyTracker.doseCount(")"));
		assertEquals(0, SupplyTracker.doseCount("(4)"));
		assertEquals(0, SupplyTracker.doseCount("Mixed(4a)"));
	}

	@Test
	public void explicitDropAbsorbsTheLossItCauses()
	{
		final Map<Integer, SupplyTracker.PendingIgnore> ignores = new HashMap<>();
		ignores.put(100, SupplyTracker.pendingIgnore(5));

		// The drop lands a tick later than the click, so the loss has to be forgiven whenever it
		// turns up — not only in the tick the click happened.
		assertEquals(0, SupplyTracker.applyIgnoredRemoval(ignores, 100, -5));
		assertFalse(ignores.containsKey(100));
	}

	@Test
	public void onlyTheDroppedQuantityIsForgiven()
	{
		final Map<Integer, SupplyTracker.PendingIgnore> ignores = new HashMap<>();
		ignores.put(100, SupplyTracker.pendingIgnore(2));

		// Dropping two and eating three is a cost of one, not a free lunch.
		assertEquals(-1, SupplyTracker.applyIgnoredRemoval(ignores, 100, -3));
		assertFalse(ignores.containsKey(100));

		// A gain is never a drop, and an untracked item is never forgiven.
		assertEquals(4, SupplyTracker.applyIgnoredRemoval(ignores, 100, 4));
		assertEquals(-6, SupplyTracker.applyIgnoredRemoval(ignores, 999, -6));
	}

	@Test
	public void everyFormOfOneItemSharesAFamily()
	{
		// Trident of the seas (full), the partly charged form, and the uncharged form share no
		// stem in their names, so only the variation table can connect them. Recharging or
		// depleting one used to bill the full price of whichever form vanished.
		final String full = SupplyTracker.familyKey(ItemID.TOTS, "Trident of the seas (full)");
		final String charged = SupplyTracker.familyKey(ItemID.TOTS_CHARGED, "Trident of the seas");
		final String uncharged = SupplyTracker.familyKey(ItemID.TOTS_UNCHARGED, "Uncharged trident");

		assertEquals(full, charged);
		assertEquals(charged, uncharged);

		// The swamp variants are their own item, and must not merge into the seas family.
		assertNotEquals(charged,
			SupplyTracker.familyKey(ItemID.TOXIC_TOTS_CHARGED, "Trident of the swamp"));
	}

	@Test
	public void anItemThatRevertsToADifferentItemStaysInOneFamily()
	{
		// A spent abyssal tentacle hands back a kraken tentacle. They are not variations of each
		// other, so without the explicit pairing the tentacle vanishing bills its full price.
		assertEquals(
			SupplyTracker.familyKey(ItemID.ABYSSAL_TENTACLE, "Abyssal tentacle"),
			SupplyTracker.familyKey(ItemID.KRAKEN_TENTACLE, "Kraken tentacle"));

		// The whip is the part actually consumed, and is its own item.
		assertNotEquals(
			SupplyTracker.familyKey(ItemID.ABYSSAL_WHIP, "Abyssal whip"),
			SupplyTracker.familyKey(ItemID.KRAKEN_TENTACLE, "Kraken tentacle"));
	}

	@Test
	public void unrelatedItemsFallBackToTheNameHeuristic()
	{
		// An item outside the variation table keeps the old suffix behaviour.
		assertEquals(
			SupplyTracker.familyKey(-1, "Dharok's greataxe 100"),
			SupplyTracker.familyKey(-2, "Dharok's greataxe 25"));
		assertNotEquals(
			SupplyTracker.familyKey(-1, "Shark"),
			SupplyTracker.familyKey(-2, "Lobster"));
	}
}

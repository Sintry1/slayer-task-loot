package com.slayertaskloot;

import net.runelite.api.gameval.ItemID;

/**
 * Which page a tome of fire is filled with.
 *
 * <p>There is no automatic option because there is nothing to infer from. Both pages give the
 * same 20 charges into the same pool, the charged tome is one item id either way, and the client
 * is told only how many charges remain — so a cast is indistinguishable between them, while the
 * pages differ several-fold in price.
 */
public enum TomePage
{
	BURNT_PAGE("Burnt page", ItemID.WINT_BURNT_PAGE),
	SEARING_PAGE("Searing page", ItemID.WINT_SEARING_PAGE);

	/** Charges one page adds, the same for both kinds. */
	static final int CHARGES_PER_PAGE = 20;

	private final String label;
	private final int itemId;

	TomePage(String label, int itemId)
	{
		this.label = label;
		this.itemId = itemId;
	}

	int getItemId()
	{
		return itemId;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

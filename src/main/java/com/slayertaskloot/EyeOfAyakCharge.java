package com.slayertaskloot;

/** Which resource an eye of ayak is charged with. */
public enum EyeOfAyakCharge
{
	AUTOMATIC("Automatic"),
	DEMON_TEARS("Demon tears"),
	RUNES("Death and chaos runes");

	private final String label;

	EyeOfAyakCharge(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

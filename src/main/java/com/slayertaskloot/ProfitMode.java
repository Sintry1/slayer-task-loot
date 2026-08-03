package com.slayertaskloot;

enum ProfitMode
{
	GROSS("Gross drop value"),
	NET("Net profit after supplies");

	private final String name;

	ProfitMode(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}

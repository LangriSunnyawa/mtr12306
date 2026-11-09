package com.mtr12306.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 车站。可以直接在网页后台手工添加，也可以从 MTR 模组的车站列表导入。
 */
public class Station {
	public String id = "";
	public String name = "";
	/** 对应 MTR 车站名称，仅用于展示与同步 */
	public String mtrStation = "";
	/** 站台号，例如 1、2、3A */
	public List<String> platforms = new ArrayList<>();
	/** 备注 */
	public String note = "";

	public Station() {
	}

	public Station(final String id, final String name) {
		this.id = id;
		this.name = name;
	}
}

package com.mtr12306.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 闸机与车站/检票口的绑定关系。
 * 闸机绑定到「车站」，检票口可多选；不选任何检票口表示该站所有检票口都可用（不限）。
 */
public class GateBinding {
	public String dimension = "";
	public int x;
	public int y;
	public int z;
	/** 车站 id */
	public String stationId = "";
	/** 检票口 id 列表（空 = 不限） */
	public List<String> checkpointIds = new ArrayList<>();
	/** ENTRY / EXIT / BOTH / TRANSFER / VALIDATE */
	public String mode = "ENTRY";

	public GateBinding() {
	}

	public GateBinding(final String dimension, final int x, final int y, final int z) {
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public String key() {
		return dimension + "@" + x + "," + y + "," + z;
	}
}

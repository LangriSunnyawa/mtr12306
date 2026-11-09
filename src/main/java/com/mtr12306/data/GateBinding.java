package com.mtr12306.data;

/**
 * 闸机与检票口的绑定关系。
 * 闸机方块自身也会保存一份，这里保存的是供网页后台查看/修改的副本。
 */
public class GateBinding {
	public String dimension = "";
	public int x;
	public int y;
	public int z;
	public String checkpointId = "";
	/** ENTRY（进站检票）/ EXIT（出站）/ BOTH（双向检票） */
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

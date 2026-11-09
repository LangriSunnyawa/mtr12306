package com.mtr12306.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 检票口（属于某个车站），例如「中央车站 A1 检票口」。
 * 闸机会绑定到检票口，只有绑定检票口正在检票时才会校验车票。
 */
public class Checkpoint {
	public String id = "";
	public String stationId = "";
	/** 检票口名称，例如 A1 */
	public String name = "";
	/** 该检票口负责的站台，留空表示负责本站所有站台 */
	public List<String> platforms = new ArrayList<>();
	public boolean enabled = true;

	public Checkpoint() {
	}

	public Checkpoint(final String id, final String stationId, final String name) {
		this.id = id;
		this.stationId = stationId;
		this.name = name;
	}
}

package com.mtr12306.data;

import java.util.ArrayList;
import java.util.List;

/** 车场排班表：开启后每班发车都要填写车次号（交路有几条线路就填几个，用 | 分隔）。 */
public class ScheduleDepot {
	public String name = "";
	/** 是否启用排班表 */
	public boolean useSchedule = false;
	/** 交路中的线路数量（决定每班需要几个车次号） */
	public int routes = 1;
	public List<ScheduleEntry> entries = new ArrayList<>();

	public static class ScheduleEntry {
		/** 发车时刻 HH:mm */
		public String depart = "";
		/** 车次号，用 | 分隔 */
		public String trains = "";

		public List<String> trainList() {
			final List<String> list = new ArrayList<>();
			if (trains != null) {
				for (final String part : trains.split("\\|")) {
					if (!part.isBlank()) {
						list.add(part.trim());
					}
				}
			}
			return list;
		}
	}
}

package com.mtr12306.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 车次（例如 G1234），包含沿途停靠站、时刻、坐席与车厢配置。
 */
public class TrainService {
	/** 车次号，例如 G1234 */
	public String number = "";
	/** 列车名称/备注，例如 城际动车 */
	public String name = "";
	/** 开行规则：yyyy-MM-dd 指定日期，或 * 表示每天 */
	public String date = "*";
	/** 定员（旧字段，保留兼容；实际以坐席配置为准） */
	public int capacity = 600;
	/** 停靠站列表，顺序即行车顺序 */
	public List<ServiceStop> stops = new ArrayList<>();
	/** 坐席与车厢配置 */
	public List<SeatClassConfig> seatClasses = new ArrayList<>();
	public boolean enabled = true;

	public TrainService() {
	}

	public TrainService(final String number) {
		this.number = number;
	}

	public boolean runsOn(final String dateString) {
		return "*".equals(date) || dateString.equals(date);
	}

	public int indexOfStation(final String stationId) {
		for (int i = 0; i < stops.size(); i++) {
			if (stops.get(i).stationId.equals(stationId)) {
				return i;
			}
		}
		return -1;
	}

	public ServiceStop stopAt(final String stationId) {
		final int index = indexOfStation(stationId);
		return index < 0 ? null : stops.get(index);
	}

	public SeatClassConfig seatClass(final String code) {
		for (final SeatClassConfig seatClass : seatClasses) {
			if (seatClass.code.equalsIgnoreCase(code)) {
				return seatClass;
			}
		}
		return null;
	}

	public List<SeatClassConfig> effectiveSeatClasses() {
		return seatClasses.isEmpty() ? SeatClassConfig.defaults() : seatClasses;
	}

	public int totalSeats() {
		int total = 0;
		for (final SeatClassConfig seatClass : effectiveSeatClasses()) {
			total += seatClass.seatCount();
		}
		return Math.max(1, total);
	}
}

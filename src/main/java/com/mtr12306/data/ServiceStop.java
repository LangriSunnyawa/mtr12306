package com.mtr12306.data;

/**
 * 车次在某个车站的停靠信息。
 * 时刻使用 24 小时制 HH:mm，dayOffset 用于处理跨日车次；
 * distance 为该站相对始发站的累计里程（公里），票价 = 里程差 × 每公里单价 × 坐席倍率。
 */
public class ServiceStop {
	public String stationId = "";
	public String platform = "";
	/** 到达时刻，始发站可留空 */
	public String arrive = "";
	/** 发车时刻，终到站可留空 */
	public String depart = "";
	/** 指定检票口，留空则按站台自动匹配 */
	public String checkpointId = "";
	/** 相对开行日期的天数偏移，跨零点后 +1 */
	public int dayOffset = 0;
	/** 累计里程（公里） */
	public double distance = 0.0D;
	/** 兼容旧数据：累计票价（已废弃，改用里程） */
	public int fare = 0;

	public ServiceStop() {
	}

	public ServiceStop(final String stationId, final String platform, final String arrive, final String depart) {
		this.stationId = stationId;
		this.platform = platform;
		this.arrive = arrive;
		this.depart = depart;
	}

	public ServiceStop withDayOffset(final int dayOffset) {
		this.dayOffset = dayOffset;
		return this;
	}

	public ServiceStop withDistance(final double distance) {
		this.distance = distance;
		return this;
	}

	public boolean hasDepart() {
		return depart != null && !depart.isBlank();
	}

	public boolean hasArrive() {
		return arrive != null && !arrive.isBlank();
	}
}

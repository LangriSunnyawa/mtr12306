package com.mtr12306.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 坐席等级配置（二等座 / 一等座 / 商务座 / 城际 …），同时描述车厢与座位布局。
 * layout 使用 A B C|D F 这样的写法，| 表示过道。
 */
public class SeatClassConfig {
	/** 二等座 */
	public static final String CODE_SECOND = "SECOND";
	/** 一等座 */
	public static final String CODE_FIRST = "FIRST";
	/** 商务座 */
	public static final String CODE_BUSINESS = "BUSINESS";
	/** 城际（不对号入座，自由席） */
	public static final String CODE_INTERCITY = "INTERCITY";
	/** 无座 */
	public static final String CODE_STANDING = "STANDING";

	public String code = CODE_SECOND;
	public String name = "二等座";
	/** 座位排列，| 表示过道 */
	public String layout = "A B C|D F";
	/** 每节车厢排数 */
	public int rows = 18;
	/** 该坐席的车厢号 */
	public List<Integer> cars = new ArrayList<>();
	/** 餐车车厢号（只做展示与占位，不排座位） */
	public List<Integer> diningCars = new ArrayList<>();
	/** 是否对号入座；城际/自由席为 false */
	public boolean reserved = true;
	/** 票价倍率（相对每公里票价） */
	public double priceMultiplier = 1.0D;
	/** 前端展示色 */
	public String color = "#3b7fca";

	public SeatClassConfig() {
	}

	public SeatClassConfig(final String code, final String name, final String layout, final int rows,
			final List<Integer> cars, final double priceMultiplier, final String color) {
		this.code = code;
		this.name = name;
		this.layout = layout;
		this.rows = rows;
		this.cars = cars;
		this.priceMultiplier = priceMultiplier;
		this.color = color;
	}

	public boolean dining(final int car) {
		return diningCars != null && diningCars.contains(car);
	}

	/** 座位列字母，例如 [A, B, C, D, F] */
	public List<String> columns() {
		final List<String> result = new ArrayList<>();
		for (final String token : layout.replace("|", " ").trim().split("\\s+")) {
			if (!token.isBlank()) {
				result.add(token);
			}
		}
		return result;
	}

	/** 按过道分组的列，例如 [[A,B,C],[D,F]] */
	public List<List<String>> columnGroups() {
		final List<List<String>> groups = new ArrayList<>();
		for (final String group : layout.split("\\|")) {
			final List<String> columns = new ArrayList<>();
			for (final String token : group.trim().split("\\s+")) {
				if (!token.isBlank()) {
					columns.add(token);
				}
			}
			if (!columns.isEmpty()) {
				groups.add(columns);
			}
		}
		return groups;
	}

	/** 可排座位的车厢（排除餐车） */
	public List<Integer> seatCars() {
		final List<Integer> result = new ArrayList<>();
		for (final int car : cars) {
			if (!dining(car) && car > 0) {
				result.add(car);
			}
		}
		return result;
	}

	public int seatsPerCar() {
		return reserved ? Math.max(0, rows) * columns().size() : 0;
	}

	public int seatCount() {
		if (!reserved) {
			// 不对号入座：按"每车定员"估算，席位数 = 车厢数 × 每车定员
			return Math.max(0, rows) * columns().size() * seatCars().size();
		}
		return seatsPerCar() * seatCars().size();
	}

	public boolean valid() {
		if (CODE_STANDING.equals(code)) {
			return true;
		}
		if (columns().isEmpty() || rows <= 0 || cars.isEmpty()) {
			return false;
		}
		return reserved ? !seatCars().isEmpty() : true;
	}

	/** 新建车次时使用的默认坐席配置 */
	public static List<SeatClassConfig> defaults() {
		final List<SeatClassConfig> list = new ArrayList<>();
		list.add(new SeatClassConfig(CODE_SECOND, "二等座", "A B C|D F", 18,
				new ArrayList<>(Arrays.asList(1, 2, 3, 4)), 1.0D, "#3b7fca"));
		list.add(new SeatClassConfig(CODE_FIRST, "一等座", "A C|D F", 13, new ArrayList<>(Arrays.asList(5)),
				1.6D, "#2f9e66"));
		list.add(new SeatClassConfig(CODE_BUSINESS, "商务座", "A C|F", 8, new ArrayList<>(Arrays.asList(6)),
				3.0D, "#b8860b"));
		return list;
	}

	/** 城际（不对号入座）坐席 */
	public static SeatClassConfig intercity() {
		final SeatClassConfig seatClass = new SeatClassConfig(CODE_INTERCITY, "城际", "A B C|D F", 20,
				new ArrayList<>(Arrays.asList(1, 2)), 0.9D, "#7c4dff");
		seatClass.reserved = false;
		return seatClass;
	}

	/** 无座票 */
	public static SeatClassConfig standing() {
		final SeatClassConfig seatClass = new SeatClassConfig(CODE_STANDING, "无座", "S", 0,
				new ArrayList<>(Arrays.asList(0)), 0.8D, "#8b8f96");
		seatClass.reserved = false;
		return seatClass;
	}
}

package com.mtr12306.data;

/**
 * 车票。一张票对应一个车次实例 + 一段乘坐区间 + 一个座位。
 */
public class Ticket {
	public static final String STATE_VALID = "VALID";
	public static final String STATE_CHECKED = "CHECKED";
	public static final String STATE_REFUNDED = "REFUNDED";
	public static final String STATE_EXPIRED = "EXPIRED";

	/** 票号，形如 E20261108001 */
	public String id = "";
	public String playerName = "";
	public String playerUuid = "";
	/** 下单账号昵称（网页注册账号，可为空） */
	public String account = "";
	public String serviceNumber = "";
	/** 车次开行日期（基期），跨日车次的乘车日 = serviceDate + dayOffset */
	public String serviceDate = "";
	/** 乘车日期（该站实际发车日期）yyyy-MM-dd */
	public String date = "";
	public String fromStationId = "";
	public String toStationId = "";
	/** 出发时刻 HH:mm */
	public String fromTime = "";
	/** 到达时刻 HH:mm */
	public String toTime = "";
	/** 坐席内部码，例如 SECOND */
	public String seatClass = "SECOND";
	/** 坐席名称，例如 二等座 */
	public String seatClassLabel = "二等座";
	/** 车厢号，无座/自由席为 0 */
	public int car = 0;
	/** 座位号，例如 12F；自由席/无座为空 */
	public String seatLabel = "";
	/** 展示用座位，例如 05车 12F */
	public String seat = "";
	/** 票价（元） */
	public int price = 0;
	public String checkpointId = "";
	public String state = STATE_VALID;
	public long purchasedAt = 0L;
	public long checkedAt = 0L;
	public String checkedCheckpointId = "";
	/** 是否已打印报销凭证（打印后不可退票） */
	public boolean receiptPrinted = false;
	public long receiptPrintedAt = 0L;
	/** 是否已出闸（换乘/出站闸机标记） */
	public boolean exited = false;
	public long exitedAt = 0L;
	public String exitCheckpointId = "";

	public boolean isValid() {
		return STATE_VALID.equals(state);
	}

	public boolean standee() {
		return "STANDING".equals(seatClass) || car <= 0;
	}

	/** 是否已进站但尚未出闸（换乘闸机出闸判断用） */
	public boolean onBoard() {
		return STATE_CHECKED.equals(state) && !exited;
	}
}

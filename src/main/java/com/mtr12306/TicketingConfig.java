package com.mtr12306;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;

/**
 * 模组配置，保存在 config/mtr12306.json。
 */
public class TicketingConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 是否在服务器启动时开启网页售票服务 */
	public boolean webEnabled = true;
	/** 网页服务监听地址，0.0.0.0 表示允许局域网访问 */
	public String webBind = "0.0.0.0";
	/** 网页服务端口 */
	public int webPort = 8123;
	/** 管理后台密码（首次启动请务必修改） */
	public String adminPassword = "mtr12306";
	/** 管理令牌有效期（分钟） */
	public int adminTokenMinutes = 240;
	/** 同一 IP 在统计窗口内允许的管理登录失败次数 */
	public int adminLoginMaxFailures = 5;
	/** 管理登录失败统计窗口（分钟） */
	public int adminLoginWindowMinutes = 5;

	/** 时区，留空表示跟随系统 */
	public String timezone = "";
	/** 提前多少分钟开始检票 */
	public int boardingOpenMinutes = 15;
	/** 发车前多少分钟停止检票（0 表示到发车时刻） */
	public int boardingCloseMinutes = 0;
	/** 发车后额外宽限多少分钟仍可检票 */
	public int lateGraceMinutes = 2;
	/** 手动「停止检票」的强制关闭时长（分钟） */
	public int manualCloseMinutes = 60;
	/** 最多可提前多少天购票 */
	public int maxAdvanceDays = 7;
	/** 开始检票时是否全服广播 */
	public boolean broadcastBoarding = true;

	/** 每公里票价（元），默认 0.1 元/公里 */
	public double farePerKm = 0.1D;
	/** 里程未配置时，每经过一站按此单价估算（元） */
	public int farePerStopFallback = 3;
	/** 换乘闸机：接续车次可检票的时间窗口（分钟，含开检前） */
	public int transferWindowMinutes = 30;
	/** 全局票价倍率 */
	public double fareMultiplier = 1.0D;
	/** 是否启用付费（从 MTR 的 fares 计分板扣款） */
	public boolean requirePayment = true;
	/** 余额计分板项目名，留空自动使用 MTR 的 mtr_balance */
	public String fareObjective = "";

	/** 是否要求玩家身份认证（游戏内 /ticket login 取得验证码） */
	public boolean requirePlayerAuth = true;
	/** 验证码有效期（分钟） */
	public int loginCodeMinutes = 5;
	/** 玩家网页令牌有效期（小时） */
	public int playerTokenHours = 12;

	public static ZoneId zone() {
		try {
			final TicketingConfig config = Mtr12306.CONFIG;
			if (config != null && config.timezone != null && !config.timezone.isBlank()) {
				return ZoneId.of(config.timezone.trim());
			}
		} catch (Exception ignored) {
		}
		return ZoneId.systemDefault();
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mtr12306.json");
	}

	public static TicketingConfig load() {
		final Path path = path();
		final TicketingConfig config = new TicketingConfig();
		try {
			if (Files.exists(path)) {
				final JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
				config.webEnabled = bool(json, "webEnabled", config.webEnabled);
				config.webBind = str(json, "webBind", config.webBind);
				config.webPort = integer(json, "webPort", config.webPort);
				config.adminPassword = str(json, "adminPassword", config.adminPassword);
				config.adminTokenMinutes = integer(json, "adminTokenMinutes", config.adminTokenMinutes);
				config.adminLoginMaxFailures = integer(json, "adminLoginMaxFailures", config.adminLoginMaxFailures);
				config.adminLoginWindowMinutes = integer(json, "adminLoginWindowMinutes", config.adminLoginWindowMinutes);
				config.timezone = str(json, "timezone", config.timezone);
				config.boardingOpenMinutes = integer(json, "boardingOpenMinutes", config.boardingOpenMinutes);
				config.boardingCloseMinutes = integer(json, "boardingCloseMinutes", config.boardingCloseMinutes);
				config.lateGraceMinutes = integer(json, "lateGraceMinutes", config.lateGraceMinutes);
				config.manualCloseMinutes = integer(json, "manualCloseMinutes", config.manualCloseMinutes);
				config.maxAdvanceDays = integer(json, "maxAdvanceDays", config.maxAdvanceDays);
				config.broadcastBoarding = bool(json, "broadcastBoarding", config.broadcastBoarding);
				config.farePerKm = decimal(json, "farePerKm", config.farePerKm);
				config.farePerStopFallback = integer(json, "farePerStopFallback", config.farePerStopFallback);
				config.transferWindowMinutes = integer(json, "transferWindowMinutes", config.transferWindowMinutes);
				config.fareMultiplier = decimal(json, "fareMultiplier", config.fareMultiplier);
				config.requirePayment = bool(json, "requirePayment", config.requirePayment);
				config.fareObjective = str(json, "fareObjective", config.fareObjective);
				config.requirePlayerAuth = bool(json, "requirePlayerAuth", config.requirePlayerAuth);
				config.loginCodeMinutes = integer(json, "loginCodeMinutes", config.loginCodeMinutes);
				config.playerTokenHours = integer(json, "playerTokenHours", config.playerTokenHours);
			}
			config.save();
		} catch (Exception e) {
			Mtr12306.LOGGER.warn("读取配置失败，使用默认值", e);
		}
		return config;
	}

	private static String str(final JsonObject json, final String key, final String fallback) {
		try {
			return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static int integer(final JsonObject json, final String key, final int fallback) {
		try {
			return json.has(key) ? json.get(key).getAsInt() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static double decimal(final JsonObject json, final String key, final double fallback) {
		try {
			return json.has(key) ? json.get(key).getAsDouble() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static boolean bool(final JsonObject json, final String key, final boolean fallback) {
		try {
			return json.has(key) ? json.get(key).getAsBoolean() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	public void save() {
		try {
			final Path path = path();
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Mtr12306.LOGGER.warn("保存配置失败", e);
		}
	}
}

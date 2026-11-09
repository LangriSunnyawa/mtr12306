package com.mtr12306.fares;

import com.mtr12306.Mtr12306;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

/**
 * 与 MTR 自带的票价钱包（fares scoreboard）对接。
 * MTR 把余额存在名为 mtr_balance 的计分板项目里（标题 Balance），
 * 这里读写同一个项目，因此与 MTR 的售票机、闸机共用余额。
 */
public final class FareAccount {
	private static final String FALLBACK_OBJECTIVE = "mtr_balance";
	private static final String FALLBACK_TITLE = "Balance";
	private static String objectiveName;
	private static String objectiveTitle;

	private FareAccount() {
	}

	/** 计分板项目名，优先使用配置，其次取 MTR 的常量，最后回退到 mtr_balance */
	public static synchronized String objectiveName() {
		if (objectiveName != null) {
			return objectiveName;
		}
		final String configured = Mtr12306.CONFIG == null ? "" : Mtr12306.CONFIG.fareObjective;
		if (configured != null && !configured.isBlank()) {
			objectiveName = configured.trim();
			return objectiveName;
		}
		objectiveName = readMtrConstant("BALANCE_OBJECTIVE", FALLBACK_OBJECTIVE);
		return objectiveName;
	}

	public static synchronized String objectiveTitle() {
		if (objectiveTitle != null) {
			return objectiveTitle;
		}
		objectiveTitle = readMtrConstant("BALANCE_OBJECTIVE_TITLE", FALLBACK_TITLE);
		return objectiveTitle;
	}

	private static String readMtrConstant(final String fieldName, final String fallback) {
		try {
			final Class<?> clazz = Class.forName("org.mtr.mod.data.TicketSystem");
			final Object value = clazz.getField(fieldName).get(null);
			if (value instanceof String string && !string.isBlank()) {
				return string;
			}
		} catch (Throwable ignored) {
		}
		return fallback;
	}

	public static void invalidateCache() {
		objectiveName = null;
		objectiveTitle = null;
	}

	private static ScoreboardObjective objective(final MinecraftServer server, final boolean create) {
		if (server == null) {
			return null;
		}
		final Scoreboard scoreboard = server.getScoreboard();
		ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName());
		if (objective == null && create) {
			objective = scoreboard.addObjective(objectiveName(), ScoreboardCriterion.DUMMY,
					Text.literal(objectiveTitle()), ScoreboardCriterion.RenderType.INTEGER);
		}
		return objective;
	}

	/** 读取余额；计分板项目不存在时返回 0 */
	public static int balance(final MinecraftServer server, final String playerName) {
		final ScoreboardObjective objective = objective(server, false);
		if (objective == null || playerName == null || playerName.isBlank()) {
			return 0;
		}
		final ScoreboardPlayerScore score = server.getScoreboard().getPlayerScore(playerName, objective);
		return score == null ? 0 : score.getScore();
	}

	/** 写入余额（用于扣款与退款） */
	public static void setBalance(final MinecraftServer server, final String playerName, final int value) {
		final ScoreboardObjective objective = objective(server, true);
		if (objective == null || playerName == null || playerName.isBlank()) {
			return;
		}
		final ScoreboardPlayerScore score = server.getScoreboard().getPlayerScore(playerName, objective);
		score.setScore(Math.max(0, value));
	}

	/** 尝试扣款，余额不足返回 false */
	public static boolean withdraw(final MinecraftServer server, final String playerName, final int amount) {
		if (amount <= 0) {
			return true;
		}
		final int current = balance(server, playerName);
		if (current < amount) {
			return false;
		}
		setBalance(server, playerName, current - amount);
		return true;
	}

	public static void deposit(final MinecraftServer server, final String playerName, final int amount) {
		if (amount <= 0) {
			return;
		}
		setBalance(server, playerName, balance(server, playerName) + amount);
	}
}

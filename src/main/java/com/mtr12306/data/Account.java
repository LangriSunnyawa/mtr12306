package com.mtr12306.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 网页注册账号：昵称（登录名）+ 绑定 Minecraft 名称 + 密码。
 * 密码使用 PBKDF2 风格的多轮 SHA-256 + 每账号随机盐，不存明文。
 */
public class Account {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int ROUNDS = 4096;

	public String nickname = "";
	/** 绑定的 Minecraft 玩家名（车票归属） */
	public String mcName = "";
	public String salt = "";
	public String passwordHash = "";
	public long createdAt = 0L;
	/** 绑定的网页令牌（会话）不落盘 */

	public static Account create(final String nickname, final String mcName, final String password) {
		final Account account = new Account();
		account.nickname = nickname;
		account.mcName = mcName;
		account.salt = newSalt();
		account.passwordHash = hash(password, account.salt);
		account.createdAt = System.currentTimeMillis();
		return account;
	}

	public boolean matches(final String password) {
		if (passwordHash == null || passwordHash.isBlank() || password == null) {
			return false;
		}
		return MessageDigest.isEqual(hash(password, salt).getBytes(StandardCharsets.UTF_8),
				passwordHash.getBytes(StandardCharsets.UTF_8));
	}

	public void updatePassword(final String password) {
		salt = newSalt();
		passwordHash = hash(password, salt);
	}

	private static String newSalt() {
		final byte[] bytes = new byte[12];
		RANDOM.nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static String hash(final String password, final String salt) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] value = (salt + ":" + password).getBytes(StandardCharsets.UTF_8);
			for (int i = 0; i < ROUNDS; i++) {
				value = digest.digest(value);
			}
			return Base64.getEncoder().encodeToString(value);
		} catch (Exception e) {
			throw new IllegalStateException("密码哈希失败", e);
		}
	}
}

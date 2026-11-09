package com.mtr12306.data;

/**
 * 业务校验失败时抛出，message 会直接显示给玩家或网页用户。
 */
public class TicketingException extends RuntimeException {
	public TicketingException(final String message) {
		super(message);
	}
}

package com.mtr12306.net;

import com.google.gson.JsonObject;
import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingManager;
import com.mtr12306.block.TicketMachineBlockEntity;
import com.mtr12306.data.Account;
import com.mtr12306.data.Ticket;
import com.mtr12306.data.TicketingException;
import com.mtr12306.item.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * 售票机的服务端逻辑：购票 / 打印报销凭证。
 */
public final class MachineHandler {
	private MachineHandler() {
	}

	/** 按 Minecraft 名称反查注册账号 */
	private static Account accountOf(final String mcName) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return null;
		}
		for (final Account account : manager.data().accounts.values()) {
			if (account.mcName.equalsIgnoreCase(mcName)) {
				return account;
			}
		}
		return null;
	}

	public static void buy(final ServerPlayerEntity player, final BlockPos pos, final String serviceNumber,
			final String seatClass, final String toStationId) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		final Account account = accountOf(player.getGameProfile().getName());
		if (account == null) {
			ModNetworking.sendMachineResult(player, false,
					"需要 mtr12306 账号：请到网页注册（昵称 + Minecraft 名称填 " + player.getGameProfile().getName() + "）");
			return;
		}
		if (!(player.getWorld().getBlockEntity(pos) instanceof TicketMachineBlockEntity machine)) {
			return;
		}
		final String stationId = machine.stationId(player.getServerWorld(), pos);
		if (stationId.isEmpty()) {
			ModNetworking.sendMachineResult(player, false, "售票机未对应任何车站，请先在后台创建同名车站");
			return;
		}
		try {
			final Ticket ticket = manager.purchase(player.getGameProfile().getName(), account.nickname,
					serviceNumber, TicketingManager.today().toString(), stationId, toStationId, seatClass, 0, "");
			ModNetworking.sendMachineResult(player, true, "购票成功：" + ticket.serviceNumber + " 次 "
					+ manager.data().stationName(ticket.fromStationId) + " → "
					+ manager.data().stationName(ticket.toStationId) + " · " + ticket.seatClassLabel + " "
					+ ticket.seat + " · " + ticket.price + " 元");
			ModNetworking.openMachine(player, pos, stationId);
		} catch (TicketingException exception) {
			ModNetworking.sendMachineResult(player, false, exception.getMessage());
		} catch (Throwable throwable) {
			Mtr12306.LOGGER.warn("[MTR-12306] 售票机购票失败", throwable);
			ModNetworking.sendMachineResult(player, false, "购票失败：" + throwable);
		}
	}

	public static void print(final ServerPlayerEntity player, final String ticketId) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		try {
			final JsonObject receipt = manager.printReceipt(ticketId, player.getGameProfile().getName());
			final ItemStack stack = ModItems.createReceipt(receipt);
			if (!player.getInventory().insertStack(stack)) {
				player.dropItem(stack, false);
			}
			ModNetworking.sendMachineResult(player, true, "报销凭证已打印（" + ticketId + "），该车票不能再退票");
		} catch (TicketingException exception) {
			ModNetworking.sendMachineResult(player, false, exception.getMessage());
		} catch (Throwable throwable) {
			Mtr12306.LOGGER.warn("[MTR-12306] 打印报销凭证失败", throwable);
			ModNetworking.sendMachineResult(player, false, "打印失败：" + throwable);
		}
	}

	public static void hint(final ServerPlayerEntity player, final String message) {
		player.sendMessage(Text.literal(message), false);
	}
}

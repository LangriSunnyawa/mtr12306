package com.mtr12306.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtr12306.net.ModNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class Mtr12306Client implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 报销凭证：手持右键打开蓝票界面（客户端注入，服务端不会加载这些类）
		com.mtr12306.item.ItemReceipt.clientViewer = stack -> net.minecraft.client.MinecraftClient.getInstance()
				.setScreen(new ReceiptScreen(com.mtr12306.item.ItemReceipt.json(stack)));
		// 报销凭证的悬浮提示（只能在客户端注册，避免服务端加载客户端类）
		net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
			if (stack.getItem() instanceof com.mtr12306.item.ItemReceipt) {
				final com.google.gson.JsonObject json = com.mtr12306.item.ItemReceipt.json(stack);
				if (json == null) {
					lines.add(Text.literal("§7未写入车票信息"));
					return;
				}
				lines.add(Text.literal("§7" + com.mtr12306.item.ItemReceipt.opt(json, "fromStation") + " → "
						+ com.mtr12306.item.ItemReceipt.opt(json, "toStation")));
				lines.add(Text.literal("§7" + com.mtr12306.item.ItemReceipt.opt(json, "fromTime") + " - "
						+ com.mtr12306.item.ItemReceipt.opt(json, "toTime") + " · "
						+ com.mtr12306.item.ItemReceipt.opt(json, "seatClass") + " "
						+ com.mtr12306.item.ItemReceipt.opt(json, "seat")));
				lines.add(Text.literal("§8票价 " + com.mtr12306.item.ItemReceipt.opt(json, "price") + " 元 · 票号 "
						+ com.mtr12306.item.ItemReceipt.opt(json, "ticketId")));
				lines.add(Text.literal("§9手持右键查看报销凭证"));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(ModNetworking.OPEN_GATE_CONFIG, (client, handler, buf, sender) -> {
			final BlockPos pos = buf.readBlockPos();
			final String stationId = buf.readString(32767);
			final String mode = buf.readString(16);
			final String mtrStation = buf.readString(32767);
			final List<String[]> stations = new ArrayList<>();
			final int stationCount = buf.readInt();
			for (int i = 0; i < stationCount; i++) {
				stations.add(new String[]{buf.readString(32767), buf.readString(32767)});
			}
			final List<String[]> checkpoints = new ArrayList<>();
			final int checkpointCount = buf.readInt();
			for (int i = 0; i < checkpointCount; i++) {
				checkpoints.add(new String[]{buf.readString(32767), buf.readString(32767), buf.readString(32767)});
			}
			final List<String> selected = new ArrayList<>();
			final int selectedCount = buf.readInt();
			for (int i = 0; i < selectedCount; i++) {
				selected.add(buf.readString(32767));
			}
			client.execute(() -> client.setScreen(
					new GateConfigScreen(pos, stationId, mode, mtrStation, stations, checkpoints, selected)));
		});

		ClientPlayNetworking.registerGlobalReceiver(ModNetworking.OPEN_MACHINE, (client, handler, buf, sender) -> {
			final BlockPos pos = buf.readBlockPos();
			final String json = buf.readString(1 << 20);
			client.execute(() -> {
				try {
					client.setScreen(new TicketMachineScreen(pos, JsonParser.parseString(json).getAsJsonObject()));
				} catch (Exception exception) {
					if (client.player != null) {
						client.player.sendMessage(Text.literal("§c售票机数据解析失败"), false);
					}
				}
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(ModNetworking.MACHINE_RESULT, (client, handler, buf, sender) -> {
			final boolean success = buf.readBoolean();
			final String message = buf.readString(1 << 20);
			client.execute(() -> {
				TicketMachineScreen.lastResult = (success ? "§a" : "§c") + message;
				if (client.player != null) {
					client.player.sendMessage(Text.literal((success ? "§a" : "§c") + message), false);
				}
			});
		});
	}

	@SuppressWarnings("unused")
	private static JsonObject unused() {
		return new JsonObject();
	}
}

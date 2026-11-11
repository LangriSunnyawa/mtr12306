package com.mtr12306.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtr12306.net.ModNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 售票机界面：购买车票（需要账号）+ 打印报销凭证。
 */
public class TicketMachineScreen extends Screen {
	public static String lastResult = "";

	private final BlockPos pos;
	private final String station;
	private final List<JsonObject> services = new ArrayList<>();
	private final List<JsonObject> tickets = new ArrayList<>();

	public TicketMachineScreen(final BlockPos pos, final JsonObject payload) {
		super(Text.literal("售票机"));
		this.pos = pos;
		this.station = opt(payload, "station");
		final JsonArray serviceArray = payload.has("services") ? payload.getAsJsonArray("services") : new JsonArray();
		for (final JsonElement element : serviceArray) {
			services.add(element.getAsJsonObject());
		}
		final JsonArray ticketArray = payload.has("tickets") ? payload.getAsJsonArray("tickets") : new JsonArray();
		for (final JsonElement element : ticketArray) {
			tickets.add(element.getAsJsonObject());
		}
	}

	private static String opt(final JsonObject json, final String key) {
		try {
			return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	@Override
	protected void init() {
		final int cx = width / 2;
		int y = 40;

		int row = 0;
		for (final JsonObject service : services) {
			if (row >= 6) {
				break;
			}
			final JsonArray classes = service.has("classes") ? service.getAsJsonArray("classes") : new JsonArray();
			if (classes.isEmpty()) {
				continue;
			}
			final JsonObject first = classes.get(0).getAsJsonObject();
			final String label = "§f" + opt(service, "service") + " §7" + opt(service, "depart") + " → "
					+ opt(first, "toStation") + " §e" + opt(first, "price") + " 元 §7(" + opt(first, "name")
					+ " 余 " + opt(first, "remaining") + ")";
			addDrawableChild(ButtonWidget.builder(Text.literal("购 " + label), button -> buy(service, first))
					.dimensions(cx - 200, y, 400, 20).build());
			y += 22;
			row++;
		}

		y += 6;
		int t = 0;
		for (final JsonObject ticket : tickets) {
			if (t >= 6) {
				break;
			}
			final boolean printed = "true".equals(opt(ticket, "printed"));
			final String label = (printed ? "§8" : "§b") + "凭证 " + opt(ticket, "ticketId") + " §7"
					+ opt(ticket, "service") + " " + opt(ticket, "fromStation") + "→" + opt(ticket, "toStation")
					+ (printed ? " §8(已打印)" : "");
			addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> print(ticket))
					.dimensions(cx - 200, y, 400, 20).build());
			y += 22;
			t++;
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
				.dimensions(cx - 60, height - 28, 120, 20).build());
	}

	private void buy(final JsonObject service, final JsonObject seatClass) {
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(opt(service, "service"));
		buf.writeString(opt(seatClass, "code"));
		buf.writeString(opt(seatClass, "toStationId"));
		ClientPlayNetworking.send(ModNetworking.MACHINE_BUY, buf);
		lastResult = "§7正在购票…";
	}

	private void print(final JsonObject ticket) {
		if ("true".equals(opt(ticket, "printed"))) {
			lastResult = "§e该车票的报销凭证已打印过";
			return;
		}
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeString(opt(ticket, "ticketId"));
		ClientPlayNetworking.send(ModNetworking.MACHINE_PRINT, buf);
		lastResult = "§7正在打印报销凭证…";
	}

	@Override
	public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("§l售票机 §r§7· " + station), width / 2, 14, 0xFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("§7上半部分：本站今日可售车次（点「购」即购票）"),
				width / 2, 26, 0xAAAAAA);
		if (!lastResult.isBlank()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(lastResult), width / 2, height - 44, 0xFFFF55);
		}
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}

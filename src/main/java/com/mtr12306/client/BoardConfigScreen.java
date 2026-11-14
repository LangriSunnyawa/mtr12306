package com.mtr12306.client;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 车站大屏配置：车站 + 尺寸（宽×高，向右/向上扩展）+ 检票口多选（空=不限）。 */
public class BoardConfigScreen extends Screen {
	private final BlockPos pos;
	private final String rightDirection;
	private final List<String[]> stations = new ArrayList<>();
	private final List<String[]> checkpoints = new ArrayList<>();
	private final Set<String> selected = new LinkedHashSet<>();
	private String stationId;
	private int boardWidth;
	private int boardHeight;
	private final int kind;

	public BoardConfigScreen(final BlockPos pos, final String stationId, final String rightDirection, final int width,
			final int height, final List<String[]> stations, final List<String[]> checkpoints,
			final List<String> selectedIds, final int kind) {
		super(Text.literal(kind == 1 ? "广播器配置" : "车站大屏配置"));
		this.pos = pos;
		this.stationId = stationId == null ? "" : stationId;
		this.rightDirection = rightDirection;
		this.boardWidth = Math.max(1, width);
		this.boardHeight = Math.max(1, height);
		this.stations.addAll(stations);
		this.checkpoints.addAll(checkpoints);
		this.selected.addAll(selectedIds);
		this.kind = kind;
		if (this.stationId.isEmpty() && !this.stations.isEmpty()) {
			this.stationId = this.stations.get(0)[0];
		}
	}

	private List<String[]> checkpointsOfStation() {
		final List<String[]> list = new ArrayList<>();
		for (final String[] cp : checkpoints) {
			if (cp[1].equals(stationId)) {
				list.add(cp);
			}
		}
		return list;
	}

	private String stationName() {
		for (final String[] station : stations) {
			if (station[0].equals(stationId)) {
				return station[1];
			}
		}
		return "未选择";
	}

	@Override
	protected void init() {
		final int cx = width / 2;
		int y = 42;

		if (stations.size() > 1) {
			addDrawableChild(ButtonWidget.builder(Text.literal("车站：" + stationName()), button -> {
				int index = 0;
				for (int i = 0; i < stations.size(); i++) {
					if (stations.get(i)[0].equals(stationId)) {
						index = i;
					}
				}
				stationId = stations.get((index + 1) % stations.size())[0];
				selected.clear();
				clearAndInit();
			}).dimensions(cx - 150, y, 300, 20).build());
			y += 24;
		}

		if (kind == 0) {
		addDrawableChild(ButtonWidget.builder(Text.literal("宽度：" + boardWidth + "（点击 +1）"), button -> {
			boardWidth = boardWidth >= 12 ? 1 : boardWidth + 1;
			button.setMessage(Text.literal("宽度：" + boardWidth + "（点击 +1）"));
		}).dimensions(cx - 150, y, 146, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("高度：" + boardHeight + "（点击 +1）"), button -> {
			boardHeight = boardHeight >= 8 ? 1 : boardHeight + 1;
			button.setMessage(Text.literal("高度：" + boardHeight + "（点击 +1）"));
		}).dimensions(cx + 4, y, 146, 20).build());
		y += 24;
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("§e不限检票口（清空）"), button -> {
			selected.clear();
			clearAndInit();
		}).dimensions(cx - 150, y, 300, 20).build());
		y += 24;

		int shown = 0;
		for (final String[] cp : checkpointsOfStation()) {
			if (shown >= 7) {
				break;
			}
			final boolean on = selected.contains(cp[0]);
			addDrawableChild(ButtonWidget.builder(Text.literal((on ? "§a✔ " : "§7") + cp[2]), button -> {
				if (selected.contains(cp[0])) {
					selected.remove(cp[0]);
				} else {
					selected.add(cp[0]);
				}
				clearAndInit();
			}).dimensions(cx - 150, y, 300, 20).build());
			y += 22;
			shown++;
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("§a保存"), button -> save())
				.dimensions(cx - 152, height - 28, 148, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("§c取消"), button -> close())
				.dimensions(cx + 4, height - 28, 148, 20).build());
	}

	private void save() {
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(stationId);
		if (kind == 1) {
			buf.writeInt(selected.size());
			for (final String id : selected) {
				buf.writeString(id);
			}
			ClientPlayNetworking.send(ModNetworking.BIND_BROADCAST, buf);
			close();
			return;
		}
		buf.writeInt(boardWidth);
		buf.writeInt(boardHeight);
		buf.writeInt(selected.size());
		for (final String id : selected) {
			buf.writeString(id);
		}
		ClientPlayNetworking.send(ModNetworking.BIND_BOARD, buf);
		close();
	}

	@Override
	public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7屏幕向右扩展方向：§f" + rightDirection + " §7（向上按高度堆叠）"), width / 2, 26, 0xAAAAAA);
		if (stations.size() <= 1) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("§f车站：" + stationName()), width / 2, 44, 0xFFFFFF);
		}
		context.drawCenteredTextWithShadow(textRenderer, Text.literal((kind == 1 ? "§7检票口：" : "§7屏幕尺寸：§f"
				+ boardWidth + " × " + boardHeight + " §7｜检票口：") + (selected.isEmpty() ? "§e不限" : "§f" + selected.size() + " 个")),
				width / 2, height - 44, 0xFFFFFF);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}

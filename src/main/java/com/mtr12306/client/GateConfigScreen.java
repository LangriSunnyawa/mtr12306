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

/**
 * 闸机配置：选择所属车站（受闸机所在 MTR 车站限制）、多选检票口（不选 = 不限）、通行模式。
 */
public class GateConfigScreen extends Screen {
	private static final String[] MODES = {"ENTRY", "EXIT", "BOTH", "TRANSFER", "VALIDATE"};
	private static final String[] MODE_NAMES = {"进站检票", "出站放行", "双向", "换乘", "车票核验"};

	private final BlockPos pos;
	private final String mtrStation;
	private final List<String[]> stations = new ArrayList<>();
	private final List<String[]> checkpoints = new ArrayList<>();
	private final Set<String> selected = new LinkedHashSet<>();
	private String stationId;
	private String mode;

	public GateConfigScreen(final BlockPos pos, final String stationId, final String mode, final String mtrStation,
			final List<String[]> stations, final List<String[]> checkpoints, final List<String> selectedIds) {
		super(Text.literal("闸机配置"));
		this.pos = pos;
		this.stationId = stationId == null ? "" : stationId;
		this.mode = mode == null || mode.isBlank() ? "ENTRY" : mode;
		this.mtrStation = mtrStation == null ? "" : mtrStation;
		this.stations.addAll(stations);
		this.checkpoints.addAll(checkpoints);
		this.selected.addAll(selectedIds);
		if (this.stationId.isEmpty() && !this.stations.isEmpty()) {
			this.stationId = this.stations.get(0)[0];
		}
	}

	private int modeIndex() {
		for (int i = 0; i < MODES.length; i++) {
			if (MODES[i].equals(mode)) {
				return i;
			}
		}
		return 0;
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

	@Override
	protected void init() {
		final int cx = width / 2;
		int y = 44;

		// 车站（识别到 MTR 车站时只有一项）
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
		}
		y += 24;

		addDrawableChild(ButtonWidget.builder(Text.literal("通行模式：" + MODE_NAMES[modeIndex()]), button -> {
			mode = MODES[(modeIndex() + 1) % MODES.length];
			button.setMessage(Text.literal("通行模式：" + MODE_NAMES[modeIndex()]));
		}).dimensions(cx - 150, y, 300, 20).build());
		y += 24;

		addDrawableChild(ButtonWidget.builder(Text.literal("§e不限检票口（清空选择）"), button -> {
			selected.clear();
			clearAndInit();
		}).dimensions(cx - 150, y, 300, 20).build());
		y += 24;

		final List<String[]> list = checkpointsOfStation();
		int shown = 0;
		for (final String[] cp : list) {
			if (shown >= 8) {
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

	private String stationName() {
		for (final String[] station : stations) {
			if (station[0].equals(stationId)) {
				return station[1];
			}
		}
		return "未选择";
	}

	private void save() {
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(stationId);
		buf.writeString(mode);
		buf.writeInt(selected.size());
		for (final String id : selected) {
			buf.writeString(id);
		}
		ClientPlayNetworking.send(ModNetworking.BIND_GATE, buf);
		close();
	}

	@Override
	public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7闸机 " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "  §b"
						+ (mtrStation.isEmpty() ? "（未识别到 MTR 车站）" : "MTR 车站：" + mtrStation)), width / 2, 26, 0xAAAAAA);
		if (stations.size() <= 1) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("§f车站：" + stationName()), width / 2, 46, 0xFFFFFF);
		}
		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7已选检票口：" + (selected.isEmpty() ? "§e不限" : "§f" + selected.size() + " 个")),
				width / 2, height - 44, 0xFFFFFF);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}

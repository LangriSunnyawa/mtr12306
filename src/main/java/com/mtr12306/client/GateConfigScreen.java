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
import java.util.List;

/**
 * 闸机配置界面：选择该闸机所属检票口（仅限闸机所在 MTR 车站）与通行模式。
 */
public class GateConfigScreen extends Screen {
	private static final int PER_PAGE = 5;
	private static final String[] MODES = {"ENTRY", "EXIT", "BOTH", "TRANSFER"};

	private final BlockPos pos;
	private final String stationName;
	private final List<String[]> options;
	private String selected;
	private String mode;
	private int page = 0;

	public GateConfigScreen(final BlockPos pos, final String currentCheckpointId, final String currentMode,
			final String stationName, final List<String[]> options) {
		super(Text.literal("闸机配置"));
		this.pos = pos;
		this.selected = currentCheckpointId == null ? "" : currentCheckpointId;
		this.mode = currentMode == null || currentMode.isBlank() ? "ENTRY" : currentMode;
		this.stationName = stationName == null ? "" : stationName;
		this.options = options == null ? new ArrayList<>() : options;
	}

	private static String modeText(final String mode) {
		return switch (mode) {
			case "EXIT" -> "出站放行";
			case "BOTH" -> "双向检票";
			case "TRANSFER" -> "换乘闸机";
			default -> "进站检票";
		};
	}

	@Override
	protected void init() {
		final int centerX = width / 2;
		int y = 56;

		addDrawableChild(ButtonWidget.builder(Text.literal("通行模式：" + modeText(mode)), button -> {
			int index = 0;
			for (int i = 0; i < MODES.length; i++) {
				if (MODES[i].equals(mode)) {
					index = i;
				}
			}
			mode = MODES[(index + 1) % MODES.length];
			button.setMessage(Text.literal("通行模式：" + modeText(mode)));
		}).dimensions(centerX - 110, y, 220, 20).build());
		y += 26;

		final int pages = Math.max(1, (options.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.min(page, pages - 1);
		final int start = page * PER_PAGE;
		for (int i = 0; i < PER_PAGE && start + i < options.size(); i++) {
			final String[] option = options.get(start + i);
			final String id = option[0];
			final String label = option[1];
			final boolean isSelected = id.equals(selected);
			addDrawableChild(ButtonWidget.builder(Text.literal((isSelected ? "§a✔ " : "") + label), button -> {
				selected = id;
				clearAndInit();
			}).dimensions(centerX - 150, y, 300, 20).build());
			y += 23;
		}

		if (pages > 1) {
			final int pageY = height - 56;
			addDrawableChild(ButtonWidget.builder(Text.literal("◀"), button -> {
				page = (page - 1 + pages) % pages;
				clearAndInit();
			}).dimensions(centerX - 152, pageY, 40, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("第 " + (page + 1) + "/" + pages + " 页"), button -> {
			}).dimensions(centerX - 108, pageY, 216, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("▶"), button -> {
				page = (page + 1) % pages;
				clearAndInit();
			}).dimensions(centerX + 112, pageY, 40, 20).build());
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("§a保存"), button -> save())
				.dimensions(centerX - 152, height - 28, 148, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("§c取消"), button -> close())
				.dimensions(centerX + 4, height - 28, 148, 20).build());
	}

	private void save() {
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(selected);
		buf.writeString(mode);
		ClientPlayNetworking.send(ModNetworking.BIND_GATE, buf);
		close();
	}

	@Override
	public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7闸机坐标 " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), width / 2, 30, 0xAAAAAA);
		if (stationName.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer,
					Text.literal("§c此闸机不在任何 MTR 车站范围内，无法选择检票口"), width / 2, 44, 0xFF5555);
		} else {
			context.drawCenteredTextWithShadow(textRenderer,
					Text.literal("§b所在车站：" + stationName), width / 2, 44, 0x55FFFF);
		}
		if (options.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer,
					Text.literal(stationName.isEmpty()
							? "§7请先把闸机放到 MTR 车站范围内"
							: "§7该车站还没有检票口（请在网页后台为同名车站创建检票口）"),
					width / 2, 80, 0xAAAAAA);
		}
		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7当前选择：" + (selected.isEmpty() ? "未绑定" : selected)), width / 2, height - 44, 0xFFFFFF);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}

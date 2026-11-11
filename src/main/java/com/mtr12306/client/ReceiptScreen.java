package com.mtr12306.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 报销凭证界面：国铁蓝票样式，内容按车票信息即时渲染（不依赖固定贴图文本）。
 */
public class ReceiptScreen extends Screen {
	// 国铁蓝票配色
	private static final int PAPER = 0xFFDCEBF8;
	private static final int PAPER_DARK = 0xFFC9DEF1;
	private static final int INK = 0xFF16233A;
	private static final int HEADER = 0xFF1B2B41;
	private static final int SEAL = 0xFFC8323C;

	private final JsonObject data;

	public ReceiptScreen(final JsonObject data) {
		super(Text.literal("报销凭证"));
		this.data = data == null ? new JsonObject() : data;
	}

	private String opt(final String key) {
		try {
			return data.has(key) && !data.get(key).isJsonNull() ? data.get(key).getAsString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	@Override
	protected void init() {
		addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
				.dimensions(width / 2 - 60, height - 34, 120, 20).build());
	}

	@Override
	public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
		renderBackground(context);
		final int w = Math.min(360, width - 40);
		final int h = 250;
		final int x = (width - w) / 2;
		final int y = Math.max(16, (height - h) / 2 - 10);

		// 纸张 + 抬头
		context.fill(x, y, x + w, y + h, PAPER);
		context.fill(x, y, x + w, y + 26, HEADER);
		context.fill(x, y + h - 46, x + w, y + h - 44, PAPER_DARK);
		context.drawBorder(x, y, w, h, 0xFF8FA9C0);

		context.drawTextWithShadow(textRenderer, Text.literal("§f中国铁路 · 电子客票报销凭证"), x + 10, y + 8, 0xFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal("§7" + opt("state")), x + w - 70, y + 8, 0xDDDDDD);

		int ty = y + 34;
		final String[][] rows = {
				{"车次", opt("service") + " 次", "乘车日期", opt("date")},
				{"出发站", opt("fromStation") + "  " + opt("fromTime"), "到达站", opt("toStation") + "  " + opt("toTime")},
				{"乘车人", opt("passenger"), "席位", opt("seatClass") + "  " + opt("seat")},
				{"票价", "￥" + opt("price"), "检票口", opt("checkpoint")},
				{"票号", opt("ticketId"), "账号", opt("account")},
		};
		for (final String[] row : rows) {
			context.drawTextWithShadow(textRenderer, Text.literal("§8" + row[0]), x + 12, ty, INK);
			context.drawTextWithShadow(textRenderer, Text.literal("§0" + row[1]), x + 58, ty, INK);
			context.drawTextWithShadow(textRenderer, Text.literal("§8" + row[2]), x + 200, ty, INK);
			context.drawTextWithShadow(textRenderer, Text.literal("§0" + row[3]), x + 244, ty, INK);
			ty += 18;
		}

		// 电子客票章（红色）
		final int cx = x + w - 52;
		final int cy = y + h - 40;
		context.fill(cx - 18, cy - 18, cx + 18, cy + 18, 0x00FFFFFF);
		context.drawBorder(cx - 18, cy - 18, 36, 36, SEAL);
		context.drawBorder(cx - 16, cy - 16, 32, 32, SEAL);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("§c电子"), cx, cy - 10, SEAL);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("§c客票"), cx, cy - 2, SEAL);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("§c章"), cx, cy + 6, SEAL);

		context.drawTextWithShadow(textRenderer, Text.literal("§7仅供报销使用 · 本凭证打印后车票不可退票"), x + 12, y + h - 38, 0x555555);
		context.drawTextWithShadow(textRenderer, Text.literal("§7开具时间 " + opt("printedAt")), x + 12, y + h - 24, 0x555555);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}

package com.mtr12306.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtr12306.block.ModBlocks;
import com.mtr12306.block.StationBoardBlock;
import com.mtr12306.block.StationBoardBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;

/**
 * 车站大屏渲染：只在锚点方块处绘制整块矩形画面（一次绘制，性能与普通方块持平）。
 */
public class StationBoardRenderer implements BlockEntityRenderer<StationBoardBlockEntity> {
	private static final int BACKGROUND = 0xFF0A1018;
	private static final int HEADER_COLOR = 0xFFEAF2FF;
	private static final int ROW_COLOR = 0xFFB8CBE0;
	private static final int BOARDING_COLOR = 0xFF4CD964;

	/** 1.20.1 的 BER 工厂需要接收 Context 的构造器 */
	public StationBoardRenderer(final net.minecraft.client.render.block.entity.BlockEntityRendererFactory.Context context) {
	}

	@Override
	public void render(final StationBoardBlockEntity entity, final float tickDelta, final MatrixStack matrices,
			final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
		if (!entity.getCachedState().get(StationBoardBlock.MAIN)) {
			return;
		}
		final NbtCompound nbt = entity.getCachedState() == null ? null : null;
		final JsonObject payload = readPayload(entity);
		final int width = Math.max(1, payload.has("width") ? payload.get("width").getAsInt() : extractInt(entity, "w"));
		final int height = Math.max(1, payload.has("height") ? payload.get("height").getAsInt() : extractInt(entity, "h"));
		final JsonArray rows = payload.has("rows") ? payload.getAsJsonArray("rows") : new JsonArray();

		final Direction facing = entity.getCachedState().get(StationBoardBlock.FACING);
		final float yaw = switch (facing) {
			case EAST -> 90.0F;
			case SOUTH -> 180.0F;
			case WEST -> 270.0F;
			default -> 0.0F;
		};

		matrices.push();
		// 以方块中心为轴做水平旋转，和 blockstate 的 y 旋转一致
		matrices.translate(0.5, 0.0, 0.5);
		matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
		matrices.translate(-0.5, 0.0, -0.5);

		final float w = width;
		final float h = height;
		final float z = 14.55F / 16.0F;

		// 背景板
		final Matrix4f matrix = matrices.peek().getPositionMatrix();
		final VertexConsumer solid = vertexConsumers.getBuffer(RenderLayer.getSolid());
		quad(solid, matrix, 0.02F, 0.02F, w - 0.02F, h - 0.02F, z, BACKGROUND, light);

		// 文本
		final TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		matrices.push();
		matrices.translate(0.0F, h - 0.02F, z + 0.001F);
		matrices.scale(1.0F / 16.0F, -1.0F / 16.0F, 1.0F / 16.0F);

		final String station = payload.has("station") ? payload.get("station").getAsString() : "";
		textRenderer.draw(Text.literal("站名 / 车次    开点   终到站    站台  状态"), 2.0F, 2.0F, HEADER_COLOR, false,
				matrices.peek().getPositionMatrix(), vertexConsumers, TextRenderer.TextLayerType.NORMAL, 0, light);

		int line = 0;
		for (int i = 0; i < rows.size(); i++) {
			final JsonObject row = rows.get(i).getAsJsonObject();
			final int y = 12 + line * 9;
			if (y > h * 16.0F - 8.0F) {
				break;
			}
			final boolean boarding = row.has("boarding") && row.get("boarding").getAsBoolean();
			final String text = str(row, "service") + "  " + str(row, "depart") + "  " + str(row, "toStation") + "  "
					+ str(row, "platform") + "  " + str(row, "status");
			textRenderer.draw(Text.literal(text), 2.0F, y, boarding ? BOARDING_COLOR : ROW_COLOR, false,
					matrices.peek().getPositionMatrix(), vertexConsumers, TextRenderer.TextLayerType.NORMAL, 0, light);
			line++;
		}
		if (rows.isEmpty()) {
			textRenderer.draw(Text.literal("暂无车次"), 2.0F, 12.0F, ROW_COLOR, false,
					matrices.peek().getPositionMatrix(), vertexConsumers, TextRenderer.TextLayerType.NORMAL, 0, light);
		}
		matrices.pop();
		matrices.pop();
	}

	private static String str(final JsonObject json, final String key) {
		try {
			return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	private static int extractInt(final StationBoardBlockEntity entity, final String key) {
		final NbtCompound nbt = entity.createNbt();
		return Math.max(1, nbt.getInt(key));
	}

	private static JsonObject readPayload(final StationBoardBlockEntity entity) {
		final NbtCompound nbt = entity.createNbt();
		if (nbt.contains("payload")) {
			try {
				return JsonParser.parseString(nbt.getString("payload")).getAsJsonObject();
			} catch (Exception ignored) {
			}
		}
		return new JsonObject();
	}

	private static void quad(final VertexConsumer consumer, final Matrix4f matrix, final float x1, final float y1,
			final float x2, final float y2, final float z, final int color, final int light) {
		final int a = (color >>> 24) & 0xFF;
		final int r = (color >>> 16) & 0xFF;
		final int g = (color >>> 8) & 0xFF;
		final int b = color & 0xFF;
		consumer.vertex(matrix, x1, y1, z).color(r, g, b, a).texture(0.0F, 0.0F).overlay(0, 10)
				.light(light).normal(0.0F, 0.0F, -1.0F).next();
		consumer.vertex(matrix, x1, y2, z).color(r, g, b, a).texture(0.0F, 1.0F).overlay(0, 10)
				.light(light).normal(0.0F, 0.0F, -1.0F).next();
		consumer.vertex(matrix, x2, y2, z).color(r, g, b, a).texture(1.0F, 1.0F).overlay(0, 10)
				.light(light).normal(0.0F, 0.0F, -1.0F).next();
		consumer.vertex(matrix, x2, y1, z).color(r, g, b, a).texture(1.0F, 0.0F).overlay(0, 10)
				.light(light).normal(0.0F, 0.0F, -1.0F).next();
	}

	@Override
	public boolean rendersOutsideBoundingBox(final StationBoardBlockEntity entity) {
		return true;
	}

	@SuppressWarnings("unused")
	private static void unused() {
		ModBlocks.STATION_BOARD.toString();
	}
}

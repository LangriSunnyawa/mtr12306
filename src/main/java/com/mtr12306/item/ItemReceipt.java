package com.mtr12306.item;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 报销凭证物品：NBT 里保存车票信息，手持右键打开"国铁蓝票"界面（内容即时渲染）。
 * 注意：本类不能直接引用任何 {@code net.minecraft.client.*} 类型，否则专用服务器会加载失败。
 */
public class ItemReceipt extends Item {
	public static final String NBT_KEY = "ReceiptData";

	/** 由客户端初始化时注入：拿着凭证右键时打开蓝票界面 */
	public static java.util.function.Consumer<ItemStack> clientViewer = null;

	public ItemReceipt(final Settings settings) {
		super(settings);
	}

	@Nullable
	public static String data(final ItemStack stack) {
		final NbtCompound nbt = stack.getNbt();
		if (nbt == null || !nbt.contains(NBT_KEY)) {
			return null;
		}
		return nbt.getString(NBT_KEY);
	}

	public static JsonObject json(final ItemStack stack) {
		final String raw = data(stack);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	public static String opt(final JsonObject json, final String key) {
		try {
			return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	@Override
	public Text getName(final ItemStack stack) {
		final JsonObject json = json(stack);
		if (json == null) {
			return Text.translatable("item.mtr12306.receipt");
		}
		return Text.literal("报销凭证 · " + opt(json, "service") + " 次 " + opt(json, "date"));
	}

	@Override
	public boolean hasGlint(final ItemStack stack) {
		return false;
	}

	@Override
	public TypedActionResult<ItemStack> use(final World world, final PlayerEntity user, final Hand hand) {
		final ItemStack stack = user.getStackInHand(hand);
		if (world.isClient && clientViewer != null) {
			clientViewer.accept(stack);
		}
		return TypedActionResult.success(stack);
	}
}

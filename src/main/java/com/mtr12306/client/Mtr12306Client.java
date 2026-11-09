package com.mtr12306.client;

import com.mtr12306.net.ModNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class Mtr12306Client implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(ModNetworking.OPEN_GATE_CONFIG, (client, handler, buf, responseSender) -> {
			final BlockPos pos = buf.readBlockPos();
			final String currentCheckpointId = buf.readString(32767);
			final String currentMode = buf.readString(16);
			final String stationName = buf.readString(32767);
			final int count = buf.readInt();
			final List<String[]> options = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				final String id = buf.readString(32767);
				final String label = buf.readString(32767);
				options.add(new String[]{id, label});
			}
			client.execute(() -> client.setScreen(new GateConfigScreen(pos, currentCheckpointId, currentMode, stationName, options)));
		});
	}
}

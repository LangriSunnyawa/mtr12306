package com.mtr12306;

import com.mtr12306.block.ModBlocks;
import com.mtr12306.item.ModItems;
import com.mtr12306.command.TicketCommands;
import com.mtr12306.mtr.MtrIntegration;
import com.mtr12306.net.ModNetworking;
import com.mtr12306.web.WebServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mtr12306 implements ModInitializer {
	public static final String MOD_ID = "mtr12306";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static TicketingConfig CONFIG = new TicketingConfig();
	private static TicketingManager manager;
	private static WebServer webServer;

	public static Identifier id(final String path) {
		return new Identifier(MOD_ID, path);
	}

	public static TicketingManager manager() {
		return manager;
	}

	public static WebServer webServer() {
		return webServer;
	}

	public static void setWebServer(final WebServer instance) {
		webServer = instance;
	}

	@Override
	public void onInitialize() {
		CONFIG = TicketingConfig.load();
		ModBlocks.register();
		ModItems.register();
		ModNetworking.registerCommon();
		TicketCommands.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			manager = new TicketingManager(server);
			MtrIntegration.init();
			if (CONFIG.webEnabled) {
				new WebServer(manager).start(CONFIG.webBind, CONFIG.webPort);
			}
			LOGGER.info("MTR-12306 购票系统已启动，车票文件：{}", manager.file());
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			final WebServer runningWebServer = webServer;
			if (runningWebServer != null) {
				runningWebServer.stop();
				webServer = null;
			}
			if (manager != null) {
				manager.save();
				manager = null;
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			final TicketingManager current = manager;
			if (current != null) {
				current.tick();
			}
		});

		LOGGER.info("MTR-12306 购票系统已加载");
	}
}

package com.mtr12306.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingManager;
import com.mtr12306.block.ModBlocks;
import com.mtr12306.block.TicketGateBlockEntity;
import com.mtr12306.data.Checkpoint;
import com.mtr12306.data.ServiceStop;
import com.mtr12306.data.Station;
import com.mtr12306.data.Ticket;
import com.mtr12306.data.TicketingException;
import com.mtr12306.data.TrainService;
import com.mtr12306.fares.FareAccount;
import com.mtr12306.web.WebServer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * /mtr12306 管理命令 与 /ticket 乘客命令。
 */
public final class TicketCommands {
	private TicketCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerAdmin(dispatcher);
			registerPlayer(dispatcher);
		});
	}

	private static void registerAdmin(final CommandDispatcher<ServerCommandSource> dispatcher) {
		final LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("mtr12306")
				.requires(source -> source.hasPermissionLevel(2));

		root.then(CommandManager.literal("help").executes(TicketCommands::help));

		root.then(CommandManager.literal("web")
				.then(CommandManager.literal("start").executes(TicketCommands::webStart))
				.then(CommandManager.literal("stop").executes(TicketCommands::webStop))
				.then(CommandManager.literal("info").executes(TicketCommands::webInfo)));

		root.then(CommandManager.literal("station")
				.then(CommandManager.literal("add")
						.then(CommandManager.argument("name", StringArgumentType.string())
								.executes(context -> addStation(context, ""))
								.then(CommandManager.argument("platforms", StringArgumentType.string())
										.executes(context -> addStation(context,
												StringArgumentType.getString(context, "platforms"))))))
				.then(CommandManager.literal("list").executes(TicketCommands::listStations))
				.then(CommandManager.literal("remove")
						.then(CommandManager.argument("id", StringArgumentType.word()).executes(TicketCommands::removeStation))));

		root.then(CommandManager.literal("checkpoint")
				.then(CommandManager.literal("add")
						.then(CommandManager.argument("station", StringArgumentType.word())
								.then(CommandManager.argument("name", StringArgumentType.string())
										.executes(context -> addCheckpoint(context, ""))
										.then(CommandManager.argument("platforms", StringArgumentType.string())
												.executes(context -> addCheckpoint(context,
														StringArgumentType.getString(context, "platforms")))))))
				.then(CommandManager.literal("list").executes(TicketCommands::listCheckpoints))
				.then(CommandManager.literal("remove")
						.then(CommandManager.argument("id", StringArgumentType.word())
								.executes(TicketCommands::removeCheckpoint))));

		root.then(CommandManager.literal("service")
				.then(CommandManager.literal("list").executes(TicketCommands::listServices))
				.then(CommandManager.literal("remove")
						.then(CommandManager.argument("number", StringArgumentType.word())
								.executes(TicketCommands::removeService))));

		root.then(CommandManager.literal("gate")
				.then(CommandManager.literal("bind")
						.then(CommandManager.argument("checkpoint", StringArgumentType.word())
								.then(CommandManager.argument("mode", StringArgumentType.word())
										.executes(TicketCommands::bindGateWithMode))
								.executes(TicketCommands::bindGate)))
				.then(CommandManager.literal("unbind").executes(TicketCommands::unbindGate))
				.then(CommandManager.literal("info").executes(TicketCommands::gateInfo)));

		root.then(CommandManager.literal("boarding")
				.then(CommandManager.literal("list").executes(TicketCommands::listBoarding))
				.then(CommandManager.literal("open")
						.then(CommandManager.argument("service", StringArgumentType.word())
								.then(CommandManager.argument("station", StringArgumentType.word())
										.executes(context -> openBoarding(context, 15))
										.then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 600))
												.executes(context -> openBoarding(context,
														IntegerArgumentType.getInteger(context, "minutes")))))))
				.then(CommandManager.literal("close")
						.then(CommandManager.argument("service", StringArgumentType.word())
								.then(CommandManager.argument("station", StringArgumentType.word())
										.executes(TicketCommands::closeBoarding)))));

		final LiteralArgumentBuilder<ServerCommandSource> buy = CommandManager.literal("buy")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("service", StringArgumentType.word())
								.then(CommandManager.argument("from", StringArgumentType.word())
										.then(CommandManager.argument("to", StringArgumentType.word())
												.executes(context -> buyTicket(context, ""))
												.then(CommandManager.argument("seatClass", StringArgumentType.word())
														.executes(context -> buyTicket(context,
																StringArgumentType.getString(context, "seatClass"))))))));
		root.then(CommandManager.literal("ticket")
				.then(buy)
				.then(CommandManager.literal("list").executes(TicketCommands::listTickets)));

		root.then(CommandManager.literal("login")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(TicketCommands::issueLoginCode)));

		root.then(CommandManager.literal("balance")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(TicketCommands::showBalance)
						.then(CommandManager.argument("amount", IntegerArgumentType.integer(-1000000, 1000000))
								.executes(context -> changeBalance(context,
										IntegerArgumentType.getInteger(context, "amount"))))));

		dispatcher.register(root);
	}

	private static void registerPlayer(final CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("ticket")
				.executes(TicketCommands::myTickets)
				.then(CommandManager.literal("list").executes(TicketCommands::myTickets))
				.then(CommandManager.literal("login").executes(TicketCommands::loginCode)));
	}

	/* ------------------------------------------------------------------ 玩家命令 */

	private static int loginCode(final CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		final ServerPlayerEntity player = player(context);
		final TicketingManager manager = requireManager(context);
		final String code = manager.createLoginCode(player.getGameProfile().getName());
		player.sendMessage(Text.literal("§6§lMTR-12306 网页登录验证码：§e" + code), false);
		player.sendMessage(Text.literal("§7请在购票网页输入玩家 ID §f" + player.getGameProfile().getName()
				+ " §7与该验证码，§c" + Mtr12306.CONFIG.loginCodeMinutes + " 分钟内有效§7。"), false);
		if (Mtr12306.webServer() != null && Mtr12306.webServer().isRunning()) {
			player.sendMessage(Text.literal("§7购票地址：§b" + Mtr12306.webServer().url()), false);
		}
		return 1;
	}

	private static int myTickets(final CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		final ServerPlayerEntity player = player(context);
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			send(context, "§c购票系统尚未就绪");
			return 0;
		}
		final List<Ticket> tickets = manager.ticketsOf(player.getGameProfile().getName());
		send(context, "§6我的车票（" + tickets.size() + " 张）· 余额 "
				+ FareAccount.balance(manager.server(), player.getGameProfile().getName()) + " 元");
		for (final Ticket ticket : tickets) {
			send(context, "§b" + ticket.id + " §f" + ticket.serviceNumber + " " + ticket.date + " §7"
					+ manager.data().stationName(ticket.fromStationId) + " " + ticket.fromTime + " → "
					+ manager.data().stationName(ticket.toStationId) + " " + ticket.toTime
					+ " §7" + ticket.seatClassLabel + " " + ticket.seat + " " + ticket.price + "元 状态 " + ticket.state);
		}
		send(context, "§7网页登录：§e/ticket login §7获取验证码");
		return 1;
	}

	/* ------------------------------------------------------------------ 管理命令 */

	private static int help(final CommandContext<ServerCommandSource> context) {
		send(context, "§6§lMTR-12306 购票系统§r §7（管理员命令）");
		send(context, "§e/mtr12306 web start|stop|info §7网页服务控制");
		send(context, "§e/mtr12306 station add <名称> [站台1,站台2] | list | remove <id>");
		send(context, "§e/mtr12306 checkpoint add <车站id> <检票口名> [站台…] | list | remove <id>");
		send(context, "§e/mtr12306 service list|remove <车次> §7（车次/坐席/票价在网页后台配置）");
		send(context, "§e/mtr12306 gate bind <检票口id> [ENTRY|EXIT|BOTH] | unbind | info");
		send(context, "§e/mtr12306 boarding list | open <车次> <车站id> [分钟] | close <车次> <车站id>");
		send(context, "§e/mtr12306 ticket buy <玩家> <车次> <出发站id> <到达站id> [坐席码]");
		send(context, "§e/mtr12306 ticket list");
		send(context, "§e/mtr12306 balance <玩家> [充值金额] §7查看/调整票价余额（MTR fares）");
		send(context, "§e/mtr12306 login <玩家> §7为其签发网页登录验证码");
		send(context, "§7乘客：§e/ticket §7查看车票，§e/ticket login §7获取网页登录验证码");
		return 1;
	}

	private static int webStart(final CommandContext<ServerCommandSource> context) {
		final TicketingManager manager = requireManager(context);
		WebServer webServer = Mtr12306.webServer();
		if (webServer != null && webServer.isRunning()) {
			send(context, "§e网页服务已在运行：§b" + webServer.url());
			return 1;
		}
		webServer = new WebServer(manager);
		webServer.start(Mtr12306.CONFIG.webBind, Mtr12306.CONFIG.webPort);
		send(context, "§a网页服务已启动：§b" + webServer.url() + " §7（后台 " + webServer.url() + "admin）");
		return 1;
	}

	private static int webStop(final CommandContext<ServerCommandSource> context) {
		final WebServer webServer = Mtr12306.webServer();
		if (webServer != null) {
			webServer.stop();
			send(context, "§a网页服务已停止");
		} else {
			send(context, "§7网页服务未运行");
		}
		return 1;
	}

	private static int webInfo(final CommandContext<ServerCommandSource> context) {
		final WebServer webServer = Mtr12306.webServer();
		send(context, webServer != null && webServer.isRunning()
				? "§a网页购票地址：§b" + webServer.url() + " §7后台：§b" + webServer.url() + "admin"
				: "§7网页服务未运行，使用 §e/mtr12306 web start §7启动");
		return 1;
	}

	private static int addStation(final CommandContext<ServerCommandSource> context, final String platforms) {
		final TicketingManager manager = requireManager(context);
		final Station station = manager.addStation(StringArgumentType.getString(context, "name"), "", splitCsv(platforms));
		send(context, "§a已创建车站 §e" + station.id + " " + station.name + " §7站台 " + String.join(",", station.platforms));
		return 1;
	}

	private static int listStations(final CommandContext<ServerCommandSource> context) {
		final TicketingManager manager = requireManager(context);
		if (manager.data().stations.isEmpty()) {
			send(context, "§7还没有车站，使用 §e/mtr12306 station add <名称> §7新增，或到网页后台创建");
			return 1;
		}
		for (final Station station : manager.data().stations.values()) {
			int checkpoints = 0;
			for (final Checkpoint checkpoint : manager.data().checkpoints.values()) {
				if (checkpoint.stationId.equals(station.id)) {
					checkpoints++;
				}
			}
			send(context, "§b" + station.id + " §f" + station.name + " §7站台[" + String.join(",", station.platforms)
					+ "] 检票口 " + checkpoints + " 个");
		}
		return 1;
	}

	private static int removeStation(final CommandContext<ServerCommandSource> context) {
		requireManager(context).removeStation(StringArgumentType.getString(context, "id"));
		send(context, "§a已删除车站");
		return 1;
	}

	private static int addCheckpoint(final CommandContext<ServerCommandSource> context, final String platforms) {
		final TicketingManager manager = requireManager(context);
		final Checkpoint checkpoint = manager.addCheckpoint(StringArgumentType.getString(context, "station"),
				StringArgumentType.getString(context, "name"), splitCsv(platforms));
		send(context, "§a已创建检票口 §e" + checkpoint.id + " " + manager.data().stationName(checkpoint.stationId)
				+ " " + checkpoint.name);
		return 1;
	}

	private static int listCheckpoints(final CommandContext<ServerCommandSource> context) {
		final TicketingManager manager = requireManager(context);
		if (manager.data().checkpoints.isEmpty()) {
			send(context, "§7还没有检票口");
			return 1;
		}
		for (final Checkpoint checkpoint : manager.data().checkpoints.values()) {
			send(context, "§b" + checkpoint.id + " §f" + manager.data().stationName(checkpoint.stationId) + " "
					+ checkpoint.name + " §7站台[" + String.join(",", checkpoint.platforms) + "]"
					+ (checkpoint.enabled ? "" : " §c已停用"));
		}
		return 1;
	}

	private static int removeCheckpoint(final CommandContext<ServerCommandSource> context) {
		requireManager(context).removeCheckpoint(StringArgumentType.getString(context, "id"));
		send(context, "§a已删除检票口");
		return 1;
	}

	private static int listServices(final CommandContext<ServerCommandSource> context) {
		final TicketingManager manager = requireManager(context);
		if (manager.data().services.isEmpty()) {
			send(context, "§7还没有车次，请在网页后台「车次管理」中添加");
			return 1;
		}
		for (final TrainService service : manager.data().services.values()) {
			final List<String> names = new ArrayList<>();
			service.stops.forEach(stop -> names.add(manager.data().stationName(stop.stationId)));
			send(context, "§b" + service.number + " §7[" + service.date + "] " + String.join(" → ", names)
					+ " §7坐席 " + service.effectiveSeatClasses().size() + " 种 定员 " + service.totalSeats());
		}
		return 1;
	}

	private static int removeService(final CommandContext<ServerCommandSource> context) {
		requireManager(context).removeService(StringArgumentType.getString(context, "number"));
		send(context, "§a已删除车次");
		return 1;
	}

	private static int bindGate(final CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		return bindGate(context, StringArgumentType.getString(context, "checkpoint"), "ENTRY");
	}

	private static int bindGateWithMode(final CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		return bindGate(context, StringArgumentType.getString(context, "checkpoint"),
				StringArgumentType.getString(context, "mode"));
	}

	private static int unbindGate(final CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		return bindGate(context, "", "ENTRY");
	}

	private static int bindGate(final CommandContext<ServerCommandSource> context, final String checkpointId,
			final String mode) throws CommandSyntaxException {
		final ServerPlayerEntity player = player(context);
		final TicketingManager manager = requireManager(context);
		final BlockPos pos = findGate(player);
		if (pos == null) {
			send(context, "§c请看向闸机方块（或将闸机放在脚下）");
			return 0;
		}
		if (!(player.getWorld().getBlockEntity(pos) instanceof TicketGateBlockEntity gate)) {
			send(context, "§c目标方块不是检票闸机");
			return 0;
		}
		if (!checkpointId.isEmpty() && manager.data().checkpoint(checkpointId) == null) {
			send(context, "§c检票口不存在: " + checkpointId);
			return 0;
		}
		gate.setCheckpoint(checkpointId, mode);
		gate.syncBinding();
		send(context, "§a闸机已设置：检票口 §e" + gate.checkpointName() + " §a模式 §e" + gate.mode());
		return 1;
	}

	private static int gateInfo(final CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		final ServerPlayerEntity player = player(context);
		final BlockPos pos = findGate(player);
		if (pos == null) {
			send(context, "§c请看向闸机方块（或将闸机放在脚下）");
			return 0;
		}
		if (player.getWorld().getBlockEntity(pos) instanceof TicketGateBlockEntity gate) {
			send(context, "§a闸机 §7" + pos.toShortString() + " §a检票口：§e" + gate.checkpointName()
					+ " §a模式：§e" + gate.mode() + " §7（ENRTY=进站检票 EXIT=出站放行 BOTH=双向）");
		}
		return 1;
	}

	private static int listBoarding(final CommandContext<ServerCommandSource> context) {
		final TicketingManager manager = requireManager(context);
		final List<TicketingManager.BoardingEntry> entries = manager.boardingEntries(TicketingManager.today());
		if (entries.isEmpty()) {
			send(context, "§7今日没有车次发车计划");
			return 1;
		}
		for (final TicketingManager.BoardingEntry entry : entries) {
			send(context, (entry.openNow ? "§a[检票中] " : "§7[未开始] ") + "§f" + entry.service.number + " §7"
					+ manager.data().stationName(entry.stop.stationId) + " " + entry.stop.depart
					+ " 站台" + entry.stop.platform + " 检票口"
					+ (entry.checkpoint == null ? "未设置" : entry.checkpoint.name));
		}
		return 1;
	}

	private static int openBoarding(final CommandContext<ServerCommandSource> context, final int minutes) {
		final TicketingManager manager = requireManager(context);
		manager.openBoarding(StringArgumentType.getString(context, "service"),
				StringArgumentType.getString(context, "station"), minutes);
		send(context, "§a已手动开始检票，持续 " + minutes + " 分钟");
		return 1;
	}

	private static int closeBoarding(final CommandContext<ServerCommandSource> context) {
		requireManager(context).closeBoarding(StringArgumentType.getString(context, "service"),
				StringArgumentType.getString(context, "station"));
		send(context, "§a已强制停止检票 " + Mtr12306.CONFIG.manualCloseMinutes + " 分钟（覆盖自动检票窗口）");
		return 1;
	}

	private static int buyTicket(final CommandContext<ServerCommandSource> context, final String seatClass) {
		final TicketingManager manager = requireManager(context);
		final TrainService service = manager.data().service(StringArgumentType.getString(context, "service"));
		if (service == null) {
			send(context, "§c车次不存在");
			return 0;
		}
		final String fromId = StringArgumentType.getString(context, "from");
		final ServiceStop fromStop = service.stopAt(fromId);
		if (fromStop == null) {
			send(context, "§c该车次不经过出发站");
			return 0;
		}
		final LocalDate serviceDate = TicketingManager.today().minusDays(fromStop.dayOffset);
		final Ticket ticket = manager.purchase(
				StringArgumentType.getString(context, "player"),
				service.number,
				serviceDate.toString(),
				fromId,
				StringArgumentType.getString(context, "to"),
				seatClass,
				0,
				"");
		send(context, "§a出票成功：§e" + ticket.id + " §7" + manager.data().stationName(ticket.fromStationId)
				+ " → " + manager.data().stationName(ticket.toStationId) + " " + ticket.fromTime
				+ " §7" + ticket.seatClassLabel + " " + ticket.seat + " §e" + ticket.price + " 元");
		return 1;
	}

	private static int listTickets(final CommandContext<ServerCommandSource> context) {
		final TicketingManager manager = requireManager(context);
		send(context, "§a已售车票 " + manager.data().tickets.size() + " 张");
		manager.data().tickets.values().stream().limit(20)
				.forEach(ticket -> send(context, "§7" + ticket.id + " §f" + ticket.playerName + " §7"
						+ ticket.serviceNumber + " " + manager.data().stationName(ticket.fromStationId) + "→"
						+ manager.data().stationName(ticket.toStationId) + " " + ticket.seatClassLabel + " "
						+ ticket.seat + " " + ticket.state));
		return 1;
	}

	/** 管理员/控制台为指定玩家签发网页登录验证码 */
	private static int issueLoginCode(final CommandContext<ServerCommandSource> context) {
		final String player = StringArgumentType.getString(context, "player");
		final String code = requireManager(context).createLoginCode(player);
		send(context, "§a已为 " + player + " 生成网页登录验证码：§e" + code + " §7（"
				+ Mtr12306.CONFIG.loginCodeMinutes + " 分钟内有效，5 次输错作废）");
		return 1;
	}

	private static int showBalance(final CommandContext<ServerCommandSource> context) {
		final TicketingManager manager = requireManager(context);
		final String player = StringArgumentType.getString(context, "player");
		send(context, "§a" + player + " 的票价余额：§e" + FareAccount.balance(manager.server(), player)
				+ " 元 §7（计分板 " + FareAccount.objectiveName() + "）");
		return 1;
	}

	private static int changeBalance(final CommandContext<ServerCommandSource> context, final int amount) {
		final TicketingManager manager = requireManager(context);
		final String player = StringArgumentType.getString(context, "player");
		if (amount >= 0) {
			FareAccount.deposit(manager.server(), player, amount);
		} else {
			FareAccount.withdraw(manager.server(), player, -amount);
		}
		send(context, "§a已更新余额：" + player + " → §e" + FareAccount.balance(manager.server(), player) + " 元");
		return 1;
	}

	/* ------------------------------------------------------------------ 工具 */

	private static List<String> splitCsv(final String text) {
		final List<String> list = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return list;
		}
		for (final String part : text.split("[,，]")) {
			if (!part.isBlank()) {
				list.add(part.trim());
			}
		}
		return list;
	}

	private static TicketingManager requireManager(final CommandContext<ServerCommandSource> context) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			throw new TicketingException("购票系统尚未就绪");
		}
		return manager;
	}

	private static final SimpleCommandExceptionType PLAYER_ONLY =
			new SimpleCommandExceptionType(Text.literal("该命令只能由玩家执行"));

	private static ServerPlayerEntity player(final CommandContext<ServerCommandSource> context)
			throws CommandSyntaxException {
		final ServerPlayerEntity player = context.getSource().getPlayer();
		if (player == null) {
			throw PLAYER_ONLY.create();
		}
		return player;
	}

	private static void send(final CommandContext<ServerCommandSource> context, final String message) {
		context.getSource().sendFeedback(() -> Text.literal(message), false);
	}

	private static BlockPos findGate(final ServerPlayerEntity player) {
		final HitResult hit = player.raycast(6.0, 0.0F, false);
		if (hit instanceof BlockHitResult blockHit
				&& player.getWorld().getBlockState(blockHit.getBlockPos()).getBlock() == ModBlocks.TICKET_GATE) {
			return blockHit.getBlockPos();
		}
		for (final BlockPos candidate : new BlockPos[]{player.getBlockPos(), player.getBlockPos().up()}) {
			if (player.getWorld().getBlockState(candidate).getBlock() == ModBlocks.TICKET_GATE) {
				return candidate;
			}
		}
		return null;
	}
}

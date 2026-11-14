package com.mtr12306.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingConfig;
import com.mtr12306.TicketingManager;
import com.mtr12306.data.Account;
import com.mtr12306.data.Checkpoint;
import com.mtr12306.data.GateBinding;
import com.mtr12306.data.SeatClassConfig;
import com.mtr12306.data.ServiceStop;
import com.mtr12306.data.Station;
import com.mtr12306.data.Ticket;
import com.mtr12306.data.TicketingException;
import com.mtr12306.data.TrainService;
import com.mtr12306.fares.FareAccount;
import com.mtr12306.mtr.MtrIntegration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 内嵌网页购票服务（12306 风格）。
 */
public class WebServer {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final String RESOURCE_ROOT = "/assets/mtr12306/web/";

	private final TicketingManager manager;
	private final Map<String, Long> adminTokens = new ConcurrentHashMap<>();
	private final Map<String, LoginWindow> loginWindows = new ConcurrentHashMap<>();
	private HttpServer server;
	private int port = 0;

	public WebServer(final TicketingManager manager) {
		this.manager = manager;
	}

	public boolean isRunning() {
		return server != null;
	}

	public int port() {
		return port;
	}

	public String url() {
		return "http://localhost:" + port + "/";
	}

	public void start(final String bind, final int requestedPort) {
		final String address = bind == null || bind.isBlank() ? "0.0.0.0" : bind;
		Exception lastError = null;
		for (int offset = 0; offset <= 9; offset++) {
			final int candidate = requestedPort + offset;
			try {
				final HttpServer created = HttpServer.create(new InetSocketAddress(address, candidate), 0);
				created.createContext("/", this::handle);
				created.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
					final Thread thread = new Thread(runnable, "mtr12306-web");
					thread.setDaemon(true);
					return thread;
				}));
				created.start();
				server = created;
				port = candidate;
				Mtr12306.setWebServer(this);
				if (offset > 0) {
					Mtr12306.LOGGER.warn("[MTR-12306] 端口 {} 已被占用，网页服务改用 {}", requestedPort, candidate);
				}
				Mtr12306.LOGGER.info("[MTR-12306] 网页购票系统已启动：{} （管理后台 {}admin ）", url(), url());
				Mtr12306.LOGGER.info("[MTR-12306] 局域网访问地址：http://{}:{}/", localAddress(), candidate);
				if ("mtr12306".equals(Mtr12306.CONFIG.adminPassword)) {
					Mtr12306.LOGGER.warn("[MTR-12306] 管理密码仍是默认值，公开服务器请在 config/mtr12306.json 中修改 adminPassword");
				}
				return;
			} catch (Exception exception) {
				lastError = exception;
				server = null;
			}
		}
		Mtr12306.LOGGER.error("[MTR-12306] 网页服务启动失败，端口 {}-{} 均不可用", requestedPort, requestedPort + 9, lastError);
	}

	public void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
			if (Mtr12306.webServer() == this) {
				Mtr12306.setWebServer(null);
			}
			Mtr12306.LOGGER.info("[MTR-12306] 网页购票系统已停止");
		}
	}

	private static String localAddress() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (Exception ignored) {
			return "localhost";
		}
	}

	/* ------------------------------------------------------------------ 请求分发 */

	private void handle(final HttpExchange exchange) {
		try {
			final String path = exchange.getRequestURI().getPath();
			if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(204, -1);
				return;
			}
			if (path.startsWith("/api/")) {
				handleApi(exchange, path);
			} else {
				serveStatic(exchange, path);
			}
		} catch (TicketingException exception) {
			writeJson(exchange, 400, error(exception.getMessage()));
		} catch (Throwable throwable) {
			Mtr12306.LOGGER.warn("[MTR-12306] 网页请求异常 {} {}", exchange.getRequestURI(), throwable.toString());
			writeJson(exchange, 500, error("服务器内部错误：" + throwable));
		} finally {
			exchange.close();
		}
	}

	private void serveStatic(final HttpExchange exchange, final String rawPath) throws IOException {
		String path = rawPath;
		if (path.isEmpty() || "/".equals(path)) {
			path = "index.html";
		} else if ("/admin".equals(path) || "/admin/".equals(path)) {
			path = "admin.html";
		} else if (path.startsWith("/")) {
			path = path.substring(1);
		}
		if (path.contains("..")) {
			writeText(exchange, 404, "text/plain; charset=utf-8", "not found");
			return;
		}
		try (InputStream input = WebServer.class.getResourceAsStream(RESOURCE_ROOT + path)) {
			if (input == null) {
				writeText(exchange, 404, "text/plain; charset=utf-8", "404 not found: " + path);
				return;
			}
			final byte[] bytes = input.readAllBytes();
			exchange.getResponseHeaders().set("Content-Type", contentType(path));
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(bytes);
			}
		}
	}

	private static String contentType(final String path) {
		if (path.endsWith(".html")) return "text/html; charset=utf-8";
		if (path.endsWith(".css")) return "text/css; charset=utf-8";
		if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
		if (path.endsWith(".json")) return "application/json; charset=utf-8";
		if (path.endsWith(".svg")) return "image/svg+xml";
		if (path.endsWith(".png")) return "image/png";
		return "application/octet-stream";
	}

	/* ------------------------------------------------------------------ API */

	private void handleApi(final HttpExchange exchange, final String path) throws IOException {
		final JsonObject body = readBody(exchange);
		switch (path) {
			case "/api/status" -> writeJson(exchange, 200, status());
			case "/api/stations" -> writeJson(exchange, 200, manager.stationsJson());
			case "/api/checkpoints" -> writeJson(exchange, 200, manager.checkpointsJson());
			case "/api/boarding" -> writeJson(exchange, 200, manager.boardingStatusJson());
			case "/api/search" -> writeJson(exchange, 200, search(body));
			case "/api/seatmap" -> writeJson(exchange, 200, seatMap(exchange, body));
			case "/api/register" -> writeJson(exchange, 200, register(body));
			case "/api/login" -> writeJson(exchange, 200, login(body));
			case "/api/balance" -> writeJson(exchange, 200, balance(exchange));
			case "/api/purchase" -> writeJson(exchange, 200, purchase(exchange, body));
			case "/api/tickets" -> writeJson(exchange, 200, ticketsOf(exchange));
			case "/api/refund" -> writeJson(exchange, 200, refund(exchange, body));
			case "/api/admin/schedule" -> {
				final JsonObject json = new JsonObject();
				json.add("depots", manager.scheduleJson());
				writeJson(exchange, 200, json);
			}
			case "/api/admin/schedule/depot" -> {
				manager.setScheduleDepot(text(body, "name"), (body.has("useSchedule") && body.get("useSchedule").getAsBoolean()),
						intValue(body, "routes"));
				writeJson(exchange, 200, ok("ok"));
			}
			case "/api/admin/schedule/entry" -> {
				manager.addScheduleEntry(text(body, "name"), text(body, "depart"), text(body, "trains"));
				writeJson(exchange, 200, ok("ok"));
			}
			case "/api/admin/schedule/entry/delete" -> {
				manager.deleteScheduleEntry(text(body, "name"), intValue(body, "index"));
				writeJson(exchange, 200, ok("ok"));
			}
			case "/api/admin/schedule/depot/delete" -> {
				manager.deleteScheduleDepot(text(body, "name"));
				writeJson(exchange, 200, ok("ok"));
			}
			case "/api/receipt" -> {
				final String ticketId = firstNonBlank(text(body, "ticket"), queryParam(exchange, "ticket"));
				final Ticket ticket = manager.data().tickets.get(ticketId);
				if (ticket == null) {
					throw new TicketingException("车票不存在: " + ticketId);
				}
				requirePlayer(exchange, ticket.playerName);
				writeJson(exchange, 200, manager.receiptJson(ticket));
			}
			case "/api/receipt/print" -> {
				final String ticketId = text(body, "ticket");
				final Ticket ticket = manager.data().tickets.get(ticketId);
				if (ticket == null) {
					throw new TicketingException("车票不存在: " + ticketId);
				}
				final String player = requirePlayer(exchange, ticket.playerName);
				writeJson(exchange, 200, manager.printReceipt(ticketId, player));
			}
			case "/api/admin/login" -> writeJson(exchange, 200, adminLogin(exchange, body));
			case "/api/admin/mtr/stations" -> requireAdmin(exchange, () -> {
				final JsonObject result = new JsonObject();
				result.addProperty("available", MtrIntegration.isAvailable());
				final JsonArray array = new JsonArray();
				MtrIntegration.stations().forEach(array::add);
				result.add("stations", array);
				writeJson(exchange, 200, result);
			});
			default -> {
				if (path.startsWith("/api/admin/")) {
					requireAdmin(exchange, () -> handleAdmin(exchange, path, body));
				} else {
					writeJson(exchange, 404, error("接口不存在: " + path));
				}
			}
		}
	}

	private static String modVersion() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("mtr12306")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
	}

	private JsonObject status() {
		final JsonObject json = new JsonObject();
		json.addProperty("name", "MTR-12306 铁路购票系统");
		json.addProperty("version", modVersion());
		json.addProperty("date", TicketingManager.today().toString());
		json.addProperty("time", TicketingManager.clockText());
		json.addProperty("timezone", TicketingConfig.zone().getId());
		json.addProperty("stations", manager.data().stations.size());
		json.addProperty("checkpoints", manager.data().checkpoints.size());
		json.addProperty("services", manager.data().services.size());
		json.addProperty("tickets", manager.data().tickets.size());
		json.addProperty("mtr", MtrIntegration.isAvailable());
		json.addProperty("boardingOpenMinutes", Mtr12306.CONFIG.boardingOpenMinutes);
		json.addProperty("boardingCloseMinutes", Mtr12306.CONFIG.boardingCloseMinutes);
		json.addProperty("lateGraceMinutes", Mtr12306.CONFIG.lateGraceMinutes);
		json.addProperty("maxAdvanceDays", Mtr12306.CONFIG.maxAdvanceDays);
		json.addProperty("requirePayment", Mtr12306.CONFIG.requirePayment);
		json.addProperty("requirePlayerAuth", Mtr12306.CONFIG.requirePlayerAuth);
		json.addProperty("fareAccount", FareAccount.objectiveName());
		int openNow = 0;
		for (final JsonElement element : manager.boardingStatusJson()) {
			if (element.getAsJsonObject().get("openNow").getAsBoolean()) {
				openNow++;
			}
		}
		json.addProperty("boardingCount", openNow);
		return json;
	}

	private JsonObject search(final JsonObject body) {
		final Station from = resolveStation(text(body, "from"));
		final Station to = resolveStation(text(body, "to"));
		if (from == null || to == null) {
			throw new TicketingException("请选择有效的出发站与到达站");
		}
		if (from.id.equals(to.id)) {
			throw new TicketingException("出发站与到达站不能相同");
		}
		return manager.search(from.id, to.id, parseDate(text(body, "date")));
	}

	private JsonObject seatMap(final HttpExchange exchange, final JsonObject body) {
		final String service = firstNonBlank(text(body, "service"), queryParam(exchange, "service"));
		final String serviceDate = firstNonBlank(text(body, "serviceDate"), queryParam(exchange, "serviceDate"));
		final String from = firstNonBlank(text(body, "from"), queryParam(exchange, "from"));
		final String to = firstNonBlank(text(body, "to"), queryParam(exchange, "to"));
		final String seatClass = firstNonBlank(text(body, "class"), queryParam(exchange, "class"));
		final Station fromStation = resolveStation(from);
		final Station toStation = resolveStation(to);
		if (service.isBlank() || serviceDate.isBlank()) {
			throw new TicketingException("缺少车次或开行日期");
		}
		return manager.seatMap(service, serviceDate,
				fromStation == null ? from : fromStation.id,
				toStation == null ? to : toStation.id,
				seatClass.isBlank() ? null : seatClass);
	}

	private JsonObject register(final JsonObject body) {
		final Account account = manager.register(text(body, "nickname"), text(body, "mcName"), text(body, "password"));
		final String token = manager.authenticate(account.nickname, text(body, "password"));
		final JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("token", token);
		json.addProperty("nickname", account.nickname);
		json.addProperty("player", account.mcName);
		json.addProperty("balance", FareAccount.balance(manager.server(), account.mcName));
		return json;
	}

	/** 登录：优先用「昵称 + 密码」，兼容旧的「玩家名 + 游戏验证码」 */
	private JsonObject login(final JsonObject body) {
		final String password = text(body, "password");
		final String nickname = text(body, "nickname");
		final JsonObject json = new JsonObject();
		if (!password.isBlank() && !nickname.isBlank()) {
			final String token = manager.authenticate(nickname, password);
			json.addProperty("ok", true);
			json.addProperty("token", token);
			json.addProperty("nickname", nickname.trim());
			json.addProperty("player", manager.playerOfToken(token));
			json.addProperty("balance", FareAccount.balance(manager.server(), manager.playerOfToken(token)));
			return json;
		}
		final String player = text(body, "player");
		final String token = manager.loginWithCode(player, text(body, "code"));
		json.addProperty("ok", true);
		json.addProperty("token", token);
		json.addProperty("nickname", "");
		json.addProperty("player", player.trim());
		json.addProperty("balance", FareAccount.balance(manager.server(), player));
		return json;
	}

	private JsonObject balance(final HttpExchange exchange) {
		final String player = requirePlayer(exchange, queryParam(exchange, "player"));
		final JsonObject json = new JsonObject();
		json.addProperty("player", player);
		json.addProperty("balance", FareAccount.balance(manager.server(), player));
		json.addProperty("account", FareAccount.objectiveName());
		json.addProperty("requirePayment", Mtr12306.CONFIG.requirePayment);
		return json;
	}

	private JsonObject purchase(final HttpExchange exchange, final JsonObject body) {
		final String player = requirePlayer(exchange, text(body, "player"));
		final TrainService service = manager.data().service(text(body, "service"));
		if (service == null) {
			throw new TicketingException("车次不存在: " + text(body, "service"));
		}
		final Station from = resolveStation(text(body, "from"));
		final Station to = resolveStation(text(body, "to"));
		if (from == null || to == null) {
			throw new TicketingException("请选择有效的出发站与到达站");
		}
		final String serviceDate = firstNonBlank(text(body, "serviceDate"), text(body, "date"));
		final String token = firstNonBlank(exchange.getRequestHeaders().getFirst("X-Player-Token"),
				queryParam(exchange, "playerToken"));
		final Ticket ticket = manager.purchase(player, manager.accountOfToken(token), service.number, serviceDate,
				from.id, to.id, text(body, "seatClass"), intValue(body, "car"), text(body, "seat"));
		final JsonObject json = manager.ticketJson(ticket);
		json.addProperty("ok", true);
		json.addProperty("balance", FareAccount.balance(manager.server(), player));
		return json;
	}

	private JsonArray ticketsOf(final HttpExchange exchange) {
		final String requested = queryParam(exchange, "player");
		final String player = adminAuthorized(exchange)
				? (requested == null ? "" : requested.trim())
				: requirePlayer(exchange, requested);
		if (player.isBlank()) {
			throw new TicketingException("请提供玩家 ID");
		}
		final JsonArray array = new JsonArray();
		manager.ticketsOf(player).forEach(ticket -> array.add(manager.ticketJson(ticket)));
		return array;
	}

	private JsonObject refund(final HttpExchange exchange, final JsonObject body) {
		final String ticketId = text(body, "ticket");
		final Ticket ticket = manager.data().tickets.get(ticketId);
		if (ticket == null) {
			throw new TicketingException("车票不存在: " + ticketId);
		}
		final String player = requirePlayer(exchange, ticket.playerName);
		manager.refund(ticketId, player);
		final JsonObject json = ok("退票成功，票款已退回 " + FareAccount.objectiveName() + " 账户");
		json.addProperty("balance", FareAccount.balance(manager.server(), player));
		return json;
	}

	/* ------------------------------------------------------------------ 管理 */

	private JsonObject adminLogin(final HttpExchange exchange, final JsonObject body) {
		final String ip = clientIp(exchange);
		if (loginBlocked(ip)) {
			throw new TicketingException("登录失败次数过多，请稍后再试");
		}
		final String password = text(body, "password");
		if (!Mtr12306.CONFIG.adminPassword.equals(password)) {
			recordLoginFailure(ip);
			throw new TicketingException("密码错误");
		}
		clearLoginFailures(ip);
		final String token = UUID.randomUUID().toString().replace("-", "");
		adminTokens.put(token, System.currentTimeMillis() + Math.max(5, Mtr12306.CONFIG.adminTokenMinutes) * 60_000L);
		final JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("token", token);
		return json;
	}

	private void handleAdmin(final HttpExchange exchange, final String path, final JsonObject body) throws IOException {
		switch (path) {
			case "/api/admin/overview" -> {
				final JsonObject json = new JsonObject();
				json.add("stations", manager.stationsJson());
				json.add("checkpoints", manager.checkpointsJson());
				json.add("services", manager.servicesJson());
				json.add("gates", manager.gatesJson());
				json.add("tickets", manager.ticketsJson());
				json.add("boarding", manager.boardingStatusJson());
				json.add("accounts", manager.accountsJson());
				final JsonObject config = new JsonObject();
				config.addProperty("boardingOpenMinutes", Mtr12306.CONFIG.boardingOpenMinutes);
				config.addProperty("boardingCloseMinutes", Mtr12306.CONFIG.boardingCloseMinutes);
				config.addProperty("lateGraceMinutes", Mtr12306.CONFIG.lateGraceMinutes);
				config.addProperty("manualCloseMinutes", Mtr12306.CONFIG.manualCloseMinutes);
				config.addProperty("maxAdvanceDays", Mtr12306.CONFIG.maxAdvanceDays);
				config.addProperty("webPort", port);
				config.addProperty("timezone", TicketingConfig.zone().getId());
				config.addProperty("farePerKm", Mtr12306.CONFIG.farePerKm);
				config.addProperty("transferWindowMinutes", Mtr12306.CONFIG.transferWindowMinutes);
				config.addProperty("requirePayment", Mtr12306.CONFIG.requirePayment);
				config.addProperty("requirePlayerAuth", Mtr12306.CONFIG.requirePlayerAuth);
				config.addProperty("fareAccount", FareAccount.objectiveName());
				json.add("config", config);
				writeJson(exchange, 200, json);
			}
			case "/api/admin/station" -> {
				final Station station = manager.addStation(text(body, "name"), text(body, "mtrStation"),
						stringList(body, "platforms"));
				writeJson(exchange, 200, manager.stationJson(station));
			}
			case "/api/admin/station/update" -> {
				manager.updateStation(text(body, "id"), text(body, "name"), stringList(body, "platforms"));
				writeJson(exchange, 200, ok("车站已更新"));
			}
			case "/api/admin/station/delete" -> {
				manager.removeStation(text(body, "id"));
				writeJson(exchange, 200, ok("车站已删除"));
			}
			case "/api/admin/checkpoint" -> {
				final Checkpoint checkpoint = manager.addCheckpoint(text(body, "stationId"), text(body, "name"),
						stringList(body, "platforms"));
				writeJson(exchange, 200, manager.checkpointJson(checkpoint));
			}
			case "/api/admin/checkpoint/update" -> {
				manager.updateCheckpoint(text(body, "id"), text(body, "name"), stringList(body, "platforms"),
						!body.has("enabled") || body.get("enabled").getAsBoolean());
				writeJson(exchange, 200, ok("检票口已更新"));
			}
			case "/api/admin/checkpoint/delete" -> {
				manager.removeCheckpoint(text(body, "id"));
				writeJson(exchange, 200, ok("检票口已删除"));
			}
			case "/api/admin/service" -> {
				final List<ServiceStop> stops = new ArrayList<>();
				if (body.has("stops") && body.get("stops").isJsonArray()) {
					for (final JsonElement element : body.getAsJsonArray("stops")) {
						final JsonObject stopJson = element.getAsJsonObject();
						final ServiceStop stop = new ServiceStop(text(stopJson, "stationId"), text(stopJson, "platform"),
								text(stopJson, "arrive"), text(stopJson, "depart"));
						stop.dayOffset = stopJson.has("dayOffset") ? stopJson.get("dayOffset").getAsInt() : 0;
						stop.distance = stopJson.has("distance") ? stopJson.get("distance").getAsDouble() : 0.0D;
						stop.fare = stopJson.has("fare") ? stopJson.get("fare").getAsInt() : 0;
						stop.checkpointId = text(stopJson, "checkpointId");
						stops.add(stop);
					}
				}
				final TrainService service = manager.saveService(text(body, "number"), text(body, "name"),
						text(body, "date"), stops, parseSeatClasses(body));
				writeJson(exchange, 200, manager.serviceJson(service));
			}
			case "/api/admin/service/batch" -> {
				final String prefix = text(body, "prefix");
				final int fromNumber = intValue(body, "from");
				final int toNumber = intValue(body, "to");
				final int step = Math.max(1, intValue(body, "step"));
				final int digits = Math.max(1, intValue(body, "digits"));
				final int headway = Math.max(0, intValue(body, "headway"));
				final int startMinutes = parseClock(text(body, "startTime"));
				final List<TicketingManager.BatchStop> batchStops = new ArrayList<>();
				if (body.has("stops") && body.get("stops").isJsonArray()) {
					for (final JsonElement element : body.getAsJsonArray("stops")) {
						final JsonObject stopJson = element.getAsJsonObject();
						final TicketingManager.BatchStop stop = new TicketingManager.BatchStop();
						stop.stationId = text(stopJson, "stationId");
						stop.platform = text(stopJson, "platform");
						stop.runMinutes = intValue(stopJson, "runMinutes");
						stop.dwellMinutes = intValue(stopJson, "dwellMinutes");
						stop.km = stopJson.has("km") ? stopJson.get("km").getAsDouble() : 0.0D;
						batchStops.add(stop);
					}
				}
				final List<SeatClassConfig> batchSeatClasses = parseSeatClasses(body);
				final List<TrainService> created = manager.createBatchServices(prefix, fromNumber, toNumber, step,
						digits, text(body, "date"), text(body, "name"), startMinutes, headway, batchStops,
						batchSeatClasses);
				final JsonArray numbers = new JsonArray();
				created.forEach(service -> numbers.add(service.number));
				final JsonObject json = ok("已批量开行 " + created.size() + " 趟车次");
				json.add("created", numbers);
				writeJson(exchange, 200, json);
			}
			case "/api/admin/service/delete" -> {
				manager.removeService(text(body, "number"));
				writeJson(exchange, 200, ok("车次已删除"));
			}
			case "/api/admin/gate" -> {
				final GateBinding binding = manager.bindGate(text(body, "dimension"), intValue(body, "x"),
						intValue(body, "y"), intValue(body, "z"), text(body, "stationId"),
						stringList(body, "checkpointIds"), text(body, "mode"));
				manager.applyBindingToWorld(binding.dimension, binding.x, binding.y, binding.z, binding.stationId,
						binding.checkpointIds, binding.mode);
				writeJson(exchange, 200, manager.gateJson(binding));
			}
			case "/api/admin/gate/delete" -> {
				manager.removeGate(text(body, "key"));
				writeJson(exchange, 200, ok("闸机绑定已移除"));
			}
			case "/api/admin/boarding/open" -> {
				final int minutes = body.has("minutes") ? body.get("minutes").getAsInt() : 15;
				manager.openBoarding(text(body, "service"), text(body, "stationId"), minutes);
				writeJson(exchange, 200, ok("已手动开始检票 " + minutes + " 分钟"));
			}
			case "/api/admin/boarding/close" -> {
				manager.closeBoarding(text(body, "service"), text(body, "stationId"));
				writeJson(exchange, 200, ok("已强制停止检票 " + Mtr12306.CONFIG.manualCloseMinutes + " 分钟"));
			}
			case "/api/admin/mtr/station-at" -> {
				final String dimension = firstNonBlank(text(body, "dimension"), "minecraft:overworld");
				final String stationName = MtrIntegration.stationNameAt(dimension, intValue(body, "x"),
						intValue(body, "y"), intValue(body, "z"));
				final JsonObject json = new JsonObject();
				json.addProperty("station", stationName);
				json.add("checkpoints", manager.checkpointsForStationName(stationName));
				writeJson(exchange, 200, json);
			}
			case "/api/admin/mtr/import-all" -> {
				final int added = manager.importMtrStations(MtrIntegration.stations());
				writeJson(exchange, 200, ok("已导入 " + added + " 个 MTR 车站"));
			}
			case "/api/admin/transfer" -> {
				final Checkpoint checkpoint = manager.data().checkpoint(text(body, "checkpointId"));
				if (checkpoint == null) {
					throw new TicketingException("检票口不存在: " + text(body, "checkpointId"));
				}
				final TicketingManager.TransferResult result = manager.transfer(text(body, "player"), checkpoint);
				final JsonObject json = new JsonObject();
				json.addProperty("allow", result.allow);
				json.addProperty("reason", result.reason);
				json.addProperty("arrivingSegment", result.arrivingSegment);
				json.addProperty("nextSegment", result.nextSegment);
				json.addProperty("checkpoint", result.checkpointName);
				if (result.arriving != null) {
					json.add("arriving", manager.ticketJson(result.arriving));
				}
				if (result.next != null) {
					json.add("next", manager.ticketJson(result.next));
				}
				writeJson(exchange, 200, json);
			}
			case "/api/admin/check" -> writeJson(exchange, 200, checkResult(body, false));
			case "/api/admin/check-in" -> writeJson(exchange, 200, checkResult(body, true));
			case "/api/admin/ticket/cancel" -> {
				manager.refund(text(body, "ticket"), null);
				writeJson(exchange, 200, ok("车票已作废并退款"));
			}
			case "/api/admin/balance" -> {
				final String player = text(body, "player");
				final int amount = intValue(body, "amount");
				if (amount >= 0) {
					FareAccount.deposit(manager.server(), player, amount);
				} else {
					FareAccount.withdraw(manager.server(), player, -amount);
				}
				final JsonObject json = ok("余额已更新");
				json.addProperty("balance", FareAccount.balance(manager.server(), player));
				writeJson(exchange, 200, json);
			}
			default -> writeJson(exchange, 404, error("管理接口不存在: " + path));
		}
	}

	private JsonObject checkResult(final JsonObject body, final boolean consume) {
		final Checkpoint checkpoint = manager.data().checkpoint(text(body, "checkpointId"));
		if (checkpoint == null) {
			throw new TicketingException("检票口不存在: " + text(body, "checkpointId"));
		}
		final TicketingManager.CheckResult result = manager.checkPlayer(text(body, "player"), checkpoint, consume);
		final JsonObject json = new JsonObject();
		json.addProperty("allow", result.allow);
		json.addProperty("consume", consume);
		json.addProperty("reason", result.reason);
		json.addProperty("segment", result.segment);
		json.addProperty("service", result.serviceNumber);
		json.addProperty("seat", result.seat);
		json.addProperty("seatClass", result.seatClassLabel);
		json.addProperty("price", result.price);
		json.addProperty("checkpoint", result.checkpointName);
		if (result.ticket != null) {
			json.add("ticket", manager.ticketJson(result.ticket));
		}
		return json;
	}

	/* ------------------------------------------------------------------ 授权 */

	private interface AdminAction {
		void run() throws IOException;
	}

	private void requireAdmin(final HttpExchange exchange, final AdminAction action) throws IOException {
		if (!adminAuthorized(exchange)) {
			writeJson(exchange, 401, error("未授权，请先登录管理后台"));
			return;
		}
		action.run();
	}

	private boolean adminAuthorized(final HttpExchange exchange) {
		String token = exchange.getRequestHeaders().getFirst("X-Admin-Token");
		if (token == null || token.isBlank()) {
			token = queryParam(exchange, "adminToken");
		}
		if (token == null || token.isBlank()) {
			return false;
		}
		final Long expiry = adminTokens.get(token);
		if (expiry == null) {
			return false;
		}
		if (expiry < System.currentTimeMillis()) {
			adminTokens.remove(token);
			return false;
		}
		return true;
	}

	/**
	 * 玩家身份：优先用网页令牌；管理员令牌可直接代查。
	 * 当 config.requirePlayerAuth = false 时退化为按名字（不推荐公开服务器使用）。
	 */
	private String requirePlayer(final HttpExchange exchange, final String requestedPlayer) {
		final String token = firstNonBlank(exchange.getRequestHeaders().getFirst("X-Player-Token"),
				queryParam(exchange, "playerToken"));
		final String player = manager.playerOfToken(token);
		if (player != null) {
			if (requestedPlayer != null && !requestedPlayer.isBlank() && !requestedPlayer.trim().equalsIgnoreCase(player)) {
				throw new TicketingException("登录身份与请求的玩家不一致");
			}
			return player;
		}
		if (!Mtr12306.CONFIG.requirePlayerAuth && requestedPlayer != null && !requestedPlayer.isBlank()) {
			return requestedPlayer.trim();
		}
		throw new TicketingException("请先在游戏内执行 /ticket login 获取验证码，再用玩家 ID + 验证码登录");
	}

	private String clientIp(final HttpExchange exchange) {
		try {
			return exchange.getRemoteAddress().getAddress().getHostAddress();
		} catch (Exception e) {
			return "unknown";
		}
	}

	private static final class LoginWindow {
		int failures;
		long windowStart;
	}

	private boolean loginBlocked(final String ip) {
		final LoginWindow window = loginWindows.get(ip);
		if (window == null) {
			return false;
		}
		final long windowMillis = Math.max(1, Mtr12306.CONFIG.adminLoginWindowMinutes) * 60_000L;
		if (System.currentTimeMillis() - window.windowStart > windowMillis) {
			loginWindows.remove(ip);
			return false;
		}
		return window.failures >= Math.max(1, Mtr12306.CONFIG.adminLoginMaxFailures);
	}

	private void recordLoginFailure(final String ip) {
		loginWindows.compute(ip, (key, window) -> {
			final long windowMillis = Math.max(1, Mtr12306.CONFIG.adminLoginWindowMinutes) * 60_000L;
			final LoginWindow current = window == null ? new LoginWindow() : window;
			if (System.currentTimeMillis() - current.windowStart > windowMillis) {
				current.failures = 0;
				current.windowStart = System.currentTimeMillis();
			}
			if (current.windowStart == 0L) {
				current.windowStart = System.currentTimeMillis();
			}
			current.failures++;
			return current;
		});
	}

	private void clearLoginFailures(final String ip) {
		loginWindows.remove(ip);
	}

	/* ------------------------------------------------------------------ 工具 */

	private Station resolveStation(final String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		final Station byId = manager.data().station(text);
		if (byId != null) {
			return byId;
		}
		for (final Station station : manager.data().stations.values()) {
			if (station.name.equalsIgnoreCase(text)) {
				return station;
			}
		}
		for (final Station station : manager.data().stations.values()) {
			if (station.name.contains(text) || station.id.equalsIgnoreCase(text)) {
				return station;
			}
		}
		return null;
	}

	private static LocalDate parseDate(final String text) {
		try {
			return LocalDate.parse(text);
		} catch (Exception e) {
			throw new TicketingException("日期格式应为 yyyy-MM-dd");
		}
	}

	/** 解析请求体中的坐席配置 */
	private static List<SeatClassConfig> parseSeatClasses(final JsonObject body) {
		final List<SeatClassConfig> seatClasses = new ArrayList<>();
		if (body == null || !body.has("seatClasses") || !body.get("seatClasses").isJsonArray()) {
			return seatClasses;
		}
		for (final JsonElement element : body.getAsJsonArray("seatClasses")) {
			final JsonObject classJson = element.getAsJsonObject();
			final SeatClassConfig seatClass = new SeatClassConfig();
			seatClass.code = firstNonBlank(text(classJson, "code"), "SECOND").toUpperCase();
			seatClass.name = firstNonBlank(text(classJson, "name"), seatClass.code);
			seatClass.layout = firstNonBlank(text(classJson, "layout"), "A B C|D F");
			seatClass.rows = Math.max(0, intValue(classJson, "rows"));
			seatClass.priceMultiplier = classJson.has("priceMultiplier")
					? classJson.get("priceMultiplier").getAsDouble() : 1.0D;
			seatClass.color = firstNonBlank(text(classJson, "color"), "#3b7fca");
			seatClass.reserved = !classJson.has("reserved") || classJson.get("reserved").getAsBoolean();
			if (classJson.has("cars") && classJson.get("cars").isJsonArray()) {
				for (final JsonElement car : classJson.getAsJsonArray("cars")) {
					try {
						seatClass.cars.add(car.getAsInt());
					} catch (Exception ignored) {
					}
				}
			}
			if (classJson.has("diningCars") && classJson.get("diningCars").isJsonArray()) {
				for (final JsonElement dining : classJson.getAsJsonArray("diningCars")) {
					try {
						seatClass.diningCars.add(dining.getAsInt());
					} catch (Exception ignored) {
					}
				}
			}
			if (SeatClassConfig.CODE_STANDING.equals(seatClass.code)) {
				seatClass.cars.clear();
				seatClass.cars.add(0);
				seatClass.rows = 0;
				seatClass.reserved = false;
			}
			seatClasses.add(seatClass);
		}
		return seatClasses;
	}

	/** "06:30" -> 390 分钟 */
	private static int parseClock(final String text) {
		try {
			final String[] parts = text.trim().split(":");
			return Integer.parseInt(parts[0].trim()) * 60 + (parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0);
		} catch (Exception e) {
			return 0;
		}
	}

	private static String firstNonBlank(final String first, final String second) {
		if (first != null && !first.isBlank()) {
			return first.trim();
		}
		return second == null ? "" : second.trim();
	}

	private static String text(final JsonObject json, final String key) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			return "";
		}
		try {
			return json.get(key).getAsString();
		} catch (Exception exception) {
			return "";
		}
	}

	private static int intValue(final JsonObject json, final String key) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			return 0;
		}
		try {
			return json.get(key).getAsInt();
		} catch (Exception exception) {
			return 0;
		}
	}

	private static List<String> stringList(final JsonObject json, final String key) {
		final List<String> list = new ArrayList<>();
		if (json == null || !json.has(key) || !json.get(key).isJsonArray()) {
			return list;
		}
		for (final JsonElement element : json.getAsJsonArray(key)) {
			if (!element.isJsonNull() && !element.getAsString().isBlank()) {
				list.add(element.getAsString().trim());
			}
		}
		return list;
	}

	private static JsonObject readBody(final HttpExchange exchange) {
		try (InputStream input = exchange.getRequestBody()) {
			final String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			if (body.isBlank()) {
				return new JsonObject();
			}
			return TicketingManager.parseJson(body);
		} catch (Exception exception) {
			return new JsonObject();
		}
	}

	private static String queryParam(final HttpExchange exchange, final String name) {
		final String query = exchange.getRequestURI().getQuery();
		if (query == null) {
			return null;
		}
		for (final String pair : query.split("&")) {
			final String[] parts = pair.split("=", 2);
			if (parts.length == 2 && parts[0].equals(name)) {
				return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private static JsonObject ok(final String message) {
		final JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", message);
		return json;
	}

	private static JsonObject error(final String message) {
		final JsonObject json = new JsonObject();
		json.addProperty("ok", false);
		json.addProperty("error", message == null ? "未知错误" : message);
		return json;
	}

	private static void writeJson(final HttpExchange exchange, final int status, final Object payload) {
		try {
			final byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(bytes);
			}
		} catch (IOException ignored) {
		}
	}

	private static void writeText(final HttpExchange exchange, final int status, final String contentType,
			final String body) {
		try {
			final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(bytes);
			}
		} catch (IOException ignored) {
		}
	}
}

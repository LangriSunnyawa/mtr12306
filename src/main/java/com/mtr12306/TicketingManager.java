package com.mtr12306;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtr12306.data.Account;
import com.mtr12306.data.Checkpoint;
import com.mtr12306.data.GateBinding;
import com.mtr12306.data.SeatClassConfig;
import com.mtr12306.data.ServiceStop;
import com.mtr12306.data.Station;
import com.mtr12306.data.Ticket;
import com.mtr12306.data.TicketingData;
import com.mtr12306.data.TicketingException;
import com.mtr12306.data.TrainService;
import com.mtr12306.fares.FareAccount;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 购票系统核心：车站 / 检票口 / 车次（含坐席车厢）/ 车票 的增删改查、
 * 检票校验、座位分配、MTR 票价钱包扣款与持久化。
 */
public class TicketingManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter TICKET_SEQ = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter CLOCK_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final int MAX_DAY_OFFSET = 3;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final MinecraftServer server;
	private final Path file;
	private TicketingData data = new TicketingData();
	/** 手动开检：key = 车次|车站，value = 过期时间戳 */
	private final Map<String, Long> manualOpen = new HashMap<>();
	/** 手动强制停检：key = 车次|车站，value = 过期时间戳 */
	private final Map<String, Long> manualClose = new HashMap<>();
	private final Set<String> announcedBoarding = new LinkedHashSet<>();
	/** 玩家网页令牌 */
	private final Map<String, PlayerSession> playerTokens = new HashMap<>();
	/** 游戏内登录验证码 */
	private final Map<String, LoginCode> loginCodes = new HashMap<>();
	/** 世界加载早于购票管理器初始化时，先暂存闸机登记信息，管理器就绪后再写入 */
	private static final class PendingGate {
		final String dimension;
		final int x;
		final int y;
		final int z;
		final String stationId;
		final String checkpointIdsCsv;
		final String mode;

		PendingGate(final String dimension, final int x, final int y, final int z, final String stationId,
				final String checkpointIdsCsv, final String mode) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.stationId = stationId;
			this.checkpointIdsCsv = checkpointIdsCsv;
			this.mode = mode;
		}
	}

	private static final List<PendingGate> PENDING_GATES = new ArrayList<>();
	private boolean dirty = false;
	private long lastSaveMillis = 0L;
	private int tickCounter = 0;

	public TicketingManager(final MinecraftServer server) {
		this.server = server;
		this.file = server.getSavePath(WorldSavePath.ROOT).resolve("mtr12306").resolve("data.json").toAbsolutePath().normalize();
		load();
		drainPendingGates();
	}

	/** 方块实体在管理器就绪前加载时登记闸机（服务器启动早期区块就已加载） */
	public static void queueGateRegistration(final String dimension, final int x, final int y, final int z,
			final String stationId, final String checkpointIdsCsv, final String mode) {
		synchronized (PENDING_GATES) {
			PENDING_GATES.add(new PendingGate(dimension, x, y, z, stationId, checkpointIdsCsv, mode));
		}
	}

	private synchronized void drainPendingGates() {
		synchronized (PENDING_GATES) {
			for (final PendingGate pending : PENDING_GATES) {
				final List<String> ids = new ArrayList<>();
				if (pending.checkpointIdsCsv != null && !pending.checkpointIdsCsv.isBlank()) {
					for (final String id : pending.checkpointIdsCsv.split(",")) {
						if (!id.isBlank()) {
							ids.add(id.trim());
						}
					}
				}
				bindGateIfAbsent(pending.dimension, pending.x, pending.y, pending.z, pending.stationId, ids, pending.mode);
			}
			PENDING_GATES.clear();
		}
	}

	/* ------------------------------------------------------------------ 持久化 */

	public synchronized void load() {
		try {
			if (Files.exists(file)) {
				final String json = Files.readString(file, StandardCharsets.UTF_8);
				final TicketingData loaded = GSON.fromJson(json, TicketingData.class);
				if (loaded != null) {
					data = loaded;
					if (data.stations == null) data.stations = new java.util.LinkedHashMap<>();
					if (data.checkpoints == null) data.checkpoints = new java.util.LinkedHashMap<>();
					if (data.services == null) data.services = new java.util.LinkedHashMap<>();
					if (data.tickets == null) data.tickets = new java.util.LinkedHashMap<>();
					if (data.gates == null) data.gates = new ArrayList<>();
					if (data.accounts == null) data.accounts = new java.util.LinkedHashMap<>();
				}
			}
		} catch (Exception e) {
			Mtr12306.LOGGER.error("读取购票数据失败: " + file, e);
		}
	}

	public synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
			dirty = false;
			lastSaveMillis = System.currentTimeMillis();
		} catch (IOException e) {
			Mtr12306.LOGGER.error("保存购票数据失败: " + file, e);
		}
	}

	private void markDirty() {
		dirty = true;
		// 立刻落盘（2 秒内多次修改只写一次），避免异常退出丢失配置
		if (System.currentTimeMillis() - lastSaveMillis > 2000L) {
			save();
		}
	}

	public Path file() {
		return file;
	}

	public MinecraftServer server() {
		return server;
	}

	public synchronized TicketingData data() {
		return data;
	}

	/* ------------------------------------------------------------------ 时间工具 */

	public static ZoneId zone() {
		return TicketingConfig.zone();
	}

	public static LocalDate today() {
		return LocalDate.now(zone());
	}

	public static ZonedDateTime now() {
		return ZonedDateTime.now(zone());
	}

	public static LocalTime parseTime(final String text) {
		if (text == null) {
			return null;
		}
		final String value = text.trim();
		if (value.isEmpty()) {
			return null;
		}
		try {
			final String[] parts = value.split(":");
			final int hour = Integer.parseInt(parts[0].trim());
			final int minute = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
			if (hour < 0 || hour > 47 || minute < 0 || minute > 59) {
				return null;
			}
			return LocalTime.of(hour % 24, minute);
		} catch (Exception e) {
			return null;
		}
	}

	private static String timeText(final LocalTime time) {
		return time == null ? "" : time.format(CLOCK);
	}

	/** 该停靠站的实际发车时刻（含跨日偏移） */
	public static ZonedDateTime departureInstant(final LocalDate serviceDate, final ServiceStop stop) {
		final LocalTime time = parseTime(stop.depart);
		if (time == null) {
			return null;
		}
		return serviceDate.atTime(time).atZone(zone()).plusDays(stop.dayOffset);
	}

	/** 该停靠站的实际到达时刻（含跨日偏移） */
	private static ZonedDateTime arrivalInstant(final LocalDate serviceDate, final ServiceStop stop) {
		final LocalTime time = parseTime(stop.hasArrive() ? stop.arrive : stop.depart);
		if (time == null) {
			return null;
		}
		return serviceDate.atTime(time).atZone(zone()).plusDays(stop.dayOffset);
	}

	/** 该停靠站的乘车日期（跨日车次会落在开行日期的后几天） */
	public static LocalDate boardingDate(final LocalDate serviceDate, final ServiceStop stop) {
		return serviceDate.plusDays(stop.dayOffset);
	}

	public static ZonedDateTime boardingWindowOpen(final LocalDate serviceDate, final ServiceStop stop) {
		final ZonedDateTime departure = departureInstant(serviceDate, stop);
		return departure == null ? null : departure.minusMinutes(Math.max(0, Mtr12306.CONFIG.boardingOpenMinutes));
	}

	public static ZonedDateTime boardingWindowClose(final LocalDate serviceDate, final ServiceStop stop) {
		final ZonedDateTime departure = departureInstant(serviceDate, stop);
		if (departure == null) {
			return null;
		}
		return departure.minusMinutes(Math.max(0, Mtr12306.CONFIG.boardingCloseMinutes))
				.plusMinutes(Math.max(0, Mtr12306.CONFIG.lateGraceMinutes));
	}

	private static String manualKey(final String serviceNumber, final String stationId) {
		return serviceNumber + "|" + stationId;
	}

	private static String instanceKey(final String serviceNumber, final String serviceDate) {
		return serviceNumber + "@" + serviceDate;
	}

	private static String instanceDateOf(final Ticket ticket) {
		return ticket.serviceDate == null || ticket.serviceDate.isBlank() ? ticket.date : ticket.serviceDate;
	}

	/* ------------------------------------------------------------------ 车站 */

	public synchronized Station addStation(final String name, final String mtrStation, final List<String> platforms) {
		final String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			throw new TicketingException("车站名称不能为空");
		}
		for (final Station existing : data.stations.values()) {
			if (existing.name.equalsIgnoreCase(trimmed)) {
				throw new TicketingException("车站「" + trimmed + "」已存在");
			}
		}
		final Station station = new Station(nextId("S", data.stations.keySet()), trimmed);
		station.mtrStation = mtrStation == null ? "" : mtrStation.trim();
		if (platforms != null) {
			for (final String platform : platforms) {
				if (platform != null && !platform.isBlank() && !station.platforms.contains(platform.trim())) {
					station.platforms.add(platform.trim());
				}
			}
		}
		data.stations.put(station.id, station);
		markDirty();
		return station;
	}

	public synchronized void updateStation(final String id, final String name, final List<String> platforms) {
		final Station station = data.stations.get(id);
		if (station == null) {
			throw new TicketingException("车站不存在: " + id);
		}
		if (name != null && !name.isBlank()) {
			station.name = name.trim();
		}
		if (platforms != null) {
			station.platforms.clear();
			for (final String platform : platforms) {
				if (platform != null && !platform.isBlank()) {
					station.platforms.add(platform.trim());
				}
			}
		}
		markDirty();
	}

	public synchronized void removeStation(final String id) {
		if (data.stations.remove(id) == null) {
			throw new TicketingException("车站不存在: " + id);
		}
		data.checkpoints.values().removeIf(checkpoint -> checkpoint.stationId.equals(id));
		data.services.values().removeIf(service -> service.indexOfStation(id) >= 0);
		markDirty();
	}

	/* ------------------------------------------------------------------ 检票口 */

	public synchronized Checkpoint addCheckpoint(final String stationId, final String name, final List<String> platforms) {
		final Station station = data.stations.get(stationId);
		if (station == null) {
			throw new TicketingException("车站不存在: " + stationId);
		}
		final String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			throw new TicketingException("检票口名称不能为空");
		}
		for (final Checkpoint checkpoint : data.checkpoints.values()) {
			if (checkpoint.stationId.equals(stationId) && checkpoint.name.equalsIgnoreCase(trimmed)) {
				throw new TicketingException("车站「" + station.name + "」已有检票口「" + trimmed + "」");
			}
		}
		final Checkpoint checkpoint = new Checkpoint(nextId("C", data.checkpoints.keySet()), stationId, trimmed);
		if (platforms != null) {
			for (final String platform : platforms) {
				if (platform != null && !platform.isBlank() && !checkpoint.platforms.contains(platform.trim())) {
					checkpoint.platforms.add(platform.trim());
				}
			}
		}
		data.checkpoints.put(checkpoint.id, checkpoint);
		markDirty();
		return checkpoint;
	}

	public synchronized void updateCheckpoint(final String id, final String name, final List<String> platforms,
			final boolean enabled) {
		final Checkpoint checkpoint = data.checkpoints.get(id);
		if (checkpoint == null) {
			throw new TicketingException("检票口不存在: " + id);
		}
		if (name != null && !name.isBlank()) {
			checkpoint.name = name.trim();
		}
		if (platforms != null) {
			checkpoint.platforms.clear();
			for (final String platform : platforms) {
				if (platform != null && !platform.isBlank()) {
					checkpoint.platforms.add(platform.trim());
				}
			}
		}
		checkpoint.enabled = enabled;
		markDirty();
	}

	public synchronized void removeCheckpoint(final String id) {
		data.checkpoints.remove(id);
		for (final GateBinding gate : data.gates) {
			if (gate.checkpointIds.remove(id)) {
				markDirty();
			}
		}
		markDirty();
	}

	/* ------------------------------------------------------------------ 车次 */

	public synchronized TrainService saveService(final String number, final String name, final String date,
			final List<ServiceStop> stops, final List<SeatClassConfig> seatClasses) {
		final String trimmed = number == null ? "" : number.trim();
		if (trimmed.isEmpty()) {
			throw new TicketingException("车次号不能为空");
		}
		if (stops == null || stops.size() < 2) {
			throw new TicketingException("车次至少需要两个停靠站");
		}
		final TrainService service = data.services.computeIfAbsent(trimmed, TrainService::new);
		service.name = name == null ? "" : name.trim();
		service.date = date == null || date.isBlank() ? "*" : date.trim();
		service.stops = stops;
		if (seatClasses != null && !seatClasses.isEmpty()) {
			service.seatClasses = seatClasses;
		} else if (service.seatClasses.isEmpty()) {
			service.seatClasses = SeatClassConfig.defaults();
		}
		service.capacity = service.totalSeats();
		markDirty();
		return service;
	}

	public synchronized void removeService(final String number) {
		if (data.services.remove(number) == null) {
			throw new TicketingException("车次不存在: " + number);
		}
		markDirty();
	}

	/* ------------------------------------------------------------------ 闸机绑定 */

	public synchronized GateBinding bindGate(final String dimension, final int x, final int y, final int z,
			final String stationId, final List<String> checkpointIds, final String mode) {
		final String key = dimension + "@" + x + "," + y + "," + z;
		GateBinding binding = data.gate(key);
		if (binding == null) {
			binding = new GateBinding(dimension, x, y, z);
			data.gates.add(binding);
		}
		binding.stationId = stationId == null ? "" : stationId;
		binding.checkpointIds = checkpointIds == null ? new ArrayList<>() : new ArrayList<>(checkpointIds);
		binding.mode = mode == null || mode.isBlank() ? "ENTRY" : mode.toUpperCase();
		markDirty();
		return binding;
	}

	/** 兼容旧接口：按单个检票口绑定 */
	public synchronized GateBinding bindGate(final String dimension, final int x, final int y, final int z,
			final String checkpointId, final String mode) {
		String station = "";
		final List<String> ids = new ArrayList<>();
		if (checkpointId != null && !checkpointId.isBlank()) {
			final Checkpoint checkpoint = data.checkpoints.get(checkpointId);
			if (checkpoint != null) {
				station = checkpoint.stationId;
				ids.add(checkpointId);
			}
		}
		return bindGate(dimension, x, y, z, station, ids, mode);
	}

	/** 仅在绑定缺失或不同时写入（方块实体加载时会调用，避免反复落盘） */
	public synchronized boolean bindGateIfAbsent(final String dimension, final int x, final int y, final int z,
			final String stationId, final List<String> checkpointIds, final String mode) {
		final GateBinding existing = data.gate(dimension + "@" + x + "," + y + "," + z);
		final String normalizedMode = mode == null || mode.isBlank() ? "ENTRY" : mode.toUpperCase();
		final List<String> ids = checkpointIds == null ? new ArrayList<>() : new ArrayList<>(checkpointIds);
		if (existing != null && existing.stationId.equals(stationId == null ? "" : stationId)
				&& existing.checkpointIds.equals(ids) && existing.mode.equals(normalizedMode)) {
			return false;
		}
		bindGate(dimension, x, y, z, stationId, ids, normalizedMode);
		return true;
	}

	/** 把网页后台的绑定结果同步到已经放置在世界中的闸机方块 */
	public void applyBindingToWorld(final String dimension, final int x, final int y, final int z,
			final String stationId, final List<String> checkpointIds, final String mode) {
		if (server == null) {
			return;
		}
		server.execute(() -> {
			for (final net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
				if (!world.getRegistryKey().getValue().toString().equals(dimension)) {
					continue;
				}
				final net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
				if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
					continue;
				}
				if (world.getBlockEntity(pos) instanceof com.mtr12306.block.TicketGateBlockEntity gate) {
					gate.setBinding(stationId, checkpointIds, mode);
					gate.markDirty();
					world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
				}
			}
		});
	}

	public synchronized void removeGate(final String key) {
		data.gates.removeIf(gate -> gate.key().equals(key));
		markDirty();
	}

	public synchronized GateBinding gateBinding(final String dimension, final int x, final int y, final int z) {
		return data.gate(dimension + "@" + x + "," + y + "," + z);
	}

	/* ------------------------------------------------------------------ 座位 */

	private Set<String> occupiedSeats(final String serviceNumber, final String serviceDate, final String classCode) {
		final Set<String> occupied = new HashSet<>();
		for (final Ticket ticket : data.tickets.values()) {
			if (Ticket.STATE_REFUNDED.equals(ticket.state) || ticket.standee()) {
				continue;
			}
			if (!ticket.serviceNumber.equals(serviceNumber) || !instanceDateOf(ticket).equals(serviceDate)) {
				continue;
			}
			if (classCode != null && !ticket.seatClass.equalsIgnoreCase(classCode)) {
				continue;
			}
			occupied.add(ticket.car + "-" + ticket.seatLabel);
		}
		return occupied;
	}

	private int soldStandee(final String serviceNumber, final String serviceDate) {
		int count = 0;
		for (final Ticket ticket : data.tickets.values()) {
			if (Ticket.STATE_REFUNDED.equals(ticket.state) || !ticket.standee()) {
				continue;
			}
			if (ticket.serviceNumber.equals(serviceNumber) && instanceDateOf(ticket).equals(serviceDate)) {
				count++;
			}
		}
		return count;
	}

	private static String seatText(final int car, final String label) {
		if (label == null || label.isBlank()) {
			return car <= 0 ? "无座" : String.format("%02d车 自由席", car);
		}
		return String.format("%02d车 %s", car, label);
	}

	/** 为某坐席找一个空位，返回 {car, label}，没有空位返回 null */
	private String[] findFreeSeat(final SeatClassConfig seatClass, final String serviceNumber, final String serviceDate) {
		final Set<String> occupied = occupiedSeats(serviceNumber, serviceDate, seatClass.code);
		final List<Integer> cars = new ArrayList<>(seatClass.seatCars());
		cars.sort(Integer::compareTo);
		final List<String> columns = seatClass.columns();
		for (final int car : cars) {
			for (int row = 1; row <= Math.max(1, seatClass.rows); row++) {
				for (final String column : columns) {
					final String label = row + column;
					if (!occupied.contains(car + "-" + label)) {
						return new String[]{String.valueOf(car), label};
					}
				}
			}
		}
		return null;
	}

	private int soldCountOfClass(final String serviceNumber, final String serviceDate, final String classCode) {
		int count = 0;
		for (final Ticket ticket : data.tickets.values()) {
			if (Ticket.STATE_REFUNDED.equals(ticket.state)) {
				continue;
			}
			if (ticket.serviceNumber.equals(serviceNumber) && instanceDateOf(ticket).equals(serviceDate)
					&& ticket.seatClass.equalsIgnoreCase(classCode)) {
				count++;
			}
		}
		return count;
	}

	private int remainingSeats(final TrainService service, final String serviceDate, final SeatClassConfig seatClass) {
		if (!seatClass.reserved) {
			final int capacity = "STANDING".equals(seatClass.code) ? 999 : Math.max(1, seatClass.seatCount());
			return Math.max(0, capacity - soldCountOfClass(service.number, serviceDate, seatClass.code));
		}
		return Math.max(0, seatClass.seatCount() - occupiedSeats(service.number, serviceDate, seatClass.code).size());
	}

	/* ------------------------------------------------------------------ 查询 */

	public synchronized JsonObject search(final String fromStationId, final String toStationId, final LocalDate date) {
		final JsonArray results = new JsonArray();
		final ZonedDateTime moment = now();
		for (final TrainService service : data.services.values()) {
			if (!service.enabled) {
				continue;
			}
			final int fromIndex = service.indexOfStation(fromStationId);
			final int toIndex = service.indexOfStation(toStationId);
			if (fromIndex < 0 || toIndex <= fromIndex) {
				continue;
			}
			final ServiceStop fromStop = service.stops.get(fromIndex);
			final ServiceStop toStop = service.stops.get(toIndex);
			if (!fromStop.hasDepart()) {
				continue;
			}
			// 找出所有使该站发车日期等于 date 的车次实例（支持跨日）
			for (int offset = 0; offset <= MAX_DAY_OFFSET; offset++) {
				final LocalDate serviceDate = date.minusDays(offset);
				if (!service.runsOn(serviceDate.toString())) {
					continue;
				}
				if (!boardingDate(serviceDate, fromStop).equals(date)) {
					continue;
				}
				final ZonedDateTime departure = departureInstant(serviceDate, fromStop);
				final ZonedDateTime arrival = arrivalInstant(serviceDate, toStop);
				if (departure == null) {
					continue;
				}
				final JsonObject entry = new JsonObject();
				entry.addProperty("service", service.number);
				entry.addProperty("name", service.name);
				entry.addProperty("serviceDate", serviceDate.toString());
				entry.addProperty("date", date.toString());
				entry.addProperty("fromStationId", fromStationId);
				entry.addProperty("fromStation", data.stationName(fromStationId));
				entry.addProperty("toStationId", toStationId);
				entry.addProperty("toStation", data.stationName(toStationId));
				entry.addProperty("fromPlatform", fromStop.platform);
				entry.addProperty("fromTime", timeText(parseTime(fromStop.depart)));
				entry.addProperty("toTime", timeText(parseTime(toStop.hasArrive() ? toStop.arrive : toStop.depart)));
				entry.addProperty("days", toStop.dayOffset - fromStop.dayOffset);
				entry.addProperty("duration", arrival == null ? 0
						: Math.max(0, Duration.between(departure, arrival).toMinutes()));
				final Checkpoint checkpoint = findCheckpointFor(fromStationId, fromStop);
				entry.addProperty("checkpointId", checkpoint == null ? "" : checkpoint.id);
				entry.addProperty("checkpoint", checkpoint == null ? "未设置" : checkpoint.name);
				final boolean boardingNow = isBoardingOpen(service, fromStop, serviceDate, moment);
				entry.addProperty("boarding", boardingNow);
				final ZonedDateTime open = boardingWindowOpen(serviceDate, fromStop);
				final ZonedDateTime close = boardingWindowClose(serviceDate, fromStop);
				entry.addProperty("boardingOpen", open == null ? "" : timeText(open.toLocalTime()));
				entry.addProperty("boardingClose", close == null ? "" : timeText(close.toLocalTime()));
				entry.addProperty("departed", departure.isBefore(moment));
				final JsonArray classes = new JsonArray();
				int remainingTotal = 0;
				int minPrice = Integer.MAX_VALUE;
				for (final SeatClassConfig seatClass : service.effectiveSeatClasses()) {
					final int remaining = remainingSeats(service, serviceDate.toString(), seatClass);
					remainingTotal += Math.min(remaining, 999);
					final int price = priceOf(service, fromIndex, toIndex, seatClass);
					minPrice = Math.min(minPrice, price);
					final JsonObject classJson = new JsonObject();
					classJson.addProperty("code", seatClass.code);
					classJson.addProperty("name", seatClass.name);
					classJson.addProperty("price", price);
					classJson.addProperty("remaining", remaining);
					classJson.addProperty("color", seatClass.color);
					classes.add(classJson);
				}
				entry.add("classes", classes);
				entry.addProperty("remaining", remainingTotal);
				entry.addProperty("minPrice", minPrice == Integer.MAX_VALUE ? 0 : minPrice);
				results.add(entry);
			}
		}
		final JsonObject response = new JsonObject();
		response.addProperty("from", data.stationName(fromStationId));
		response.addProperty("to", data.stationName(toStationId));
		response.addProperty("date", date.toString());
		response.add("services", results);
		return response;
	}

	/** 区间票价：按累计票价差 × 坐席倍率 × 全局倍率 */
	private int priceOf(final TrainService service, final int fromIndex, final int toIndex, final SeatClassConfig seatClass) {
		final ServiceStop fromStop = service.stops.get(fromIndex);
		final ServiceStop toStop = service.stops.get(toIndex);
		double base;
		if (toStop.distance > fromStop.distance) {
			// 里程差（公里）× 每公里单价
			base = (toStop.distance - fromStop.distance) * Math.max(0.01D, Mtr12306.CONFIG.farePerKm);
		} else if (toStop.fare > fromStop.fare) {
			// 兼容旧数据：按累计票价差
			base = toStop.fare - fromStop.fare;
		} else {
			base = Math.max(1, toIndex - fromIndex) * Math.max(1, Mtr12306.CONFIG.farePerStopFallback);
		}
		final double multiplier = Math.max(0.1D, seatClass.priceMultiplier) * Math.max(0.1D, Mtr12306.CONFIG.fareMultiplier);
		return (int) Math.max(1, Math.round(base * multiplier));
	}

	public synchronized JsonObject seatMap(final String serviceNumber, final String serviceDateText,
			final String fromStationId, final String toStationId, final String classCode) {
		final TrainService service = data.services.get(serviceNumber);
		if (service == null) {
			throw new TicketingException("车次不存在: " + serviceNumber);
		}
		final LocalDate serviceDate = LocalDate.parse(serviceDateText);
		final SeatClassConfig seatClass = service.seatClass(classCode);
		if (seatClass == null && !"STANDING".equals(classCode)) {
			throw new TicketingException("坐席不存在: " + classCode);
		}
		final int fromIndex = service.indexOfStation(fromStationId);
		final int toIndex = service.indexOfStation(toStationId);
		final JsonObject json = new JsonObject();
		json.addProperty("service", service.number);
		json.addProperty("serviceDate", serviceDate.toString());
		json.addProperty("class", classCode);
		final SeatClassConfig effective = seatClass == null ? SeatClassConfig.standing() : seatClass;
		json.addProperty("className", effective.name);
		json.addProperty("price", fromIndex >= 0 && toIndex > fromIndex ? priceOf(service, fromIndex, toIndex, effective) : 0);
		json.addProperty("remaining", remainingSeats(service, serviceDate.toString(), effective));
		json.addProperty("reserved", effective.reserved);
		final Set<String> occupied = occupiedSeats(service.number, serviceDate.toString(), effective.code);
		final JsonArray cars = new JsonArray();
		if (!"STANDING".equals(effective.code)) {
			final java.util.TreeSet<Integer> merged = new java.util.TreeSet<>(effective.cars);
			if (effective.diningCars != null) {
				for (final int diningCar : effective.diningCars) {
					if (diningCar > 0) {
						merged.add(diningCar);
					}
				}
			}
			final List<Integer> carNumbers = new ArrayList<>(merged);
			for (final int car : carNumbers) {
				final JsonObject carJson = new JsonObject();
				carJson.addProperty("car", car);
				carJson.addProperty("dining", effective.dining(car));
				carJson.addProperty("reserved", effective.reserved);
				carJson.addProperty("rows", effective.dining(car) ? 0 : effective.rows);
				final JsonArray groups = new JsonArray();
				for (final List<String> group : effective.reserved && !effective.dining(car)
						? effective.columnGroups() : java.util.List.<List<String>>of()) {
					final JsonArray groupJson = new JsonArray();
					group.forEach(groupJson::add);
					groups.add(groupJson);
				}
				carJson.add("groups", groups);
				final JsonArray occupiedJson = new JsonArray();
				for (int row = 1; effective.reserved && !effective.dining(car) && row <= effective.rows; row++) {
					for (final String column : effective.columns()) {
						final String label = row + column;
						if (occupied.contains(car + "-" + label)) {
							occupiedJson.add(label);
						}
					}
				}
				carJson.add("occupied", occupiedJson);
				cars.add(carJson);
			}
		}
		json.add("cars", cars);
		return json;
	}

	/* ------------------------------------------------------------------ 购票 */

	public synchronized Ticket purchase(final String playerNameRaw, final String serviceNumber, final String serviceDateText,
			final String fromStationId, final String toStationId, final String seatClassCode, final int requestedCar,
			final String requestedSeat) {
		return purchase(playerNameRaw, null, serviceNumber, serviceDateText, fromStationId, toStationId, seatClassCode,
				requestedCar, requestedSeat);
	}

	public synchronized Ticket purchase(final String playerNameRaw, final String accountNickname, final String serviceNumber,
			final String serviceDateText, final String fromStationId, final String toStationId,
			final String seatClassCode, final int requestedCar, final String requestedSeat) {
		final String player = playerNameRaw == null ? "" : playerNameRaw.trim();
		if (player.isEmpty() || player.length() > 16) {
			throw new TicketingException("请输入正确的玩家 ID（1-16 个字符）");
		}
		final TrainService service = data.services.get(serviceNumber);
		if (service == null || !service.enabled) {
			throw new TicketingException("车次不存在或已停运: " + serviceNumber);
		}
		final LocalDate serviceDate;
		try {
			serviceDate = LocalDate.parse(serviceDateText);
		} catch (Exception e) {
			throw new TicketingException("日期格式应为 yyyy-MM-dd");
		}
		if (!service.runsOn(serviceDate.toString())) {
			throw new TicketingException("车次 " + service.number + " 在 " + serviceDate + " 不开行");
		}
		final int fromIndex = service.indexOfStation(fromStationId);
		final int toIndex = service.indexOfStation(toStationId);
		if (fromIndex < 0 || toIndex < 0) {
			throw new TicketingException("该车次不经过所选车站");
		}
		if (toIndex <= fromIndex) {
			throw new TicketingException("到达站必须位于出发站之后，请重新选择区间");
		}
		final ServiceStop fromStop = service.stops.get(fromIndex);
		if (!fromStop.hasDepart()) {
			throw new TicketingException("所选出发站是该车次的终到站，无法乘车");
		}
		final LocalDate travelDate = boardingDate(serviceDate, fromStop);
		if (travelDate.isBefore(today())) {
			throw new TicketingException("不能购买过去的车票");
		}
		if (travelDate.isAfter(today().plusDays(Math.max(0, Mtr12306.CONFIG.maxAdvanceDays)))) {
			throw new TicketingException("最多只能提前 " + Mtr12306.CONFIG.maxAdvanceDays + " 天购票");
		}
		final ZonedDateTime departure = departureInstant(serviceDate, fromStop);
		if (departure == null) {
			throw new TicketingException("车次时刻配置有误");
		}
		if (departure.isBefore(now())) {
			throw new TicketingException("该车次已发车，无法购票");
		}
		for (final Ticket existing : data.tickets.values()) {
			if (existing.isValid() && existing.serviceNumber.equals(service.number)
					&& instanceDateOf(existing).equals(serviceDate.toString())
					&& existing.playerName.equalsIgnoreCase(player)) {
				throw new TicketingException("您已购买 " + service.number + " 次 " + travelDate + " 的车票，请勿重复购票");
			}
		}
		// 12306 规则：同一乘车人不能同时购买时间冲突的车票
		{
			final ZonedDateTime newStart = departureInstant(serviceDate, fromStop);
			final ZonedDateTime newEnd = arrivalInstant(serviceDate, service.stops.get(toIndex));
			if (newStart != null) {
				final ZonedDateTime end = newEnd != null ? newEnd : newStart.plusHours(2);
				for (final Ticket existing : data.tickets.values()) {
					if (!existing.isValid() || !existing.playerName.equalsIgnoreCase(player)) {
						continue;
					}
					final TrainService other = data.services.get(existing.serviceNumber);
					if (other == null) {
						continue;
					}
					final LocalDate otherDate;
					try {
						otherDate = LocalDate.parse(instanceDateOf(existing));
					} catch (Exception e) {
						continue;
					}
					final ServiceStop otherFrom = other.stopAt(existing.fromStationId);
					final ServiceStop otherTo = other.stopAt(existing.toStationId);
					final ZonedDateTime otherStart = otherFrom == null ? null : departureInstant(otherDate, otherFrom);
					final ZonedDateTime otherEnd = otherTo == null ? null : arrivalInstant(otherDate, otherTo);
					if (otherStart == null) {
						continue;
					}
					final ZonedDateTime otherEndValue = otherEnd != null ? otherEnd : otherStart.plusHours(2);
					if (newStart.isBefore(otherEndValue) && otherStart.isBefore(end)) {
						throw new TicketingException("行程冲突：您已购买 " + existing.serviceNumber + " 次（"
								+ data.stationName(existing.fromStationId) + " " + existing.fromTime + " → "
								+ data.stationName(existing.toStationId) + " " + existing.toTime
								+ "），与本次购票时间重叠，请先退票或改签");
					}
				}
			}
		}

		final SeatClassConfig requested = "STANDING".equalsIgnoreCase(seatClassCode)
				? SeatClassConfig.standing()
				: service.seatClass(seatClassCode == null ? "" : seatClassCode);
		final SeatClassConfig seatClass = requested != null ? requested
				: service.effectiveSeatClasses().get(0);
		int car = 0;
		String seatLabel = "";
		if (!seatClass.reserved) {
			// 城际 / 无座：不对号入座，只校验定员
			if (remainingSeats(service, serviceDate.toString(), seatClass) <= 0) {
				throw new TicketingException("「" + seatClass.name + "」已售完，请选择其他坐席");
			}
			final List<Integer> seatCars = seatClass.seatCars();
			car = seatCars.isEmpty() ? 0 : seatCars.get(0);
			seatLabel = "";
		} else {
			final Set<Integer> seatCars = new HashSet<>(seatClass.seatCars());
			final boolean explicit = requestedCar > 0 && requestedSeat != null && !requestedSeat.isBlank();
			if (explicit) {
				if (!seatCars.contains(requestedCar)) {
					throw new TicketingException(seatClass.dining(requestedCar)
							? (requestedCar + " 号车厢是餐车，不能选座")
							: ("该坐席没有 " + requestedCar + " 号车厢"));
				}
				if (!seatClass.columns().contains(requestedSeat.replaceAll("\\d", "").trim())) {
					throw new TicketingException("座位号不合法: " + requestedSeat);
				}
				final Set<String> occupied = occupiedSeats(service.number, serviceDate.toString(), seatClass.code);
				if (occupied.contains(requestedCar + "-" + requestedSeat)) {
					throw new TicketingException("座位 " + requestedCar + "车" + requestedSeat + " 已被占用，请重新选择");
				}
				car = requestedCar;
				seatLabel = requestedSeat;
			} else {
				final String[] free = findFreeSeat(seatClass, service.number, serviceDate.toString());
				if (free == null) {
					throw new TicketingException("「" + seatClass.name + "」已售完，请选择其他坐席");
				}
				car = Integer.parseInt(free[0]);
				seatLabel = free[1];
			}
		}

		final int price = priceOf(service, fromIndex, toIndex, seatClass);
		final boolean charged = Mtr12306.CONFIG.requirePayment && price > 0;
		if (charged && !FareAccount.withdraw(server, player, price)) {
			throw new TicketingException("余额不足：本张车票 " + price + " 元，当前余额 "
					+ FareAccount.balance(server, player) + " 元，请先在 MTR 售票机或票价闸机充值");
		}

		final Ticket ticket = new Ticket();
		ticket.id = nextTicketId();
		ticket.playerName = player;
		ticket.account = accountNickname == null ? "" : accountNickname;
		ticket.playerUuid = resolveUuid(player);
		ticket.serviceNumber = service.number;
		ticket.serviceDate = serviceDate.toString();
		ticket.date = travelDate.toString();
		ticket.fromStationId = fromStationId;
		ticket.toStationId = toStationId;
		ticket.fromTime = timeText(parseTime(fromStop.depart));
		final ServiceStop toStop = service.stops.get(toIndex);
		ticket.toTime = timeText(parseTime(toStop.hasArrive() ? toStop.arrive : toStop.depart));
		ticket.seatClass = seatClass.code;
		ticket.seatClassLabel = seatClass.name;
		ticket.car = car;
		ticket.seatLabel = seatLabel;
		ticket.seat = seatText(car, seatLabel);
		ticket.price = price;
		final Checkpoint checkpoint = findCheckpointFor(fromStationId, fromStop);
		ticket.checkpointId = checkpoint == null ? "" : checkpoint.id;
		ticket.state = Ticket.STATE_VALID;
		ticket.purchasedAt = System.currentTimeMillis();
		data.tickets.put(ticket.id, ticket);
		markDirty();
		save();
		return ticket;
	}

	public synchronized void refund(final String ticketId, final String playerName) {
		final Ticket ticket = data.tickets.get(ticketId);
		if (ticket == null) {
			throw new TicketingException("车票不存在: " + ticketId);
		}
		if (playerName != null && !playerName.isBlank() && !ticket.playerName.equalsIgnoreCase(playerName.trim())) {
			throw new TicketingException("只能退订自己的车票");
		}
		if (ticket.receiptPrinted) {
			throw new TicketingException("该车票已打印报销凭证，无法退票");
		}
		if (Ticket.STATE_CHECKED.equals(ticket.state)) {
			throw new TicketingException("该车票已检票，无法退票");
		}
		if (!ticket.isValid()) {
			throw new TicketingException("该车票状态为 " + ticket.state + "，无法退票");
		}
		final TrainService service = data.services.get(ticket.serviceNumber);
		final LocalDate serviceDate;
		try {
			serviceDate = LocalDate.parse(instanceDateOf(ticket));
		} catch (Exception e) {
			throw new TicketingException("车票日期异常");
		}
		if (service != null) {
			final ServiceStop stop = service.stopAt(ticket.fromStationId);
			final ZonedDateTime departure = stop == null ? null : departureInstant(serviceDate, stop);
			if (departure != null && departure.isBefore(now())) {
				throw new TicketingException("列车已发车，无法退票");
			}
		}
		ticket.state = Ticket.STATE_REFUNDED;
		if (Mtr12306.CONFIG.requirePayment && ticket.price > 0) {
			FareAccount.deposit(server, ticket.playerName, ticket.price);
		}
		markDirty();
		save();
	}

	/** 今日是否已有检票进站的车票（双向闸机出站时使用） */
	public synchronized boolean hasBoardedToday(final String playerName) {
		final String date = today().toString();
		for (final Ticket ticket : data.tickets.values()) {
			if (ticket.playerName.equalsIgnoreCase(playerName) && Ticket.STATE_CHECKED.equals(ticket.state)
					&& ticket.date.equals(date)) {
				return true;
			}
		}
		return false;
	}

	public synchronized List<Ticket> ticketsOf(final String playerName) {
		final List<Ticket> result = new ArrayList<>();
		for (final Ticket ticket : data.tickets.values()) {
			if (ticket.playerName.equalsIgnoreCase(playerName)) {
				result.add(ticket);
			}
		}
		result.sort(Comparator.comparing((Ticket t) -> t.date).thenComparing(t -> t.fromTime).reversed());
		return result;
	}

	private String nextTicketId() {
		final String prefix = "E" + LocalDate.now(zone()).format(TICKET_SEQ);
		long seq = data.nextTicketSeq;
		String id;
		do {
			id = prefix + String.format("%04d", seq++ % 10000);
		} while (data.tickets.containsKey(id));
		data.nextTicketSeq = seq;
		return id;
	}

	private String resolveUuid(final String playerName) {
		if (server != null) {
			for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				if (player.getGameProfile().getName().equalsIgnoreCase(playerName)) {
					return player.getUuidAsString();
				}
			}
		}
		return "";
	}

	private static String nextId(final String prefix, final Set<String> existing) {
		int index = existing.size() + 1;
		String id = prefix + index;
		while (existing.contains(id)) {
			index++;
			id = prefix + index;
		}
		return id;
	}

	/* ------------------------------------------------------------------ 检票 */

	public static final class CheckResult {
		public boolean allow;
		public String reason = "";
		public Ticket ticket;
		public String segment = "";
		public String serviceNumber = "";
		public String seatClassLabel = "";
		public String fromStation = "";
		public String toStation = "";
		public String fromTime = "";
		public String toTime = "";
		public String seat = "";
		public int price = 0;
		public String checkpointName = "";

		/** 只读展示用摘要 */
		public String resultSummary() {
			return serviceNumber.isEmpty() ? checkpointName
					: (serviceNumber + " 次 · " + seatClassLabel + " " + seat + " · " + segment);
		}
	}

	private Checkpoint findCheckpointFor(final String stationId, final ServiceStop stop) {
		if (stop.checkpointId != null && !stop.checkpointId.isBlank()) {
			final Checkpoint explicit = data.checkpoints.get(stop.checkpointId);
			if (explicit != null) {
				return explicit;
			}
		}
		for (final Checkpoint checkpoint : data.checkpoints.values()) {
			if (checkpoint.stationId.equals(stationId) && checkpoint.platforms.isEmpty()) {
				return checkpoint;
			}
		}
		for (final Checkpoint checkpoint : data.checkpoints.values()) {
			if (checkpoint.stationId.equals(stationId) && checkpoint.platforms.contains(stop.platform)) {
				return checkpoint;
			}
		}
		return null;
	}

	private boolean checkpointServes(final Checkpoint checkpoint, final ServiceStop stop) {
		return checkpoint.platforms.isEmpty() || checkpoint.platforms.contains(stop.platform);
	}

	public boolean isBoardingOpen(final TrainService service, final ServiceStop stop, final LocalDate serviceDate,
			final ZonedDateTime moment) {
		if (stop == null || !stop.hasDepart()) {
			return false;
		}
		final String key = manualKey(service.number, stop.stationId);
		final long millis = System.currentTimeMillis();
		final Long forcedClose = manualClose.get(key);
		if (forcedClose != null) {
			if (millis < forcedClose) {
				return false;
			}
			manualClose.remove(key);
		}
		final Long forcedOpen = manualOpen.get(key);
		if (forcedOpen != null) {
			if (millis < forcedOpen) {
				return true;
			}
			manualOpen.remove(key);
		}
		final ZonedDateTime open = boardingWindowOpen(serviceDate, stop);
		final ZonedDateTime close = boardingWindowClose(serviceDate, stop);
		if (open == null || close == null) {
			return false;
		}
		return !moment.isBefore(open) && !moment.isAfter(close);
	}

	/** 管理员手动开始检票 */
	public synchronized void openBoarding(final String serviceNumber, final String stationId, final int minutes) {
		final TrainService service = data.services.get(serviceNumber);
		if (service == null) {
			throw new TicketingException("车次不存在: " + serviceNumber);
		}
		if (service.stopAt(stationId) == null) {
			throw new TicketingException("该车次不经过此车站");
		}
		final String key = manualKey(serviceNumber, stationId);
		manualOpen.put(key, System.currentTimeMillis() + Math.max(1, minutes) * 60_000L);
		manualClose.remove(key);
	}

	/** 管理员手动强制停检（在有效期内覆盖自动检票窗口） */
	public synchronized void closeBoarding(final String serviceNumber, final String stationId) {
		final String key = manualKey(serviceNumber, stationId);
		manualOpen.remove(key);
		manualClose.put(key, System.currentTimeMillis()
				+ Math.max(1, Mtr12306.CONFIG.manualCloseMinutes) * 60_000L);
	}

	public synchronized boolean isManuallyClosed(final String serviceNumber, final String stationId) {
		final Long until = manualClose.get(manualKey(serviceNumber, stationId));
		return until != null && System.currentTimeMillis() < until;
	}

	/**
	 * 闸机检票：校验玩家是否有本站当前检票车次的车票。
	 * 与网页「模拟检票」共用，consume=false 时不会核销车票。
	 */
	public synchronized CheckResult checkPlayer(final String playerNameRaw, final Checkpoint checkpoint,
			final boolean consume) {
		final CheckResult result = new CheckResult();
		result.checkpointName = checkpoint.name;
		final String playerName = playerNameRaw == null ? "" : playerNameRaw.trim();
		if (!checkpoint.enabled) {
			result.reason = "该检票口已停用";
			return result;
		}
		if (data.stations.get(checkpoint.stationId) == null) {
			result.reason = "该检票口未关联有效车站";
			return result;
		}
		final LocalDate date = today();
		final ZonedDateTime moment = now();
		Ticket best = null;
		ZonedDateTime bestDeparture = null;
		boolean hasTicketAtStation = false;
		boolean hasTicketAnywhere = false;
		boolean hasUsedTicketToday = false;
		for (final Ticket ticket : data.tickets.values()) {
			if (!ticket.playerName.equalsIgnoreCase(playerName)) {
				continue;
			}
			if (Ticket.STATE_CHECKED.equals(ticket.state) && ticket.date.equals(date.toString())) {
				hasUsedTicketToday = true;
			}
			if (!ticket.isValid() || !ticket.date.equals(date.toString())) {
				continue;
			}
			hasTicketAnywhere = true;
			if (!ticket.fromStationId.equals(checkpoint.stationId)) {
				continue;
			}
			hasTicketAtStation = true;
			final TrainService service = data.services.get(ticket.serviceNumber);
			if (service == null || !service.enabled) {
				continue;
			}
			final LocalDate serviceDate;
			try {
				serviceDate = LocalDate.parse(instanceDateOf(ticket));
			} catch (Exception e) {
				continue;
			}
			if (!service.runsOn(serviceDate.toString())) {
				continue;
			}
			final ServiceStop stop = service.stopAt(checkpoint.stationId);
			if (stop == null || !stop.hasDepart() || !checkpointServes(checkpoint, stop)) {
				continue;
			}
			// 跨日车次：该站实际发车日期必须等于今天
			if (!boardingDate(serviceDate, stop).equals(date)) {
				continue;
			}
			if (!isBoardingOpen(service, stop, serviceDate, moment)) {
				continue;
			}
			final ZonedDateTime departure = departureInstant(serviceDate, stop);
			if (departure == null) {
				continue;
			}
			if (bestDeparture == null || departure.isBefore(bestDeparture)) {
				best = ticket;
				bestDeparture = departure;
			}
		}
		if (best == null) {
			if (hasTicketAtStation) {
				result.reason = "该车站当前没有正在检票的车次，请留意检票时间";
			} else if (hasTicketAnywhere) {
				result.reason = "您的车票不是从本站出发，请到对应车站检票";
			} else if (hasUsedTicketToday) {
				result.reason = "您的车票已检票使用，如需再次乘车请重新购票";
			} else {
				result.reason = "未查询到您今日的有效车票，请先在购票系统购票";
			}
			return result;
		}
		if (consume) {
			best.state = Ticket.STATE_CHECKED;
			best.checkedAt = System.currentTimeMillis();
			best.checkedCheckpointId = checkpoint.id;
			markDirty();
			save();
		}
		final TrainService service = data.services.get(best.serviceNumber);
		final ServiceStop stop = service == null ? null : service.stopAt(checkpoint.stationId);
		result.allow = true;
		result.ticket = best;
		result.serviceNumber = best.serviceNumber;
		result.seatClassLabel = best.seatClassLabel;
		result.fromStation = data.stationName(best.fromStationId);
		result.toStation = data.stationName(best.toStationId);
		result.fromTime = stop == null ? best.fromTime : timeText(parseTime(stop.depart));
		result.toTime = best.toTime;
		result.seat = best.seat;
		result.price = best.price;
		result.segment = "乘坐区间：" + result.fromStation + " → " + result.toStation
				+ "（" + result.fromTime + "-" + result.toTime + "）· " + best.seatClassLabel + " " + best.seat;
		return result;
	}

	/* ------------------------------------------------------------------ 出闸 / 换乘 */

	/** 需要出闸（到达）的车票列表：已进站、未出闸、到达站为本站 */
	public synchronized List<Ticket> onboardTicketsAt(final String playerName, final String stationId) {
		final List<Ticket> list = new ArrayList<>();
		for (final Ticket ticket : data.tickets.values()) {
			if (ticket.playerName.equalsIgnoreCase(playerName) && ticket.onBoard()
					&& ticket.toStationId.equals(stationId)) {
				list.add(ticket);
			}
		}
		list.sort(Comparator.comparingLong((Ticket t) -> t.checkedAt));
		return list;
	}

	/** 记录出闸（出站闸机 / 换乘闸机） */
	public synchronized int markExits(final String playerName, final String stationId, final String checkpointId) {
		final List<Ticket> candidates = onboardTicketsAt(playerName, stationId);
		if (candidates.isEmpty()) {
			return 0;
		}
		final long now = System.currentTimeMillis();
		for (final Ticket ticket : candidates) {
			ticket.exited = true;
			ticket.exitedAt = now;
			ticket.exitCheckpointId = checkpointId;
		}
		markDirty();
		save();
		return candidates.size();
	}

	public static final class TransferResult {
		public boolean allow;
		public String reason = "";
		public Ticket arriving;
		public Ticket next;
		public String arrivingSegment = "";
		public String nextSegment = "";
		public String checkpointName = "";
		public String account = "";
	}

	/**
	 * 换乘闸机：一次通过同时完成「上一段出闸」+「接续车次检票」。
	 */
	public synchronized TransferResult transfer(final String playerNameRaw, final Checkpoint checkpoint) {
		return transfer(playerNameRaw, checkpoint, true);
	}

	public synchronized TransferResult transfer(final String playerNameRaw, final Checkpoint checkpoint,
			final boolean consume) {
		final TransferResult result = new TransferResult();
		result.checkpointName = checkpoint.name;
		final String playerName = playerNameRaw == null ? "" : playerNameRaw.trim();
		if (!checkpoint.enabled) {
			result.reason = "该检票口已停用";
			return result;
		}
		if (data.stations.get(checkpoint.stationId) == null) {
			result.reason = "该检票口未关联有效车站";
			return result;
		}
		final LocalDate date = today();
		final ZonedDateTime moment = now();

		// 1) 出闸：本站到达、尚未出闸的车票
		final List<Ticket> arrivingList = onboardTicketsAt(playerName, checkpoint.stationId);
		// 同一站可能有多张未出闸的票（联程/换乘），取最近一次进站的那一段作为"刚下车"的区段
		final Ticket arriving = arrivingList.isEmpty() ? null : arrivingList.get(arrivingList.size() - 1);

		// 2) 接续：本站出发、正在检票或处于换乘窗口内的车票
		final ZonedDateTime limit = moment.plusMinutes(Math.max(1, Mtr12306.CONFIG.transferWindowMinutes));
		Ticket next = null;
		ZonedDateTime nextDeparture = null;
		for (final Ticket candidate : data.tickets.values()) {
			if (!candidate.playerName.equalsIgnoreCase(playerName) || !candidate.isValid()) {
				continue;
			}
			if (!candidate.fromStationId.equals(checkpoint.stationId) || !candidate.date.equals(date.toString())) {
				continue;
			}
			final TrainService service = data.services.get(candidate.serviceNumber);
			if (service == null || !service.enabled) {
				continue;
			}
			final LocalDate serviceDate;
			try {
				serviceDate = LocalDate.parse(instanceDateOf(candidate));
			} catch (Exception e) {
				continue;
			}
			if (!service.runsOn(serviceDate.toString())) {
				continue;
			}
			final ServiceStop stop = service.stopAt(checkpoint.stationId);
			if (stop == null || !stop.hasDepart() || !checkpointServes(checkpoint, stop)) {
				continue;
			}
			if (!boardingDate(serviceDate, stop).equals(date)) {
				continue;
			}
			final ZonedDateTime departure = departureInstant(serviceDate, stop);
			if (departure == null || departure.isBefore(moment.minusMinutes(2))) {
				continue;
			}
			final boolean boardingNow = isBoardingOpen(service, stop, serviceDate, moment);
			if (!boardingNow && !departure.isBefore(limit)) {
				continue;
			}
			if (nextDeparture == null || departure.isBefore(nextDeparture)) {
				next = candidate;
				nextDeparture = departure;
			}
		}

		if (next == null) {
			result.reason = arriving == null
					? "未查询到从本站出发且正在检票的车票"
					: ("已验到站，但没有可接续的车票（换乘窗口 " + Mtr12306.CONFIG.transferWindowMinutes + " 分钟）");
			return result;
		}

		final long now = System.currentTimeMillis();
		if (arriving != null) {
			if (consume) {
				arriving.exited = true;
				arriving.exitedAt = now;
				arriving.exitCheckpointId = checkpoint.id;
			}
			result.arriving = arriving;
			result.arrivingSegment = "换乘出闸：" + arriving.serviceNumber + " 次 "
					+ data.stationName(arriving.fromStationId) + " → " + data.stationName(arriving.toStationId);
		}
		if (consume) {
			next.state = Ticket.STATE_CHECKED;
			next.checkedAt = now;
			next.checkedCheckpointId = checkpoint.id;
		}
		result.next = next;
		result.nextSegment = "接续检票：" + next.serviceNumber + " 次 " + data.stationName(next.fromStationId)
				+ " " + next.fromTime + " → " + data.stationName(next.toStationId) + " " + next.toTime
				+ " · " + next.seatClassLabel + " " + next.seat;
		result.allow = true;
		if (consume) {
			markDirty();
			save();
		}
		return result;
	}

	/* ------------------------------------------------------------------ 批量开行车次 */

	/** 批量添加车次用的停靠站模板 */
	public static final class BatchStop {
		public String stationId = "";
		public String platform = "";
		/** 从上一站到本站的运行时间（分钟） */
		public int runMinutes = 0;
		/** 本站停站时间（分钟），终到站忽略 */
		public int dwellMinutes = 1;
		/** 上一站到本站的里程（公里） */
		public double km = 0.0D;
	}

	/**
	 * 按号段批量生成车次：同一起讫与停站、相同车底，发车时刻按间隔递增。
	 *
	 * @param startMinutes 首班车发车时刻（0 点起的分钟数）
	 * @param headway      相邻车次的发车间隔（分钟）
	 */
	public synchronized List<TrainService> createBatchServices(final String prefix, final int fromNumber,
			final int toNumber, final int step, final int digits, final String date, final String nameText,
			final int startMinutes, final int headway, final List<BatchStop> batchStops,
			final List<SeatClassConfig> seatClasses) {
		if (batchStops == null || batchStops.size() < 2) {
			throw new TicketingException("批量开行至少需要两个停靠站");
		}
		if (toNumber < fromNumber) {
			throw new TicketingException("号段结束值不能小于起始值");
		}
		if ((toNumber - fromNumber) / Math.max(1, step) > 200) {
			throw new TicketingException("一次最多批量开行 200 趟车");
		}
		final List<TrainService> created = new ArrayList<>();
		int index = 0;
		for (int number = fromNumber; number <= toNumber; number += Math.max(1, step)) {
			final String serviceNumber = prefix + String.format("%0" + Math.max(1, digits) + "d", number);
			if (data.services.containsKey(serviceNumber)) {
				index++;
				continue;
			}
			final int offset = startMinutes + index * Math.max(0, headway);
			final List<ServiceStop> stops = new ArrayList<>();
			double distance = 0.0D;
			int clock = offset;
			for (int i = 0; i < batchStops.size(); i++) {
				final BatchStop template = batchStops.get(i);
				final ServiceStop stop = new ServiceStop();
				stop.stationId = template.stationId;
				stop.platform = template.platform;
				if (i > 0) {
					clock += Math.max(0, template.runMinutes);
					stop.arrive = minutesToClock(clock);
				}
				distance += Math.max(0.0D, template.km);
				stop.distance = Math.round(distance * 10.0D) / 10.0D;
				stop.dayOffset = clock / 1440;
				if (i == 0) {
					stop.depart = minutesToClock(clock);
				} else if (i < batchStops.size() - 1) {
					clock += Math.max(0, template.dwellMinutes);
					stop.dayOffset = clock / 1440;
					stop.depart = minutesToClock(clock);
				}
				stops.add(stop);
			}
			created.add(saveService(serviceNumber, nameText, date, stops, seatClasses));
			index++;
		}
		if (created.isEmpty()) {
			throw new TicketingException("号段内所有车次都已存在，未创建新车次");
		}
		return created;
	}

	private static String minutesToClock(final int minutes) {
		final int normalized = ((minutes % 1440) + 1440) % 1440;
		return String.format("%02d:%02d", normalized / 60, normalized % 60);
	}

	/* ------------------------------------------------------------------ 按 MTR 车站筛选检票口 */

	/** 返回属于指定 MTR 车站的检票口（用于闸机配置时限定范围） */
	public synchronized JsonArray checkpointsForStationName(final String stationName) {
		final JsonArray array = new JsonArray();
		if (stationName == null || stationName.isBlank()) {
			return array;
		}
		for (final Station station : data.stations.values()) {
			final boolean match = station.mtrStation.equalsIgnoreCase(stationName)
					|| station.name.equalsIgnoreCase(stationName);
			if (!match) {
				continue;
			}
			for (final Checkpoint checkpoint : data.checkpoints.values()) {
				if (!checkpoint.stationId.equals(station.id) || !checkpoint.enabled) {
					continue;
				}
				final JsonObject json = new JsonObject();
				json.addProperty("id", checkpoint.id);
				json.addProperty("name", checkpoint.name);
				json.addProperty("station", station.name);
				json.addProperty("platforms", String.join(",", checkpoint.platforms));
				array.add(json);
			}
		}
		return array;
	}

	/* ------------------------------------------------------------------ 闸机：车站 / 多检票口 */

	/** 检票口列表是否允许该停靠站（空列表 = 不限） */
	public synchronized boolean checkpointAllows(final List<String> checkpointIds, final String stationId,
			final ServiceStop stop) {
		if (checkpointIds == null || checkpointIds.isEmpty()) {
			return true;
		}
		for (final String id : checkpointIds) {
			final Checkpoint checkpoint = data.checkpoints.get(id);
			if (checkpoint == null || !checkpoint.stationId.equals(stationId)) {
				continue;
			}
			if (checkpoint.platforms.isEmpty() || (stop != null && checkpoint.platforms.contains(stop.platform))) {
				return true;
			}
		}
		return false;
	}

	/** 检票口的展示名（多选或"不限"） */
	public synchronized String checkpointLabel(final String stationId, final List<String> checkpointIds) {
		final String station = data.stationName(stationId);
		if (checkpointIds == null || checkpointIds.isEmpty()) {
			return station + "（不限检票口）";
		}
		final List<String> names = new ArrayList<>();
		for (final String id : checkpointIds) {
			final Checkpoint checkpoint = data.checkpoints.get(id);
			if (checkpoint != null) {
				names.add(checkpoint.name);
			}
		}
		return station + (names.isEmpty() ? "" : " " + String.join("/", names));
	}

	/** 由「车站 + 可选检票口」构造一个用于判定的临时检票口（空列表 = 不限） */
	public synchronized Checkpoint virtualCheckpoint(final String stationId, final List<String> checkpointIds) {
		final Checkpoint virtual = new Checkpoint("__gate__", stationId, data.stationName(stationId));
		if (checkpointIds != null && !checkpointIds.isEmpty()) {
			for (final String id : checkpointIds) {
				final Checkpoint checkpoint = data.checkpoints.get(id);
				if (checkpoint == null) {
					continue;
				}
				if (checkpoint.platforms.isEmpty()) {
					virtual.platforms.clear();
					break;
				}
				virtual.platforms.addAll(checkpoint.platforms);
			}
		}
		return virtual;
	}

	/** 进站检票（按车站 + 可选检票口集合），复用单检票口的判定逻辑 */
	public synchronized CheckResult checkEntry(final String playerName, final String stationId,
			final List<String> checkpointIds, final boolean consume) {
		final Checkpoint virtual = virtualCheckpoint(stationId, checkpointIds);
		final CheckResult result = checkPlayer(playerName, virtual, consume);
		result.checkpointName = checkpointLabel(stationId, checkpointIds);
		return result;
	}

	/**
	 * 出站：只核销"最近一张"已进站未出闸的车票，并允许买短乘长
	 * （票面到站 <= 本闸机所在站在该车次中的位置即可出闸）。
	 */
	public synchronized Ticket exitLatest(final String playerName, final String stationId,
			final List<String> checkpointIds) {
		Ticket best = null;
		for (final Ticket ticket : data.tickets.values()) {
			if (!ticket.playerName.equalsIgnoreCase(playerName) || !ticket.onBoard()) {
				continue;
			}
			final TrainService service = data.services.get(ticket.serviceNumber);
			if (service == null) {
				continue;
			}
			final int gateIndex = service.indexOfStation(stationId);
			final int toIndex = service.indexOfStation(ticket.toStationId);
			if (gateIndex < 0 || toIndex < 0 || toIndex > gateIndex) {
				continue;
			}
			if (!checkpointAllows(checkpointIds, stationId, service.stops.get(gateIndex))) {
				continue;
			}
			if (best == null || ticket.checkedAt > best.checkedAt) {
				best = ticket;
			}
		}
		if (best != null) {
			best.exited = true;
			best.exitedAt = System.currentTimeMillis();
			best.exitCheckpointId = checkpointIds == null || checkpointIds.isEmpty()
					? "" : checkpointIds.get(0);
			markDirty();
			save();
		}
		return best;
	}

	/** 核验闸机：只核验是否存在 N 小时内出发的有效车票（不核销） */
	public synchronized Ticket validateTicket(final String playerName, final int hours) {
		final ZonedDateTime moment = now();
		final ZonedDateTime limit = moment.plusHours(Math.max(1, hours));
		Ticket best = null;
		ZonedDateTime bestDeparture = null;
		for (final Ticket ticket : data.tickets.values()) {
			if (!ticket.playerName.equalsIgnoreCase(playerName) || !ticket.isValid()) {
				continue;
			}
			final TrainService service = data.services.get(ticket.serviceNumber);
			if (service == null || !service.enabled) {
				continue;
			}
			final LocalDate serviceDate;
			try {
				serviceDate = LocalDate.parse(instanceDateOf(ticket));
			} catch (Exception e) {
				continue;
			}
			final ServiceStop from = service.stopAt(ticket.fromStationId);
			final ZonedDateTime departure = from == null ? null : departureInstant(serviceDate, from);
			if (departure == null || departure.isBefore(moment.minusMinutes(30)) || departure.isAfter(limit)) {
				continue;
			}
			if (bestDeparture == null || departure.isBefore(bestDeparture)) {
				best = ticket;
				bestDeparture = departure;
			}
		}
		return best;
	}

	/* ------------------------------------------------------------------ 报销凭证 */

	/** 打印报销凭证：标记车票已打印（打印后不可退票） */
	public synchronized JsonObject printReceipt(final String ticketId, final String playerName) {
		final Ticket ticket = data.tickets.get(ticketId);
		if (ticket == null) {
			throw new TicketingException("车票不存在: " + ticketId);
		}
		if (playerName != null && !playerName.isBlank() && !ticket.playerName.equalsIgnoreCase(playerName.trim())) {
			throw new TicketingException("只能打印自己的报销凭证");
		}
		if (Ticket.STATE_REFUNDED.equals(ticket.state)) {
			throw new TicketingException("已退票的车票不能打印报销凭证");
		}
		if (!ticket.receiptPrinted) {
			ticket.receiptPrinted = true;
			ticket.receiptPrintedAt = System.currentTimeMillis();
			markDirty();
			save();
		}
		return receiptJson(ticket);
	}

	/** 报销凭证展示数据（网页与物品界面共用） */
	public synchronized JsonObject receiptJson(final Ticket ticket) {
		final JsonObject json = new JsonObject();
		json.addProperty("ticketId", ticket.id);
		json.addProperty("passenger", ticket.playerName);
		json.addProperty("account", ticket.account == null ? "" : ticket.account);
		json.addProperty("service", ticket.serviceNumber);
		json.addProperty("date", ticket.date);
		json.addProperty("fromStation", data.stationName(ticket.fromStationId));
		json.addProperty("toStation", data.stationName(ticket.toStationId));
		json.addProperty("fromTime", ticket.fromTime);
		json.addProperty("toTime", ticket.toTime);
		json.addProperty("seatClass", ticket.seatClassLabel);
		json.addProperty("seat", ticket.seat);
		json.addProperty("price", ticket.price);
		json.addProperty("checkpoint", data.checkpointName(ticket.checkpointId));
		json.addProperty("state", ticket.state);
		json.addProperty("printed", ticket.receiptPrinted);
		json.addProperty("printedAt", ticket.receiptPrintedAt);
		json.addProperty("issuedAt", ticket.purchasedAt);
		return json;
	}

	/** 售票机：本站今天可售车次（含坐席与票价） */
	public synchronized JsonArray servicesAtStation(final String stationId) {
		final JsonArray array = new JsonArray();
		final LocalDate date = today();
		final ZonedDateTime moment = now();
		for (final TrainService service : data.services.values()) {
			if (!service.enabled || !service.runsOn(date.toString())) {
				continue;
			}
			final int fromIndex = service.indexOfStation(stationId);
			if (fromIndex < 0) {
				continue;
			}
			final ServiceStop fromStop = service.stops.get(fromIndex);
			if (!fromStop.hasDepart() || !boardingDate(date, fromStop).equals(date)) {
				continue;
			}
			final ZonedDateTime departure = departureInstant(date, fromStop);
			if (departure == null || departure.isBefore(moment)) {
				continue;
			}
			final JsonObject json = new JsonObject();
			json.addProperty("service", service.number);
			json.addProperty("name", service.name);
			json.addProperty("serviceDate", date.toString());
			json.addProperty("date", date.toString());
			json.addProperty("depart", timeText(parseTime(fromStop.depart)));
			json.addProperty("platform", fromStop.platform);
			final JsonArray classes = new JsonArray();
			int minPrice = Integer.MAX_VALUE;
			for (final SeatClassConfig seatClass : service.effectiveSeatClasses()) {
				for (int toIndex = fromIndex + 1; toIndex < service.stops.size(); toIndex++) {
					final ServiceStop toStop = service.stops.get(toIndex);
					final int price = priceOf(service, fromIndex, toIndex, seatClass);
					final int remaining = remainingSeats(service, date.toString(), seatClass);
					final JsonObject classJson = new JsonObject();
					classJson.addProperty("code", seatClass.code);
					classJson.addProperty("name", seatClass.name);
					classJson.addProperty("toStationId", toStop.stationId);
					classJson.addProperty("toStation", data.stationName(toStop.stationId));
					classJson.addProperty("price", price);
					classJson.addProperty("remaining", remaining);
					minPrice = Math.min(minPrice, price);
					classes.add(classJson);
				}
			}
			json.add("classes", classes);
			json.addProperty("minPrice", minPrice == Integer.MAX_VALUE ? 0 : minPrice);
			array.add(json);
		}
		return array;
	}

	/** 玩家的可打印车票（未退票） */
	public synchronized JsonArray receiptTickets(final String playerName) {
		final JsonArray array = new JsonArray();
		for (final Ticket ticket : ticketsOf(playerName)) {
			if (Ticket.STATE_REFUNDED.equals(ticket.state)) {
				continue;
			}
			array.add(receiptJson(ticket));
		}
		return array;
	}

	/* ------------------------------------------------------------------ 检票计划 / 公告 */

	public static final class BoardingEntry {
		public final TrainService service;
		public final ServiceStop stop;
		public final LocalDate serviceDate;
		public final ZonedDateTime open;
		public final ZonedDateTime close;
		public final boolean openNow;
		public final Checkpoint checkpoint;

		BoardingEntry(final TrainService service, final ServiceStop stop, final LocalDate serviceDate,
				final ZonedDateTime open, final ZonedDateTime close, final boolean openNow, final Checkpoint checkpoint) {
			this.service = service;
			this.stop = stop;
			this.serviceDate = serviceDate;
			this.open = open;
			this.close = close;
			this.openNow = openNow;
			this.checkpoint = checkpoint;
		}
	}

	/** 指定乘车日期的检票计划（含前一天开行、今天才到站的跨日车次） */
	public synchronized List<BoardingEntry> boardingEntries(final LocalDate date) {
		final List<BoardingEntry> list = new ArrayList<>();
		final ZonedDateTime moment = now();
		for (final TrainService service : data.services.values()) {
			if (!service.enabled) {
				continue;
			}
			for (int offset = 0; offset <= MAX_DAY_OFFSET; offset++) {
				final LocalDate serviceDate = date.minusDays(offset);
				if (!service.runsOn(serviceDate.toString())) {
					continue;
				}
				for (final ServiceStop stop : service.stops) {
					if (!stop.hasDepart() || !boardingDate(serviceDate, stop).equals(date)) {
						continue;
					}
					final ZonedDateTime open = boardingWindowOpen(serviceDate, stop);
					final ZonedDateTime close = boardingWindowClose(serviceDate, stop);
					if (open == null || close == null) {
						continue;
					}
					list.add(new BoardingEntry(service, stop, serviceDate, open, close,
							isBoardingOpen(service, stop, serviceDate, moment), findCheckpointFor(stop.stationId, stop)));
				}
			}
		}
		list.sort(Comparator.comparing(entry -> entry.open));
		return list;
	}

	/** 每秒检查一次：检票广播与自动存档 */
	public synchronized void tick() {
		tickCounter++;
		if (tickCounter % 20 != 0) {
			return;
		}
		if (dirty && System.currentTimeMillis() - lastSaveMillis > 30_000L) {
			save();
		}
		if (tickCounter % 100 == 0) {
			// MTR 可能在服务器启动后才初始化完成，这里定期重试（首次成功后会直接返回）
			com.mtr12306.mtr.MtrIntegration.isAvailable();
		}
		if (System.currentTimeMillis() - lastSaveMillis > 600_000L) {
			playerTokens.values().removeIf(session -> session.expiresAt < System.currentTimeMillis());
			loginCodes.values().removeIf(code -> code.expiresAt < System.currentTimeMillis());
		}
		final LocalDate date = today();
		final Set<String> currentlyOpen = new LinkedHashSet<>();
		for (final BoardingEntry entry : boardingEntries(date)) {
			if (!entry.openNow) {
				continue;
			}
			final String key = entry.service.number + "|" + entry.stop.stationId + "|" + entry.serviceDate;
			currentlyOpen.add(key);
			if (announcedBoarding.add(key) && Mtr12306.CONFIG.broadcastBoarding && server != null) {
				final String stationName = data.stationName(entry.stop.stationId);
				final String checkpointName = entry.checkpoint == null ? "未设置" : entry.checkpoint.name;
				final Text message = Text.literal("【检票公告】" + entry.service.number + " 次（" + stationName + " "
						+ timeText(parseTime(entry.stop.depart)) + " 开，站台 " + entry.stop.platform + "）开始检票，检票口："
						+ checkpointName + "，请凭车票通过闸机。");
				for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
					player.sendMessage(message, false);
				}
			}
		}
		announcedBoarding.retainAll(currentlyOpen);
	}

	/* ------------------------------------------------------------------ 玩家身份 */

	public static final class PlayerSession {
		/** 绑定的 Minecraft 玩家名（车票归属） */
		public final String playerName;
		/** 网页注册账号昵称（用游戏验证码登录时为空） */
		public final String nickname;
		public final long expiresAt;

		PlayerSession(final String playerName, final String nickname, final long expiresAt) {
			this.playerName = playerName;
			this.nickname = nickname;
			this.expiresAt = expiresAt;
		}
	}

	private static final class LoginCode {
		final String code;
		final long expiresAt;
		int failures;

		LoginCode(final String code, final long expiresAt) {
			this.code = code;
			this.expiresAt = expiresAt;
		}
	}

	/** 游戏内生成登录验证码 */
	public synchronized String createLoginCode(final String playerName) {
		final String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		loginCodes.put(playerName.toLowerCase(), new LoginCode(code,
				System.currentTimeMillis() + Math.max(1, Mtr12306.CONFIG.loginCodeMinutes) * 60_000L));
		markDirty();
		return code;
	}

	/** 网页登录：玩家名 + 验证码换取令牌 */
	public synchronized String loginWithCode(final String playerNameRaw, final String codeRaw) {
		final String playerName = playerNameRaw == null ? "" : playerNameRaw.trim();
		final String code = codeRaw == null ? "" : codeRaw.trim();
		if (playerName.isEmpty() || code.isEmpty()) {
			throw new TicketingException("请输入玩家 ID 与游戏内获取的验证码");
		}
		final String key = playerName.toLowerCase();
		final LoginCode loginCode = loginCodes.get(key);
		if (loginCode == null || loginCode.expiresAt < System.currentTimeMillis()) {
			loginCodes.remove(key);
			throw new TicketingException("验证码不存在或已过期，请在游戏内执行 /ticket login 重新获取");
		}
		if (!loginCode.code.equals(code)) {
			loginCode.failures++;
			if (loginCode.failures >= 5) {
				loginCodes.remove(key);
				throw new TicketingException("验证码错误次数过多，请重新获取验证码");
			}
			throw new TicketingException("验证码错误");
		}
		loginCodes.remove(key);
		final String token = randomToken();
		playerTokens.put(token, new PlayerSession(playerName, "",
				System.currentTimeMillis() + Math.max(1, Mtr12306.CONFIG.playerTokenHours) * 3_600_000L));
		return token;
	}

	/* ---------------------------------------------------------------- 注册账号 */

	/** 网页注册：昵称 + 绑定 MC 名称 + 密码 */
	public synchronized Account register(final String nicknameRaw, final String mcNameRaw, final String password) {
		final String nickname = nicknameRaw == null ? "" : nicknameRaw.trim();
		final String mcName = mcNameRaw == null ? "" : mcNameRaw.trim();
		if (nickname.length() < 2 || nickname.length() > 16) {
			throw new TicketingException("昵称长度需为 2-16 个字符");
		}
		if (!mcName.matches("[A-Za-z0-9_]{3,16}")) {
			throw new TicketingException("Minecraft 名称只能包含字母、数字和下划线，长度 3-16");
		}
		if (password == null || password.length() < 6) {
			throw new TicketingException("密码至少 6 位");
		}
		if (data.account(nickname) != null) {
			throw new TicketingException("昵称「" + nickname + "」已被注册");
		}
		for (final Account existing : data.accounts.values()) {
			if (existing.mcName.equalsIgnoreCase(mcName)) {
				throw new TicketingException("Minecraft 名称「" + mcName + "」已被其他账号绑定");
			}
		}
		final Account account = Account.create(nickname, mcName, password);
		data.accounts.put(nickname.toLowerCase(), account);
		markDirty();
		save();
		return account;
	}

	/** 网页登录：昵称 + 密码 → 令牌 */
	public synchronized String authenticate(final String nicknameRaw, final String password) {
		final String nickname = nicknameRaw == null ? "" : nicknameRaw.trim();
		final Account account = data.account(nickname);
		if (account == null) {
			throw new TicketingException("账号不存在，请先注册");
		}
		if (!account.matches(password)) {
			throw new TicketingException("密码错误");
		}
		final String token = randomToken();
		playerTokens.put(token, new PlayerSession(account.mcName, account.nickname,
				System.currentTimeMillis() + Math.max(1, Mtr12306.CONFIG.playerTokenHours) * 3_600_000L));
		return token;
	}

	public synchronized String accountOfToken(final String token) {
		if (token == null || token.isBlank()) {
			return "";
		}
		final PlayerSession session = playerTokens.get(token);
		return session == null ? "" : session.nickname;
	}

	public synchronized String playerOfToken(final String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		final PlayerSession session = playerTokens.get(token);
		if (session == null) {
			return null;
		}
		if (session.expiresAt < System.currentTimeMillis()) {
			playerTokens.remove(token);
			return null;
		}
		return session.playerName;
	}

	private static String randomToken() {
		final byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		final StringBuilder builder = new StringBuilder();
		for (final byte value : bytes) {
			builder.append(String.format("%02x", value));
		}
		return builder.toString();
	}

	/* ------------------------------------------------------------------ 序列化给网页 */

	public synchronized JsonObject ticketJson(final Ticket ticket) {
		final JsonObject json = new JsonObject();
		json.addProperty("id", ticket.id);
		json.addProperty("player", ticket.playerName);
		json.addProperty("service", ticket.serviceNumber);
		json.addProperty("serviceDate", instanceDateOf(ticket));
		json.addProperty("date", ticket.date);
		json.addProperty("fromStationId", ticket.fromStationId);
		json.addProperty("fromStation", data.stationName(ticket.fromStationId));
		json.addProperty("toStationId", ticket.toStationId);
		json.addProperty("toStation", data.stationName(ticket.toStationId));
		json.addProperty("fromTime", ticket.fromTime);
		json.addProperty("toTime", ticket.toTime);
		json.addProperty("seatClass", ticket.seatClass);
		json.addProperty("seatClassLabel", ticket.seatClassLabel);
		json.addProperty("car", ticket.car);
		json.addProperty("seatLabel", ticket.seatLabel);
		json.addProperty("seat", ticket.seat);
		json.addProperty("price", ticket.price);
		json.addProperty("checkpointId", ticket.checkpointId);
		json.addProperty("checkpoint", data.checkpointName(ticket.checkpointId));
		json.addProperty("state", ticket.state);
		json.addProperty("purchasedAt", ticket.purchasedAt);
		json.addProperty("checkedAt", ticket.checkedAt);
		json.addProperty("checkedCheckpoint", data.checkpointName(ticket.checkedCheckpointId));
		json.addProperty("exited", ticket.exited);
		json.addProperty("exitCheckpoint", data.checkpointName(ticket.exitCheckpointId));
		json.addProperty("account", ticket.account == null ? "" : ticket.account);
		json.addProperty("segment", ticket.fromTime + " " + data.stationName(ticket.fromStationId) + " → "
				+ ticket.toTime + " " + data.stationName(ticket.toStationId));
		final TrainService service = data.service(ticket.serviceNumber);
		if (service != null) {
			final ServiceStop stop = service.stopAt(ticket.fromStationId);
			if (stop != null) {
				try {
					final LocalDate serviceDate = LocalDate.parse(instanceDateOf(ticket));
					final ZonedDateTime open = boardingWindowOpen(serviceDate, stop);
					final ZonedDateTime close = boardingWindowClose(serviceDate, stop);
					json.addProperty("boardingOpen", open == null ? "" : timeText(open.toLocalTime()));
					json.addProperty("boardingClose", close == null ? "" : timeText(close.toLocalTime()));
				} catch (Exception ignored) {
				}
			}
		}
		return json;
	}

	public synchronized JsonObject stationJson(final Station station) {
		final JsonObject json = new JsonObject();
		json.addProperty("id", station.id);
		json.addProperty("name", station.name);
		json.addProperty("mtrStation", station.mtrStation);
		final JsonArray platforms = new JsonArray();
		station.platforms.forEach(platforms::add);
		json.add("platforms", platforms);
		final JsonArray checkpoints = new JsonArray();
		for (final Checkpoint checkpoint : data.checkpoints.values()) {
			if (checkpoint.stationId.equals(station.id)) {
				checkpoints.add(checkpointJson(checkpoint));
			}
		}
		json.add("checkpoints", checkpoints);
		return json;
	}

	public synchronized JsonObject checkpointJson(final Checkpoint checkpoint) {
		final JsonObject json = new JsonObject();
		json.addProperty("id", checkpoint.id);
		json.addProperty("stationId", checkpoint.stationId);
		json.addProperty("station", data.stationName(checkpoint.stationId));
		json.addProperty("name", checkpoint.name);
		json.addProperty("enabled", checkpoint.enabled);
		
		final JsonArray platforms = new JsonArray();
		checkpoint.platforms.forEach(platforms::add);
		json.add("platforms", platforms);
		int gates = 0;
		for (final GateBinding gate : data.gates) {
			if (gate.checkpointIds.contains(checkpoint.id)
					|| (gate.stationId.equals(checkpoint.stationId) && gate.checkpointIds.isEmpty())) {
				gates++;
			}
		}
		json.addProperty("gates", gates);
		final JsonArray services = new JsonArray();
		for (final BoardingEntry entry : boardingEntries(today())) {
			if (entry.checkpoint != null && entry.checkpoint.id.equals(checkpoint.id) && entry.openNow) {
				final JsonObject service = new JsonObject();
				service.addProperty("service", entry.service.number);
				service.addProperty("platform", entry.stop.platform);
				service.addProperty("depart", timeText(parseTime(entry.stop.depart)));
				service.addProperty("close", entry.close == null ? "" : timeText(entry.close.toLocalTime()));
				services.add(service);
			}
		}
		json.add("boarding", services);
		return json;
	}

	public synchronized JsonObject serviceJson(final TrainService service) {
		final JsonObject json = new JsonObject();
		json.addProperty("number", service.number);
		json.addProperty("name", service.name);
		json.addProperty("date", service.date);
		json.addProperty("capacity", service.totalSeats());
		json.addProperty("enabled", service.enabled);
		final JsonArray stops = new JsonArray();
		for (final ServiceStop stop : service.stops) {
			final JsonObject stopJson = new JsonObject();
			stopJson.addProperty("stationId", stop.stationId);
			stopJson.addProperty("station", data.stationName(stop.stationId));
			stopJson.addProperty("platform", stop.platform);
			stopJson.addProperty("arrive", stop.arrive);
			stopJson.addProperty("depart", stop.depart);
			stopJson.addProperty("dayOffset", stop.dayOffset);
			stopJson.addProperty("distance", stop.distance);
			stopJson.addProperty("fare", stop.fare);
			stopJson.addProperty("checkpointId", stop.checkpointId);
			stops.add(stopJson);
		}
		json.add("stops", stops);
		final JsonArray classes = new JsonArray();
		for (final SeatClassConfig seatClass : service.effectiveSeatClasses()) {
			final JsonObject classJson = new JsonObject();
			classJson.addProperty("code", seatClass.code);
			classJson.addProperty("name", seatClass.name);
			classJson.addProperty("layout", seatClass.layout);
			classJson.addProperty("rows", seatClass.rows);
			classJson.addProperty("priceMultiplier", seatClass.priceMultiplier);
			classJson.addProperty("color", seatClass.color);
			classJson.addProperty("reserved", seatClass.reserved);
			classJson.addProperty("seats", seatClass.seatCount());
			final JsonArray cars = new JsonArray();
			seatClass.cars.forEach(cars::add);
			classJson.add("cars", cars);
			final JsonArray diningCars = new JsonArray();
			seatClass.diningCars.forEach(diningCars::add);
			classJson.add("diningCars", diningCars);
			classes.add(classJson);
		}
		json.add("seatClasses", classes);
		return json;
	}

	public synchronized JsonObject gateJson(final GateBinding gate) {
		final JsonObject json = new JsonObject();
		json.addProperty("dimension", gate.dimension);
		json.addProperty("x", gate.x);
		json.addProperty("y", gate.y);
		json.addProperty("z", gate.z);
		json.addProperty("stationId", gate.stationId);
		json.addProperty("station", data.stationName(gate.stationId));
		json.addProperty("checkpoint", checkpointLabel(gate.stationId, gate.checkpointIds));
		final JsonArray cpIds = new JsonArray();
		gate.checkpointIds.forEach(cpIds::add);
		json.add("checkpointIds", cpIds);
		json.addProperty("mode", gate.mode);
		json.addProperty("pos", gate.x + ", " + gate.y + ", " + gate.z);
		return json;
	}

	public synchronized JsonArray boardingStatusJson() {
		final JsonArray array = new JsonArray();
		for (final BoardingEntry entry : boardingEntries(today())) {
			final JsonObject json = new JsonObject();
			json.addProperty("service", entry.service.number);
			json.addProperty("serviceDate", entry.serviceDate.toString());
			json.addProperty("station", data.stationName(entry.stop.stationId));
			json.addProperty("stationId", entry.stop.stationId);
			json.addProperty("platform", entry.stop.platform);
			json.addProperty("depart", timeText(parseTime(entry.stop.depart)));
			json.addProperty("open", entry.open == null ? "" : timeText(entry.open.toLocalTime()));
			json.addProperty("close", entry.close == null ? "" : timeText(entry.close.toLocalTime()));
			json.addProperty("checkpoint", entry.checkpoint == null ? "未设置" : entry.checkpoint.name);
			json.addProperty("checkpointId", entry.checkpoint == null ? "" : entry.checkpoint.id);
			json.addProperty("openNow", entry.openNow);
			json.addProperty("manualClosed", isManuallyClosed(entry.service.number, entry.stop.stationId));
			array.add(json);
		}
		return array;
	}

	public synchronized JsonArray accountsJson() {
		final JsonArray array = new JsonArray();
		for (final Account account : data.accounts.values()) {
			final JsonObject json = new JsonObject();
			json.addProperty("nickname", account.nickname);
			json.addProperty("mcName", account.mcName);
			json.addProperty("createdAt", account.createdAt);
			array.add(json);
		}
		return array;
	}

	public synchronized JsonArray checkpointsJson() {
		final JsonArray array = new JsonArray();
		for (final Checkpoint checkpoint : data.checkpoints.values()) {
			array.add(checkpointJson(checkpoint));
		}
		return array;
	}

	public synchronized JsonArray stationsJson() {
		final JsonArray array = new JsonArray();
		for (final Station station : data.stations.values()) {
			array.add(stationJson(station));
		}
		return array;
	}

	public synchronized JsonArray servicesJson() {
		final JsonArray array = new JsonArray();
		for (final TrainService service : data.services.values()) {
			array.add(serviceJson(service));
		}
		return array;
	}

	public synchronized JsonArray gatesJson() {
		final JsonArray array = new JsonArray();
		for (final GateBinding gate : data.gates) {
			array.add(gateJson(gate));
		}
		return array;
	}

	public synchronized JsonArray ticketsJson() {
		final JsonArray array = new JsonArray();
		for (final Ticket ticket : data.tickets.values()) {
			array.add(ticketJson(ticket));
		}
		return array;
	}

	public synchronized JsonElement importMtrStation(final JsonObject json) {
		final String name = json.has("name") ? json.get("name").getAsString() : "";
		final String mtr = json.has("mtrStation") ? json.get("mtrStation").getAsString() : "";
		final List<String> platforms = new ArrayList<>();
		if (json.has("platforms") && json.get("platforms").isJsonArray()) {
			for (final JsonElement element : json.getAsJsonArray("platforms")) {
				platforms.add(element.getAsString());
			}
		}
		final Station station = addStation(name, mtr, platforms);
		return stationJson(station);
	}

	/** 一键导入全部 MTR 车站，返回新增数量 */
	public synchronized int importMtrStations(final List<JsonObject> stations) {
		int added = 0;
		for (final JsonObject json : stations) {
			try {
				importMtrStation(json);
				added++;
			} catch (TicketingException ignored) {
				// 已存在，跳过
			}
		}
		return added;
	}

	public static JsonObject parseJson(final String body) {
		try {
			final JsonElement element = JsonParser.parseString(body);
			return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			return new JsonObject();
		}
	}

	/** 当前时刻字符串，供状态接口使用 */
	public static String clockText() {
		return now().format(CLOCK_SECONDS);
	}

	public static String clockMinutesText() {
		return now().format(CLOCK);
	}
}

package com.mtr12306.mtr;

import com.google.gson.JsonObject;
import com.mtr12306.Mtr12306;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 与 MTR 模组的集成：读取 MTR 的车站/站台列表，并判断某个方块坐标位于哪个 MTR 车站内。
 * MTR 已是本模组的强制前置，但这里仍通过反射访问，避免绑定到具体 MTR 版本的方法签名。
 */
public final class MtrIntegration {
	private static boolean initialized = false;
	private static boolean available = false;
	private static Object mainObject;
	private static Field simulatorsField;
	private static Field simulatorDimensionField;
	private static Field simulatorDimensionsField;
	private static Constructor<?> positionConstructor;
	private static Method inAreaMethod;

	private MtrIntegration() {
	}

	public static synchronized void init() {
		initialized = true;
		available = false;
		mainObject = null;
		if (!FabricLoader.getInstance().isModLoaded("mtr")) {
			Mtr12306.LOGGER.error("[MTR-12306] 未检测到 MTR 模组：本模组需要 MTR 4.0.5 及以上版本");
			return;
		}
		try {
			final Class<?> initClass = Class.forName("org.mtr.mod.Init");
			final Field mainField = initClass.getDeclaredField("main");
			mainField.setAccessible(true);
			mainObject = mainField.get(null);
			if (mainObject == null) {
				Mtr12306.LOGGER.info("[MTR-12306] MTR 尚未初始化完成，稍后重试");
				return;
			}
			simulatorsField = findField(mainObject.getClass(), "simulators");
			simulatorsField.setAccessible(true);
			try {
				simulatorDimensionField = findField(Class.forName("org.mtr.core.simulation.Simulator"), "dimension");
				simulatorDimensionField.setAccessible(true);
			} catch (Exception ignored) {
			}
			try {
				simulatorDimensionsField = findField(Class.forName("org.mtr.core.simulation.Simulator"), "dimensions");
				simulatorDimensionsField.setAccessible(true);
			} catch (Exception ignored) {
			}
			final Class<?> positionClass = Class.forName("org.mtr.core.data.Position");
			positionConstructor = positionClass.getConstructor(long.class, long.class, long.class);
			inAreaMethod = Class.forName("org.mtr.core.data.AreaBase").getMethod("inArea", positionClass);
			available = true;
			Mtr12306.LOGGER.info("[MTR-12306] MTR 集成已启用（车站导入 + 闸机位置车站判定）");
		} catch (Throwable throwable) {
			Mtr12306.LOGGER.error("[MTR-12306] MTR 集成初始化失败：{}", throwable.toString());
		}
	}

	private static long lastAttempt = 0L;

	private static void initIfNeeded() {
		if (available) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (initialized && now - lastAttempt < 2000L) {
			return;
		}
		lastAttempt = now;
		init();
	}

	public static boolean isAvailable() {
		initIfNeeded();
		return available;
	}

	private static List<Object> simulators() throws Exception {
		final List<Object> result = new ArrayList<>();
		if (simulatorsField == null || mainObject == null) {
			return result;
		}
		final Object simulators = simulatorsField.get(mainObject);
		if (simulators instanceof Iterable<?> iterable) {
			for (final Object element : iterable) {
				result.add(element);
			}
		}
		return result;
	}

	private static String normalizeDimension(final String value) {
		return value == null ? "" : value.trim().replace(':', '/');
	}

	private static boolean simulatorInDimension(final Object simulator, final String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank()) {
			return true;
		}
		final String wanted = normalizeDimension(dimensionId);
		try {
			if (simulatorDimensionField != null) {
				final Object dimension = simulatorDimensionField.get(simulator);
				if (dimension instanceof String value) {
					final String candidate = normalizeDimension(value);
					if (candidate.equals(wanted) || wanted.endsWith(candidate) || candidate.endsWith(wanted)) {
						return true;
					}
				}
			}
			if (simulatorDimensionsField != null) {
				final Object dimensions = simulatorDimensionsField.get(simulator);
				if (dimensions instanceof String[] array && array.length > 0) {
					for (final String value : array) {
						final String candidate = normalizeDimension(value);
						if (candidate.equals(wanted) || wanted.endsWith(candidate) || candidate.endsWith(wanted)) {
							return true;
						}
					}
					return false;
				}
			}
		} catch (Exception ignored) {
		}
		return true;
	}

	/** 返回方块所在 MTR 车站名称；不在任何车站内时返回空字符串 */
	public static String stationNameAt(final String dimensionId, final int x, final int y, final int z) {
		// 先按维度精确匹配，找不到再退化为全局搜索（MTR 的维度字段格式在不同版本间可能不同）
		String name = findStation(dimensionId, x, y, z, true);
		if (name.isEmpty()) {
			name = findStation(dimensionId, x, y, z, false);
		}
		return name;
	}

	private static String findStation(final String dimensionId, final int x, final int y, final int z,
			final boolean strictDimension) {
		initIfNeeded();
		if (!available || positionConstructor == null || inAreaMethod == null) {
			return "";
		}
		try {
			final Object position = positionConstructor.newInstance((long) x, (long) y, (long) z);
			for (final Object simulator : simulators()) {
				if (strictDimension && !simulatorInDimension(simulator, dimensionId)) {
					continue;
				}
				final Object stationSet = getField(simulator, "stations");
				if (!(stationSet instanceof Iterable<?> stations)) {
					continue;
				}
				for (final Object station : stations) {
					final Object result = inAreaMethod.invoke(station, position);
					if (result instanceof Boolean inside && inside) {
						final String name = asString(invoke(station, "getName"));
						return name == null ? "" : name;
					}
				}
			}
		} catch (Throwable throwable) {
			Mtr12306.LOGGER.warn("[MTR-12306] 判定闸机所在车站失败：{}", throwable.toString());
		}
		return "";
	}

	/** 返回 MTR 车站列表：[{name, mtrStation, platforms:[...]}] */
	public static List<JsonObject> stations() {
		initIfNeeded();
		final List<JsonObject> result = new ArrayList<>();
		if (!available) {
			return result;
		}
		final Set<String> seen = new LinkedHashSet<>();
		try {
			for (final Object simulator : simulators()) {
				final Object stationSet = getField(simulator, "stations");
				if (!(stationSet instanceof Iterable<?> stations)) {
					continue;
				}
				for (final Object station : stations) {
					final String name = asString(invoke(station, "getName"));
					if (name == null || name.isBlank() || !seen.add(name)) {
						continue;
					}
					final JsonObject json = new JsonObject();
					json.addProperty("name", name);
					json.addProperty("mtrStation", name);
					final Set<String> platforms = new LinkedHashSet<>();
					try {
						final Object savedRails = getField(station, "savedRails");
						if (savedRails instanceof Iterable<?> rails) {
							for (final Object platform : rails) {
								final String platformName = asString(invoke(platform, "getName"));
								if (platformName != null && !platformName.isBlank()) {
									platforms.add(platformName);
								}
							}
						}
					} catch (Exception ignored) {
					}
					final List<String> sorted = new ArrayList<>(platforms);
					sorted.sort((a, b) -> a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT)));
					final com.google.gson.JsonArray array = new com.google.gson.JsonArray();
					sorted.forEach(array::add);
					json.add("platforms", array);
					result.add(json);
				}
			}
		} catch (Throwable throwable) {
			Mtr12306.LOGGER.warn("[MTR-12306] 读取 MTR 车站失败：{}", throwable.toString());
		}
		return result;
	}

	private static String asString(final Object value) {
		return value instanceof String string ? string : null;
	}

	private static Object invoke(final Object target, final String method) throws Exception {
		final Method found = target.getClass().getMethod(method);
		found.setAccessible(true);
		return found.invoke(target);
	}

	private static Object getField(final Object target, final String name) throws Exception {
		final Field field = findField(target.getClass(), name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static Field findField(final Class<?> type, final String name) throws NoSuchFieldException {
		Class<?> current = type;
		while (current != null && current != Object.class) {
			try {
				return current.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}

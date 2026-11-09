package com.mtr12306.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全部购票系统数据，直接序列化为 JSON 保存在存档目录中。
 */
public class TicketingData {
	public Map<String, Station> stations = new LinkedHashMap<>();
	public Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
	public Map<String, TrainService> services = new LinkedHashMap<>();
	public Map<String, Ticket> tickets = new LinkedHashMap<>();
	/** 网页注册账号：key = 昵称小写 */
	public Map<String, Account> accounts = new LinkedHashMap<>();
	public List<GateBinding> gates = new ArrayList<>();
	public long nextTicketSeq = 1L;

	public Station station(final String id) {
		return id == null ? null : stations.get(id);
	}

	public Checkpoint checkpoint(final String id) {
		return id == null ? null : checkpoints.get(id);
	}

	public TrainService service(final String number) {
		return number == null ? null : services.get(number);
	}

	public Account account(final String nickname) {
		return nickname == null ? null : accounts.get(nickname.trim().toLowerCase());
	}

	public String stationName(final String id) {
		final Station station = station(id);
		return station == null ? (id == null ? "" : id) : station.name;
	}

	public String checkpointName(final String id) {
		final Checkpoint checkpoint = checkpoint(id);
		return checkpoint == null ? "" : checkpoint.name;
	}

	public GateBinding gate(final String key) {
		for (final GateBinding gate : gates) {
			if (gate.key().equals(key)) {
				return gate;
			}
		}
		return null;
	}
}

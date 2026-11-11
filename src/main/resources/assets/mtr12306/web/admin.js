const admin = {
	token: sessionStorage.getItem("mtr12306:admin") || "",
	data: null
};

function $(selector) {
	return document.querySelector(selector);
}

function escapeHtml(text) {
	return String(text === undefined || text === null ? "" : text)
		.replace(/&/g, "&amp;")
		.replace(/</g, "&lt;")
		.replace(/>/g, "&gt;")
		.replace(/"/g, "&quot;");
}

function toast(message, isError) {
	const element = $("#toast");
	element.textContent = message;
	element.className = "toast show" + (isError ? " error" : "");
	clearTimeout(element._timer);
	element._timer = setTimeout(() => {
		element.className = "toast";
	}, 2600);
}

async function apiAdmin(path, body, method) {
	const options = { method: method || (body ? "POST" : "GET"), headers: {} };
	if (admin.token) {
		options.headers["X-Admin-Token"] = admin.token;
	}
	if (body) {
		options.headers["Content-Type"] = "application/json";
		options.body = JSON.stringify(body);
	}
	const response = await fetch(path, options);
	let data = null;
	try {
		data = await response.json();
	} catch (error) {
		data = null;
	}
	if (response.status === 401) {
		logout();
		throw new Error("登录已过期，请重新登录");
	}
	if (!response.ok || (data && data.ok === false)) {
		throw new Error((data && (data.error || data.message)) || ("请求失败 (" + response.status + ")"));
	}
	return data;
}

function showAdmin() {
	$("#login-view").style.display = "none";
	$("#admin-view").style.display = "";
	refresh();
}

function logout() {
	admin.token = "";
	sessionStorage.removeItem("mtr12306:admin");
	$("#login-view").style.display = "";
	$("#admin-view").style.display = "none";
}

async function login() {
	try {
		const result = await apiAdmin("/api/admin/login", { password: $("#admin-password").value });
		admin.token = result.token;
		sessionStorage.setItem("mtr12306:admin", admin.token);
		toast("登录成功");
		showAdmin();
	} catch (error) {
		toast(error.message, true);
	}
}

async function refresh() {
	try {
		admin.data = await apiAdmin("/api/admin/overview");
		renderOverview();
		renderStationOptions();
		renderStations();
		renderStationTabs();
		renderCheckpoints();
		renderServices();
		renderGates();
		renderBoarding();
		renderTickets();
		renderAccounts();
		renderCheckpointSelect();
	} catch (error) {
		toast(error.message, true);
	}
}

function renderOverview() {
	const data = admin.data;
	const items = [
		["车站", data.stations.length],
		["检票口", data.checkpoints.length],
		["车次", data.services.length],
		["闸机", data.gates.length],
		["车票", data.tickets.length],
		["账号", (data.accounts || []).length],
		["正在检票", data.boarding.filter((entry) => entry.openNow).length],
		["每公里票价", data.config.farePerKm + " 元"],
		["换乘窗口", data.config.transferWindowMinutes + " 分钟"],
		["票价钱包", data.config.requirePayment ? data.config.fareAccount : "未启用"]
	];
	$("#overview").innerHTML = items
		.map((item) => '<span class="badge blue">' + escapeHtml(item[0]) + " " + escapeHtml(item[1]) + "</span>")
		.join(" ");
}

function renderStationOptions() {
	const select = $("#checkpoint-station");
	if (!select) {
		return;
	}
	const current = select.value;
	select.innerHTML = admin.data.stations
		.map((station) => '<option value="' + escapeHtml(station.id) + '">' + escapeHtml(station.name) + "</option>")
		.join("");
	if (current) {
		select.value = current;
	}
}

function renderStations() {
	const rows = admin.data.stations.map((station) => "<tr>"
		+ "<td>" + escapeHtml(station.id) + "</td>"
		+ "<td>" + escapeHtml(station.name) + "</td>"
		+ '<td class="muted">' + escapeHtml((station.platforms || []).join(", ") || "-") + "</td>"
		+ "<td>" + station.checkpoints.length + "</td>"
		+ '<td><button class="danger small" data-del-station="' + escapeHtml(station.id) + '">删除</button></td>'
		+ "</tr>").join("");
	$("#station-table").innerHTML = admin.data.stations.length
		? "<table><thead><tr><th>ID</th><th>车站</th><th>站台</th><th>检票口</th><th>操作</th></tr></thead><tbody>" + rows + "</tbody></table>"
		: '<div class="empty">还没有车站（请与 MTR 车站同名，闸机才能绑定它的检票口）</div>';
	$("#station-table").querySelectorAll("[data-del-station]").forEach((button) => {
		button.addEventListener("click", async () => {
			if (!confirm("删除车站会同时删除其检票口与经过该站的车次，确定？")) {
				return;
			}
			try {
				await apiAdmin("/api/admin/station/delete", { id: button.dataset.delStation });
				toast("车站已删除");
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
}

let activeStationTab = "";

function renderStationTabs() {
	const tabs = $("#station-tabs");
	const panel = $("#station-panel");
	if (!tabs || !panel) {
		return;
	}
	const stations = admin.data.stations;
	if (!stations.length) {
		tabs.innerHTML = "";
		panel.innerHTML = '<div class="empty">还没有车站</div>';
		return;
	}
	if (!activeStationTab || !stations.some((station) => station.id === activeStationTab)) {
		activeStationTab = stations[0].id;
	}
	tabs.innerHTML = stations.map((station) =>
		'<button class="chip' + (station.id === activeStationTab ? " active" : "") + '" data-station-tab="'
		+ escapeHtml(station.id) + '">' + escapeHtml(station.name) + "</button>").join(" ");
	tabs.querySelectorAll("[data-station-tab]").forEach((button) => {
		button.addEventListener("click", () => {
			activeStationTab = button.dataset.stationTab;
			renderStationTabs();
		});
	});
	const station = stations.find((entry) => entry.id === activeStationTab);
	const rows = station.checkpoints.map((checkpoint) => "<tr>"
		+ "<td>" + escapeHtml(checkpoint.id) + "</td>"
		+ "<td>" + escapeHtml(checkpoint.name) + "</td>"
		+ '<td class="muted">' + escapeHtml((checkpoint.platforms || []).join(", ") || "全部站台") + "</td>"
		+ "<td>" + checkpoint.gates + "</td>"
		+ "<td>" + (checkpoint.enabled ? '<span class="badge green">启用</span>' : '<span class="badge gray">停用</span>') + "</td>"
		+ '<td><button class="danger small" data-tab-del-cp="' + escapeHtml(checkpoint.id) + '">删除</button></td>'
		+ "</tr>").join("");
	panel.innerHTML = '<div class="card" style="background:#fafcff;box-shadow:none;border:1px solid #e3e6ea">'
		+ "<h2>" + escapeHtml(station.name) + "（" + escapeHtml(station.id) + "）</h2>"
		+ '<p class="muted">站台：' + escapeHtml((station.platforms || []).join(", ") || "-")
		+ " · 检票口 " + station.checkpoints.length + " 个</p>"
		+ '<div class="stop-row" style="grid-template-columns:1fr 1.4fr auto;margin:10px 0">'
		+ '<input type="text" id="tab-cp-name" placeholder="检票口名称，如 A1">'
		+ '<input type="text" id="tab-cp-platforms" placeholder="负责站台（逗号分隔，留空=全部）">'
		+ '<button class="primary" id="tab-cp-add">为本站新增检票口</button></div>'
		+ (station.checkpoints.length
			? "<table><thead><tr><th>ID</th><th>名称</th><th>站台</th><th>闸机</th><th>状态</th><th>操作</th></tr></thead><tbody>" + rows + "</tbody></table>"
			: '<div class="empty">本站还没有检票口</div>')
		+ "</div>";
	$("#tab-cp-add").addEventListener("click", async () => {
		try {
			await apiAdmin("/api/admin/checkpoint", {
				stationId: activeStationTab,
				name: $("#tab-cp-name").value.trim(),
				platforms: ($("#tab-cp-platforms").value || "").split(/[,，]/).map((item) => item.trim()).filter(Boolean)
			});
			toast("检票口已创建");
			refresh();
		} catch (error) {
			toast(error.message, true);
		}
	});
	panel.querySelectorAll("[data-tab-del-cp]").forEach((button) => {
		button.addEventListener("click", async () => {
			if (!confirm("确定删除该检票口？")) {
				return;
			}
			try {
				await apiAdmin("/api/admin/checkpoint/delete", { id: button.dataset.tabDelCp });
				toast("检票口已删除");
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
}

function renderCheckpoints() {
	const rows = admin.data.checkpoints.map((checkpoint) => {
		const boarding = checkpoint.boarding.length
			? checkpoint.boarding.map((entry) => entry.service + "（" + entry.depart + "）").join("、")
			: "-";
		return "<tr>"
			+ "<td>" + escapeHtml(checkpoint.id) + "</td>"
			+ "<td>" + escapeHtml(checkpoint.station) + "</td>"
			+ "<td>" + escapeHtml(checkpoint.name) + "</td>"
			+ '<td class="muted">' + escapeHtml((checkpoint.platforms || []).join(", ") || "全部") + "</td>"
			+ "<td>" + checkpoint.gates + "</td>"
			+ "<td>" + (checkpoint.enabled ? '<span class="badge green">启用</span>' : '<span class="badge gray">停用</span>') + "</td>"
			+ "<td>" + escapeHtml(boarding) + "</td>"
			+ '<td><button class="ghost small" data-toggle-cp="' + escapeHtml(checkpoint.id) + '">'
			+ (checkpoint.enabled ? "停用" : "启用") + "</button> "
			+ '<button class="danger small" data-del-cp="' + escapeHtml(checkpoint.id) + '">删除</button></td>'
			+ "</tr>";
	}).join("");
	$("#checkpoint-table").innerHTML = admin.data.checkpoints.length
		? "<table><thead><tr><th>ID</th><th>车站</th><th>检票口</th><th>负责站台</th><th>闸机</th><th>状态</th><th>正在检票</th><th>操作</th></tr></thead><tbody>" + rows + "</tbody></table>"
		: '<div class="empty">还没有检票口，先创建车站再添加检票口</div>';
	$("#checkpoint-table").querySelectorAll("[data-del-cp]").forEach((button) => {
		button.addEventListener("click", async () => {
			if (!confirm("确定删除该检票口？")) {
				return;
			}
			try {
				await apiAdmin("/api/admin/checkpoint/delete", { id: button.dataset.delCp });
				toast("检票口已删除");
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
	$("#checkpoint-table").querySelectorAll("[data-toggle-cp]").forEach((button) => {
		button.addEventListener("click", async () => {
			const checkpoint = admin.data.checkpoints.find((entry) => entry.id === button.dataset.toggleCp);
			if (!checkpoint) {
				return;
			}
			try {
				await apiAdmin("/api/admin/checkpoint/update", {
					id: checkpoint.id,
					name: checkpoint.name,
					platforms: checkpoint.platforms,
					enabled: !checkpoint.enabled
				});
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
}

function renderServices() {
	const rows = admin.data.services.map((service) => {
		const stops = service.stops
			.map((stop) => escapeHtml(stop.station) + (stop.depart ? " " + escapeHtml(stop.depart) : "")).join(" → ");
		const classes = service.seatClasses
			.map((seatClass) => escapeHtml(seatClass.name)
				+ (seatClass.reserved ? "" : "(自由席)")
				+ (seatClass.diningCars && seatClass.diningCars.length ? "(餐车" + seatClass.diningCars.join("/") + ")" : ""))
			.join("、");
		return "<tr>"
			+ "<td><b>" + escapeHtml(service.number) + "</b></td>"
			+ '<td class="muted">' + escapeHtml(service.date === "*" ? "每天" : service.date) + "</td>"
			+ '<td class="muted">' + stops + "</td>"
			+ '<td class="muted">' + classes + "</td>"
			+ "<td>" + service.capacity + "</td>"
			+ '<td><button class="ghost small" data-edit-service="' + escapeHtml(service.number) + '">修改</button> '
			+ '<button class="danger small" data-del-service="' + escapeHtml(service.number) + '">删除</button></td>'
			+ "</tr>";
	}).join("");
	$("#service-table").innerHTML = admin.data.services.length
		? "<table><thead><tr><th>车次</th><th>开行</th><th>停靠站</th><th>坐席/车厢</th><th>定员</th><th>操作</th></tr></thead><tbody>" + rows + "</tbody></table>"
		: '<div class="empty">还没有车次</div>';
	$("#service-table").querySelectorAll("[data-del-service]").forEach((button) => {
		button.addEventListener("click", async () => {
			if (!confirm("确定删除车次 " + button.dataset.delService + "？")) {
				return;
			}
			try {
				await apiAdmin("/api/admin/service/delete", { number: button.dataset.delService });
				toast("车次已删除");
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
	$("#service-table").querySelectorAll("[data-edit-service]").forEach((button) => {
		button.addEventListener("click", () => editService(button.dataset.editService));
	});
}

function editService(number) {
	const service = admin.data.services.find((entry) => entry.number === number);
	if (!service) {
		return;
	}
	$("#service-number").value = service.number;
	$("#service-name").value = service.name || "";
	$("#service-date").value = service.date === "*" ? "" : service.date;
	$("#stop-rows").innerHTML = "";
	$("#seat-rows").innerHTML = "";
	service.stops.forEach((stop) => addStopRow("#stop-rows", stop.stationId, stop.platform, stop.arrive, stop.depart,
		stop.dayOffset, stop.distance));
	(service.seatClasses || []).forEach((seatClass) => addSeatRow("#seat-rows", seatClass.code, seatClass.name,
		seatClass.layout, seatClass.rows, seatClass.cars, seatClass.diningCars, seatClass.priceMultiplier,
		seatClass.reserved));
	$("#service-editing").textContent = "正在修改车次 " + number + "（保存后覆盖配置）";
	$("#service-number").focus();
}

function renderGates() {
	const checkpoints = admin.data.checkpoints;
	const rows = admin.data.gates.map((gate) => {
		const options = ['<option value="">未绑定</option>'].concat(checkpoints.map((checkpoint) =>
			'<option value="' + escapeHtml(checkpoint.id) + '"' + (checkpoint.id === gate.checkpointId ? " selected" : "") + ">"
			+ escapeHtml(checkpoint.station + " " + checkpoint.name) + "</option>")).join("");
		const key = gate.dimension + "@" + gate.x + "," + gate.y + "," + gate.z;
		return "<tr>"
			+ '<td class="muted">' + escapeHtml(gate.dimension) + "</td>"
			+ "<td>" + escapeHtml(gate.pos) + "</td>"
			+ '<td><select data-gate-cp="' + escapeHtml(key) + '">' + options + "</select></td>"
			+ "<td>" + escapeHtml(gate.mode) + "</td>"
			+ '<td><button class="danger small" data-del-gate="' + escapeHtml(key) + '">移除</button></td>'
			+ "</tr>";
	}).join("");
	$("#gate-table").innerHTML = admin.data.gates.length
		? "<table><thead><tr><th>维度</th><th>坐标</th><th>绑定检票口</th><th>模式</th><th>操作</th></tr></thead><tbody>" + rows + "</tbody></table>"
		: '<div class="empty">还没有放置闸机</div>';
	$("#gate-table").querySelectorAll("[data-gate-cp]").forEach((select) => {
		select.addEventListener("change", async () => {
			const [dimension, coords] = select.dataset.gateCp.split("@");
			const [x, y, z] = coords.split(",").map(Number);
			try {
				await apiAdmin("/api/admin/gate", { dimension, x, y, z, checkpointId: select.value, mode: "ENTRY" });
				toast("闸机绑定已更新");
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
	$("#gate-table").querySelectorAll("[data-del-gate]").forEach((button) => {
		button.addEventListener("click", async () => {
			try {
				await apiAdmin("/api/admin/gate/delete", { key: button.dataset.delGate });
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
}

function renderBoarding() {
	const rows = admin.data.boarding.map((entry) => {
		const key = entry.service + "|" + entry.stationId;
		const badge = entry.openNow ? '<span class="badge green">检票中</span>'
			: (entry.manualClosed ? '<span class="badge red">强制停检</span>' : '<span class="badge gray">未开始</span>');
		return "<tr>"
			+ '<td><b>' + escapeHtml(entry.service) + "</b></td>"
			+ "<td>" + escapeHtml(entry.station) + "</td>"
			+ '<td class="muted">' + escapeHtml(entry.platform) + "</td>"
			+ "<td>" + escapeHtml(entry.depart) + "</td>"
			+ '<td class="muted">' + escapeHtml(entry.open) + " — " + escapeHtml(entry.close) + "</td>"
			+ "<td>" + escapeHtml(entry.checkpoint) + "</td>"
			+ "<td>" + badge + "</td>"
			+ '<td><button class="primary small" data-open-boarding="' + escapeHtml(key) + '">手动开检15分钟</button> '
			+ '<button class="danger small" data-close-boarding="' + escapeHtml(key) + '">强制停检</button></td>'
			+ "</tr>";
	}).join("");
	$("#boarding-table").innerHTML = admin.data.boarding.length
		? "<table><thead><tr><th>车次</th><th>车站</th><th>站台</th><th>发车</th><th>检票时间</th><th>检票口</th><th>状态</th><th>手动控制</th></tr></thead><tbody>" + rows + "</tbody></table>"
		: '<div class="empty">今日没有发车计划，请先添加车次</div>';
	$("#boarding-table").querySelectorAll("[data-open-boarding]").forEach((button) => {
		button.addEventListener("click", async () => {
			const [service, stationId] = button.dataset.openBoarding.split("|");
			try {
				await apiAdmin("/api/admin/boarding/open", { service, stationId, minutes: 15 });
				toast("已手动开始检票 15 分钟");
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
	$("#boarding-table").querySelectorAll("[data-close-boarding]").forEach((button) => {
		button.addEventListener("click", async () => {
			const [service, stationId] = button.dataset.closeBoarding.split("|");
			try {
				const result = await apiAdmin("/api/admin/boarding/close", { service, stationId });
				toast(result.message);
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
}

function renderTickets() {
	const rows = admin.data.tickets.slice(-80).reverse().map((ticket) => "<tr>"
		+ "<td>" + escapeHtml(ticket.id) + "</td>"
		+ "<td>" + escapeHtml(ticket.player) + "</td>"
		+ "<td>" + escapeHtml(ticket.service) + "</td>"
		+ '<td class="muted">' + escapeHtml(ticket.date) + "</td>"
		+ '<td class="muted">' + escapeHtml(ticket.segment) + "</td>"
		+ "<td>" + escapeHtml(ticket.seatClassLabel + " " + ticket.seat) + "</td>"
		+ "<td>" + ticket.price + " 元</td>"
		+ "<td>" + escapeHtml(ticket.state) + (ticket.exited ? ' <span class="badge gray">已出闸</span>' : "") + "</td>"
		+ '<td><button class="danger small" data-cancel-ticket="' + escapeHtml(ticket.id) + '">作废退款</button></td>'
		+ "</tr>").join("");
	$("#ticket-table").innerHTML = admin.data.tickets.length
		? "<table><thead><tr><th>票号</th><th>乘客</th><th>车次</th><th>日期</th><th>区间</th><th>坐席/座位</th><th>票价</th><th>状态</th><th>操作</th></tr></thead><tbody>" + rows + "</tbody></table>"
		: '<div class="empty">还没有售出车票</div>';
	$("#ticket-table").querySelectorAll("[data-cancel-ticket]").forEach((button) => {
		button.addEventListener("click", async () => {
			if (!confirm("确定作废该车票并退款？")) {
				return;
			}
			try {
				await apiAdmin("/api/admin/ticket/cancel", { ticket: button.dataset.cancelTicket });
				toast("车票已作废并退款");
				refresh();
			} catch (error) {
				toast(error.message, true);
			}
		});
	});
}

function renderAccounts() {
	const accounts = admin.data.accounts || [];
	const rows = accounts.map((account) => "<tr>"
		+ "<td>" + escapeHtml(account.nickname) + "</td>"
		+ "<td>" + escapeHtml(account.mcName) + "</td>"
		+ '<td class="muted">' + new Date(account.createdAt).toLocaleString() + "</td>"
		+ "</tr>").join("");
	$("#account-table").innerHTML = accounts.length
		? "<table><thead><tr><th>昵称</th><th>Minecraft 名称</th><th>注册时间</th></tr></thead><tbody>" + rows + "</tbody></table>"
		: '<div class="empty">还没有网页注册账号</div>';
}

function renderCheckpointSelect() {
	const select = $("#check-checkpoint");
	if (!select) {
		return;
	}
	const current = select.value;
	select.innerHTML = admin.data.checkpoints
		.map((checkpoint) => '<option value="' + escapeHtml(checkpoint.id) + '">'
			+ escapeHtml(checkpoint.station + " · " + checkpoint.name) + "</option>")
		.join("");
	if (current) {
		select.value = current;
	}
}

/* ------------------------------------------------------------------ 表单行 */

function stationOptionsHtml() {
	return admin.data.stations
		.map((station) => '<option value="' + escapeHtml(station.id) + '">' + escapeHtml(station.name) + "</option>")
		.join("");
}

function addStopRow(container, stationId, platform, arrive, depart, dayOffset, distance) {
	const row = document.createElement("div");
	row.className = "stop-row wide";
	row.innerHTML = '<select class="stop-station"></select>'
		+ '<input type="text" class="stop-platform" placeholder="1" value="' + escapeHtml(platform || "") + '">'
		+ '<input type="text" class="stop-arrive" placeholder="08:00" value="' + escapeHtml(arrive || "") + '">'
		+ '<input type="text" class="stop-depart" placeholder="08:02" value="' + escapeHtml(depart || "") + '">'
		+ '<input type="number" class="stop-day" value="' + (dayOffset || 0) + '">'
		+ '<input type="number" step="0.1" class="stop-km" value="' + (distance || 0) + '">'
		+ '<button class="danger small">删除</button>';
	$(container).appendChild(row);
	row.querySelector(".stop-station").innerHTML = stationOptionsHtml();
	if (stationId) {
		row.querySelector(".stop-station").value = stationId;
	}
	row.querySelector("button").addEventListener("click", () => row.remove());
}

function addBatchStopRow(container, stationId, platform, runMinutes, dwellMinutes, km) {
	const row = document.createElement("div");
	row.className = "batch-stop-row";
	row.innerHTML = '<select class="batch-station"></select>'
		+ '<input type="text" class="batch-platform" placeholder="1" value="' + escapeHtml(platform || "") + '">'
		+ '<input type="number" class="batch-run" placeholder="0" value="' + (runMinutes || 0) + '">'
		+ '<input type="number" class="batch-dwell" placeholder="2" value="' + (dwellMinutes || 0) + '">'
		+ '<input type="number" step="0.1" class="batch-km" placeholder="0.0" value="' + (km || 0) + '">'
		+ '<button class="danger small">删除</button>';
	$(container).appendChild(row);
	row.querySelector(".batch-station").innerHTML = stationOptionsHtml();
	if (stationId) {
		row.querySelector(".batch-station").value = stationId;
	}
	row.querySelector("button").addEventListener("click", () => row.remove());
}

function addSeatRow(container, code, name, layout, rows, cars, diningCars, multiplier, reserved) {
	const row = document.createElement("div");
	row.className = "seat-config-row";
	const reservedValue = reserved === undefined ? true : reserved;
	row.innerHTML = '<input type="text" class="seat-code" placeholder="SECOND" value="' + escapeHtml(code || "") + '">'
		+ '<input type="text" class="seat-name" placeholder="二等座" value="' + escapeHtml(name || "") + '">'
		+ '<input type="text" class="seat-layout" placeholder="A B C|D F" value="' + escapeHtml(layout || "") + '">'
		+ '<input type="number" class="seat-rows" placeholder="18" value="' + (rows || 0) + '">'
		+ '<input type="text" class="seat-cars" placeholder="1,2,3,4" value="'
		+ escapeHtml((cars || []).join(",")) + '">'
		+ '<input type="text" class="seat-dining" placeholder="餐车号，如 4" value="'
		+ escapeHtml((diningCars || []).join(",")) + '">'
		+ '<input type="number" step="0.1" class="seat-mult" value="' + (multiplier || 1) + '">'
		+ '<input type="checkbox" class="seat-reserved"' + (reservedValue ? " checked" : "") + ">"
		+ '<button class="danger small">删除</button>';
	$(container).appendChild(row);
	row.querySelector("button").addEventListener("click", () => row.remove());
}

function defaultSeatRows(container, withDining) {
	$(container).innerHTML = "";
	addSeatRow(container, "SECOND", "二等座", "A B C|D F", 18, [1, 2, 3, 5, 6], withDining ? [4] : [], 1.0, true);
	addSeatRow(container, "FIRST", "一等座", "A C|D F", 13, [7], [], 1.6, true);
	addSeatRow(container, "BUSINESS", "商务座", "A C|F", 8, [8], [], 3.0, true);
	addSeatRow(container, "INTERCITY", "城际", "A B C|D F", 20, [9, 10], [], 0.9, false);
	addSeatRow(container, "STANDING", "无座", "S", 0, [0], [], 0.8, false);
}

function readSeatClasses(container) {
	const seatClasses = [];
	document.querySelectorAll(container + " .seat-config-row").forEach((row) => {
		const parseCars = (text) => (text || "").split(/[,，]/).map((item) => Number(item.trim()))
			.filter((item) => !isNaN(item) && item !== 0);
		seatClasses.push({
			code: row.querySelector(".seat-code").value.trim().toUpperCase(),
			name: row.querySelector(".seat-name").value.trim(),
			layout: row.querySelector(".seat-layout").value.trim(),
			rows: Number(row.querySelector(".seat-rows").value || 0),
			cars: parseCars(row.querySelector(".seat-cars").value),
			diningCars: parseCars(row.querySelector(".seat-dining").value),
			priceMultiplier: Number(row.querySelector(".seat-mult").value || 1),
			reserved: row.querySelector(".seat-reserved").checked
		});
	});
	return seatClasses;
}

async function saveService() {
	const stops = [];
	document.querySelectorAll("#stop-rows .stop-row").forEach((row) => {
		stops.push({
			stationId: row.querySelector(".stop-station").value,
			platform: row.querySelector(".stop-platform").value.trim(),
			arrive: row.querySelector(".stop-arrive").value.trim(),
			depart: row.querySelector(".stop-depart").value.trim(),
			dayOffset: Number(row.querySelector(".stop-day").value || 0),
			distance: Number(row.querySelector(".stop-km").value || 0)
		});
	});
	try {
		await apiAdmin("/api/admin/service", {
			number: $("#service-number").value.trim(),
			name: $("#service-name").value.trim(),
			date: $("#service-date").value || "*",
			stops,
			seatClasses: readSeatClasses("#seat-rows")
		});
		toast("车次已保存");
		resetServiceForm();
		refresh();
	} catch (error) {
		toast(error.message, true);
	}
}

function resetServiceForm() {
	$("#service-number").value = "";
	$("#service-name").value = "";
	$("#service-date").value = "";
	$("#stop-rows").innerHTML = "";
	$("#service-editing").textContent = "";
	addStopRow("#stop-rows");
	addStopRow("#stop-rows");
	defaultSeatRows("#seat-rows", true);
}

async function createBatch() {
	const stops = [];
	document.querySelectorAll("#batch-stop-rows .batch-stop-row").forEach((row) => {
		stops.push({
			stationId: row.querySelector(".batch-station").value,
			platform: row.querySelector(".batch-platform").value.trim(),
			runMinutes: Number(row.querySelector(".batch-run").value || 0),
			dwellMinutes: Number(row.querySelector(".batch-dwell").value || 0),
			km: Number(row.querySelector(".batch-km").value || 0)
		});
	});
	try {
		const result = await apiAdmin("/api/admin/service/batch", {
			prefix: $("#batch-prefix").value.trim(),
			from: Number($("#batch-from").value || 0),
			to: Number($("#batch-to").value || 0),
			step: Number($("#batch-step").value || 1),
			digits: Number($("#batch-digits").value || 4),
			startTime: $("#batch-start").value || "06:00",
			headway: Number($("#batch-headway").value || 0),
			date: $("#batch-date").value || "*",
			name: $("#batch-name").value.trim(),
			stops,
			seatClasses: readSeatClasses("#batch-seat-rows")
		});
		$("#batch-result").textContent = result.message + "：" + (result.created || []).join("、");
		toast(result.message);
		refresh();
	} catch (error) {
		toast(error.message, true);
	}
}

/* ------------------------------------------------------------------ 检票校验 / 余额 */

async function checkTicket(consume) {
	const player = ($("#check-player").value || "").trim();
	const checkpointId = $("#check-checkpoint").value;
	if (!player || !checkpointId) {
		toast("请填写玩家 ID 并选择检票口", true);
		return;
	}
	try {
		const result = await apiAdmin(consume ? "/api/admin/check-in" : "/api/admin/check", { player, checkpointId });
		const color = result.allow ? "#1f9254" : "#ce3333";
		$("#check-result").innerHTML = '<div style="border-left:4px solid ' + color + ';padding:8px 12px;background:#f7f9fc">'
			+ '<b style="color:' + color + '">' + (result.allow ? "✔ 允许通行" : "✘ 拒绝通行")
			+ (consume ? "（已核销）" : "（只读模拟）") + "</b><br>"
			+ (result.allow
				? escapeHtml(result.service + " 次 · " + result.seatClass + " " + result.seat + " · " + result.price + " 元 · " + result.segment)
				: escapeHtml(result.reason))
			+ "</div>";
		if (consume) {
			refresh();
		}
	} catch (error) {
		toast(error.message, true);
	}
}

async function adjustBalance() {
	const player = ($("#balance-player").value || "").trim();
	const amount = Number($("#balance-amount").value || 0);
	if (!player) {
		toast("请填写玩家 ID", true);
		return;
	}
	try {
		const result = await apiAdmin("/api/admin/balance", { player, amount });
		$("#balance-result").innerHTML = '<span class="badge blue">' + escapeHtml(player) + " 当前余额 "
			+ result.balance + " 元</span>";
		toast(result.message);
	} catch (error) {
		toast(error.message, true);
	}
}

/* ------------------------------------------------------------------ MTR 导入 */

async function loadMtrStations() {
	try {
		const result = await apiAdmin("/api/admin/mtr/stations");
		$("#mtr-hint").textContent = result.available ? "" : "未检测到 MTR 模组";
		if (!result.stations.length) {
			$("#mtr-list").innerHTML = '<div class="empty">没有读取到 MTR 车站</div>';
			return;
		}
		$("#mtr-list").innerHTML = result.stations.map((station, index) => '<div class="inline" style="padding:6px 0;border-bottom:1px dashed #e3e6ea">'
			+ "<b>" + escapeHtml(station.name) + "</b>"
			+ '<span class="muted">站台 ' + escapeHtml((station.platforms || []).join(", ") || "-") + "</span>"
			+ '<button class="ghost small" data-import="' + index + '">导入</button>'
			+ "</div>").join("");
		$("#mtr-list").querySelectorAll("[data-import]").forEach((button) => {
			button.addEventListener("click", async () => {
				const station = result.stations[Number(button.dataset.import)];
				try {
					await apiAdmin("/api/admin/station", { name: station.name, mtrStation: station.mtrStation, platforms: station.platforms });
					toast("已导入车站 " + station.name);
					refresh();
				} catch (error) {
					toast(error.message, true);
				}
			});
		});
	} catch (error) {
		toast(error.message, true);
	}
}

document.addEventListener("DOMContentLoaded", () => {
	$("#btn-login").addEventListener("click", login);
	$("#admin-password").addEventListener("keydown", (event) => {
		if (event.key === "Enter") {
			login();
		}
	});
	$("#btn-logout").addEventListener("click", logout);
	$("#btn-mtr").addEventListener("click", loadMtrStations);
	$("#btn-mtr-all").addEventListener("click", async () => {
		try {
			const result = await apiAdmin("/api/admin/mtr/import-all", {});
			toast(result.message);
			refresh();
		} catch (error) {
			toast(error.message, true);
		}
	});
	$("#btn-add-station").addEventListener("click", async () => {
		try {
			await apiAdmin("/api/admin/station", {
				name: $("#station-name").value.trim(),
				platforms: ($("#station-platforms").value || "").split(/[,，]/).map((item) => item.trim()).filter(Boolean)
			});
			toast("车站已创建");
			$("#station-name").value = "";
			$("#station-platforms").value = "";
			refresh();
		} catch (error) {
			toast(error.message, true);
		}
	});
	$("#btn-add-checkpoint").addEventListener("click", async () => {
		try {
			await apiAdmin("/api/admin/checkpoint", {
				stationId: $("#checkpoint-station").value,
				name: $("#checkpoint-name").value.trim(),
				platforms: ($("#checkpoint-platforms").value || "").split(/[,，]/).map((item) => item.trim()).filter(Boolean)
			});
			toast("检票口已创建");
			$("#checkpoint-name").value = "";
			$("#checkpoint-platforms").value = "";
			refresh();
		} catch (error) {
			toast(error.message, true);
		}
	});
	$("#btn-add-stop").addEventListener("click", () => addStopRow("#stop-rows"));
	$("#btn-add-seat").addEventListener("click", () => addSeatRow("#seat-rows", "", "", "A B C|D F", 18, [], [], 1, true));
	$("#btn-add-intercity").addEventListener("click", () => addSeatRow("#seat-rows", "INTERCITY", "城际", "A B C|D F", 20, [9, 10], [], 0.9, false));
	$("#btn-default-seat").addEventListener("click", () => defaultSeatRows("#seat-rows", true));
	$("#btn-save-service").addEventListener("click", saveService);
	$("#btn-batch-add-stop").addEventListener("click", () => addBatchStopRow("#batch-stop-rows"));
	$("#btn-batch-add-seat").addEventListener("click", () => addSeatRow("#batch-seat-rows", "", "", "A B C|D F", 18, [], [], 1, true));
	$("#btn-batch-default-seat").addEventListener("click", () => defaultSeatRows("#batch-seat-rows", true));
	$("#btn-batch-create").addEventListener("click", createBatch);
	$("#btn-check").addEventListener("click", () => checkTicket(false));
	$("#btn-check-in").addEventListener("click", () => checkTicket(true));
	$("#btn-balance").addEventListener("click", adjustBalance);

	resetServiceForm();
	addBatchStopRow("#batch-stop-rows");
	addBatchStopRow("#batch-stop-rows");
	defaultSeatRows("#batch-seat-rows", true);
	if (admin.token) {
		showAdmin();
	} else {
		logout();
	}
});

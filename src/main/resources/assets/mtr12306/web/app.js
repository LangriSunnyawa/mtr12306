const state = {
	stations: [],
	date: "",
	results: [],
	status: null,
	token: localStorage.getItem("mtr12306:token") || "",
	nickname: localStorage.getItem("mtr12306:nickname") || "",
	player: localStorage.getItem("mtr12306:player") || "",
	balance: null,
	buying: null,
	seatMap: null
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

async function api(path, body, method) {
	const options = { method: method || (body ? "POST" : "GET"), headers: {} };
	if (state.token) {
		options.headers["X-Player-Token"] = state.token;
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
	if (!response.ok || (data && data.ok === false)) {
		const message = (data && (data.error || data.message)) || ("请求失败 (" + response.status + ")");
		const error = new Error(message);
		error.needLogin = message.indexOf("验证码") >= 0 || message.indexOf("登录") >= 0;
		throw error;
	}
	return data;
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

function showModal(html) {
	$("#modal-card").innerHTML = html;
	$("#modal").classList.remove("hidden");
}

function hideModal() {
	$("#modal").classList.add("hidden");
}

function stateText(stateName) {
	switch (stateName) {
		case "VALID": return '<span class="badge green">待使用</span>';
		case "CHECKED": return '<span class="badge blue">已检票</span>';
		case "REFUNDED": return '<span class="badge gray">已退票</span>';
		case "EXPIRED": return '<span class="badge gray">已过期</span>';
		default: return '<span class="badge gray">' + escapeHtml(stateName) + "</span>";
	}
}

/* ------------------------------------------------------------------ 登录 / 注册 */

function renderSession() {
	const bar = $("#session-bar");
	if (state.token && state.player) {
		const balance = state.balance === null ? "…" : state.balance + " 元";
		const who = state.nickname
			? ("<b>" + escapeHtml(state.nickname) + "</b>（" + escapeHtml(state.player) + "）")
			: ("<b>" + escapeHtml(state.player) + "</b>");
		bar.innerHTML = '已登录：' + who + " · 余额 <b>" + escapeHtml(balance)
			+ '</b> <button class="ghost small" id="btn-logout">退出</button>';
		const logout = $("#btn-logout");
		if (logout) {
			logout.addEventListener("click", () => {
				state.token = "";
				state.player = "";
				state.nickname = "";
				state.balance = null;
				localStorage.removeItem("mtr12306:token");
				localStorage.removeItem("mtr12306:nickname");
				renderSession();
			});
		}
		$("#btn-login-top").textContent = "切换账号";
	} else {
		bar.innerHTML = '未登录 · 请先 <b>注册/登录</b>（也可以游戏内 <b>/ticket login</b> 取验证码登录）';
		$("#btn-login-top").textContent = "登录";
	}
}

async function refreshBalance() {
	if (!state.token) {
		state.balance = null;
		renderSession();
		return;
	}
	try {
		const data = await api("/api/balance");
		state.balance = data.balance;
	} catch (error) {
		state.balance = null;
	}
	renderSession();
}

function storeSession(data) {
	state.token = data.token;
	state.player = data.player;
	state.nickname = data.nickname || "";
	state.balance = data.balance;
	localStorage.setItem("mtr12306:token", state.token);
	localStorage.setItem("mtr12306:nickname", state.nickname);
	localStorage.setItem("mtr12306:player", state.player);
	renderSession();
}

function openLogin(tab) {
	const activeTab = tab || "login";
	showModal(`
		<h3>乘客登录</h3>
		<div class="inline" style="margin-bottom:12px">
			<button class="chip ${activeTab === "login" ? "active" : ""}" id="tab-login">登录</button>
			<button class="chip ${activeTab === "register" ? "active" : ""}" id="tab-register">注册新账号</button>
		</div>
		<div id="auth-body"></div>
	`);
	$("#tab-login").addEventListener("click", () => openLogin("login"));
	$("#tab-register").addEventListener("click", () => openLogin("register"));
	renderAuthBody(activeTab);
}

function renderAuthBody(tab) {
	const body = $("#auth-body");
	if (tab === "register") {
		body.innerHTML = `
			<label class="field" style="margin-bottom:10px">昵称（登录用）
				<input type="text" id="reg-nickname" maxlength="16" placeholder="2-16 个字符">
			</label>
			<label class="field" style="margin-bottom:10px">Minecraft 名称（车票归属）
				<input type="text" id="reg-mcname" maxlength="16" placeholder="例如 Steve">
			</label>
			<label class="field" style="margin-bottom:10px">密码（至少 6 位）
				<input type="password" id="reg-password" maxlength="32">
			</label>
			<label class="field">确认密码
				<input type="password" id="reg-password2" maxlength="32">
			</label>
			<div class="inline" style="margin-top:14px">
				<button class="primary" id="reg-submit">注册并登录</button>
				<button class="ghost" id="auth-cancel">取消</button>
			</div>`;
		$("#reg-submit").addEventListener("click", submitRegister);
	} else {
		body.innerHTML = `
			<label class="field" style="margin-bottom:10px">昵称
				<input type="text" id="login-nickname" maxlength="16" value="${escapeHtml(localStorage.getItem("mtr12306:nickname") || "")}">
			</label>
			<label class="field">密码
				<input type="password" id="login-password" maxlength="32">
			</label>
			<div class="inline" style="margin-top:14px">
				<button class="primary" id="login-submit">登录</button>
				<button class="ghost" id="auth-cancel">取消</button>
			</div>
			<p class="muted" style="margin-top:12px">还没有账号？点上面的「注册新账号」。</p>`;
		$("#login-submit").addEventListener("click", submitLogin);
	}
	$("#auth-cancel").addEventListener("click", hideModal);
	const firstInput = body.querySelector("input");
	if (firstInput) {
		firstInput.focus();
	}
}

async function submitLogin() {
	const nickname = ($("#login-nickname").value || "").trim();
	const password = $("#login-password").value || "";
	if (!nickname || !password) {
		toast("请填写昵称与密码", true);
		return;
	}
	try {
		const data = await api("/api/login", { nickname, password });
		storeSession(data);
		hideModal();
		toast("登录成功，余额 " + data.balance + " 元");
		loadMine();
	} catch (error) {
		toast(error.message, true);
	}
}

async function submitRegister() {
	const nickname = ($("#reg-nickname").value || "").trim();
	const mcName = ($("#reg-mcname").value || "").trim();
	const password = $("#reg-password").value || "";
	const password2 = $("#reg-password2").value || "";
	if (!nickname || !mcName || !password) {
		toast("请填写昵称、Minecraft 名称与密码", true);
		return;
	}
	if (password !== password2) {
		toast("两次输入的密码不一致", true);
		return;
	}
	if (password.length < 6) {
		toast("密码至少 6 位", true);
		return;
	}
	try {
		const data = await api("/api/register", { nickname, mcName, password });
		storeSession(data);
		hideModal();
		toast("注册成功，已自动登录");
		loadMine();
	} catch (error) {
		toast(error.message, true);
	}
}

function requireLogin() {
	if (state.token && state.player) {
		return true;
	}
	openLogin();
	toast("请先登录或注册账号", true);
	return false;
}

/* ------------------------------------------------------------------ 查询 */

async function loadStations() {
	state.stations = await api("/api/stations");
	const from = $("#from");
	const to = $("#to");
	from.innerHTML = "";
	to.innerHTML = "";
	if (!state.stations.length) {
		from.innerHTML = '<option value="">（暂无车站，请先到管理后台创建）</option>';
		to.innerHTML = '<option value="">（暂无车站，请先到管理后台创建）</option>';
		return;
	}
	state.stations.forEach((station) => {
		const option = document.createElement("option");
		option.value = station.id;
		option.textContent = station.name;
		from.appendChild(option);
		to.appendChild(option.cloneNode(true));
	});
	if (state.stations.length > 1) {
		to.selectedIndex = 1;
	}
}

async function loadStatus() {
	try {
		const status = await api("/api/status");
		state.status = status;
		if (!state.date) {
			state.date = status.date;
			$("#date").value = status.date;
		}
		const parts = [];
		parts.push("系统日期 " + status.date + " " + status.time + "（" + status.timezone + "）");
		parts.push("车站 " + status.stations + " 个");
		parts.push("车次 " + status.services + " 个");
		parts.push("已售 " + status.tickets + " 张");
		parts.push("正在检票 " + status.boardingCount + " 趟");
		parts.push("发车前 " + status.boardingOpenMinutes + " 分钟开检");
		parts.push(status.requirePayment ? "票价钱包 " + status.fareAccount : "未启用扣费");
		$("#status-line").textContent = parts.join(" · ");
	} catch (error) {
		$("#status-line").textContent = "";
	}
}

async function doSearch() {
	const from = $("#from").value;
	const to = $("#to").value;
	const date = $("#date").value || state.date;
	if (!from || !to) {
		toast("请先选择出发站与到达站", true);
		return;
	}
	if (from === to) {
		toast("出发站与到达站不能相同", true);
		return;
	}
	try {
		const data = await api("/api/search", { from, to, date });
		state.results = data.services || [];
		state.date = date;
		renderResults(data);
	} catch (error) {
		toast(error.message, true);
	}
}

function classChips(index) {
	const service = state.results[index];
	return (service.classes || []).map((seatClass) => {
		const soldOut = seatClass.remaining <= 0;
		return '<button class="seat-class' + (soldOut ? " sold-out" : "") + '" data-buy="' + index
			+ '" data-class="' + escapeHtml(seatClass.code) + '"' + (soldOut ? " disabled" : "") + ">"
			+ escapeHtml(seatClass.name) + " <b>" + seatClass.price + "元</b>"
			+ '<span class="muted">余 ' + seatClass.remaining + "</span></button>";
	}).join(" ");
}

function renderResults(data) {
	const container = $("#results");
	if (!state.results.length) {
		container.innerHTML = '<div class="empty">' + escapeHtml(data.from) + " → " + escapeHtml(data.to)
			+ " 在 " + escapeHtml(data.date) + " 没有可售车次</div>";
		return;
	}
	const rows = state.results.map((service, index) => {
		const boardingBadge = service.boarding ? '<span class="badge green">检票中</span>'
			: (service.departed ? '<span class="badge gray">已发车</span>' : '<span class="badge gray">未开始检票</span>');
		return "<tr>"
			+ '<td><span class="train-no">' + escapeHtml(service.service) + "</span><br><span class=\"muted\">"
			+ escapeHtml(service.name || "") + "</span></td>"
			+ '<td><span class="time">' + escapeHtml(service.fromTime) + '</span><br><span class="station">'
			+ escapeHtml(service.fromStation) + (service.fromPlatform ? " · " + escapeHtml(service.fromPlatform) + "站台" : "")
			+ "</span></td>"
			+ '<td><span class="time">' + escapeHtml(service.toTime) + (service.days > 0 ? '<span class="muted">+' + service.days + "天</span>" : "")
			+ '</span><br><span class="station">' + escapeHtml(service.toStation) + "</span></td>"
			+ '<td class="muted">' + service.duration + " 分钟</td>"
			+ '<td><span class="badge blue">' + escapeHtml(service.checkpoint || "未设置") + "</span></td>"
			+ "<td>" + boardingBadge + '<br><span class="muted">' + escapeHtml(service.boardingOpen) + " 开检</span></td>"
			+ '<td><div class="class-chips">' + classChips(index) + "</div></td>"
			+ "</tr>";
	}).join("");
	container.innerHTML = '<h2>' + escapeHtml(data.from) + " → " + escapeHtml(data.to) + " · " + escapeHtml(data.date) + "</h2>"
		+ "<table><thead><tr><th>车次</th><th>出发</th><th>到达</th><th>历时</th><th>检票口</th><th>检票状态</th><th>坐席 / 票价（点击预订）</th></tr></thead><tbody>"
		+ rows + "</tbody></table>";
	container.querySelectorAll("[data-buy]").forEach((button) => {
		button.addEventListener("click", () => openBuy(Number(button.dataset.buy), button.dataset.class));
	});
}

/* ------------------------------------------------------------------ 购票 + 选座 */

async function openBuy(index, classCode) {
	if (!requireLogin()) {
		return;
	}
	const service = state.results[index];
	if (!service) {
		return;
	}
	const seatClass = (service.classes || []).find((item) => item.code === classCode)
		|| (service.classes || [])[0];
	if (!seatClass) {
		toast("该车次没有可售坐席", true);
		return;
	}
	state.buying = {
		service,
		index,
		classCode: seatClass.code,
		car: 0,
		seat: ""
	};
	state.seatMap = null;
	showModal('<h3>预订车票</h3><div class="empty">正在加载座位图…</div>');
	await loadSeatMap();
}

async function loadSeatMap() {
	const buying = state.buying;
	const service = buying.service;
	const url = "/api/seatmap?service=" + encodeURIComponent(service.service)
		+ "&serviceDate=" + encodeURIComponent(service.serviceDate)
		+ "&from=" + encodeURIComponent(service.fromStationId)
		+ "&to=" + encodeURIComponent(service.toStationId)
		+ "&class=" + encodeURIComponent(buying.classCode);
	try {
		state.seatMap = await api(url);
	} catch (error) {
		toast(error.message, true);
		hideModal();
		return;
	}
	if (!state.seatMap.cars || !state.seatMap.cars.length) {
		buying.car = 0;
	} else if (!state.seatMap.cars.some((car) => car.car === buying.car)) {
		buying.car = state.seatMap.cars[0].car;
	}
	renderBuyModal();
}

function renderBuyModal() {
	const buying = state.buying;
	const service = buying.service;
	const map = state.seatMap;
	const classes = (service.classes || []).map((seatClass) =>
		'<button class="chip' + (seatClass.code === buying.classCode ? " active" : "") + '" data-pick-class="'
		+ escapeHtml(seatClass.code) + '"' + (seatClass.remaining <= 0 ? " disabled" : "") + ">"
		+ escapeHtml(seatClass.name) + " " + seatClass.price + "元 · 余" + seatClass.remaining + "</button>").join(" ");
	const reserved = map.reserved !== false;
	const cars = (map.cars || []).map((car) => {
		if (car.dining) {
			return '<button class="chip" disabled>' + car.car + "车 餐车</button>";
		}
		return '<button class="chip' + (car.car === buying.car ? " active" : "") + '" data-pick-car="' + car.car + '">'
			+ car.car + "车</button>";
	}).join(" ");
	const seatGrid = reserved ? renderSeatGrid(map, buying) : "";
	const carSection = reserved
		? '<div class="picker"><div class="picker-label">车厢</div><div class="chips">'
			+ (map.cars && map.cars.length ? cars : '<span class="muted">无座票，不需要选择座位</span>')
			+ "</div></div>"
		: "";
	const seatSection = reserved && map.cars && map.cars.length
		? '<div class="picker"><div class="picker-label">座位（点击选择，留空为自动分配）</div>' + seatGrid + "</div>"
		: '<div class="picker"><div class="picker-label">座位</div><div class="muted">'
			+ escapeHtml(map.className) + '为自由席（不对号入座），上车后自行找座位。</div></div>';
	showModal(`
		<h3>${escapeHtml(service.service)} 次 · ${escapeHtml(service.fromStation)} → ${escapeHtml(service.toStation)}</h3>
		<p class="muted">${escapeHtml(service.date)} ${escapeHtml(service.fromTime)} → ${escapeHtml(service.toTime)}
			· ${escapeHtml(service.serviceDate)} 开行 · 检票口 ${escapeHtml(service.checkpoint || "未设置")}</p>
		<div class="picker">
			<div class="picker-label">坐席</div>
			<div class="chips">${classes}</div>
		</div>
		${carSection}
		${seatSection}
		<div class="inline" style="margin-top:14px">
			<span class="badge blue">${escapeHtml(map.className)} ${map.price} 元</span>
			<span class="badge gray">余票 ${map.remaining}</span>
			<span class="badge gray">余额 ${state.balance === null ? "…" : state.balance + " 元"}</span>
			<span class="badge green">已选 ${buying.seat ? escapeHtml(String(buying.car).padStart(2, "0") + "车 " + buying.seat) : "自动分配"}</span>
		</div>
		<div class="inline" style="margin-top:14px">
			<button class="primary" id="buy-confirm">确认支付 ${map.price} 元</button>
			<button class="ghost" id="buy-cancel">取消</button>
		</div>
	`);
	document.querySelectorAll("[data-pick-class]").forEach((button) => {
		button.addEventListener("click", async () => {
			buying.classCode = button.dataset.pickClass;
			buying.seat = "";
			await loadSeatMap();
		});
	});
	document.querySelectorAll("[data-pick-car]").forEach((button) => {
		button.addEventListener("click", () => {
			buying.car = Number(button.dataset.pickCar);
			buying.seat = "";
			renderBuyModal();
		});
	});
	document.querySelectorAll("[data-pick-seat]").forEach((button) => {
		button.addEventListener("click", () => {
			const seat = button.dataset.pickSeat;
			buying.seat = buying.seat === seat ? "" : seat;
			renderBuyModal();
		});
	});
	const cancel = $("#buy-cancel");
	if (cancel) {
		cancel.addEventListener("click", hideModal);
	}
	const confirm = $("#buy-confirm");
	if (confirm) {
		confirm.addEventListener("click", submitBuy);
	}
}

function renderSeatGrid(map, buying) {
	const car = (map.cars || []).find((item) => item.car === buying.car);
	if (!car) {
		return "";
	}
	const groups = car.groups || [];
	const columns = groups.flat();
	const occupied = new Set(car.occupied || []);
	const rows = [];
	for (let row = car.rows; row >= 1; row--) {
		const cells = [];
		groups.forEach((group, groupIndex) => {
			if (groupIndex > 0) {
				cells.push('<span class="aisle"></span>');
			}
			group.forEach((column) => {
				const label = row + column;
				const isOccupied = occupied.has(label);
				const isSelected = buying.seat === label;
				cells.push('<button class="seat' + (isOccupied ? " occupied" : "") + (isSelected ? " selected" : "")
					+ '" data-pick-seat="' + label + '"' + (isOccupied ? " disabled" : "") + ">"
					+ (isOccupied ? "×" : label) + "</button>");
			});
		});
		rows.push('<div class="seat-row"><span class="row-no">' + row + "</span>" + cells.join("") + "</div>");
	}
	const header = groups.map((group) =>
		'<span class="seat-col">' + group.join(" ") + "</span>").join('<span class="aisle"></span>');
	return '<div class="seat-map">' + '<div class="seat-row header"><span class="row-no"></span>' + header + "</div>"
		+ rows.join("") + "</div>";
}

async function submitBuy() {
	const buying = state.buying;
	const service = buying.service;
	try {
		const ticket = await api("/api/purchase", {
			service: service.service,
			serviceDate: service.serviceDate,
			from: service.fromStationId,
			to: service.toStationId,
			seatClass: buying.classCode,
			car: buying.car,
			seat: buying.seat
		});
		state.balance = ticket.balance;
		renderSession();
		showModal(`
			<h3>购票成功</h3>
			${ticketCard(ticket)}
			<p class="muted">请在检票时间内到 <b>${escapeHtml(ticket.fromStation)} ${escapeHtml(ticket.checkpoint || "检票口")}</b>
				的闸机处通过，闸机会校验车票并显示乘坐区间。</p>
			<div class="inline" style="margin-top:8px">
				<button class="primary" id="buy-done">完成</button>
				<span class="badge blue">余额 ${ticket.balance} 元</span>
			</div>
		`);
		$("#buy-done").addEventListener("click", hideModal);
		toast("出票成功：" + ticket.id);
		doSearch();
	} catch (error) {
		toast(error.message, true);
		if (error.needLogin) {
			openLogin();
		} else {
			loadSeatMap();
		}
	}
}

function ticketCard(ticket) {
	return `
		<div class="ticket">
			<div class="ticket-head">
				<span>${escapeHtml(ticket.service)} 次 · ${escapeHtml(ticket.date)}</span>
				<span class="no">${escapeHtml(ticket.id)}</span>
			</div>
			<div class="ticket-body">
				<div>
					<div class="time-big">${escapeHtml(ticket.fromTime)}</div>
					<div class="station-big">${escapeHtml(ticket.fromStation)}</div>
				</div>
				<div class="ticket-arrow">⟶</div>
				<div style="text-align:right">
					<div class="time-big">${escapeHtml(ticket.toTime)}</div>
					<div class="station-big">${escapeHtml(ticket.toStation)}</div>
				</div>
			</div>
			<div class="ticket-foot">
				<span>乘客 <b>${escapeHtml(ticket.player)}</b></span>
				<span>坐席 <b>${escapeHtml(ticket.seatClassLabel)}</b></span>
				<span>座位 <b>${escapeHtml(ticket.seat)}</b></span>
				<span>票价 <b>${ticket.price} 元</b></span>
				<span>检票口 <b>${escapeHtml(ticket.checkpoint || "未设置")}</b></span>
				<span>状态 ${stateText(ticket.state)}</span>
			</div>
		</div>`;
}

/* ------------------------------------------------------------------ 我的车票 / 检票动态 */

async function loadMine() {
	if (!state.token) {
		$("#mine-list").innerHTML = '<div class="card"><div class="empty">请先登录或注册账号</div></div>';
		return;
	}
	try {
		const tickets = await api("/api/tickets?player=" + encodeURIComponent(state.player));
		const container = $("#mine-list");
		$("#mine-hint").textContent = state.player + " 的车票";
		if (!tickets.length) {
			container.innerHTML = '<div class="card"><div class="empty">还没有车票，先去「购票」页面买一张吧</div></div>';
			return;
		}
		container.innerHTML = '<div class="card"><h2>' + escapeHtml(state.player) + " 的车票（" + tickets.length + " 张）</h2>"
			+ tickets.map((ticket) => ticketCard(ticket)
				+ '<div class="inline" style="margin:-6px 0 14px">'
				+ (ticket.state === "VALID" && !ticket.receiptPrinted
					? '<button class="danger small" data-refund="' + escapeHtml(ticket.id) + '">退票（退回 ' + ticket.price + ' 元）</button>'
					: "")
				+ '<button class="ghost small" data-receipt="' + escapeHtml(ticket.id) + '">报销凭证</button>'
				+ '<span class="muted">检票时间 ' + escapeHtml(ticket.boardingOpen || "-") + " — " + escapeHtml(ticket.boardingClose || "-") + "</span>"
				+ "</div>").join("")
			+ "</div>";
		container.querySelectorAll("[data-receipt]").forEach((button) => {
			button.addEventListener("click", () => showReceipt(button.dataset.receipt));
		});
		container.querySelectorAll("[data-refund]").forEach((button) => {
			button.addEventListener("click", () => refund(button.dataset.refund));
		});
	} catch (error) {
		toast(error.message, true);
	}
}

async function showReceipt(ticketId) {
	try {
		const data = await api("/api/receipt?ticket=" + encodeURIComponent(ticketId));
		showModal(`
			<h3>报销凭证</h3>
			${receiptHtml(data)}
			<div class="inline" style="margin-top:12px">
				<button class="primary" id="receipt-print" ${data.printed ? "disabled" : ""}>${data.printed ? "已打印" : "打印报销凭证"}</button>
				<button class="ghost" id="receipt-close">关闭</button>
			</div>
			<p class="muted" style="margin-top:8px">打印后该车票不能退票；游戏内售票机同样可以打印。</p>
		`);
		$("#receipt-close").addEventListener("click", hideModal);
		const printBtn = $("#receipt-print");
		if (printBtn && !data.printed) {
			printBtn.addEventListener("click", async () => {
				try {
					const result = await api("/api/receipt/print", { ticket: ticketId });
					toast("报销凭证已打印");
					hideModal();
					loadMine();
					showReceipt(result.ticketId);
				} catch (error) {
					toast(error.message, true);
				}
			});
		}
	} catch (error) {
		toast(error.message, true);
	}
}

function receiptHtml(data) {
	const printed = data.printed ? new Date(data.printedAt).toLocaleString() : "-";
	return `<div class="blue-ticket">
		<div class="bt-head"><span>中国铁路 · 电子客票报销凭证</span><span>${escapeHtml(data.state)}</span></div>
		<div class="bt-grid">
			<div><span>车次</span><b>${escapeHtml(data.service)} 次</b></div>
			<div><span>乘车日期</span><b>${escapeHtml(data.date)}</b></div>
			<div><span>出发站</span><b>${escapeHtml(data.fromStation)} ${escapeHtml(data.fromTime)}</b></div>
			<div><span>到达站</span><b>${escapeHtml(data.toStation)} ${escapeHtml(data.toTime)}</b></div>
			<div><span>乘车人</span><b>${escapeHtml(data.passenger)}</b></div>
			<div><span>席位</span><b>${escapeHtml(data.seatClass)} ${escapeHtml(data.seat)}</b></div>
			<div><span>票价</span><b>￥${escapeHtml(data.price)}</b></div>
			<div><span>检票口</span><b>${escapeHtml(data.checkpoint || "-")}</b></div>
			<div><span>票号</span><b>${escapeHtml(data.ticketId)}</b></div>
			<div><span>账号</span><b>${escapeHtml(data.account || "-")}</b></div>
		</div>
		<div class="bt-foot">
			<span>仅供报销使用 · 打印后不可退票</span>
			<span class="bt-seal">电子客票</span>
			<span>开具时间 ${escapeHtml(printed)}</span>
		</div>
	</div>`;
}

async function refund(ticketId) {
	if (!confirm("确定要退订车票 " + ticketId + " 吗？")) {
		return;
	}
	try {
		const result = await api("/api/refund", { ticket: ticketId });
		state.balance = result.balance;
		renderSession();
		toast(result.message);
		loadMine();
	} catch (error) {
		toast(error.message, true);
	}
}

async function loadLive() {
	try {
		const entries = await api("/api/boarding");
		const container = $("#live-list");
		if (!entries.length) {
			container.innerHTML = '<div class="empty">今日暂无发车计划</div>';
			return;
		}
		container.innerHTML = entries.map((entry) => {
			const badge = entry.openNow ? '<span class="badge green">检票中</span>'
				: (entry.manualClosed ? '<span class="badge red">已停止检票</span>' : '<span class="badge gray">未开始</span>');
			return '<div class="boarding-row">'
				+ "<div><span class=\"train-no\">" + escapeHtml(entry.service) + "</span> "
				+ '<span class="station">' + escapeHtml(entry.station) + " · " + escapeHtml(entry.platform) + " 站台</span></div>"
				+ '<div class="muted">发车 ' + escapeHtml(entry.depart) + " · 检票 " + escapeHtml(entry.open) + " — " + escapeHtml(entry.close) + "</div>"
				+ "<div>" + badge + ' <span class="badge blue">' + escapeHtml(entry.checkpoint) + "</span></div>"
				+ "</div>";
		}).join("");
	} catch (error) {
		$("#live-list").innerHTML = '<div class="empty">' + escapeHtml(error.message) + "</div>";
	}
}

/* ------------------------------------------------------------------ 初始化 */

function switchTab(name) {
	document.querySelectorAll("nav.tabs button[data-tab]").forEach((button) => {
		button.classList.toggle("active", button.dataset.tab === name);
	});
	document.querySelectorAll(".tab").forEach((section) => {
		section.classList.toggle("active", section.id === "tab-" + name);
	});
	if (name === "live") {
		loadLive();
		clearInterval(state.liveTimer);
		state.liveTimer = setInterval(loadLive, 10000);
	} else {
		clearInterval(state.liveTimer);
	}
	if (name === "mine") {
		loadMine();
	}
}

document.addEventListener("DOMContentLoaded", async () => {
	document.querySelectorAll("nav.tabs button[data-tab]").forEach((button) => {
		button.addEventListener("click", () => switchTab(button.dataset.tab));
	});
	$("#btn-search").addEventListener("click", doSearch);
	$("#btn-mine").addEventListener("click", loadMine);
	$("#btn-login-top").addEventListener("click", openLogin);
	$("#modal").addEventListener("click", (event) => {
		if (event.target.id === "modal") {
			hideModal();
		}
	});
	renderSession();
	try {
		await loadStations();
	} catch (error) {
		toast("加载车站失败：" + error.message, true);
	}
	await loadStatus();
	await refreshBalance();
	setInterval(loadStatus, 20000);
});

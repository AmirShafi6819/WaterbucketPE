/* ============================================================
   WaterBucketPE panel frontend
   - Liquid Glass theme (dark/light), blue accents, bottom hotbar
   - fa/en localization with RTL, layout editor, per-server detail
   ============================================================ */
"use strict";

/* ---------------- i18n ---------------- */
const I18N = {
  en: {
    "login.sub": "WaterBucketPE proxy panel",
    "login.hint": "Enter the panel token from panel_token.txt",
    "login.placeholder": "Panel token",
    "login.enter": "Enter",
    "login.badToken": "Invalid token",
    "login.secure": "Every API call requires the token",
    "stat.players": "Players", "stat.cpu": "CPU", "stat.memory": "Memory", "stat.uptime": "Uptime",
    "stat.maxPlayers": "max {n}", "stat.cores": "{n} cores", "stat.threads": "{n} threads",
    "chart.players": "Players over time", "chart.memory": "Memory over time", "chart.cpu": "CPU over time",
    "servers.title": "Servers", "servers.empty": "No servers configured yet",
    "servers.players": "Players", "servers.ping": "Ping", "servers.upFor": "Up for",
    "servers.pingNow": "Ping now", "servers.onServer": "Players on this server",
    "servers.online": "Online", "servers.offline": "Offline", "servers.clickForDetail": "Click for detail",
    "players.title": "Players", "players.name": "Player", "players.server": "Server",
    "players.address": "Address", "players.protocol": "Protocol", "players.actions": "Actions",
    "players.empty": "No players online", "players.kick": "Kick", "players.transfer": "Move",
    "console.title": "Console", "console.all": "All levels", "console.warnPlus": "Warnings and errors",
    "console.errors": "Errors only", "console.clear": "Clear view", "console.send": "Send",
    "console.placeholder": "Type a command…",
    "settings.title": "Settings", "settings.appearance": "Appearance", "settings.theme": "Theme",
    "settings.dark": "Dark", "settings.light": "Light", "settings.accent": "Accent",
    "settings.glass": "Glass intensity", "settings.layout": "Layout",
    "settings.layoutHint": "Drag cards to reorder them, press × to hide. Hidden cards can be restored here.",
    "settings.editLayout": "Edit layout", "settings.resetLayout": "Reset",
    "settings.done": "Done", "settings.about": "About", "settings.version": "Version",
    "settings.java": "Java", "settings.os": "OS", "settings.proxyName": "Proxy",
    "settings.hiddenCards": "Hidden cards",
    "nav.overview": "Overview", "nav.servers": "Servers", "nav.players": "Players",
    "nav.console": "Console", "nav.settings": "Settings",
    "edit.active": "Editing layout — drag cards, × hides, Done when finished",
    "common.offline": "offline", "common.never": "—", "common.kicked": "kicked", "common.moved": "moved",
    "common.pinged": "ping sent", "common.commandSent": "command sent",
    "common.confirmKick": "Kick {name}?", "common.failed": "Action failed", "common.close": "Close",
    "settings.language": "Language", "settings.restoreCards": "Restore hidden cards",
    "settings.layoutReset": "Layout reset",
  },
  fa: {
    "login.sub": "پنل مدیریت WaterBucketPE",
    "login.hint": "توکن پنل را از فایل panel_token.txt وارد کنید",
    "login.placeholder": "توکن پنل",
    "login.enter": "ورود",
    "login.badToken": "توکن نامعتبر است",
    "login.secure": "هر درخواست نیازمند توکن است",
    "stat.players": "بازیکنان", "stat.cpu": "پردازنده", "stat.memory": "حافظه", "stat.uptime": "مدت فعالیت",
    "stat.maxPlayers": "حداکثر {n}", "stat.cores": "{n} هسته", "stat.threads": "{n} نخ",
    "chart.players": "بازیکنان در طول زمان", "chart.memory": "مصرف حافظه در طول زمان", "chart.cpu": "مصرف پردازنده در طول زمان",
    "servers.title": "سرورها", "servers.empty": "هنوز سروری تعریف نشده است",
    "servers.players": "بازیکنان", "servers.ping": "پینگ", "servers.upFor": "مدت فعالیت",
    "servers.pingNow": "پینگ فوری", "servers.onServer": "بازیکنان این سرور",
    "servers.online": "آنلاین", "servers.offline": "آفلاین",
    "servers.clickForDetail": "برای جزئیات کلیک کنید",
    "players.title": "بازیکنان", "players.name": "بازیکن", "players.server": "سرور",
    "players.address": "آدرس", "players.protocol": "پروتکل", "players.actions": "عملیات",
    "players.empty": "بازیکنی آنلاین نیست", "players.kick": "اخراج", "players.transfer": "انتقال",
    "console.title": "کنسول", "console.all": "همه سطوح", "console.warnPlus": "هشدار و خطا",
    "console.errors": "فقط خطاها", "console.clear": "پاک کردن", "console.send": "ارسال",
    "console.placeholder": "دستور را بنویسید…",
    "settings.title": "تنظیمات", "settings.appearance": "ظاهر", "settings.theme": "پوسته",
    "settings.dark": "تیره", "settings.light": "روشن", "settings.accent": "رنگ اصلی",
    "settings.glass": "شدت افکت شیشه‌ای", "settings.layout": "چیدمان",
    "settings.layoutHint": "کارت‌ها را بکشید تا جابه‌جا شوند؛ با × مخفی می‌شوند. کارت‌های مخفی از همین‌جا بازمی‌گردند.",
    "settings.editLayout": "ویرایش چیدمان", "settings.resetLayout": "بازنشانی",
    "settings.done": "اتمام", "settings.about": "درباره", "settings.version": "نسخه",
    "settings.java": "جاوا", "settings.os": "سیستم‌عامل", "settings.proxyName": "پروکسی",
    "settings.hiddenCards": "کارت‌های مخفی",
    "nav.overview": "خانه", "nav.servers": "سرورها", "nav.players": "بازیکنان",
    "nav.console": "کنسول", "nav.settings": "تنظیمات",
    "edit.active": "ویرایش چیدمان — کارت‌ها را بکشید، × مخفی می‌کند، «اتمام» برای پایان",
    "common.offline": "آفلاین", "common.never": "—", "common.kicked": "اخراج شد", "common.moved": "منتقل شد",
    "common.pinged": "پینگ ارسال شد", "common.commandSent": "دستور ارسال شد",
    "common.confirmKick": "اخراج {name}؟", "common.failed": "عملیات ناموفق بود", "common.close": "بستن",
    "settings.language": "زبان", "settings.restoreCards": "بازگرداندن کارت‌های مخفی",
    "settings.layoutReset": "چیدمان بازنشانی شد",
  },
};

/* ---------------- helpers ---------------- */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => [...document.querySelectorAll(sel)];
const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
};
// <use>-based SVG icon from the sprite in index.html
const SVG_NS = "http://www.w3.org/2000/svg";
const icon = (name) => {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("class", "icon");
  const use = document.createElementNS(SVG_NS, "use");
  use.setAttribute("href", "#i-" + name);
  svg.appendChild(use);
  return svg;
};
const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem(key); return v === null ? fallback : JSON.parse(v); }
    catch { return fallback; }
  },
  set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* private mode */ } },
};

let LANG = store.get("wb_lang", navigator.language && navigator.language.startsWith("fa") ? "fa" : "en");
const t = (key, vars) => {
  let text = (I18N[LANG] && I18N[LANG][key]) || I18N.en[key] || key;
  if (vars) for (const [k, v] of Object.entries(vars)) text = text.replace(`{${k}}`, v);
  return text;
};

function applyI18n() {
  document.documentElement.lang = LANG;
  document.documentElement.dir = LANG === "fa" ? "rtl" : "ltr";
  $$("[data-i18n]").forEach((node) => { node.textContent = t(node.dataset.i18n); });
  $$("[data-i18n-ph]").forEach((node) => { node.placeholder = t(node.dataset.i18nPh); });
  $$("[data-i18n-title]").forEach((node) => { node.title = t(node.dataset.i18nTitle); });
  $("#lang-next").textContent = LANG === "fa" ? "EN" : "ف";
}

/* format bytes / durations / time */
const MB = 1024 * 1024;
const fmtBytes = (b) => (b >= GB ? (b / GB).toFixed(1) + " GB" : Math.round(b / MB) + " MB");
const GB = 1024 * MB;
const fmtUptime = (ms) => {
  const s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600),
        m = Math.floor(s % 3600 / 60);
  return (d ? d + "d " : "") + (h ? h + "h " : "") + m + "m";
};
const fmtClock = (epochMs) => new Date(epochMs).toLocaleTimeString(LANG === "fa" ? "fa-IR" : "en-GB",
  { hour: "2-digit", minute: "2-digit", second: "2-digit" });

/* ---------------- settings (theme / accent / glass) ---------------- */
function applyTheme(theme) {
  document.body.dataset.theme = theme;
  store.set("wb_theme", theme);
  $$("#theme-seg button").forEach((b) => b.classList.toggle("active", b.dataset.theme === theme));
  const themeIcon = $("#theme-icon");
  if (themeIcon) themeIcon.setAttribute("href", theme === "dark" ? "#i-sun" : "#i-moon");
  rebuildChartPalette();
}
function applyAccent(name) {
  const palettes = { blue: ["#4da3ff", "#7cc5ff"], cyan: ["#39d3d8", "#7ee7ea"],
                     indigo: ["#7c8cff", "#a9b3ff"], teal: ["#3ad2a0", "#7fe3c2"] };
  const [a, b] = palettes[name] || palettes.blue;
  document.documentElement.style.setProperty("--accent1", a);
  document.documentElement.style.setProperty("--accent2", b);
  $$(".swatch").forEach((sw) => sw.classList.toggle("active", sw.dataset.accent === name));
  store.set("wb_accent", name);
  rebuildChartPalette();
}
function applyGlass(v) {
  document.documentElement.style.setProperty("--glass-alpha", (0.35 + (v / 100) * 0.5).toFixed(2));
  store.set("wb_glass", v);
}

/* ---------------- API ---------------- */
const API = {
  headers() { return { "X-Panel-Token": store.get("wb_token", "") }; },
  async get(path) {
    const res = await fetch(path, { headers: this.headers() });
    if (res.status === 401) { showLogin(); throw new Error("unauthorized"); }
    if (!res.ok) throw new Error(res.status);
    return res.json();
  },
  async post(path, body) {
    const res = await fetch(path, { method: "POST", headers: { ...this.headers(), "Content-Type": "application/json" },
      body: JSON.stringify(body || {}) });
    if (res.status === 401) { showLogin(); throw new Error("unauthorized"); }
    if (!res.ok) throw new Error(res.status);
    return res.json();
  },
};

function toast(message) {
  const box = $("#toast");
  box.textContent = message;
  box.classList.remove("hidden");
  clearTimeout(box._timer);
  box._timer = setTimeout(() => box.classList.add("hidden"), 2600);
}

/* ---------------- login ---------------- */
function showLogin() {
  clearInterval(pollTimer);
  $("#app").classList.add("hidden");
  $("#login").classList.remove("hidden");
  setTimeout(() => $("#token-input").focus(), 50);
}
function showApp() {
  $("#login").classList.add("hidden");
  $("#app").classList.remove("hidden");
  refreshAll();
  startPolling();
}
async function tryLogin(token) {
  const res = await fetch("/api/status", { headers: { "X-Panel-Token": token } });
  if (res.status === 401) return false;
  if (!res.ok) throw new Error(res.status);
  store.set("wb_token", token);
  return true;
}
$("#login-btn").addEventListener("click", async () => {
  $("#login-error").classList.add("hidden");
  try {
    if (await tryLogin($("#token-input").value.trim())) showApp();
    else $("#login-error").classList.remove("hidden");
  } catch { $("#login-error").classList.remove("hidden"); }
});
$("#token-input").addEventListener("keydown", (e) => { if (e.key === "Enter") $("#login-btn").click(); });

/* ---------------- hotbar navigation ---------------- */
let currentView = "overview";
function switchView(name) {
  currentView = name;
  $$(".view").forEach((v) => v.classList.toggle("hidden", v.id !== "view-" + name));
  $$("#hotbar .hotbtn").forEach((b) => b.classList.toggle("active", b.dataset.view === name));
  // fetch immediately instead of waiting for the next poll tick
  if (name === "players") refreshPlayers();
}
$$("#hotbar .hotbtn").forEach((btn) =>
  btn.addEventListener("click", () => switchView(btn.dataset.view)));
$("#home-btn").addEventListener("click", () => switchView("overview"));

/* ---------------- charts (created once, updated in place) ---------------- */
const charts = {};
function chartTheme() {
  const dark = document.body.dataset.theme !== "light";
  return {
    grid: dark ? "rgba(255,255,255,.07)" : "rgba(13,40,80,.09)",
    ticks: dark ? "#8fa3c4" : "#5a6f8f",
  };
}
function makeChart(id, label, color) {
  const ctx = document.getElementById(id);
  if (!ctx || !window.Chart) return null;
  const theme = chartTheme();
  return new Chart(ctx, {
    type: "line",
    data: { labels: [], datasets: [{
      data: [], label, borderColor: color, borderWidth: 2,
      backgroundColor: color + '1f', fill: true, tension: .38, pointRadius: 0, pointHitRadius: 8,
    }]},
    options: {
      responsive: true, maintainAspectRatio: false,
      animation: false,
      plugins: { legend: { display: false }, tooltip: { intersect: false, mode: "index" } },
      scales: {
        x: { ticks: { color: theme.ticks, maxTicksLimit: 8, font: { size: 10 } },
             grid: { color: theme.grid } },
        y: { beginAtZero: true, ticks: { color: theme.ticks, maxTicksLimit: 5, font: { size: 10 } },
             grid: { color: theme.grid } },
      },
    },
  });
}
function fillChart(chart, points, valueFn, labelFn, maxLabelCount) {
  if (!chart) return;
  // Downsample for the X axis: labels only for the last N points
  chart.data.labels = points.map((p, i) =>
    i % Math.ceil(points.length / (maxLabelCount || 8)) === 0 || i === points.length - 1 ? labelFn(p) : "");
  chart.data.datasets[0].data = points.map(valueFn);
  chart.update("none");
}
function rebuildChartPalette() {
  const theme = chartTheme();
  const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent1").trim();
  Object.values(charts).forEach((chart) => {
    if (!chart) return;
    chart.options.scales.x.ticks.color = theme.ticks;
    chart.options.scales.x.grid.color = theme.grid;
    chart.options.scales.y.ticks.color = theme.ticks;
    chart.options.scales.y.grid.color = theme.grid;
    if (chart.canvas.id === "chart-memory") chart.data.datasets[0].borderColor = accent;
    chart.update("none");
  });
}

/* ---------------- overview ---------------- */
let lastStatus = null;
function renderStatus(status) {
  lastStatus = status;
  $("#stat-players").textContent = status.players;
  $("#stat-maxplayers").textContent = t("stat.maxPlayers", { n: status.maxPlayers ?? "?" });
  $("#stat-cpu").textContent = (status.cpu ?? 0) + "%";
  $("#stat-cores").textContent = t("stat.cores", { n: status.cores });
  const used = status.memoryUsed, max = status.memoryMax || 1;
  $("#stat-memory").textContent = fmtBytes(used) + " / " + fmtBytes(max);
  $("#memory-fill").style.width = Math.min(100, used / max * 100).toFixed(1) + "%";
  $("#stat-uptime").textContent = fmtUptime(status.uptimeMs);
  $("#stat-threads").textContent = t("stat.threads", { n: status.threads });
  $("#version-pill").textContent = "v" + status.proxyVersion;
  $("#uptime-pill").textContent = fmtUptime(status.uptimeMs);
  $("#health-dot").classList.toggle("bad", false);
  $("#badge-players").textContent = status.players;
  $("#badge-servers").textContent = (status.servers || []).length;

  $("#about-version").textContent = status.proxyVersion;
  $("#about-java").textContent = status.javaVersion;
  $("#about-os").textContent = status.osName + " " + (status.osArch || "");
  $("#about-proxy").textContent = status.proxyName;

  renderServers(status.servers || []);
  renderPlayersIfVisible();
}
function renderPlayersIfVisible() {
  if (currentView === "players") refreshPlayers();
}

/* ---------------- servers ---------------- */
const sparkCache = new Map();
function sparkline(canvas, series, color) {
  const width = canvas.width = canvas.clientWidth * (window.devicePixelRatio || 1);
  const height = canvas.height = canvas.clientHeight * (window.devicePixelRatio || 1);
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, width, height);
  const data = series.filter((v) => v >= 0);
  if (data.length < 2) return;
  const max = Math.max(...data), min = Math.min(...data), span = (max - min) || 1;
  ctx.beginPath();
  data.forEach((v, i) => {
    const x = i / (data.length - 1) * width;
    const y = height - 3 - ((v - min) / span) * (height - 6);
    i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
  });
  ctx.strokeStyle = color; ctx.lineWidth = 2 * (window.devicePixelRatio || 1);
  ctx.lineJoin = "round"; ctx.stroke();
  ctx.lineTo(width, height); ctx.lineTo(0, height); ctx.closePath();
  const grad = ctx.createLinearGradient(0, 0, 0, height);
  grad.addColorStop(0, color + "55"); grad.addColorStop(1, color + "00");
  ctx.fillStyle = grad; ctx.fill();
}
function renderServers(servers) {
  const grid = $("#servers-grid");
  $("#servers-empty").classList.toggle("hidden", servers.length > 0);
  const existing = new Map(sparkCache);
  const names = new Set();
  servers.forEach((server) => {
    names.add(server.name);
    let card = existing.get(server.name);
    if (!card) {
      card = buildServerCard(server);
      grid.appendChild(card);
    }
    updateServerCard(card, server);
  });
  for (const [name, card] of existing) {
    if (!names.has(name)) { card.remove(); sparkCache.delete(name); }
  }
  if (dialogServer) refreshServerDialog();
}
function buildServerCard(server) {
  const card = el("div", "card glass server-card");
  card.dataset.card = "server-" + server.name;
  card.dataset.editor = "servers";
  card.dataset.serverName = server.name;

  const head = el("div", "server-head");
  const dot = el("span", "dot");
  dot.classList.add("server-dot");
  const name = el("span", "server-name", server.name);
  const type = el("span", "server-type", server.type);
  head.append(dot, name, type);
  card.appendChild(head);

  const meta = el("div", "server-meta");
  [["users", "m-players", "0"], ["wifi", "m-ping", "—"], ["clock", "m-up", "—"]].forEach(([ic, cls, val]) => {
    const item = el("span");
    item.appendChild(icon(ic));
    item.appendChild(el("b", cls, val));
    meta.appendChild(item);
  });
  card.appendChild(meta);

  const addr = el("div", "muted small", server.address);
  addr.style.marginTop = "8px";
  card.appendChild(addr);

  const spark = el("div", "server-spark");
  const canvas = document.createElement("canvas");
  spark.appendChild(canvas);
  card.appendChild(spark);

  const hint = el("div", "server-open-hint", t("servers.clickForDetail"));
  card.appendChild(hint);

  card.addEventListener("click", (event) => {
    if (event.target.classList.contains("card-hide") || document.body.classList.contains("editing")) return;
    openServerDialog(server.name);
  });
  attachEditorTools(card);
  sparkCache.set(server.name, card);
  return card;
}
function updateServerCard(card, server) {
  card.querySelector(".server-dot").classList.toggle("bad", !server.online);
  card.querySelector(".m-players").textContent = server.players;
  card.querySelector(".m-ping").textContent = server.pingMs >= 0 ? server.pingMs + " ms" : t("common.offline");
  card.querySelector(".m-up").textContent = server.upMs > 0 ? fmtUptime(server.upMs) : "—";
  card.querySelector(".server-type").textContent = server.type;
  card.querySelector(".muted.small").textContent = server.address;
  sparkline(card.querySelector("canvas"), server.pingHistory || pingSparkFallback(server.name),
            getComputedStyle(document.documentElement).getPropertyValue("--accent1").trim());
}
// The status payload has no history; the detail endpoint fills the sparkline when opened.
// For the grid, keep a light rolling ping series per server from status polls.
const gridPingHistory = new Map();
function pingSparkFallback(name) {
  const series = gridPingHistory.get(name) || [];
  return series;
}

/* ---------------- server detail dialog ---------------- */
let dialogServer = null;
let dialogTimer = null;
function openServerDialog(name) {
  dialogServer = name;
  $("#server-dialog").showModal();
  refreshServerDialog();
  dialogTimer = setInterval(refreshServerDialog, 5000);
}
$("#sd-close").addEventListener("click", () => { $("#server-dialog").close(); });
$("#server-dialog").addEventListener("close", () => { clearInterval(dialogTimer); dialogServer = null; });

async function refreshServerDialog() {
  if (!dialogServer || !$("#server-dialog").open) return;
  let detail;
  try { detail = await API.get("/api/servers/" + encodeURIComponent(dialogServer)); }
  catch { return; }
  $("#sd-name").textContent = detail.name;
  $("#sd-address").textContent = detail.address;
  $("#sd-type").textContent = detail.type;
  $("#sd-dot").classList.toggle("bad", !detail.online);
  $("#sd-online").textContent = detail.online ? t("servers.online") : t("servers.offline");
  $("#sd-players").textContent = detail.players;
  $("#sd-ping").textContent = detail.pingMs >= 0 ? detail.pingMs + " ms" : t("common.offline");
  $("#sd-up").textContent = detail.upMs > 0 ? fmtUptime(detail.upMs) : "—";

  if (!charts.sdPing) {
    charts.sdPing = makeChart("sd-chart-ping", "ping", "#39d3d8");
    charts.sdPlayers = makeChart("sd-chart-players", "players", "#7c8cff");
  }
  // Each chart labels from its own series length (the ping and player histories grow
  // independently, so a shared label array produced "undefined" ticks)
  const secLabels = (series) => series.map((_, i) => (i - series.length + 1) + "s");
  fillChart(charts.sdPing, detail.pingHistory, (v) => (v >= 0 ? v : null),
    (_, i) => secLabels(detail.pingHistory)[i], 8);
  fillChart(charts.sdPlayers, detail.playersHistory, (v) => v,
    (_, i) => secLabels(detail.playersHistory)[i], 8);

  const list = $("#sd-players-list");
  list.innerHTML = "";
  (detail.playerNames || []).forEach((playerName) => {
    const chip = el("span", "chip", playerName);
    const kick = el("button", "btn mini danger");
    kick.appendChild(icon("logout"));
    kick.title = t("players.kick");
    kick.addEventListener("click", async () => {
      if (!confirm(t("common.confirmKick", { name: playerName }))) return;
      await API.post("/api/players/" + encodeURIComponent(playerName) + "/kick");
      toast(playerName + " " + t("common.kicked"));
      refreshServerDialog();
    });
    chip.appendChild(kick);
    list.appendChild(chip);
  });
  if (!(detail.playerNames || []).length) {
    list.appendChild(el("span", "muted small", t("players.empty")));
  }

  // Move a player to this server
  const box = $("#sd-transfer");
  box.innerHTML = "";
  try {
    const data = await API.get("/api/players");
    const select = el("select", "select");
    select.appendChild(el("option", "", t("players.transfer") + " →"));
    (data.players || []).forEach((p) => {
      const option = el("option", "", p.name);
      option.value = p.name;
      select.appendChild(option);
    });
    const move = el("button", "btn mini primary", dialogServer);
    move.addEventListener("click", async () => {
      if (!select.value) return;
      await API.post("/api/players/" + encodeURIComponent(select.value) + "/transfer",
        { server: dialogServer });
      toast(select.value + " " + t("common.moved"));
    });
    box.append(select, move);
  } catch { /* players fetch failed; box stays empty */ }
}
$("#sd-ping").addEventListener("click", async () => {
  if (!dialogServer) return;
  await API.post("/api/servers/ping/" + encodeURIComponent(dialogServer));
  toast(t("common.pinged"));
});

/* ---------------- players ---------------- */
async function refreshPlayers() {
  let data;
  try { data = await API.get("/api/players"); } catch { return; }
  const body = $("#players-body");
  body.innerHTML = "";
  const players = data.players || [];
  $("#players-empty").classList.toggle("hidden", players.length > 0);
  players.forEach((player) => {
    const row = el("tr");
    const nameCell = el("td", "", player.name);
    nameCell.style.fontWeight = "600";
    row.appendChild(nameCell);
    row.appendChild(el("td", "", player.server || "—"));
    row.appendChild(el("td", "muted", player.address || ""));
    row.appendChild(el("td", "muted", String(player.protocol || "")));

    const actions = el("td");
    const kick = el("button", "btn mini danger", t("players.kick"));
    kick.addEventListener("click", async () => {
      if (!confirm(t("common.confirmKick", { name: player.name }))) return;
      await API.post("/api/players/" + encodeURIComponent(player.name) + "/kick");
      toast(player.name + " " + t("common.kicked"));
      refreshPlayers();
    });
    actions.appendChild(kick);

    const move = el("button", "btn mini", t("players.transfer"));
    move.style.marginInlineStart = "6px";
    move.addEventListener("click", () => {
      const target = prompt(t("players.transfer") + " →", (player.server || "") && "");
      if (!target) return;
      API.post("/api/players/" + encodeURIComponent(player.name) + "/transfer", { server: target })
        .then(() => { toast(player.name + " " + t("common.moved")); refreshPlayers(); })
        .catch(() => toast(t("common.failed")));
    });
    actions.appendChild(move);
    row.appendChild(actions);
    body.appendChild(row);
  });
}

/* ---------------- console ---------------- */
const consoleBox = $("#console-log");
let consoleTail = [];
function renderConsole() {
  const filter = $("#console-level").value;
  const atBottom = consoleBox.scrollTop + consoleBox.clientHeight >= consoleBox.scrollHeight - 30;
  consoleBox.innerHTML = "";
  consoleTail.forEach((line) => {
    if (filter === "WARN" && / WARN /.test(line) === false && / ERROR /.test(line) === false) return;
    if (filter === "ERROR" && / ERROR /.test(line) === false) return;
    const span = renderLogLine(line);
    consoleBox.appendChild(span);
  });
  if (atBottom) consoleBox.scrollTop = consoleBox.scrollHeight;
}
/* Minecraft § color codes -> colored spans, so proxy messages keep their
   colors in the panel console instead of showing raw "§b" sequences */
const MC_COLORS = {
  0: "#8b8b8b", 1: "#5c7cff", 2: "#4bd06b", 3: "#3ecfcf", 4: "#ff6b6b",
  5: "#b07cd8", 6: "#ffb347", 7: "#c9c9c9", 8: "#7a7a7a", 9: "#6c9eff",
  a: "#6ee787", b: "#56d4dd", c: "#ff7b72", d: "#dba0ec", e: "#f2cc60", f: "#e6edf3",
};
function appendMcText(parent, text) {
  let color = null, bold = false, italic = false, underline = false, strike = false;
  const push = (part) => {
    if (!part) return;
    const node = el("span", "log-part", part);
    if (color) node.style.color = color;
    if (bold) node.classList.add("mc-bold");
    if (italic) node.classList.add("mc-italic");
    if (underline) node.classList.add("mc-underline");
    if (strike) node.classList.add("mc-strike");
    parent.appendChild(node);
  };
  text.split("§").forEach((part, i) => {
    if (i === 0) { push(part); return; }
    const code = part.slice(0, 1).toLowerCase();
    const rest = part.slice(1);
    if (code in MC_COLORS) color = MC_COLORS[code];
    else if (code === "l") bold = true;
    else if (code === "o") italic = true;
    else if (code === "n") underline = true;
    else if (code === "m") strike = true;
    else if (code === "r") { color = null; bold = italic = underline = strike = false; }
    else push("§"); // not a real code: keep the character visible
    push(rest);
  });
}
function renderLogLine(line) {
  const match = line.match(/^(\d{2}:\d{2}:\d{2}) \[([A-Z]+)\] ?(.*)$/);
  const span = el("span", "log-line");
  if (match) {
    const time = el("span", "log-time", match[1] + "  ");
    const level = el("span", "log-" + match[2], "[" + match[2] + "] ");
    const msg = el("span", "log-msg");
    appendMcText(msg, match[3]);
    span.append(time, level, msg);
  } else {
    span.classList.add("log-msg");
    appendMcText(span, line);
  }
  return span;
}
async function refreshLogs() {
  let data;
  try { data = await API.get("/api/logs"); } catch { return; }
  const lines = data.lines || [];
  if (JSON.stringify(lines.slice(-3)) === JSON.stringify(consoleTail.slice(-3)) && lines.length === consoleTail.length) {
    renderConsole();
    return;
  }
  consoleTail = lines;
  renderConsole();
}
$("#console-clear").addEventListener("click", () => { consoleBox.innerHTML = ""; });
$("#console-level").addEventListener("change", renderConsole);

const cmdHistory = store.get("wb_cmd_hist", []);
let cmdIndex = cmdHistory.length;
function sendConsoleCommand() {
  const input = $("#console-input");
  const command = input.value.trim();
  if (!command) return;
  input.value = "";
  cmdHistory.push(command);
  while (cmdHistory.length > 50) cmdHistory.shift();
  cmdIndex = cmdHistory.length;
  store.set("wb_cmd_hist", cmdHistory);
  API.post("/api/command", { command })
    .then(() => toast(t("common.commandSent")))
    .catch(() => toast(t("common.failed")));
}
$("#console-send").addEventListener("click", sendConsoleCommand);
$("#console-input").addEventListener("keydown", (e) => {
  if (e.key === "Enter") sendConsoleCommand();
  if (e.key === "ArrowUp" && cmdIndex > 0) { cmdIndex--; $("#console-input").value = cmdHistory[cmdIndex] || ""; }
  if (e.key === "ArrowDown") {
    if (cmdIndex < cmdHistory.length - 1) { cmdIndex++; $("#console-input").value = cmdHistory[cmdIndex] || ""; }
    else { cmdIndex = cmdHistory.length; $("#console-input").value = ""; }
  }
});

/* ---------------- layout editor ---------------- */
let editing = false;
const LAYOUT_KEY = "wb_layout_v1";
function savedLayout() { return store.get(LAYOUT_KEY, { hidden: [] }); }
function saveLayout() {
  const layout = { order: {}, hidden: [...currentHidden] };
  $$("[data-editor]").forEach((group) => {
    const ids = [...group.children].filter((c) => c.dataset && c.dataset.card)
      .map((c) => c.dataset.card);
    if (ids.length) layout.order[group.dataset.editor] = ids;
  });
  store.set(LAYOUT_KEY, layout);
}
let currentHidden = savedLayout().hidden || [];
function applySavedLayout() {
  const layout = savedLayout();
  currentHidden = layout.hidden || [];
  // order
  Object.entries(layout.order || {}).forEach(([group, ids]) => {
    const container = document.querySelector(`[data-editor="${group}"]`);
    if (!container) return;
    ids.forEach((id) => {
      const card = container.querySelector(`[data-card="${CSS.escape(id)}"]`);
      if (card) container.appendChild(card);
    });
  });
  // hidden
  $$("[data-card]").forEach((card) => {
    card.classList.toggle("ghosted", false);
    card.classList.toggle("hidden", currentHidden.includes(card.dataset.card));
  });
  renderHiddenChips();
  $("#restore-btn").classList.toggle("hidden", currentHidden.length === 0);
}
let dragCard = null;
function attachEditorTools(card) {
  card.draggable = editing;
  if (!card.querySelector(".card-hide")) {
    const hide = el("button", "card-hide", "×");
    hide.type = "button";
    card.appendChild(hide);
  }
  card.addEventListener("dragstart", editorDragStart);
  card.addEventListener("dragover", editorDragOver);
  card.addEventListener("drop", editorDrop);
}
function editorDragStart(event) {
  if (!editing) return;
  dragCard = event.currentTarget;
  event.dataTransfer.effectAllowed = "move";
}
function editorDragOver(event) {
  if (!editing || !dragCard || event.currentTarget === dragCard) return;
  event.preventDefault();
  event.currentTarget.classList.add("drag-over");
}
function editorDrop(event) {
  if (!editing || !dragCard) return;
  event.preventDefault();
  const target = event.currentTarget;
  target.classList.remove("drag-over");
  if (target.dataset.editor !== dragCard.dataset.editor) return;
  const parent = target.parentElement;
  const rect = target.getBoundingClientRect();
  const after = (event.clientX - rect.left) / rect.width > 0.5;
  parent.insertBefore(dragCard, after ? target.nextSibling : target);
  dragCard = null;
  saveLayout();
}
function toggleEditor(force) {
  editing = force !== undefined ? force : !editing;
  document.body.classList.toggle("editing", editing);
  $$("[data-card]").forEach((card) => { card.draggable = editing; });
  const btn = $("#edit-btn");
  if (btn) btn.textContent = editing ? t("settings.done") : t("settings.editLayout");
  $("#edit-banner").classList.toggle("hidden", !editing);
  if (!editing) saveLayout();
}
document.addEventListener("click", (event) => {
  if (!event.target.classList || !event.target.classList.contains("card-hide")) return;
  const card = event.target.closest("[data-card]");
  if (!card) return;
  card.classList.add("hidden");
  if (!currentHidden.includes(card.dataset.card)) currentHidden.push(card.dataset.card);
  saveLayout();
  $("#restore-btn").classList.toggle("hidden", currentHidden.length === 0);
  renderHiddenChips();
});
$("#restore-btn").addEventListener("click", () => { switchView("settings"); renderHiddenChips(); });
function restoreCard(name) {
  currentHidden = currentHidden.filter((id) => id !== name);
  const card = document.querySelector(`[data-card="${CSS.escape(name)}"]`);
  if (card) card.classList.remove("hidden");
  saveLayout();
  renderHiddenChips();
  $("#restore-btn").classList.toggle("hidden", currentHidden.length === 0);
}
function renderHiddenChips() {
  const box = $("#hidden-chips");
  if (!box) return;
  box.innerHTML = "";
  currentHidden.forEach((name) => {
    const chip = el("button", "chip");
    chip.appendChild(icon("eye"));
    chip.appendChild(el("span", "", name));
    chip.addEventListener("click", () => restoreCard(name));
    box.appendChild(chip);
  });
  box.classList.toggle("hidden", currentHidden.length === 0);
}

/* ---------------- header buttons ---------------- */
$("#theme-btn").addEventListener("click", () => {
  applyTheme(document.body.dataset.theme === "dark" ? "light" : "dark");
});
$("#lang-btn").addEventListener("click", () => {
  LANG = LANG === "fa" ? "en" : "fa";
  store.set("wb_lang", LANG);
  applyI18n();
  if (lastStatus) renderStatus(lastStatus);
  if (currentView === "players") refreshPlayers();
  if (editing) toggleEditor(true);
});
$$("#theme-seg button").forEach((b) => b.addEventListener("click", () => applyTheme(b.dataset.theme)));
$$(".swatch").forEach((sw) => sw.addEventListener("click", () => applyAccent(sw.dataset.accent)));
$("#glass-range").addEventListener("input", (e) => applyGlass(Number(e.target.value)));
$("#edit-btn").addEventListener("click", () => toggleEditor());
$("#reset-layout-btn").addEventListener("click", () => {
  store.set(LAYOUT_KEY, { hidden: [] });
  currentHidden = [];
  $$("[data-card]").forEach((card) => card.classList.remove("hidden"));
  renderHiddenChips();
  $("#restore-btn").classList.add("hidden");
  toast(t("settings.layoutReset"));
});

/* ---------------- polling ---------------- */
let pollTimer = null;
function refreshAll() {
  pollStatus();
  pollHistory();
  refreshLogs();
}
function startPolling() {
  clearInterval(pollTimer);
  pollTimer = setInterval(() => {
    if (document.hidden) return;
    refreshAll();
    if (currentView === "players") refreshPlayers();
  }, 5000);
}
async function pollStatus() {
  try {
    renderStatus(await API.get("/api/status"));
  } catch {
    $("#health-dot").classList.toggle("bad", true);
  }
}
async function pollHistory() {
  let points;
  try { points = await API.get("/api/history"); } catch { return; }
  if (!points.length) return;
  if (!charts.players) {
    charts.players = makeChart("chart-players", "players", "#4da3ff");
    charts.memory = makeChart("chart-memory", "memory", "#39d3d8");
    charts.cpu = makeChart("chart-cpu", "cpu", "#7c8cff");
  }
  fillChart(charts.players, points, (p) => p.players, (p) => fmtClock(p.time));
  fillChart(charts.memory, points, (p) => Math.round(p.memoryUsed / MB),
    (p) => fmtClock(p.time));
  fillChart(charts.cpu, points, (p) => p.cpu, (p) => fmtClock(p.time));
}

/* ---------------- boot ---------------- */
(async function boot() {
  document.body.dataset.theme = store.get("wb_theme", "dark");
  applyAccent(store.get("wb_accent", "blue"));
  applyGlass(store.get("wb_glass", 70));
  applyI18n();
  applySavedLayout();
  switchView("overview");
  $$("#theme-seg button").forEach((b) => b.classList.toggle("active", b.dataset.theme === document.body.dataset.theme));
  $("#glass-range").value = String(store.get("wb_glass", 70));

  // editor: every card gets drag handlers and a hide button
  $$("[data-card]").forEach((card) => attachEditorTools(card));

  // auto-login with a stored token; the login screen stays until a token verifies.
  // Polling only starts after a successful login - pre-auth polls would spam the
  // server's brute-force counter with tokenless requests.
  const stored = store.get("wb_token", "");
  if (stored) tryLogin(stored).then((ok) => ok && showApp()).catch(() => {});

  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && editing) toggleEditor(false);
  });

  startPolling();
})();

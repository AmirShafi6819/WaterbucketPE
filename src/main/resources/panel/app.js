/*
 * WaterbucketPE panel frontend. Vanilla JS + Chart.js (bundled chart.umd.min.js,
 * MIT license - https://www.chartjs.org). No external requests: everything is
 * served by the proxy itself.
 */
"use strict";

const API = {
  headers() {
    return { "X-Panel-Token": localStorage.getItem("wb_panel_token") || "" };
  },
  async get(path) {
    const res = await fetch(path, { headers: this.headers() });
    if (res.status === 401) { showLogin(); throw new Error("unauthorized"); }
    if (!res.ok) throw new Error(path + " -> " + res.status);
    return res.json();
  },
  async post(path, body) {
    const res = await fetch(path, {
      method: "POST",
      headers: Object.assign({ "Content-Type": "application/json" }, this.headers()),
      body: JSON.stringify(body || {}),
    });
    if (res.status === 401) { showLogin(); throw new Error("unauthorized"); }
    if (!res.ok) throw new Error(path + " -> " + res.status);
    return res.json();
  },
};

/* ---------------- login ---------------- */
const loginOverlay = document.getElementById("login");
function showLogin() { loginOverlay.classList.remove("hidden"); }
function hideLogin() { loginOverlay.classList.add("hidden"); }

async function tryLogin(token) {
  const loginError = document.getElementById("login-error");
  try {
    const res = await fetch("/api/status", { headers: { "X-Panel-Token": token } });
    if (res.ok) {
      localStorage.setItem("wb_panel_token", token);
      hideLogin();
      boot();
      return true;
    }
    if (res.status === 401) {
      loginError.textContent = "Invalid token";
    } else {
      loginError.textContent = "Proxy answered with HTTP " + res.status;
    }
    return false;
  } catch (e) {
    loginError.textContent = "Proxy unreachable - is it still running?";
    return false;
  }
}

const loginBtn = document.getElementById("login-btn");
loginBtn.addEventListener("click", async () => {
  loginBtn.disabled = true;
  loginBtn.textContent = "Checking…";
  try {
    const ok = await tryLogin(document.getElementById("token-input").value.trim());
    document.getElementById("login-error").classList.toggle("hidden", ok);
  } finally {
    loginBtn.disabled = false;
    loginBtn.textContent = "Open dashboard";
  }
});
document.getElementById("token-input").addEventListener("keydown", (e) => {
  if (e.key === "Enter") document.getElementById("login-btn").click();
});

/* ---------------- tabs ---------------- */
document.querySelectorAll(".tab").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".tab-page").forEach((p) => p.classList.remove("active"));
    btn.classList.add("active");
    document.getElementById("tab-" + btn.dataset.tab).classList.add("active");
    if (btn.dataset.tab === "console") scrollConsole();
  });
});

/* ---------------- charts ---------------- */
const gridColor = "rgba(125, 138, 153, 0.18)";
const chartDefaults = {
  responsive: true,
  maintainAspectRatio: false,
  animation: false,
  plugins: { legend: { display: false } },
  scales: {
    x: { ticks: { color: "#7d8a99", maxTicksLimit: 8 }, grid: { color: gridColor } },
    y: { ticks: { color: "#7d8a99" }, grid: { color: gridColor }, beginAtZero: true },
  },
};
const PALETTE = ["#4fc3f7", "#ffb74d", "#66bb6a", "#ef5350", "#ba68c8", "#26c6da", "#ffca28", "#ec407a"];

function lineChart(id, color, yFormat) {
  const ctx = document.getElementById(id).getContext("2d");
  return new Chart(ctx, {
    type: "line",
    data: {
      labels: [],
      datasets: [{ data: [], borderColor: color, backgroundColor: color + "22", fill: true, tension: 0.3, pointRadius: 0, borderWidth: 2 }],
    },
    options: Object.assign({}, chartDefaults, {
      scales: Object.assign({}, chartDefaults.scales, { y: Object.assign({}, chartDefaults.scales.y, { ticks: { color: "#7d8a99", callback: yFormat } }) }),
    }),
  });
}

const playersChart = lineChart("chart-players", "#4fc3f7", (v) => v);
const memoryChart = lineChart("chart-memory", "#66bb6a", (v) => (v / 1048576).toFixed(0) + " MB");
const cpuChart = lineChart("chart-cpu", "#ffb74d", (v) => v + "%");

const perServerChart = new Chart(document.getElementById("chart-perserver").getContext("2d"), {
  type: "bar",
  data: { labels: [], datasets: [{ data: [], backgroundColor: PALETTE, borderRadius: 6 }] },
  options: Object.assign({}, chartDefaults, { plugins: { legend: { display: false } } }),
});

const uptimeChart = new Chart(document.getElementById("chart-uptime").getContext("2d"), {
  type: "bar",
  data: { labels: [], datasets: [{ data: [], backgroundColor: "#4fc3f7", borderRadius: 6 }] },
  options: Object.assign({}, chartDefaults, {
    indexAxis: "y",
    scales: Object.assign({}, chartDefaults.scales, {
      y: Object.assign({}, chartDefaults.scales.y, { beginAtZero: false }),
      x: Object.assign({}, chartDefaults.scales.x, { ticks: { color: "#7d8a99", callback: (v) => (v / 60000).toFixed(0) + " min" } }),
    }),
  }),
});

/* ---------------- helpers ---------------- */
function fmtBytes(bytes) {
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(2) + " GB";
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + " MB";
  return (bytes / 1024).toFixed(0) + " KB";
}
function fmtUptime(ms) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s";
}
function fmtTime(ts) {
  return new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}
function pill(text, good) {
  return `<span class="pill ${good ? "good" : "bad"}">${text}</span>`;
}

/* ---------------- connection state ---------------- */
const connBanner = document.getElementById("conn-banner");
function setConnectionLost(lost) {
  connBanner.classList.toggle("hidden", !lost);
}

/* ---------------- polling ---------------- */
async function refreshStatus() {
  try {
    const s = await API.get("/api/status");
    setConnectionLost(false);
    document.getElementById("proxy-name").textContent = s.proxyName;
    document.getElementById("proxy-version").textContent = "v" + s.proxyVersion + " · java " + s.javaVersion;
    document.getElementById("stat-cpu").textContent = s.cpu + "%";
    document.getElementById("stat-mem").textContent = fmtBytes(s.memoryUsed) + " / " + fmtBytes(s.memoryMax);
    document.getElementById("stat-threads").textContent = s.threads;
    document.getElementById("stat-players").textContent = s.players;
    document.getElementById("stat-servers").textContent = s.servers.length;
    document.getElementById("stat-uptime").textContent = fmtUptime(s.uptimeMs);
    document.getElementById("stat-updated").textContent = fmtTime(Date.now());
    document.getElementById("bind-info").textContent = location.host;

    const rows = s.servers.map((srv) => {
      const online = srv.online;
      return `<tr>
        <td><strong>${escapeHtml(srv.name)}</strong></td><td class="muted">${escapeHtml(srv.address)}</td>
        <td class="muted">${escapeHtml(srv.type)}</td>
        <td>${srv.players}</td>
        <td>${srv.pingMs >= 0 ? srv.pingMs + " ms" : "–"}</td>
        <td>${pill(online ? "online" : "down", online)}</td>
      </tr>`;
    });
    document.querySelector("#server-status-table tbody").innerHTML =
      rows.length ? rows.join("") : emptyRow(6, "No servers registered in the config");

    const upLabels = [], upData = [];
    s.servers.forEach((srv) => {
      upLabels.push(srv.name);
      upData.push(srv.upMs > 0 ? srv.upMs : 0);
    });
    uptimeChart.data.labels = upLabels;
    uptimeChart.data.datasets[0].data = upData;
    uptimeChart.update();

    renderPlayers(await API.get("/api/players"));
    renderServersTable(s.servers);
  } catch (e) {
    if (e.message !== "unauthorized") {
      setConnectionLost(true);
      console.warn(e);
    }
  }
}

async function refreshHistory() {
  try {
    const history = await API.get("/api/history");
    const labels = history.map((p) => fmtTime(p.time));
    playersChart.data.labels = labels;
    playersChart.data.datasets[0].data = history.map((p) => p.players);
    playersChart.update();
    memoryChart.data.labels = labels;
    memoryChart.data.datasets[0].data = history.map((p) => p.memoryUsed);
    memoryChart.update();
    cpuChart.data.labels = labels;
    cpuChart.data.datasets[0].data = history.map((p) => p.cpu);
    cpuChart.update();

    const perServer = history.length ? history[history.length - 1].perServer : {};
    perServerChart.data.labels = Object.keys(perServer);
    perServerChart.data.datasets[0].data = Object.values(perServer);
    perServerChart.update();
  } catch (e) {
    if (e.message !== "unauthorized") console.warn(e);
  }
}

function emptyRow(columns, text) {
  return `<tr><td colspan="${columns}" class="muted empty-row">${escapeHtml(text)}</td></tr>`;
}

function renderPlayers(payload) {
  const players = payload.players || [];
  document.getElementById("player-count").textContent = players.length;
  const rows = players.map((p) => `<tr>
    <td><strong>${escapeHtml(p.name)}</strong></td>
    <td>${p.server == null ? "…" : escapeHtml(p.server)}</td>
    <td class="muted">${escapeHtml(p.address)}</td>
    <td class="muted">${p.protocol}</td>
    <td class="muted">${escapeHtml(p.xuid || "")}</td>
    <td><div class="row-actions">
      <button class="btn-warn" data-kick="${escapeHtml(p.name)}">Kick</button>
      <button data-transfer="${escapeHtml(p.name)}">Transfer</button>
    </div></td>
  </tr>`);
  document.querySelector("#players-table tbody").innerHTML =
    rows.length ? rows.join("") : emptyRow(6, "No players online");
}

function renderServersTable(servers) {
  const rows = servers.map((srv) => `<tr>
    <td><strong>${escapeHtml(srv.name)}</strong></td>
    <td class="muted">${escapeHtml(srv.address)}</td>
    <td class="muted">${escapeHtml(srv.type)}</td>
    <td>${srv.players}</td>
    <td>${srv.pingMs >= 0 ? srv.pingMs + " ms" : "–"}</td>
    <td><div class="row-actions"><button data-ping="${escapeHtml(srv.name)}">Ping</button></div></td>
  </tr>`);
  document.querySelector("#servers-table tbody").innerHTML =
    rows.length ? rows.join("") : emptyRow(6, "No servers registered in the config");
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

document.addEventListener("click", async (event) => {
  const target = event.target;
  const kick = target.getAttribute && target.getAttribute("data-kick");
  const transfer = target.getAttribute && target.getAttribute("data-transfer");
  const ping = target.getAttribute && target.getAttribute("data-ping");
  try {
    if (kick) {
      if (confirm("Kick " + kick + "?")) {
        target.disabled = true;
        try {
          await API.post("/api/players/" + encodeURIComponent(kick) + "/kick", { reason: "Kicked from the panel" });
        } finally {
          target.disabled = false;
        }
      }
      refreshStatus();
    } else if (transfer) {
      const server = prompt("Transfer " + transfer + " to which server?");
      if (server) {
        target.disabled = true;
        try {
          await API.post("/api/players/" + encodeURIComponent(transfer) + "/transfer", { server });
        } finally {
          target.disabled = false;
        }
      }
      refreshStatus();
    } else if (ping) {
      target.disabled = true;
      target.textContent = "Pinging…";
      try {
        await API.post("/api/servers/ping/" + encodeURIComponent(ping));
        setTimeout(refreshStatus, 1500);
      } finally {
        target.disabled = false;
        target.textContent = "Ping";
      }
    }
  } catch (e) {
    if (e.message !== "unauthorized") {
      setConnectionLost(true);
      console.warn(e);
    }
  }
});

/* ---------------- console ---------------- */
const consoleOutput = document.getElementById("console-output");
let renderedLogLines = 0;
let lastLogPayload = [];

function appendLog(line) {
  const div = document.createElement("div");
  const level = line.includes("[ERROR]") ? "log-error" : line.includes("[WARN]") ? "log-warn" : "log-info";
  div.className = level;
  div.textContent = line;
  consoleOutput.appendChild(div);
  while (consoleOutput.childElementCount > 400) consoleOutput.removeChild(consoleOutput.firstChild);
}
function scrollConsole() { consoleOutput.scrollTop = consoleOutput.scrollHeight; }

async function refreshLogs() {
  try {
    const payload = await API.get("/api/logs");
    const lines = payload.lines || [];
    // Append only the new tail when the already rendered prefix still matches: re-rendering
    // the whole 400-line tail three times a second caused visible console churn and wasted
    // DOM work. A mismatch (rotation, server restart) falls back to a full rebuild.
    if (renderedLogLines > 0 && renderedLogLines <= lastLogPayload.length && lines.length >= renderedLogLines
        && lastLogPayload[renderedLogLines - 1] === lines[renderedLogLines - 1]) {
      const fresh = lines.slice(renderedLogLines);
      fresh.forEach(appendLog);
      renderedLogLines = lines.length;
    } else {
      consoleOutput.replaceChildren();
      if (!lines.length) appendLog("(no log lines yet)");
      lines.forEach(appendLog);
      renderedLogLines = lines.length;
    }
    lastLogPayload = lines;
    scrollConsole();
  } catch (e) {
    if (e.message !== "unauthorized") console.warn(e);
  }
}

async function sendCommand() {
  const input = document.getElementById("console-command");
  const command = input.value.trim();
  if (!command) return;
  input.value = "";
  appendLog("> " + command);
  try {
    await API.post("/api/command", { command });
  } catch (e) {
    if (e.message !== "unauthorized") appendLog("[panel] command failed: " + e.message);
  }
  setTimeout(refreshLogs, 350);
}
document.getElementById("console-send").addEventListener("click", sendCommand);
document.getElementById("console-command").addEventListener("keydown", (e) => {
  if (e.key === "Enter") sendCommand();
});

/* ---------------- boot ---------------- */
let booted = false;
function boot() {
  if (booted) return;
  booted = true;
  refreshStatus();
  refreshHistory();
  refreshLogs();
  setInterval(refreshStatus, 3000);
  setInterval(refreshHistory, 5000);
  setInterval(refreshLogs, 3000);
}

(function init() {
  const token = localStorage.getItem("wb_panel_token");
  if (token) {
    tryLogin(token).then((ok) => { if (!ok) showLogin(); });
  } else {
    showLogin();
  }
  document.getElementById("stat-clock").textContent = fmtTime(Date.now());
  setInterval(() => document.getElementById("stat-clock").textContent = fmtTime(Date.now()), 1000);
})();

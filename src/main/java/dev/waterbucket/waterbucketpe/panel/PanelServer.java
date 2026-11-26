/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.EventLoops;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.network.serverinfo.BedrockServerInfo;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.utils.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Future;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.BedrockPong;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * The local web panel: a small HTTP API plus a static dashboard served straight from the proxy
 * process. Shows live players, downstream servers, average charts and a console, so operators
 * get Waterdog-equivalent data plus history without installing anything.
 * <p>
 * Security model:
 * <ul>
 *     <li>binds to the loopback interface by default ({@code panel_bind_address});</li>
 *     <li>every {@code /api/*} call requires the panel token ({@code Authorization: Bearer},
 *     {@code X-Panel-Token} or {@code ?token=});</li>
 *     <li>when no token is configured, a 192-bit random token is generated, stored with
 *     owner-only permissions in {@code panel_token.txt} and never logged in full;</li>
 *     <li>per-IP rate limiting protects the API from hammering;</li>
 *     <li>only embedded static resources are served, so there is no path traversal surface;</li>
 *     <li>responses carry nosniff/DENY/no-referrer hardening headers.</li>
 * </ul>
 */
@Log4j2
public class PanelServer {

    public static final String NAME = "panel-server";
    private static final int MAX_REQUEST_BYTES = 512 * 1024;
    private static final int RATE_LIMIT_REQUESTS = 80;
    private static final long RATE_LIMIT_WINDOW_MS = TimeUnit.SECONDS.toMillis(10);
    /**
     * Brute-force protection: an IP that fails this many auths inside the window loses access
     * to the API for the lockout duration, even with a valid token afterwards.
     */
    private static final int AUTH_FAILURE_LIMIT = 10;
    private static final long AUTH_FAILURE_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long AUTH_LOCKOUT_MS = TimeUnit.MINUTES.toMillis(15);
    /**
     * Upper bound for the rate-limit bucket map. Scanner fleets hit the panel from many source
     * IPs; without a bound the map grew by one entry per IP and never shrank. When the bound is
     * reached, expired buckets are evicted on the spot.
     */
    private static final int RATE_BUCKET_LIMIT = 4096;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final java.util.regex.Pattern WOFF2_PATTERN =
            java.util.regex.Pattern.compile("/fonts/[a-z0-9._-]+\\.woff2");

    private final ProxyServer proxy;
    private final PanelMetrics metrics;
    private final String token;
    private final ExecutorService commandExecutor;

    private final Map<String, long[]> rateBuckets = new ConcurrentHashMap<>();
    /**
     * Per-IP auth failure tracking: ip -> {consecutive failures, window start, locked-until}.
     * Bounded together with the rate buckets; only holds IPs that actually failed.
     */
    private final Map<String, long[]> authFailures = new ConcurrentHashMap<>();
    /**
     * Immutable classpath panel assets, decompressed once. Bounded to the four fixed resources
     * the panel serves.
     */
    private final Map<String, byte[]> staticCache = new ConcurrentHashMap<>(4);
    private Channel serverChannel;

    public PanelServer(ProxyServer proxy, String configuredToken) {
        this.proxy = proxy;
        this.metrics = new PanelMetrics(proxy);
        this.commandExecutor = Executors.newSingleThreadExecutor(ThreadFactoryBuilder.builder()
                .format("PanelCommand Executor - #%d").daemon(true).build());
        this.token = this.resolveToken(configuredToken);
    }

    private String resolveToken(String configuredToken) {
        if (configuredToken != null && !configuredToken.isBlank()) {
            return configuredToken.trim();
        }
        Path tokenFile = this.proxy.getDataPath().resolve("panel_token.txt");
        try {
            if (Files.exists(tokenFile)) {
                String stored = Files.readString(tokenFile).trim();
                if (!stored.isEmpty()) {
                    return stored;
                }
            }
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Files.writeString(tokenFile, generated + "\n");
            try {
                Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows: the file inherits restrictive default permissions
            }
            this.proxy.getLogger().info("Generated web panel token: {}", tokenFile);
            return generated;
        } catch (IOException e) {
            this.proxy.getLogger().error("Unable to persist the web panel token", e);
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }

    /**
     * Constant-time token comparison; equals() leaks the match position through timing.
     */
    private static boolean tokenEquals(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    public void start(String bindAddress, int port) throws InterruptedException {
        // Use the same transport (epoll/kqueue/nio) as the proxy; a Nio channel on an epoll
        // group would refuse to register
        Class<? extends io.netty.channel.socket.ServerSocketChannel> serverChannelClass =
                EventLoops.getChannelType().serverSocketChannel();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(this.proxy.getBossEventLoopGroup(), this.proxy.getWorkerEventLoopGroup())
                .channel(serverChannelClass)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES))
                                .addLast(new PanelHttpHandler());
                    }
                });
        this.serverChannel = bootstrap.bind(bindAddress, port).sync().channel();
        // Metrics sampling starts only once the panel is actually listening; starting it before
        // the bind made a failed bind leak the sampler task and the log-tail appender.
        this.metrics.start();
        InetSocketAddress bound = (InetSocketAddress) this.serverChannel.localAddress();
        this.proxy.getLogger().info("Web panel listening on http://{}:{} (token file: {})", bound.getHostString(),
                bound.getPort(), this.proxy.getDataPath().resolve("panel_token.txt").getFileName());
        if (!bound.getAddress().isLoopbackAddress()) {
            // Exposing the panel beyond loopback is a deliberate choice; make sure it is one
            this.proxy.getLogger().warning("The web panel is reachable from other machines on {} - keep the panel "
                    + "token secret and prefer a reverse proxy with TLS in front of it", bound);
        }
        if (this.token.length() < 16) {
            this.proxy.getLogger().warning("The configured panel token is short; set panel_token or panel_token.txt "
                    + "to a long random value so it cannot be guessed");
        }
    }

    public void shutdown() {
        if (this.serverChannel != null) {
            this.serverChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        this.metrics.stop();
        this.commandExecutor.shutdownNow();
    }

    /**
     * Token via Authorization: Bearer or X-Panel-Token headers only. The token is deliberately
     * never accepted as a URL query parameter: URLs land in browser history, access logs and
     * bookmarks, which would quietly turn a secret into a shared link.
     */
    private boolean authorized(FullHttpRequest request, QueryStringDecoder query, String ip) {
        String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        String provided = null;
        if (header != null && header.startsWith("Bearer ")) {
            provided = header.substring(7).trim();
        } else {
            String panelToken = request.headers().get("X-Panel-Token");
            if (panelToken != null) {
                provided = panelToken.trim();
            }
        }

        long[] state = this.authFailures.get(ip);
        long now = System.currentTimeMillis();
        if (state != null && state[2] > now) {
            // Locked out: refuse before any comparison, even a valid token buys nothing
            return false;
        }

        if (provided != null && tokenEquals(this.token, provided)) {
            if (state != null && state[0] > 0) {
                this.authFailures.remove(ip);
            }
            return true;
        }

        // Only a presented-but-wrong token counts toward the brute-force lockout: tokenless
        // probes (a browser checking the page before login) must not trigger it.
        if (provided == null) {
            return false;
        }
        this.authFailures.compute(ip, (key, streak) -> {
            if (streak == null || now - streak[1] > AUTH_FAILURE_WINDOW_MS) {
                return new long[]{1, now, 0};
            }
            streak[0]++;
            if (streak[0] >= AUTH_FAILURE_LIMIT) {
                streak[2] = now + AUTH_LOCKOUT_MS;
            }
            return streak;
        });
        log.warn("Panel auth failed for {} (wrong token)", ip);
        return false;
    }

    /**
     * Keeps the auth-failure map bounded by sweeping expired streaks once the limit is hit.
     */
    private void sweepAuthFailures(long now) {
        if (this.authFailures.size() > RATE_BUCKET_LIMIT) {
            this.authFailures.values().removeIf(streak -> now - streak[1] > AUTH_FAILURE_WINDOW_MS
                    && streak[2] <= now);
        }
    }

    private boolean rateLimited(String ip) {
        long now = System.currentTimeMillis();
        long[] bucket = this.rateBuckets.compute(ip, (key, state) -> {
            if (state == null || now - state[1] > RATE_LIMIT_WINDOW_MS) {
                return new long[]{1, now};
            }
            state[0]++;
            return state;
        });
        if (this.rateBuckets.size() > RATE_BUCKET_LIMIT) {
            // Scanner fleets can source thousands of IPs; drop the expired windows so the map
            // stays bounded. Active windows survive the sweep untouched.
            this.rateBuckets.values().removeIf(state -> now - state[1] > RATE_LIMIT_WINDOW_MS);
        }
        return bucket[0] > RATE_LIMIT_REQUESTS;
    }

    private void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                         String contentType, byte[] body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(body));
        // HTML/CSS/JS must never be cached across restarts: the panel code ships inside the
        // proxy jar, so a cached stylesheet from the previous build renders the new HTML
        // against old rules and visually breaks the page (the "giant black icon" report).
        // Only long-lived immutable assets (fonts, logo) keep a TTL.
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .set(HttpHeaderNames.CONTENT_LENGTH, body.length)
                .set(HttpHeaderNames.CACHE_CONTROL, contentType.contains("font")
                        ? "max-age=86400" : contentType.contains("image")
                        ? "max-age=3600" : "no-store")
                .set("X-Content-Type-Options", "nosniff")
                .set("X-Frame-Options", "DENY")
                .set("Referrer-Policy", "no-referrer")
                .set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                .set("Cross-Origin-Opener-Policy", "same-origin")
                // The page only ever loads same-origin assets; the CSP makes any injected
                // script/style inert on top of the escaping the frontend already applies.
                .set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; "
                        + "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; "
                        + "font-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }
    }

    private void respondJson(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, Object payload) {
        this.respond(ctx, request, status, "application/json; charset=utf-8",
                GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
    }

    private void respondError(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, String message) {
        this.respondJson(ctx, request, status, Map.of("error", message));
    }

    private class PanelHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            QueryStringDecoder query = new QueryStringDecoder(request.uri());
            String path = query.path().toLowerCase(Locale.ROOT);
            String ip = ctx.channel().remoteAddress() instanceof InetSocketAddress address
                    ? address.getAddress().getHostAddress() : "unknown";

            if (path.startsWith("/api/")) {
                if (PanelServer.this.rateLimited(ip)) {
                    PanelServer.this.respondError(ctx, request, HttpResponseStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
                    return;
                }
                if (!PanelServer.this.authorized(request, query, ip)) {
                    PanelServer.this.sweepAuthFailures(System.currentTimeMillis());
                    PanelServer.this.respondError(ctx, request, HttpResponseStatus.UNAUTHORIZED, "Invalid or missing panel token");
                    return;
                }
                this.handleApi(ctx, request, path);
                return;
            }
            this.handleStatic(ctx, request, path);
        }

        /**
         * Browsers abort keep-alive requests when a panel tab closes or refreshes mid-poll;
         * on Windows that surfaces as "connection was aborted by the software in your host
         * machine". Without this handler the exception ran to the tail of the pipeline and
         * logged a full stack trace per aborted poll. Benign transport noise is swallowed,
         * everything unexpected still gets logged.
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof IOException || cause instanceof io.netty.handler.timeout.ReadTimeoutException) {
                ctx.close();
                return;
            }
            PanelServer.this.proxy.getLogger().warning("Panel request failed from " + ctx.channel().remoteAddress(), cause);
            ctx.close();
        }

        private void handleApi(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
            switch (path) {
                case "/api/status" -> PanelServer.this.respondJson(ctx, request, HttpResponseStatus.OK,
                        PanelServer.this.metrics.status());
                case "/api/history" -> PanelServer.this.respondJson(ctx, request, HttpResponseStatus.OK,
                        PanelServer.this.metrics.history());
                case "/api/players" -> PanelServer.this.respondJson(ctx, request, HttpResponseStatus.OK,
                        PanelServer.this.playersPayload());
                case "/api/servers" -> PanelServer.this.respondJson(ctx, request, HttpResponseStatus.OK,
                        PanelServer.this.metrics.serversSnapshot());
                case "/api/logs" -> PanelServer.this.respondJson(ctx, request, HttpResponseStatus.OK,
                        Map.of("lines", PanelServer.this.metrics.recentLogs()));
                case "/api/command" -> {
                    if (request.method() != HttpMethod.POST) {
                        PanelServer.this.respondError(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, "Use POST");
                        return;
                    }
                    PanelServer.this.handleCommand(ctx, request);
                }
                default -> {
                    if (path.startsWith("/api/servers/") && !path.endsWith("/ping") && request.method() == HttpMethod.GET) {
                        String name = extractMiddle(path, "/api/servers/", "");
                        Object detail = PanelServer.this.metrics.serverDetail(name);
                        if (detail == null) {
                            PanelServer.this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Server not registered");
                        } else {
                            PanelServer.this.respondJson(ctx, request, HttpResponseStatus.OK, detail);
                        }
                    } else if (path.startsWith("/api/servers/ping/") && request.method() == HttpMethod.POST) {
                        PanelServer.this.handleServerPing(ctx, request, path);
                    } else {
                        PanelServer.this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Unknown API endpoint");
                    }
                }
            }
        }

        private void handleStatic(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
            String resource;
            if (path.equals("/") || path.equals("/index.html")) {
                resource = "panel/index.html";
            } else if (path.equals("/app.js")) {
                resource = "panel/app.js";
            } else if (path.equals("/style.css")) {
                resource = "panel/style.css";
            } else if (path.equals("/chart.umd.min.js")) {
                resource = "panel/chart.umd.min.js";
            } else if (path.equals("/logo.png")) {
                resource = "panel/logo.png";
            } else if (path.startsWith("/fonts/") && WOFF2_PATTERN.matcher(path).matches()) {
                // Whitelisted font files only; anything else under /fonts/ is a 404
                resource = "panel" + path;
            } else {
                resource = null;
            }
            if (resource == null) {
                PanelServer.this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Not found");
                return;
            }
            byte[] body = PanelServer.this.staticCache.computeIfAbsent(resource, PanelServer::readStaticResource);
            if (body == null) {
                PanelServer.this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Not found");
                return;
            }
            // type comes from the resource (path "/" must serve index.html as HTML,
            // otherwise browsers treat the navigation as a download and abort it)
            String contentType = resource.endsWith(".html") ? "text/html; charset=utf-8"
                    : resource.endsWith(".css") ? "text/css; charset=utf-8"
                    : resource.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : resource.endsWith(".png") ? "image/png"
                    : resource.endsWith(".woff2") ? "font/woff2"
                    : "application/octet-stream";
            PanelServer.this.respond(ctx, request, HttpResponseStatus.OK, contentType, body);
        }
    }

    /**
     * Reads a panel asset from the classpath once; null when absent or unreadable.
     */
    private static byte[] readStaticResource(String resource) {
        try (InputStream stream = PanelServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to read panel resource {}", resource, e);
            return null;
        }
    }

    private Map<String, Object> playersPayload() {
        List<Map<String, Object>> players = new ArrayList<>();
        for (ProxiedPlayer player : this.proxy.getPlayerManager().getPlayers().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", player.getName());
            entry.put("xuid", player.getLoginData().getXuid());
            entry.put("address", player.getAddress().getAddress().getHostAddress());
            entry.put("protocol", player.getProtocol().getProtocol());
            ServerInfo server = player.getServerInfo();
            entry.put("server", server == null ? null : server.getServerName());
            players.add(entry);
        }
        return Map.of("players", players);
    }

    private void handleCommand(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        Map<?, ?> payload = parseBody(body);
        Object command = payload == null ? null : payload.get("command");
        if (!(command instanceof String text) || text.isBlank()) {
            this.respondError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing 'command'");
            return;
        }
        // Console commands run away from the event loop, exactly like the terminal console does.
        // The command goes through the tick scheduler, so an "end" command never runs the proxy
        // shutdown on this panel executor (which its own shutdownNow() would interrupt).
        this.commandExecutor.execute(() ->
                this.proxy.getScheduler().scheduleTask(() -> {
                    try {
                        this.proxy.dispatchCommand(this.proxy.getConsoleSender(), text.trim());
                    } catch (Throwable t) {
                        this.proxy.getLogger().error("Panel command execution failed", t);
                    }
                }, false));
        this.respondJson(ctx, request, HttpResponseStatus.OK, Map.of("queued", true));
    }

    private void handleServerPing(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        String name = extractMiddle(path, "/api/servers/ping/", "");
        ServerInfo server = this.proxy.getServerInfo(name);
        if (server == null) {
            this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Server not registered");
            return;
        }
        Future<BedrockPong> pong = server.ping(3, TimeUnit.SECONDS);
        long start = System.currentTimeMillis();
        pong.addListener(future -> {
            if (future.cause() != null) {
                this.metrics.recordPing(server.getServerName(), false, -1);
            } else {
                this.metrics.recordPing(server.getServerName(), true, System.currentTimeMillis() - start);
            }
        });
        this.respondJson(ctx, request, HttpResponseStatus.OK, Map.of("pinging", server.getServerName()));
    }

    private static String extractMiddle(String path, String prefix, String suffix) {
        String middle = path.substring(prefix.length());
        if (!suffix.isEmpty() && middle.endsWith(suffix)) {
            middle = middle.substring(0, middle.length() - suffix.length());
        }
        return middle;
    }

    /**
     * Parses a JSON object body. Returns null for a blank body, malformed JSON, or any JSON
     * that is not an object (a bare array or string used to escape as ClassCastException and
     * reset the connection without a response).
     */
    private Map<?, ?> parseBody(String body) {
        try {
            if (body.isBlank()) {
                return Map.of();
            }
            Object parsed = GSON.fromJson(body, Object.class);
            return parsed instanceof Map<?, ?> map ? map : null;
        } catch (Exception e) {
            return null;
        }
    }
}

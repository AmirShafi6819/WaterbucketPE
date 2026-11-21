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
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ProxyServer proxy;
    private final PanelMetrics metrics;
    private final String token;
    private final ExecutorService commandExecutor;

    private final Map<String, long[]> rateBuckets = new ConcurrentHashMap<>();
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

    public void start(String bindAddress, int port) throws InterruptedException {
        this.metrics.start();
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
        InetSocketAddress bound = (InetSocketAddress) this.serverChannel.localAddress();
        this.proxy.getLogger().info("Web panel listening on http://{}:{} (token file: {})", bound.getHostString(),
                bound.getPort(), this.proxy.getDataPath().resolve("panel_token.txt").getFileName());
    }

    public void shutdown() {
        if (this.serverChannel != null) {
            this.serverChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        this.metrics.stop();
        this.commandExecutor.shutdownNow();
    }

    private boolean authorized(FullHttpRequest request, QueryStringDecoder query) {
        String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return this.token.equals(header.substring(7).trim());
        }
        String panelToken = request.headers().get("X-Panel-Token");
        if (panelToken != null) {
            return this.token.equals(panelToken.trim());
        }
        List<String> values = query.parameters().get("token");
        return values != null && !values.isEmpty() && this.token.equals(values.get(0));
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
        return bucket[0] > RATE_LIMIT_REQUESTS;
    }

    private void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                         String contentType, byte[] body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(body));
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .set(HttpHeaderNames.CONTENT_LENGTH, body.length)
                .set(HttpHeaderNames.CACHE_CONTROL, contentType.contains("javascript") || contentType.contains("css")
                        ? "max-age=3600" : "no-store")
                .set("X-Content-Type-Options", "nosniff")
                .set("X-Frame-Options", "DENY")
                .set("Referrer-Policy", "no-referrer");
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
                if (!PanelServer.this.authorized(request, query)) {
                    PanelServer.this.respondError(ctx, request, HttpResponseStatus.UNAUTHORIZED, "Invalid or missing panel token");
                    return;
                }
                this.handleApi(ctx, request, path);
                return;
            }
            this.handleStatic(ctx, request, path);
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
                    if (path.startsWith("/api/players/") && path.endsWith("/kick") && request.method() == HttpMethod.POST) {
                        PanelServer.this.handleKick(ctx, request, path);
                    } else if (path.startsWith("/api/players/") && path.endsWith("/transfer") && request.method() == HttpMethod.POST) {
                        PanelServer.this.handleTransfer(ctx, request, path);
                    } else if (path.startsWith("/api/servers/ping/") && request.method() == HttpMethod.POST) {
                        PanelServer.this.handleServerPing(ctx, request, path);
                    } else {
                        PanelServer.this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Unknown API endpoint");
                    }
                }
            }
        }

        private void handleStatic(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
            String resource = switch (path) {
                case "/", "/index.html" -> "panel/index.html";
                case "/app.js" -> "panel/app.js";
                case "/style.css" -> "panel/style.css";
                case "/chart.umd.min.js" -> "panel/chart.umd.min.js";
                default -> null;
            };
            if (resource == null) {
                PanelServer.this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Not found");
                return;
            }
            try (InputStream stream = PanelServer.class.getClassLoader().getResourceAsStream(resource)) {
                if (stream == null) {
                    PanelServer.this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Not found");
                    return;
                }
                byte[] body = stream.readAllBytes();
                // type comes from the resource (path "/" must serve index.html as HTML,
                // otherwise browsers treat the navigation as a download and abort it)
                String contentType = resource.endsWith(".html") ? "text/html; charset=utf-8"
                        : resource.endsWith(".css") ? "text/css; charset=utf-8"
                        : resource.endsWith(".js") ? "application/javascript; charset=utf-8"
                        : "application/octet-stream";
                PanelServer.this.respond(ctx, request, HttpResponseStatus.OK, contentType, body);
            } catch (IOException e) {
                PanelServer.this.respondError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to read resource");
            }
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
        // Console commands run away from the event loop, exactly like the terminal console does
        this.commandExecutor.execute(() -> {
            try {
                this.proxy.dispatchCommand(this.proxy.getConsoleSender(), text.trim());
            } catch (Throwable t) {
                this.proxy.getLogger().error("Panel command execution failed", t);
            }
        });
        this.respondJson(ctx, request, HttpResponseStatus.OK, Map.of("queued", true));
    }

    private void handleKick(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        String name = extractMiddle(path, "/api/players/", "/kick");
        ProxiedPlayer player = this.proxy.getPlayerManager().getPlayer(name);
        if (player == null) {
            this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Player not found");
            return;
        }
        String body = request.content().toString(StandardCharsets.UTF_8);
        Map<?, ?> payload = parseBody(body);
        String reason = payload != null && payload.get("reason") instanceof String text && !text.isBlank()
                ? text : "Kicked from the proxy";
        this.commandExecutor.execute(() -> player.disconnect(reason));
        this.respondJson(ctx, request, HttpResponseStatus.OK, Map.of("kicked", player.getName()));
    }

    private void handleTransfer(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        String name = extractMiddle(path, "/api/players/", "/transfer");
        ProxiedPlayer player = this.proxy.getPlayerManager().getPlayer(name);
        if (player == null) {
            this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Player not found");
            return;
        }
        Map<?, ?> payload = parseBody(request.content().toString(StandardCharsets.UTF_8));
        if (payload == null || !(payload.get("server") instanceof String serverName) || serverName.isBlank()) {
            this.respondError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing 'server'");
            return;
        }
        ServerInfo target = this.proxy.getServerInfo(serverName);
        if (target == null) {
            this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "Server not registered");
            return;
        }
        this.commandExecutor.execute(() -> player.connect(target));
        this.respondJson(ctx, request, HttpResponseStatus.OK, Map.of("transferring", player.getName(), "server", target.getServerName()));
    }

    private void handleServerPing(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        String name = extractMiddle(path, "/api/servers/ping/", "");
        ServerInfo server = this.proxy.getServerInfo(name);
        if (!(server instanceof BedrockServerInfo bedrock)) {
            this.respondError(ctx, request, HttpResponseStatus.NOT_FOUND, "RakNet server not registered");
            return;
        }
        Future<BedrockPong> pong = bedrock.ping(3, TimeUnit.SECONDS);
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

    private Map<?, ?> parseBody(String body) {
        try {
            return body.isBlank() ? Map.of() : GSON.fromJson(body, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}

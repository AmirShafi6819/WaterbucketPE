import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v560.Bedrock_v560;
import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode;
import org.cloudburstmc.protocol.bedrock.data.GamePublishSetting;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.SpawnBiomeType;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.ChatRestrictionLevel;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bedrock-protocol benchmark client and fake backend, built on the same CloudburstMC
 * Protocol and RakNet libraries the proxy uses, so logins and transfers are byte-for-byte
 * what real clients and servers perform. Driven by bench_bedrock.py.
 *
 * Extends the RakNet-level harness (RakBenchClient) with the paths that need a full
 * Bedrock session: login -> StartGame latency, in-game TextPacket round-trip latency,
 * and fast-transfer (server switch) latency.
 *
 * Usage: BedrockBench <mode> <proxyHost> <proxyPort> <count> <backendAPort> <backendBPort>
 *   login  - sequential full logins through the proxy; per-login connect->StartGame latency
 *   rtt    - one login, then <count> TextPacket round-trips through proxy and backend
 *   switch - one login, then <count> fast transfers A->B->A...; per-switch trigger->StartGame latency
 */
public class BedrockBench {

    static final int PROTOCOL = 560;       // Minecraft Bedrock 1.19.50
    static final String VANILLA_VERSION = "1.19.50";
    static final BedrockCodec CODEC = Bedrock_v560.CODEC;
    static final boolean DEBUG = Boolean.getBoolean("bench.debug");

    static final Gson GSON = new Gson();

    /** Player name -> live backend session (both fake backends register here). */
    static final Map<String, TrackedSession> BACKEND_SESSIONS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String proxyHost = args[1];
        int proxyPort = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        int backendAPort = Integer.parseInt(args[4]);
        int backendBPort = Integer.parseInt(args[5]);

        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeBackend backendA = null;
        FakeBackend backendB = null;
        List<Long> latencies = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        try {
            backendA = new FakeBackend("backend-a", backendAPort, group);
            backendB = new FakeBackend("backend-b", backendBPort, group);
            backendA.bind();
            backendB.bind();

            if (mode.equals("login")) {
                for (int i = 0; i < count; i++) {
                    BenchClient client = new BenchClient(group, proxyHost, proxyPort, "Bench" + i);
                    long latency = client.loginUntilStartGame(10_000);
                    if (latency >= 0) {
                        ok++;
                        latencies.add(latency);
                    } else {
                        failed++;
                    }
                    client.close();
                    Thread.sleep(20);
                }
            } else if (mode.equals("rtt")) {
                BenchClient client = new BenchClient(group, proxyHost, proxyPort, "BenchRTT");
                long login = client.loginUntilStartGame(10_000);
                if (login < 0) {
                    failed++;
                } else {
                    for (int i = 0; i < count; i++) {
                        long rtt = client.textRoundTrip(i, 5_000);
                        if (rtt >= 0) {
                            ok++;
                            latencies.add(rtt);
                        } else {
                            failed++;
                        }
                    }
                }
                client.close();
            } else if (mode.equals("switch")) {
                BenchClient client = new BenchClient(group, proxyHost, proxyPort, "BenchSwitch");
                long login = client.loginUntilStartGame(10_000);
                if (login < 0) {
                    failed++;
                } else {
                    ok++; // login itself succeeded
                    int targetPort = backendBPort;
                    for (int i = 0; i < count; i++) {
                        long latency = client.switchOnce(targetPort, 15_000);
                        if (latency >= 0) {
                            ok++;
                            latencies.add(latency);
                        } else {
                            failed++;
                            break; // a failed switch leaves the session in an undefined state
                        }
                        targetPort = targetPort == backendBPort ? backendAPort : backendBPort;
                        Thread.sleep(20);
                    }
                }
                client.close();
            } else {
                System.err.println("Unknown mode: " + mode);
                failed++;
            }
        } finally {
            if (backendA != null) backendA.close();
            if (backendB != null) backendB.close();
            group.shutdownGracefully();
        }

        System.out.println("RESULT ok=" + ok + " failed=" + failed);
        System.out.print("LATENCIES_US=");
        for (long l : latencies) {
            System.out.print(l + ",");
        }
        System.out.println();
    }

    static void debug(String message) {
        if (DEBUG) {
            System.err.println("[bench " + System.currentTimeMillis() + "] " + message);
        }
    }

    /** Marks pipeline exceptions with the leg they happened on (client vs backend port). */
    static class ErrorMarker extends io.netty.channel.ChannelInboundHandlerAdapter {
        private final String leg;

        ErrorMarker(String leg) {
            this.leg = leg;
        }

        @Override
        public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable e) {
            debug("PIPELINE-ERROR on [" + this.leg + " -> " + ctx.channel().remoteAddress() + "]: " + e);
            ctx.fireExceptionCaught(e);
        }
    }

    /** Raw-frame dump (hex) placed after the frame-id codec, before encryption/compression. */
    static class RawDump extends io.netty.channel.ChannelDuplexHandler {
        private final String leg;

        RawDump(String leg) {
            this.leg = leg;
        }

        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
            if (DEBUG) {
                if (msg instanceof org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper wrapper) {
                    io.netty.buffer.ByteBuf compressed = wrapper.getCompressed();
                    debug("RAW-IN[" + this.leg + " wrapper " + (compressed == null ? "null" : compressed.readableBytes() + "B") + "] "
                            + (compressed == null ? "-" : hex(compressed)));
                } else if (msg instanceof io.netty.buffer.ByteBuf buf) {
                    debug("RAW-IN[" + this.leg + " " + buf.readableBytes() + "B] " + hex(buf));
                } else {
                    debug("RAW-IN[" + this.leg + "] msg-class=" + msg.getClass().getName());
                }
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) {
            if (DEBUG && msg instanceof io.netty.buffer.ByteBuf buf) {
                debug("RAW-OUT[" + this.leg + " " + buf.readableBytes() + "B] " + hex(buf));
            }
            ctx.write(msg, promise);
        }

        private static String hex(io.netty.buffer.ByteBuf buf) {
            int n = Math.min(buf.readableBytes(), 24);
            byte[] b = new byte[n];
            buf.getBytes(buf.readerIndex(), b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ client

    /**
     * One Bedrock client session through the proxy. The flow mirrors what the proxy's
     * LoginUpstreamHandler and InitialHandler expect from an upstream client.
     */
    static class BenchClient {
        final EventLoopGroup group;
        final String host;
        final int port;
        final String name;
        final KeyPair keyPair = EncryptionUtils.createKeyPair();

        volatile Channel channel;
        volatile BedrockClientSession session;
        CountDownLatch startGameLatch;
        CountDownLatch echoLatch;
        volatile long startGameAt;
        volatile long echoAt;
        volatile int startGamesSeen;
        volatile int changeDimensionsSeen;
        CountDownLatch switchLatch;
        volatile String error;
        volatile long connectAt;

        BenchClient(EventLoopGroup group, String host, int port, String name) {
            this.group = group;
            this.host = host;
            this.port = port;
            this.name = name;
        }

        /** Returns latency in microseconds from connect() start to the first StartGamePacket, or -1. */
        long loginUntilStartGame(long timeoutMs) throws InterruptedException {
            this.startGameLatch = new CountDownLatch(1);
            this.startGamesSeen = 0;
            this.error = null;
            ClientFlow flow = new ClientFlow();
            Bootstrap bootstrap = new Bootstrap()
                    .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                    .group(this.group)
                    .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                    .handler(new ClientInit(flow));
            this.connectAt = System.nanoTime();
            try {
                this.channel = bootstrap.connect(this.host, this.port).syncUninterruptibly().channel();
            } catch (Exception e) {
                this.error = "connect: " + e;
                return -1;
            }
            if (!this.startGameLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                this.error = "StartGame timeout";
                return -1;
            }
            if (this.startGamesSeen < 1 || this.error != null) {
                if (this.error != null) {
                    System.err.println("CLIENT-ERROR[" + this.name + "]: " + this.error);
                }
                return -1;
            }
            return (this.startGameAt - this.connectAt) / 1_000;
        }

        /** Sends a TextPacket and waits for its echo through the proxy + backend. Microseconds, or -1. */
        long textRoundTrip(int seq, long timeoutMs) throws InterruptedException {
            BedrockClientSession s = this.session;
            if (s == null || this.startGamesSeen < 1) {
                return -1;
            }
            this.echoLatch = new CountDownLatch(1);
            this.error = null;
            TextPacket packet = new TextPacket();
            packet.setType(TextPacket.Type.RAW);
            packet.setMessage("rtt:" + seq);
            packet.setSourceName("");
            packet.setXuid("");
            packet.setPlatformChatId("");
            long t0 = System.nanoTime();
            s.sendPacket(packet);
            if (!this.echoLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                this.error = "echo timeout";
                return -1;
            }
            return (this.echoAt - t0) / 1_000;
        }

        /**
         * Triggers a fast transfer: the currently connected fake backend sends a TransferPacket
         * for the target port, and we measure until the client's dimension-change round trip
         * completes (two synthetic ChangeDimensionPackets: out to the fake dimension and back).
         * The fast-transfer path does NOT deliver a second StartGamePacket to the client - the
         * proxy consumes the new server's StartGame as rewrite input - so the second dimension
         * change is the client-visible completion point. Returns microseconds, or -1.
         */
        long switchOnce(int targetPort, long timeoutMs) throws InterruptedException {
            TrackedSession tracked = BACKEND_SESSIONS.get(this.name);
            if (tracked == null) {
                this.error = "no backend session";
                return -1;
            }
            this.error = null;
            this.changeDimensionsSeen = 0;
            this.switchLatch = new CountDownLatch(2);
            TransferPacket transfer = new TransferPacket();
            transfer.setAddress("127.0.0.1");
            transfer.setPort(targetPort);
            long t0 = System.nanoTime();
            tracked.session.sendPacketImmediately(transfer);
            if (!this.switchLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                this.error = "switch dimension-change timeout (seen " + this.changeDimensionsSeen + ")";
                return -1;
            }
            return (this.switchDoneAt - t0) / 1_000;
        }

        void close() {
            Channel c = this.channel;
            if (c != null && c.isOpen()) {
                try {
                    c.close().await(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * ExceptionCaught on the client leg, so decryption/codec failures surface with the
         * client name instead of a bare Netty tail-of-pipeline warning.
         */
        void reportPipelineError(Throwable e) {
            debug("client: pipeline error: " + e);
        }

        /**
         * Packet handler driving the upstream login flow and the in-game scenarios.
         */
        class ClientFlow implements BedrockPacketHandler {
            private volatile BedrockClientSession clientSession;
            private volatile boolean encryptionReady;

            @Override
            public PacketSignal handle(RequestNetworkSettingsPacket packet) {
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(NetworkSettingsPacket packet) {
                debug("client: NetworkSettings alg=" + packet.getCompressionAlgorithm());
                BedrockClientSession s = this.clientSession;
                if (s == null) {
                    return PacketSignal.HANDLED;
                }
                s.getPeer().setCompression(packet.getCompressionAlgorithm());
                s.sendPacket(BenchClient.this.buildLoginPacket());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(LoginPacket packet) {
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(ServerToClientHandshakePacket packet) {
                debug("client: got ServerToClientHandshake");
                try {
                    SignedJWT saltJwt = SignedJWT.parse(packet.getJwt());
                    URI x5u = saltJwt.getHeader().getX509CertURL();
                    ECPublicKey serverKey = EncryptionUtils.parseKey(x5u.toASCIIString());
                    SecretKey key = EncryptionUtils.getSecretKey(
                            BenchClient.this.keyPair.getPrivate(),
                            serverKey,
                            Base64.getDecoder().decode(saltJwt.getJWTClaimsSet().getStringClaim("salt"))
                    );
                    BedrockClientSession s = this.clientSession;
                    if (s == null) {
                        return PacketSignal.HANDLED;
                    }
                    s.enableEncryption(key);
                    s.sendPacket(new ClientToServerHandshakePacket());
                    this.encryptionReady = true;
                    debug("client: encryption enabled, C2S sent");
                } catch (Exception e) {
                    BenchClient.this.error = "encryption: " + e;
                    CountDownLatch latch = BenchClient.this.startGameLatch;
                    if (latch != null) latch.countDown();
                }
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(ClientToServerHandshakePacket packet) {
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(PlayStatusPacket packet) {
                debug("client: PlayStatus " + packet.getStatus());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(ResourcePacksInfoPacket packet) {
                BedrockClientSession s = this.clientSession;
                if (s == null) {
                    return PacketSignal.HANDLED;
                }
                ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
                response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
                s.sendPacket(response);
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(ResourcePackStackPacket packet) {
                BedrockClientSession s = this.clientSession;
                if (s == null) {
                    return PacketSignal.HANDLED;
                }
                ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
                response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
                s.sendPacket(response);
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(StartGamePacket packet) {
                debug("client: StartGame " + BenchClient.this.startGamesSeen + " entity=" + packet.getRuntimeEntityId());
                BenchClient.this.lastEntityId = packet.getRuntimeEntityId();
                BenchClient.this.startGamesSeen++;
                BenchClient.this.startGameAt = System.nanoTime();
                CountDownLatch latch = BenchClient.this.startGameLatch;
                if (latch != null) latch.countDown();
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(ChangeDimensionPacket packet) {
                // The proxy sends a synthetic dimension change on fast transfers and waits for
                // PlayerActionPacket#DIMENSION_CHANGE_SUCCESS (ConnectedUpstreamHandler).
                debug("client: ChangeDimension -> " + packet.getDimension() + " entity=" + BenchClient.this.lastEntityId);
                BedrockClientSession s = this.clientSession;
                if (s == null) {
                    return PacketSignal.HANDLED;
                }
                PlayerActionPacket action = new PlayerActionPacket();
                action.setAction(PlayerActionType.DIMENSION_CHANGE_SUCCESS);
                action.setRuntimeEntityId(BenchClient.this.lastEntityId);
                action.setBlockPosition(Vector3i.ZERO);
                action.setResultPosition(Vector3i.ZERO);
                action.setFace(0);
                s.sendPacket(action);
                debug("client: DIMENSION_CHANGE_SUCCESS sent");
                BenchClient.this.changeDimensionsSeen++;
                BenchClient.this.switchDoneAt = System.nanoTime();
                CountDownLatch latch = BenchClient.this.switchLatch;
                if (latch != null) latch.countDown();
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(TextPacket packet) {
                BenchClient.this.echoAt = System.nanoTime();
                CountDownLatch latch = BenchClient.this.echoLatch;
                if (latch != null) latch.countDown();
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(DisconnectPacket packet) {
                BenchClient.this.error = "disconnect: " + packet.getKickMessage();
                debug("client: Disconnect " + packet.getKickMessage());
                CountDownLatch latch = BenchClient.this.startGameLatch;
                if (latch != null) latch.countDown();
                return PacketSignal.HANDLED;
            }

            void attach(BedrockClientSession s) {
                this.clientSession = s;
            }
        }

        volatile long lastEntityId = 1;
        volatile long switchDoneAt;

        class ClientInit extends org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer {
            private final ClientFlow flow;

            ClientInit(ClientFlow flow) {
                this.flow = flow;
            }

            @Override
            protected void initSession(BedrockClientSession session) {
                session.setLogging(false);
                session.setCodec(CODEC);
                session.getPeer().getChannel().pipeline().addLast("bench-error", new ErrorMarker("client"));
                this.flow.attach(session);
                session.setPacketHandler(this.flow);
                BenchClient.this.session = session;
                RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
                request.setProtocolVersion(PROTOCOL);
                session.sendPacket(request);
            }
        }

        LoginPacket buildLoginPacket() {
            LoginPacket login = new LoginPacket();
            login.setProtocolVersion(PROTOCOL);

            JsonObject clientData = new JsonObject();
            clientData.addProperty("ServerAddress", this.host + ":" + this.port);
            clientData.addProperty("DeviceModel", "BedrockBench");
            clientData.addProperty("DeviceOS", 1);
            clientData.addProperty("ClientRandomId", (long) (Math.random() * Long.MAX_VALUE));
            clientData.addProperty("LanguageCode", "en_US");
            clientData.addProperty("GameVersion", VANILLA_VERSION);
            clientData.addProperty("SkinData", "AAAA");
            clientData.addProperty("SkinId", "bench");

            JsonObject extraData = new JsonObject();
            extraData.addProperty("displayName", this.name);
            extraData.addProperty("XUID", "0");
            extraData.addProperty("identity", UUID.randomUUID().toString());

            SignedJWT clientJwt = encodeJwt(this.keyPair, clientData);
            SignedJWT chainJwt = createClientDataChain(this.keyPair, extraData);

            login.setClientJwt(clientJwt.serialize());
            login.setAuthPayload(new CertificateChainPayload(
                    List.of(chainJwt.serialize()), AuthType.SELF_SIGNED));
            return login;
        }
    }

    // ----------------------------------------------------------------- backend

    /** One fake downstream server. Mirrors the minimal BDS login/chunk/echo flow. */
    static class FakeBackend {
        final String name;
        final int port;
        final EventLoopGroup group;
        volatile Channel channel;

        FakeBackend(String name, int port, EventLoopGroup group) {
            this.name = name;
            this.port = port;
            this.group = group;
        }

        void bind() throws InterruptedException {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                    .group(this.group)
                    .option(RakChannelOption.RAK_GUID, UUID.nameUUIDFromBytes(this.name.getBytes()).getMostSignificantBits())
                    .option(RakChannelOption.RAK_HANDLE_PING, true)
                    .childOption(RakChannelOption.RAK_SESSION_TIMEOUT, 30_000L)
                    .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                    .childHandler(new org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer() {
                        @Override
                        protected void initSession(BedrockServerSession session) {
                            session.setLogging(false);
                            session.getPeer().getChannel().pipeline().addAfter("frame-id-codec", "bench-raw", new RawDump("backend:" + FakeBackend.this.port));
                            session.getPeer().getChannel().pipeline().addLast("bench-error", new ErrorMarker("backend:" + FakeBackend.this.port));
                            session.setPacketHandler(new BackendFlow(FakeBackend.this, session));
                        }
                    });
            this.channel = bootstrap.bind(new InetSocketAddress("127.0.0.1", this.port))
                    .syncUninterruptibly().channel();
        }

        void close() {
            Channel c = this.channel;
            if (c != null && c.isOpen()) {
                c.close();
            }
        }
    }

    static final KeyPair BACKEND_KEY_PAIR = EncryptionUtils.createKeyPair();

    static class TrackedSession {
        final int port;
        final BedrockServerSession session;

        TrackedSession(int port, BedrockServerSession session) {
            this.port = port;
            this.session = session;
        }
    }

    /**
     * Server-side flow expected by the proxy's downstream client leg (CompressionInitHandler,
     * InitialHandler, ConnectedDownstreamHandler): NetworkSettings, encryption handshake,
     * resource packs, StartGame, PLAYER_SPAWN, text echo.
     */
    static class BackendFlow implements BedrockPacketHandler {
        final FakeBackend backend;
        final BedrockServerSession session;
        volatile String playerName;
        volatile int protocolVersion = PROTOCOL;

        BackendFlow(FakeBackend backend, BedrockServerSession session) {
            this.backend = backend;
            this.session = session;
        }

        @Override
        public PacketSignal handle(RequestNetworkSettingsPacket packet) {
            this.protocolVersion = packet.getProtocolVersion();
            if (this.protocolVersion != PROTOCOL) {
                PlayStatusPacket status = new PlayStatusPacket();
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD);
                this.session.sendPacketImmediately(status);
                this.session.disconnect("unsupported protocol");
                return PacketSignal.HANDLED;
            }
            this.session.setCodec(CODEC);
            NetworkSettingsPacket settings = new NetworkSettingsPacket();
            settings.setCompressionThreshold(1);
            settings.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
            this.session.sendPacketImmediately(settings);
            this.session.getPeer().setCompression(PacketCompressionAlgorithm.ZLIB);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(LoginPacket packet) {
            debug("backend[" + this.backend.port + "]: got LoginPacket from proxy");
            try {
                ChainValidationResult result = EncryptionUtils.validatePayload(packet.getAuthPayload());
                this.playerName = result.identityClaims().extraData.displayName;
            } catch (Exception e) {
                this.session.disconnect("invalid login: " + e);
                return PacketSignal.HANDLED;
            }

            // Server -> client encryption handshake, identical to HandshakeUtils.processEncryption
            // but with the backend's own static key pair. Sent WITHOUT the pre-handshake
            // PlayStatus(LOGIN_SUCCESS): that packet makes the proxy queue a plaintext
            // ClientCacheStatusPacket, which - when the proxy's event loop is busy - can reach
            // this decoder after it is enabled and shift the CTR keystream, corrupting every
            // later frame. BDS-style ordering (handshake first, PlayStatus after C2S) closes
            // that window deterministically.
            try {
                ChainValidationResult result = EncryptionUtils.validatePayload(packet.getAuthPayload());
                ECPublicKey clientKey = (ECPublicKey) result.identityClaims().parsedIdentityPublicKey();
                byte[] token = EncryptionUtils.generateRandomToken();
                SecretKey secret = EncryptionUtils.getSecretKey(BACKEND_KEY_PAIR.getPrivate(), clientKey, token);
                ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
                handshake.setJwt(EncryptionUtils.createHandshakeJwt(BACKEND_KEY_PAIR, token));
                this.session.sendPacketImmediately(handshake);
                this.session.enableEncryption(secret);
            } catch (Exception e) {
                this.session.disconnect("encryption failed: " + e);
                return PacketSignal.HANDLED;
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ClientToServerHandshakePacket packet) {
            debug("backend[" + this.backend.port + "]: got ClientToServerHandshake from proxy");
            // Encryption is fully up on both legs now; BDS-style PlayStatus + resource packs follow.
            PlayStatusPacket status = new PlayStatusPacket();
            status.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
            this.session.sendPacket(status);
            ResourcePacksInfoPacket info = new ResourcePacksInfoPacket();
            this.session.sendPacket(info);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePackClientResponsePacket packet) {
            debug("backend[" + this.backend.port + "]: pack response " + packet.getStatus());
            if (packet.getStatus() == ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS) {
                ResourcePackStackPacket stack = new ResourcePackStackPacket();
                stack.setForcedToAccept(false);
                stack.setGameVersion(VANILLA_VERSION);
                this.session.sendPacket(stack);
            } else if (packet.getStatus() == ResourcePackClientResponsePacket.Status.COMPLETED) {
                BACKEND_SESSIONS.put(this.playerName, new TrackedSession(this.backend.port, this.session));
                this.session.sendPacket(buildStartGame(this.playerName, this.backend.port));
                PlayStatusPacket spawn = new PlayStatusPacket();
                spawn.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
                this.session.sendPacket(spawn);
            }
            return PacketSignal.HANDLED;
        }
        @Override
        public PacketSignal handle(RequestChunkRadiusPacket packet) {
            ChunkRadiusUpdatedPacket radius = new ChunkRadiusUpdatedPacket();
            radius.setRadius(packet.getRadius());
            this.session.sendPacket(radius);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(TextPacket packet) {
            // Echo for the in-game round-trip scenario. The proxy relays text both ways.
            TextPacket echo = new TextPacket();
            echo.setType(TextPacket.Type.RAW);
            echo.setMessage(packet.getMessage());
            echo.setSourceName("");
            echo.setXuid("");
            echo.setPlatformChatId("");
            this.session.sendPacket(echo);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(SetLocalPlayerAsInitializedPacket packet) {
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(NetworkSettingsPacket packet) {
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(DisconnectPacket packet) {
            return PacketSignal.HANDLED;
        }

        StartGamePacket buildStartGame(String player, int backendPort) {
            StartGamePacket packet = new StartGamePacket();
            packet.setUniqueEntityId(1);
            packet.setRuntimeEntityId(1);
            packet.setPlayerGameType(GameType.SURVIVAL);
            packet.setPlayerPosition(Vector3f.from(0, 64, 0));
            packet.setRotation(Vector2f.from(0, 0));
            packet.setSeed(0);
            packet.setSpawnBiomeType(SpawnBiomeType.DEFAULT);
            packet.setCustomBiomeName("plains");
            packet.setDimensionId(0);
            packet.setGeneratorId(1);
            packet.setLevelGameType(GameType.SURVIVAL);
            packet.setDifficulty(1);
            packet.setDefaultSpawn(Vector3i.ZERO);
            packet.setDayCycleStopTime(0);
            packet.setEduEditionOffers(0);
            packet.setRainLevel(0);
            packet.setLightningLevel(0);
            packet.setXblBroadcastMode(GamePublishSetting.NO_MULTI_PLAY);
            packet.setPlatformBroadcastMode(GamePublishSetting.NO_MULTI_PLAY);
            packet.setCommandsEnabled(true);
            packet.setTexturePacksRequired(false);
            packet.setEducationProductionId("");
            packet.setDefaultPlayerPermission(PlayerPermission.MEMBER);
            packet.setForceExperimentalGameplay(OptionalBoolean.of(false));
            packet.setVanillaVersion(VANILLA_VERSION);
            packet.setLimitedWorldWidth(0);
            packet.setLimitedWorldHeight(0);
            packet.setServerChunkTickRange(4);
            packet.setLevelName("bench-" + backendPort);
            packet.setLevelId("");
            packet.setPremiumWorldTemplateId("");
            packet.setMultiplayerCorrelationId(UUID.randomUUID().toString());
            packet.setAuthoritativeMovementMode(AuthoritativeMovementMode.CLIENT);
            packet.setRewindHistorySize(0);
            packet.setServerAuthoritativeBlockBreaking(false);
            packet.setInventoriesServerAuthoritative(false);
            packet.setBlockNetworkIdsHashed(false);
            packet.setCurrentTick(0);
            packet.setEnchantmentSeed(0);
            packet.setWorldTemplateId(UUID.nameUUIDFromBytes(new byte[16]));
            packet.setServerEngine("");
            packet.setChatRestrictionLevel(ChatRestrictionLevel.NONE);
            packet.setBlockRegistryChecksum(0);
            packet.setPlayerPropertyData(NbtMap.EMPTY);
            packet.setServerAuthoritativeBlockBreaking(false);
            return packet;
        }
    }

    // ------------------------------------------------------------------- jwt

    /** Self-signed client data chain, mirroring the proxy's HandshakeUtils (kept local so the
     *  tool only depends on the shared protocol/netty libraries, not on fork classes). */
    static SignedJWT createClientDataChain(KeyPair pair, JsonObject extraData) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        long timestamp = System.currentTimeMillis() / 1000;

        JsonObject dataChain = new JsonObject();
        dataChain.addProperty("nbf", timestamp - 3600);
        dataChain.addProperty("exp", timestamp + 24 * 3600);
        dataChain.addProperty("iat", timestamp);
        dataChain.addProperty("iss", "self");
        dataChain.addProperty("certificateAuthority", true);
        dataChain.add("extraData", extraData);
        dataChain.addProperty("randomNonce", UUID.randomUUID().getLeastSignificantBits());
        dataChain.addProperty("identityPublicKey", publicKeyBase64);
        return encodeJwt(pair, dataChain);
    }

    static SignedJWT encodeJwt(KeyPair pair, JsonObject payload) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        URI x5u = URI.create(publicKeyBase64);
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES384).x509CertURL(x5u).build();
        try {
            SignedJWT jwt = new SignedJWT(header, JWTClaimsSet.parse(payload.toString()));
            jwt.sign(new ECDSASigner((ECPrivateKey) pair.getPrivate(), Curve.P_384));
            return jwt;
        } catch (JOSEException | ParseException e) {
            throw new RuntimeException(e);
        }
    }
}

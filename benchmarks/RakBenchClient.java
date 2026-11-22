import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;

import java.util.ArrayList;
import java.util.List;

/**
 * RakNet handshake benchmark client built on the same CloudburstMC RakNet library the proxy
 * uses, so handshakes are byte-for-byte what real connections perform. Driven by bench.py.
 *
 * Usage: RakBenchClient <handshake|hold> <host> <port> <count> [holdSeconds]
 *   handshake - sequential connects; prints summary JSON
 *   hold      - opens <count> concurrent sessions and holds them, so the driver
 *               can measure proxy RSS with live sessions
 */
public class RakBenchClient {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        int holdSeconds = args.length > 4 ? Integer.parseInt(args[4]) : 5;

        EventLoopGroup group = new NioEventLoopGroup(2);
        List<Channel> held = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        List<Long> latencies = new ArrayList<>();

        try {
            for (int i = 0; i < count; i++) {
                long t0 = System.nanoTime();
                Channel channel;
                try {
                    Bootstrap bootstrap = new Bootstrap()
                            .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                            .group(group)
                            .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                            .handler(new ChannelInboundHandlerAdapter());
                    channel = bootstrap.connect(host, port).syncUninterruptibly().channel();
                } catch (Exception e) {
                    failed++;
                    System.err.println("FAIL[" + i + "]: " + e);
                    continue;
                }
                long t1 = System.nanoTime();
                ok++;
                latencies.add((t1 - t0) / 1_000); // microseconds
                if (mode.equals("hold")) {
                    held.add(channel);
                } else {
                    channel.close().syncUninterruptibly();
                }
            }

            if (mode.equals("hold")) {
                System.out.println("HOLDING " + ok);
                if (holdSeconds < 0) {
                    // hold until killed: the driver spawns one client per load
                    // stage and keeps every client alive so the session
                    // population is cumulative across stages
                    Thread.sleep(Long.MAX_VALUE);
                } else {
                    Thread.sleep(holdSeconds * 1000L);
                }
            }

            System.out.println("RESULT ok=" + ok + " failed=" + failed);
            System.out.print("LATENCIES_US=");
            for (long l : latencies) {
                System.out.print(l + ",");
            }
            System.out.println();
        } finally {
            for (Channel channel : held) {
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
            }
            group.shutdownGracefully();
        }
    }
}

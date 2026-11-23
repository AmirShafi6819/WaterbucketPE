/*
 * Copyright 2022 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe;

import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;
import io.netty.util.ResourceLeakDetector;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WaterbucketPE {

    /**
     * Branded startup banner, printed as one clean block before logging starts.
     * Kept plain text (no ANSI or color codes) so it renders safely on every terminal,
     * redirected output and Windows consoles.
     */
    private static final String[] BANNER = {
            "",
            " _       __      __               ____             __        __ ",
            "| |     / /___ _/ /____  _____   / __ )__  _______/ /_____  / /_ ",
            "| | /| / / __ `/ __/ _ \\/ ___/  / __  / / / / ___/ //_/ _ \\/ __/",
            "| |/ |/ / /_/ / /_/  __/ /     / /_/ / /_/ / /__/ ,< /  __/ /_  ",
            "|__/|__/\\__,_/\\__/\\___/_/     /_____/\\__,_/\\___/_/|_|\\___/\\__/  ",
            "",
            "  WaterBucketPE - a fast Minecraft: Bedrock Edition proxy",
            "  Forked from WaterdogPE. Commands: /wbhelp - Web panel: see panel_token.txt",
            ""
    };

    public static String DATA_PATH = System.getProperty("user.dir") + "/";
    public final static String PLUGIN_PATH = DATA_PATH + "plugins";
    private static final VersionInfo versionInfo = loadVersion();

    public static void main(String[] args) {
        Thread.currentThread().setName("WaterBucketPE-main");
        printBanner();
        System.setProperty("log4j.skipJansi", "false");
        applyAllocatorTuning();

        MainLogger logger = MainLogger.getLogger();
        logger.info("§bStarting WaterBucketPE proxy software!");
        logger.info("§3Software Version: {}", versionInfo.baseVersion());
        logger.info("§3Build Version: {}", versionInfo.buildVersion());
        logger.info("§3Development Build: {}", versionInfo.debug());
        logger.info("§3Software Authors: {}", versionInfo.author());
        logger.info("§3Latest Supported Game Version: {}", ProtocolVersion.latest().getMinecraftVersion());


        int javaVersion = getJavaVersion();
        if (javaVersion < 17) {
            logger.error("Using unsupported Java version! Minimum supported version is Java 17, found Java " + javaVersion);
            // A refused start must not exit with status 0: supervisors (Docker/systemd restart
            // policies) would treat it as a clean run.
            shutdownHook(1);
            return;
        }

        if (versionInfo.buildVersion().equals("#build") || versionInfo.branchName().equals("unknown")) {
            logger.warning("Custom build? Unofficial builds should be not run in production!");
        } else {
            logger.info("§3Discovered branch §b{}§3 commitId §b{}", versionInfo.branchName(), versionInfo.commitId());
        }

        if (versionInfo.debug()) {
            setLeakDetection(ResourceLeakDetector.Level.SIMPLE);
        } else {
            setLeakDetection(ResourceLeakDetector.Level.DISABLED);
        }

        logger.info("§eUsing memory leak detection level: {}", ResourceLeakDetector.getLevel());
        if (!versionInfo.debug() && ResourceLeakDetector.getLevel().ordinal() > ResourceLeakDetector.Level.SIMPLE.ordinal()) {
            logger.warning("§eUsing higher memory leak detection levels in production environment can affect application stability and performance!");
        }

        try {
            new ProxyServer(logger, DATA_PATH, PLUGIN_PATH);
        } catch (Exception e) {
            logger.throwing(e);
            // A failed start must not report success to process supervisors (Docker/systemd
            // restart policies depend on the exit code).
            shutdownHook(1);
        }
    }

    /**
     * Prints the startup banner as one clean block above the first log lines.
     */
    private static void printBanner() {
        for (String line : BANNER) {
            System.out.println(line);
        }
        System.out.println();
    }

    /**
     * This method is called when exception occurs or process saw shutdown
     */
    protected static void shutdownHook() {
        shutdownHook(0);
    }

    /**
     * Tears the logging framework down and force-exits with the given status code.
     *
     * @param status 0 for a clean shutdown, non-zero for a failed startup
     */
    protected static void shutdownHook(int status) {
        LogManager.shutdown();
        Runtime.getRuntime().halt(status); // force exit
    }

    private static VersionInfo loadVersion() {
        InputStream inputStream = WaterbucketPE.class.getClassLoader().getResourceAsStream("git.properties");
        if (inputStream == null) {
            return VersionInfo.unknown();
        }

        Properties properties = new Properties();
        try (inputStream) {
            properties.load(inputStream);
        } catch (IOException e) {
            return VersionInfo.unknown();
        }

        String branchName = properties.getProperty("git.branch", "unknown");
        String commitId = properties.getProperty("git.commit.id.abbrev", "unknown");
        String projectVersion = properties.getProperty("git.build.version");
        // Snapshot builds run with development mode enabled; anything else (tagged or release
        // branch builds) runs in release mode, which disables Netty's per-buffer leak tracking.
        boolean debug = projectVersion == null ? VersionInfo.DEFAULT_DEBUG : projectVersion.endsWith("-SNAPSHOT");
        return new VersionInfo(branchName, commitId, projectVersion, debug);
    }

    public static void setLoggerLevel(Level level) {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration log4jConfig = context.getConfiguration();
        LoggerConfig loggerConfig = log4jConfig.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
        loggerConfig.setLevel(level);
        context.updateLoggers();
    }

    /**
     * Right-sizes Netty's pooled allocator for a proxy workload. Must run before any Netty
     * class is loaded (Netty reads these properties once into static defaults).
     * <p>
     * Direct arenas: Netty defaults to 2x core count, and each arena owns 16 MB chunks - on a
     * 2-core host that pre-commits 64 MB of direct memory for buffer pooling before the first
     * packet. The RakNet listener already binds one channel per core, so core-count arenas
     * keep every event loop on a private arena without doubling the chunk commitment.
     * <p>
     * Per-thread caches: every event loop parks recently freed buffers in its thread cache
     * (tiny 512 / small 256 / normal 64 entries by default). With hundreds of sessions the
     * parked buffers added up to megabytes of RSS that idle connections never gave back;
     * halving to a third of the defaults keeps allocation mostly cache-hit (the small buffers
     * that dominate RakNet datagrams stay cached) while letting memory return to the pool.
     */
    private static void applyAllocatorTuning() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        System.setProperty("io.netty.allocator.numDirectArenas", String.valueOf(cores));
        System.setProperty("io.netty.allocator.numHeapArenas", String.valueOf(cores));
        System.setProperty("io.netty.allocator.tinyCacheSize", "256");
        System.setProperty("io.netty.allocator.smallCacheSize", "64");
        System.setProperty("io.netty.allocator.normalCacheSize", "8");
    }

    public static VersionInfo version() {
        return versionInfo;
    }

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2, 3));
        }

        Matcher versionMatcher = Pattern.compile("\\d+").matcher(version);
        if (versionMatcher.find()) {
            version = versionMatcher.group(0);
        }

        int index = version.indexOf(".");
        if (index != -1) {
            version = version.substring(0, index);
        }
        return Integer.parseInt(version);
    }

    private static void setLeakDetection(ResourceLeakDetector.Level level) {
        if (level == ResourceLeakDetector.Level.DISABLED) {
            // Release mode: Netty's per-buffer reference tracking is pure overhead here.
            // The old raise-only guard never applied DISABLED, so every release build
            // silently kept tracking allocations at the SIMPLE level.
            ResourceLeakDetector.setLevel(level);
        } else if (ResourceLeakDetector.getLevel().ordinal() < level.ordinal()) {
            // Debug mode only ever raises the level
            ResourceLeakDetector.setLevel(level);
        }
    }
}

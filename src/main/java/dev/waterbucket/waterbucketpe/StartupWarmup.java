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

package dev.waterbucket.waterbucketpe;

import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Moves the one-time cost of a first player join off the join path and into startup.
 * <p>
 * The first connection after a cold boot pays for every class load, every JIT compilation
 * and every JCE provider initialization the login and batch pipelines need - and that cost
 * lands on the moment a player actually joins, which shows up as a short CPU/RAM spike on
 * the dashboard. Everything warmed here is exercised on a background daemon thread right
 * after the listeners bind, so by the time a real player connects the work is already done:
 * <ul>
 *     <li>the login crypto stack: an ECDH key agreement, the AES/CFB8 session cipher and an
 *     ECDSA sign/verify round trip (the same shapes {@link EncryptionKeyRequest} handling and
 *     the downstream handshake go through);</li>
 *     <li>the zlib codec (deflate/inflate of a real payload), which every incoming and
 *     outgoing batch runs through;</li>
 *     <li>every Bedrock packet class in the active codec, so the first decodes hit warm
 *     classes instead of triggering hundreds of loads mid-join.</li>
 * </ul>
 */
@Log4j2
public final class StartupWarmup {

    private static final byte[] SAMPLE = new byte[4096];

    private StartupWarmup() {
    }

    /**
     * Starts the warm-up on a low-priority daemon thread; failures are logged and never fatal -
     * warm-up only decides when the first join pays its cost, not whether joins work.
     */
    public static void kickOff() {
        Thread thread = new Thread(StartupWarmup::warmUp, "WaterBucketPE-Warmup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
    }

    private static void warmUp() {
        long start = System.nanoTime();
        try {
            warmLoginCrypto();
            warmCompression();
            warmPacketClasses();
            warmDownstreamDial();
        } catch (Throwable t) {
            log.debug("Startup warm-up stopped early: {}", t.toString());
            return;
        }
        log.info("Startup warm-up finished in {} ms - first joins stay smooth",
                (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Mirrors the Bedrock login and session crypto: EC key generation, ECDH agreement, the
     * AES/CFB8 session cipher and an ECDSA sign/verify round trip on the P-384 curve.
     */
    private static void warmLoginCrypto() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(384);
        KeyPair local = generator.generateKeyPair();
        KeyPair remote = generator.generateKeyPair();

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(local.getPrivate());
        agreement.doPhase(remote.getPublic(), true);
        byte[] shared = agreement.generateSecret();
        SecretKey sessionKey = new SecretKeySpec(shared, 0, 16, "AES");

        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, new IvParameterSpec(new byte[16]));
        cipher.doFinal(SAMPLE);

        Signature signer = Signature.getInstance("SHA384withECDSA");
        signer.initSign(local.getPrivate());
        signer.update(SAMPLE);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(local.getPublic());
        verifier.update(SAMPLE);
        verifier.verify(signature);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.digest(SAMPLE);
    }

    /**
     * Loads the downstream dial path classes (RakNet client channel factory, the proxy-side
     * client session initializer chain and the ping probe) so the first transfer pays only
     * the network cost, not a class-loading spike on the event loop. Class loads only: no
     * channel is created and no packet leaves the process.
     */
    private static void warmDownstreamDial() throws Exception {
        Class.forName("org.cloudburstmc.netty.channel.raknet.RakChannel");
        Class.forName("org.cloudburstmc.netty.channel.raknet.RakClientChannel");
        Class.forName("dev.waterbucket.waterbucketpe.network.connection.codec.initializer.ProxiedClientSessionInitializer");
        Class.forName("dev.waterbucket.waterbucketpe.network.connection.codec.client.ClientPingHandler");
        Class.forName("dev.waterbucket.waterbucketpe.network.protocol.handler.downstream.InitialHandler");
        // One EC agreement is also what the downstream handshake cipher setup runs through
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(384);
        KeyPair local = generator.generateKeyPair();
        agreement.init(local.getPrivate());
        agreement.generateSecret();
    }

    /** One full deflate/inflate cycle through the JDK zlib code the batch codec sits on. */
    private static void warmCompression() throws Exception {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(SAMPLE);
        deflater.finish();
        byte[] compressed = new byte[SAMPLE.length * 2];
        int deflated = 0;
        while (!deflater.finished() && deflated < compressed.length) {
            deflated += deflater.deflate(compressed, deflated, compressed.length - deflated);
        }
        deflater.end();

        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed, 0, deflated);
        byte[] restored = new byte[SAMPLE.length];
        inflater.inflate(restored);
        inflater.end();
    }

    /**
     * Instantiates one packet of every registered id in the active codec, which loads (and lets
     * the JIT see) every packet class the first real join would otherwise load mid-flight.
     */
    private static void warmPacketClasses() {
        BedrockCodec codec = ProtocolVersion.latest().getBedrockCodec();
        if (codec == null) {
            return;
        }
        int loaded = 0;
        for (int id = 1; id <= 600; id++) {
            BedrockPacketDefinition<? extends BedrockPacket> definition = codec.getPacketDefinition(id);
            if (definition == null) {
                continue;
            }
            try {
                definition.getFactory().get();
                loaded++;
            } catch (Throwable ignored) {
                // A single packet factory failing must not stop the warm-up of the rest
            }
        }
        log.debug("Warm-up touched {} packet classes", loaded);
    }
}

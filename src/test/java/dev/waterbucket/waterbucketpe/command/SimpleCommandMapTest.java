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

package dev.waterbucket.waterbucketpe.command;

import dev.waterbucket.waterbucketpe.ProxyServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers registration/unregistration semantics of the command map, especially alias
 * ownership: unregistering one command must never delete another command's alias.
 */
class SimpleCommandMapTest {

    private SimpleCommandMap map;

    @BeforeEach
    void setUp() {
        // The proxy is only used for logging inside executeInternal; registration paths
        // never touch it.
        this.map = new SimpleCommandMap(null, SimpleCommandMap.DEFAULT_PREFIX);
    }

    private static Command command(String name, String... aliases) {
        return new Command(name, CommandSettings.builder().setAliases(aliases).build()) {
            @Override
            public boolean onExecute(CommandSender sender, String alias, String[] args) {
                return true;
            }
        };
    }

    @Test
    void registerResolvesByNameAndAlias() {
        Command command = command("wdhello", "hello");
        assertTrue(this.map.registerCommand(command));
        assertSame(command, this.map.getCommand("wdhello"));
        assertSame(command, this.map.getCommand("hello"));
    }

    @Test
    void unregisterRemovesCommandAndOwnedAliasesOnly() {
        Command alpha = command("alpha", "shared", "own-alpha");
        Command beta = command("beta", "shared", "own-beta");
        assertTrue(this.map.registerCommand(alpha));
        // "shared" was claimed by alpha; beta's alias attempt must lose
        assertTrue(this.map.registerCommand(beta));
        assertSame(alpha, this.map.getCommand("shared"), "first claimant owns the alias");

        // Unregistering beta must NOT delete the alias owned by alpha
        assertTrue(this.map.unregisterCommand("beta"));
        assertNull(this.map.getCommand("beta"), "command must be gone");
        assertNull(this.map.getCommand("own-beta"), "beta's owned alias must be gone");
        assertSame(alpha, this.map.getCommand("shared"),
                "an alias owned by another command must survive the unregistration");

        // Unregistering alpha removes its own claim
        assertTrue(this.map.unregisterCommand("alpha"));
        assertNull(this.map.getCommand("shared"));
        assertNull(this.map.getCommand("own-alpha"));
    }

    @Test
    void unregisterUnknownNameReturnsFalse() {
        assertFalse(this.map.unregisterCommand("does-not-exist"));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        Command first = command("dup");
        Command second = command("dup");
        assertTrue(this.map.registerCommand(first));
        assertFalse(this.map.registerCommand(second));
        assertSame(first, this.map.getCommand("dup"));
    }

    @Test
    void registerAndUnregisterFromManyThreadsDoesNotCorruptMaps() throws InterruptedException {
        int threads = 8;
        int perThread = 50;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    Command command = command("cmd" + (base + i));
                    this.map.registerCommand(command);
                    this.map.getCommand("cmd" + (base + i));
                    this.map.unregisterCommand("cmd" + (base + i));
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        // Every command was unregistered again; only the aliases remain empty too
        assertEquals(Set.of(), this.map.getCommands().keySet(), "map must be empty after the churn");
    }
}

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

package dev.waterbucket.waterbucketpe.console;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import lombok.Getter;
import net.minecrell.terminalconsole.SimpleTerminalConsole;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;

import java.io.IOException;

public class TerminalConsole extends SimpleTerminalConsole {

    private final ProxyServer proxy;
    @Getter
    private final ConsoleThread consoleThread;
    // Captured in buildReader so the shutdown path can restore the terminal attributes
    private volatile LineReader lineReader;

    public TerminalConsole(ProxyServer proxy) {
        this.proxy = proxy;
        this.consoleThread = new ConsoleThread(this);
    }

    @Override
    protected void runCommand(String command) {
        CommandSender console = this.proxy.getConsoleSender();
        this.proxy.getScheduler().scheduleTask(() -> this.proxy.dispatchCommand(console, command), false);
    }

    @Override
    protected LineReader buildReader(LineReaderBuilder builder) {
        builder.completer(new CommandCompleter(this.proxy));
        builder.appName("WaterbucketPE");
        // Persist typed commands across restarts, so operators keep their history
        builder.variable(LineReader.HISTORY_FILE, java.nio.file.Paths.get(dev.waterbucket.waterbucketpe.WaterbucketPE.DATA_PATH + ".console_history"));
        builder.option(LineReader.Option.HISTORY_BEEP, false);
        builder.option(LineReader.Option.HISTORY_IGNORE_DUPS, true);
        builder.option(LineReader.Option.HISTORY_IGNORE_SPACE, true);
        LineReader reader = super.buildReader(builder);
        this.lineReader = reader;
        return reader;
    }

    @Override
    protected void shutdown() {
        ProxyServer.getInstance().shutdown();
    }

    @Override
    protected boolean isRunning() {
        return this.proxy.isRunning();
    }

    /**
     * Closes the underlying JLine terminal, restoring the console attributes and flushing the
     * command history. Called from the shutdown path after the console thread was joined; safe
     * to call more than once.
     */
    public void close() {
        LineReader reader = this.lineReader;
        if (reader == null) {
            return;
        }
        try {
            reader.getTerminal().close();
        } catch (IOException ignored) {
            // The terminal may already be closed by the reader's own exit path
        }
    }

}

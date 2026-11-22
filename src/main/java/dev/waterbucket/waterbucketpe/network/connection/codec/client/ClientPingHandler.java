/*
 * Copyright 2023 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.network.connection.codec.client;

import io.netty.channel.*;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.channel.raknet.RakPong;
import org.cloudburstmc.protocol.bedrock.BedrockPong;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ClientPingHandler extends ChannelDuplexHandler {

    private final Promise<BedrockPong> future;

    private final long timeout;
    private final TimeUnit timeUnit;
    private ScheduledFuture<?> timeoutFuture;

    public ClientPingHandler(Promise<BedrockPong> future, long timeout, TimeUnit timeUnit) {
        this.future = future;
        this.timeout = timeout;
        this.timeUnit = timeUnit;
    }

    private void onTimeout(Channel channel) {
        channel.close();
        this.future.tryFailure(new TimeoutException());
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.timeoutFuture = ctx.channel().eventLoop().schedule(() -> this.onTimeout(ctx.channel()), this.timeout, this.timeUnit);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof RakPong rakPong)) {
            super.channelRead(ctx, msg);
            return;
        }

        if (this.timeoutFuture != null) {
            this.timeoutFuture.cancel(false);
            this.timeoutFuture = null;
        }

        try {
            // fromRakNet parses the pong record and can throw on a malformed one; a throw used
            // to leave the promise unresolved forever (the panel ping hung) and the RakPong's
            // retained slice unreleased.
            this.future.trySuccess(BedrockPong.fromRakNet(rakPong.getPongData()));
        } catch (Throwable t) {
            this.future.tryFailure(t);
        } finally {
            // The pong holds a retained slice of a pooled buffer; as the last handler in the
            // pipeline nothing after us releases it.
            rakPong.release();
            ctx.channel().close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // A channel that closes before the pong arrives (refused downstream, network drop) used
        // to cancel the timeout via close() without ever completing the promise.
        if (!this.future.isDone()) {
            this.future.tryFailure(new java.nio.channels.ClosedChannelException());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        super.close(ctx, promise);

        if (this.timeoutFuture != null) {
            this.timeoutFuture.cancel(false);
            this.timeoutFuture = null;
        }
    }
}

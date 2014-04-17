/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.meshy.netty;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.addthis.basis.util.JitterClock;
import com.addthis.basis.util.Parameter;

import com.addthis.meshy.ChannelMaster;
import com.addthis.meshy.ChannelState;
import com.addthis.meshy.MeshyConstants;
import com.addthis.meshy.SessionHandler;
import com.addthis.meshy.TargetHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.ChannelGroupFutureListener;


/**
 * Manages (possibly) multiple targets for a source.
 */
public abstract class MultiplexingSourceHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(MultiplexingSourceHandler.class);

    private final ChannelMaster master;
    private ChannelHandlerContext ctx;
    private ChannelGroup channels;

    private int session;
    private int targetHandler;
    private long readTime;
    private long readTimeout;
    private long completeTimeout;

    public MultiplexingSourceHandler(ChannelMaster master, Class<? extends TargetHandler> targetClass) {
        this(master, targetClass, MeshyConstants.LINK_ALL);
    }

    // TODO: more sane construction
    public MultiplexingSourceHandler(ChannelMaster master, Class<? extends TargetHandler> targetClass, String targetUuid) {
        this.master = master;
        master.createSession(this, targetClass, targetUuid);
    }

    @Override
    public String toString() {
        return master + "[Source:" + shortName + ":s=" + session + ",h=" + targetHandler + ",c=" + (channels != null ? channels.size() : "null") + "]";
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
    }

    private void handleChannelTimeouts() {
        if ((readTimeout > 0) && ((JitterClock.globalTime() - readTime) > readTimeout)) {
            log.info("{} response timeout on channel: {}", this, channelsToList());
            if (SLOW_SLOW_CHANNELS) {
                log.warn("closing {} channel(s)", channels.size());
                synchronized (channels) {
                    for (Channel channel : channels) {
                        channel.close();
                    }
                }
            }
            channels.clear();
            try {
                receiveComplete(session);
            } catch (Exception ex) {
                log.error("Swallowing exception while handling channel timeout", ex);
            }
        }
    }

    public ChannelMaster getChannelMaster() {
        return master;
    }

    public void init(int session, int targetHandler, ChannelGroup group) {
        this.readTime = JitterClock.globalTime();
        this.session = session;
        this.channels = group;
        this.targetHandler = targetHandler;
        setReadTimeout(DEFAULT_RESPONSE_TIMEOUT);
        setCompleteTimeout(DEFAULT_COMPLETE_TIMEOUT);
        activeSources.add(this);
    }

    public void setReadTimeout(int seconds) {
        readTimeout = (long) (seconds * 1000);
    }

    public void setCompleteTimeout(int seconds) {
        completeTimeout = (long) (seconds * 1000);
    }

    public int getPeerCount() {
        return channels.size();
    }

    public String getPeerString() {
        StringBuilder sb = new StringBuilder(10 * channels.size());
        synchronized (channels) {
            for (Channel channel : channels) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(channel.remoteAddress());
            }
        }
        return sb.toString();
    }

    @Override
    public ChannelFuture sendComplete() {
        return send(Unpooled.EMPTY_BUFFER);
    }

    public ChannelFuture send(ByteBuf data) {
        return ctx.write(ChannelState.allocateSendBuffer(targetHandler, session, data));
    }

    private boolean send(final ByteBuf buffer, final SendWatcher watcher, final int reportBytes) {
        if (log.isTraceEnabled()) {
            log.trace(this + " send " + buffer.capacity() + " to " + channels.size());
        }
        if (!channels.isEmpty()) {
            final int peerCount = channels.size();
            if (sent.compareAndSet(false, true)) {
                try {
                    gate.acquire();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            ChannelGroupFuture future = channels.writeAndFlush(buffer);
            future.addListener(new ChannelGroupFutureListener() {
                @Override
                public void operationComplete(ChannelGroupFuture future) throws Exception {
                    master.sentBytes(reportBytes * peerCount);
                    buffer.release();
                    if (watcher != null) {
                        watcher.sendFinished(reportBytes);
                    }
                }
            });
            return true;
        }
        return false;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        this.readTime = JitterClock.globalTime();
        log.debug("{} receive [{}]", this, msg);
    }

    @Override
    public void receiveComplete(ChannelState state, int completedSession) throws Exception {
        log.debug("{} receiveComplete.1 [{}]", this, completedSession);
        Channel channel = state.getChannel();
        if (channel != null) {
            channels.remove(channel);
            if (!channel.isOpen()) {
                channelClosed(state);
            }
        }
        if (channels.isEmpty()) {
            receiveComplete(completedSession);
        }
    }

    @Override
    public void receiveComplete(int completedSession) throws Exception {
        log.debug("{} receiveComplete.2 [{}]", this, completedSession);
        // ensure this is only called once
        if (complete.compareAndSet(false, true)) {
            if (sent.get()) {
                gate.release();
            }
            receiveComplete();
            activeSources.remove(this);
        }
    }

    private String channelsToList() {
        StringBuilder stringBuilder = new StringBuilder(10 * channels.size());
        synchronized (channels) {
            for (Channel channel : channels) {
                stringBuilder.append(channel.remoteAddress().toString());
            }
        }
        return stringBuilder.toString();
    }

    @Override
    public void waitComplete() {
        // this is technically incorrect, but prevents lockups
        if (waited.compareAndSet(false, true) && sent.get()) {
            try {
                if (!gate.tryAcquire(completeTimeout, TimeUnit.MILLISECONDS)) {
                    log.warn("{} failed to waitComplete() normally from channels: {}", this, channelsToList());
                    activeSources.remove(this);
                }
            } catch (Exception ex) {
                log.error("Swallowing mystery exception", ex);
            }
        }
    }

    public abstract void channelClosed(ChannelState state);

    public abstract void receive(ChannelState state, ByteBuf in) throws Exception;

    public abstract void receiveComplete() throws Exception;
}
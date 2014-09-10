/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.http;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpChunkTrailer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ChunkResponder} that writes chunks to a {@link Channel}.
 */
final class ChannelChunkResponder implements ChunkResponder {

  private final Channel channel;
  private final boolean keepAlive;
  private final AtomicBoolean closed;

  ChannelChunkResponder(Channel channel, boolean keepAlive) {
    this.channel = channel;
    this.keepAlive = keepAlive;
    this.closed = new AtomicBoolean(false);
  }

  @Override
  public void sendChunk(ByteBuffer chunk) throws IOException {
    sendChunk(ChannelBuffers.wrappedBuffer(chunk));
  }

  @Override
  public void sendChunk(ChannelBuffer chunk) throws IOException {
    if (closed.get()) {
      throw new IOException("ChunkResponder already closed.");
    }
    if (!channel.isConnected()) {
      throw new IOException("Connection already closed.");
    }
    channel.write(new DefaultHttpChunk(chunk));
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    ChannelFuture future = channel.write(new DefaultHttpChunkTrailer());
    if (!keepAlive) {
      future.addListener(ChannelFutureListener.CLOSE);
    }
  }
}

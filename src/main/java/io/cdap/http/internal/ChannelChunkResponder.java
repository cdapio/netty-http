/*
 * Copyright © 2017-2019 Cask Data, Inc.
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

package io.cdap.http.internal;


import io.cdap.http.ChunkResponder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ChunkResponder} that writes chunks to a {@link Channel}.
 */
final class ChannelChunkResponder implements ChunkResponder {

  private final Channel channel;
  private final AtomicBoolean closed;
  private final AtomicLong bufferedSize;
  private final int chunkMemoryLimit;

  ChannelChunkResponder(Channel channel, int chunkMemoryLimit) {
    this.channel = channel;
    this.closed = new AtomicBoolean();
    this.bufferedSize = new AtomicLong();
    this.chunkMemoryLimit = chunkMemoryLimit;
  }

  @Override
  public void sendChunk(ByteBuffer chunk) throws IOException {
    sendChunk(Unpooled.wrappedBuffer(chunk));
  }

  @Override
  public void sendChunk(ByteBuf chunk) throws IOException {
    if (closed.get()) {
      throw new IOException("ChunkResponder already closed.");
    }
    if (!channel.isActive()) {
      throw new IOException("Connection already closed.");
    }
    int chunkSize = chunk.readableBytes();
    channel.write(new DefaultHttpContent(chunk));
    tryFlush(chunkSize);
  }

  @Override
  public void flush() {
    // Use the limit as the size to force a flush
    tryFlush(chunkMemoryLimit);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
  }

  private void tryFlush(int size) {
    long newSize = bufferedSize.addAndGet(size);
    if (newSize >= chunkMemoryLimit) {
      channel.flush();
      // Subtract what were flushed.
      // This is correct for single thread.
      // For concurrent calls, this provides a lower bound,
      // meaning more data might get flushed then being subtracted.
      // This make sure we won't go over the memory limit, but might flush more often than needed.
      bufferedSize.addAndGet(-1 * newSize);
    }
  }
}

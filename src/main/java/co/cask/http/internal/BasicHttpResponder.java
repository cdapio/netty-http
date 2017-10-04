/*
 * Copyright © 2017 Cask Data, Inc.
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

package co.cask.http.internal;

import co.cask.http.AbstractHttpResponder;
import co.cask.http.BodyProducer;
import co.cask.http.ChunkResponder;
import co.cask.http.HttpResponder;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * Basic implementation of {@link HttpResponder} that uses {@link Channel} to write back to client.
 */
final class BasicHttpResponder extends AbstractHttpResponder {

  private static final Logger LOG = LoggerFactory.getLogger(BasicHttpResponder.class);

  private final Channel channel;
  private final AtomicBoolean responded;
  private final boolean sslEnabled;

  BasicHttpResponder(Channel channel, boolean sslEnabled) {
    this.channel = channel;
    this.responded = new AtomicBoolean(false);
    this.sslEnabled = sslEnabled;
  }

  @Override
  public ChunkResponder sendChunkStart(HttpResponseStatus status, @Nullable Multimap<String, String> headers) {
    Preconditions.checkArgument((status.code() >= 200 && status.code() < 210) , "Http Chunk Failure");
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);

    setCustomHeaders(response, headers);

    if (!hasContentLength(headers)) {
      response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
    }

    Preconditions.checkArgument(responded.compareAndSet(false, true), "Response has been already sent");
    channel.write(response);
    return new ChannelChunkResponder(channel);
  }

  @Override
  public void sendContent(HttpResponseStatus status, @Nullable ByteBuf content, String contentType,
                          @Nullable Multimap<String, String> headers) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                                                            content == null ? Unpooled.EMPTY_BUFFER : content);
    setCustomHeaders(response, headers);
    if (contentType != null) {
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    }
    HttpUtil.setContentLength(response, response.content().readableBytes());

    Preconditions.checkArgument(responded.compareAndSet(false, true), "Response has been already sent");
    channel.writeAndFlush(response);
  }

  @Override
  public void sendFile(File file, @Nullable Multimap<String, String> headers) {
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

    setCustomHeaders(response, headers);
    HttpUtil.setTransferEncodingChunked(response, false);
    HttpUtil.setContentLength(response, file.length());

    Preconditions.checkArgument(responded.compareAndSet(false, true), "Response has been already sent");

    try {
      // Open the file first to make sure it is readable before sending out the response
      RandomAccessFile raf = new RandomAccessFile(file, "r");

      // Write the initial line and the header.
      channel.write(response);

      // Write the content.
      // FileRegion only works in non-SSL case. For SSL case, use ChunkedFile instead.
      // See https://github.com/netty/netty/issues/2075
      if (sslEnabled) {
        // The HttpChunkedInput will write out the last content
        channel.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 8192)));
      } else {
        // The FileRegion will close the file channel when it is done sending.
        FileRegion region = new DefaultFileRegion(raf.getChannel(), 0, file.length());

        // Write the initial line and the header.
        channel.write(response);
        channel.write(region);
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
      }
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void sendContent(HttpResponseStatus status, final BodyProducer bodyProducer,
                          @Nullable Multimap<String, String> headers) {
    final long contentLength;
    try {
      contentLength = bodyProducer.getContentLength();
    } catch (Throwable t) {
      bodyProducer.handleError(t);
      // Response with error and close the connection
      sendContent(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                  Unpooled.wrappedBuffer(
                    Charsets.UTF_8.encode("Failed to determined content length. Cause: " + t.getMessage())),
                  "text/plain",
                  ImmutableMultimap.of(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE.toString()));
      return;
    }

    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    setCustomHeaders(response, headers);

    if (contentLength < 0L) {
      HttpUtil.setTransferEncodingChunked(response, true);
    } else {
      HttpUtil.setTransferEncodingChunked(response, false);
      HttpUtil.setContentLength(response, contentLength);
    }

    Preconditions.checkArgument(responded.compareAndSet(false, true), "Response has been already sent");

    // Streams the data produced by the given BodyProducer
    channel.writeAndFlush(response).addListener(new ChannelFutureListener() {

      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          callBodyProducerHandleError(bodyProducer, future.cause());
          channel.close();
          return;
        }
        channel.writeAndFlush(new HttpChunkedInput(new BodyProducerChunkedInput(bodyProducer)))
          .addListener(createBodyProducerCompletionListener(bodyProducer));
      }
    });
  }

  /**
   * Returns {@code true} if response was sent.
   */
  boolean isResponded() {
    return responded.get();
  }

  private ChannelFutureListener createBodyProducerCompletionListener(final BodyProducer bodyProducer) {
    return new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          callBodyProducerHandleError(bodyProducer, future.cause());
          channel.close();
          return;
        }

        try {
          bodyProducer.finished();
        } catch (Throwable t) {
          callBodyProducerHandleError(bodyProducer, t);
          channel.close();
        }
      }
    };
  }

  private void callBodyProducerHandleError(BodyProducer bodyProducer, @Nullable Throwable failureCause) {
    try {
      bodyProducer.handleError(failureCause);
    } catch (Throwable t) {
      LOG.warn("Exception raised from BodyProducer.handleError() for {}", bodyProducer, t);
    }
  }

  private void setCustomHeaders(HttpResponse response, @Nullable Multimap<String, String> headers) {
    // Add headers. They will override all headers set by the framework
    if (headers != null) {
      for (Map.Entry<String, Collection<String>> entry : headers.asMap().entrySet()) {
        response.headers().set(entry.getKey(), entry.getValue());
      }
    }
  }

  /**
   * Returns true if the {@code Content-Length} header is set.
   */
  private boolean hasContentLength(@Nullable Multimap<String, String> headers) {
    if (headers == null) {
      return false;
    }
    for (String key : headers.keySet()) {
      if (HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(key)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A {@link ChunkedInput} implementation that produce chunks using {@link BodyProducer}.
   */
  private static final class BodyProducerChunkedInput implements ChunkedInput<ByteBuf> {

    private final BodyProducer bodyProducer;
    private final long length;
    private long bytesProduced;
    private ByteBuf nextChunk;
    private boolean completed;

    private BodyProducerChunkedInput(BodyProducer bodyProducer) {
      this.bodyProducer = bodyProducer;
      this.length = bodyProducer.getContentLength();
    }

    @Override
    public boolean isEndOfInput() throws Exception {
      if (completed) {
        return true;
      }
      if (nextChunk == null) {
        nextChunk = bodyProducer.nextChunk();
      }

      completed = !nextChunk.isReadable();
      if (completed && length >= 0 && bytesProduced != length) {
        throw new IllegalStateException("Body size doesn't match with content length. " +
                                          "Content-Length: " + length + ", bytes produced: " + bytesProduced);
      }

      return completed;
    }

    @Override
    public void close() throws Exception {
      // No-op. Calling of the BodyProducer.finish() is done via the channel future completion.
    }

    @Override
    public ByteBuf readChunk(ChannelHandlerContext ctx) throws Exception {
      return readChunk(ctx.alloc());
    }

    @Override
    public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
      if (isEndOfInput()) {
        // This shouldn't happen, but just to guard
        throw new IllegalStateException("No more data to produce from body producer");
      }
      ByteBuf chunk = nextChunk;
      bytesProduced += chunk.readableBytes();
      nextChunk = null;
      return chunk;
    }

    @Override
    public long length() {
      return length;
    }

    @Override
    public long progress() {
      return bytesProduced;
    }
  }
}

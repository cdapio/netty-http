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

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.DefaultFileRegion;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * HttpResponder responds back to the client that initiated the request. Caller can use sendJson method to respond
 * back to the client in json format.
 */
public class BasicHttpResponder extends AbstractHttpResponder {

  private static final Logger LOG = LoggerFactory.getLogger(BasicHttpResponder.class);

  private final Channel channel;
  private final boolean keepAlive;
  private final AtomicBoolean responded;

  public BasicHttpResponder(Channel channel, boolean keepAlive) {
    this.channel = channel;
    this.keepAlive = keepAlive;
    responded = new AtomicBoolean(false);
  }

  @Override
  public ChunkResponder sendChunkStart(HttpResponseStatus status, @Nullable Multimap<String, String> headers) {
    Preconditions.checkArgument((status.getCode() >= 200 && status.getCode() < 210) , "Http Chunk Failure");
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);

    setCustomHeaders(response, headers);
    response.setChunked(true);

    if (!hasContentLength(headers)) {
      response.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
    }

    boolean responseKeepAlive = setResponseKeepAlive(response);
    Preconditions.checkArgument(responded.compareAndSet(false, true), "Response has been already sent");
    channel.write(response);
    return new ChannelChunkResponder(channel, responseKeepAlive);
  }

  @Override
  public void sendContent(HttpResponseStatus status, @Nullable ChannelBuffer content, String contentType,
                          @Nullable Multimap<String, String> headers) {
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);

    setCustomHeaders(response, headers);

    if (content != null) {
      response.setContent(content);
      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType);
      response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes());
    } else {
      response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, 0);
    }

    boolean responseKeepAlive = setResponseKeepAlive(response);
    Preconditions.checkArgument(responded.compareAndSet(false, true), "Response has been already sent");
    ChannelFuture future = channel.write(response);
    if (!responseKeepAlive) {
      future.addListener(ChannelFutureListener.CLOSE);
    }
  }

  @Override
  public void sendFile(File file, @Nullable Multimap<String, String> headers) {
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

    setCustomHeaders(response, headers);
    response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, file.length());

    final boolean responseKeepAlive = setResponseKeepAlive(response);

    Preconditions.checkArgument(responded.compareAndSet(false, true), "Response has been already sent");

    // Write the initial line and the header.
    channel.write(response);

    // Write the content.

    ChannelFuture writeFuture;
    try {
      FileChannel fc = new RandomAccessFile(file, "r").getChannel();

      final FileRegion region = new DefaultFileRegion(fc, 0, file.length());
      writeFuture = channel.write(region);
      writeFuture.addListener(new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
          region.releaseExternalResources();
          if (!responseKeepAlive) {
            channel.close();
          }
        }
      });
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
                  ChannelBuffers.wrappedBuffer(
                    Charsets.UTF_8.encode("Failed to determined content length. Cause: " + t.getMessage())),
                  "text/plain",
                  ImmutableMultimap.of(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE));
      return;
    }

    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    setCustomHeaders(response, headers);
    response.setChunked(true);

    if (contentLength < 0L) {
      response.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
      response.removeHeader(HttpHeaders.Names.CONTENT_LENGTH);
    } else {
      response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, contentLength);
      response.removeHeader(HttpHeaders.Names.TRANSFER_ENCODING);
    }

    boolean responseKeepAlive = setResponseKeepAlive(response);
    final ChannelFutureListener completionListener = createBodyProducerCompletionListener(bodyProducer,
                                                                                          responseKeepAlive);

    Preconditions.checkArgument(responded.compareAndSet(false, true), "Response has been already sent");

    // Streams the data produced by the given BodyProducer
    channel.write(response).addListener(new ChannelFutureListener() {

      long size = 0;

      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          callBodyProducerHandleError(bodyProducer, future.getCause());
          channel.close();
          return;
        }

        try {
          HttpChunk chunk = new DefaultHttpChunk(bodyProducer.nextChunk());
          if (contentLength >= 0) {
            size += chunk.getContent().readableBytes();
            if (size > contentLength) {
              callBodyProducerHandleError(bodyProducer,
                                          new IllegalStateException("Cannot write body longer than content length. " +
                                                                      "Content-Length: " + contentLength +
                                                                      ", bytes produced: " + size));
              channel.close();
              return;
            }
            if (chunk.isLast() && size != contentLength) {
              callBodyProducerHandleError(bodyProducer,
                                          new IllegalStateException("Body size doesn't match with content length. " +
                                                                      "Content-Length: " + contentLength +
                                                                      ", bytes produced: " + size));
              channel.close();
              return;
            }
          }

          ChannelFuture writeFuture = channel.write(chunk);
          if (chunk.isLast()) {
            writeFuture.addListener(completionListener);
          } else {
            writeFuture.addListener(this);
          }

        } catch (Throwable t) {
          callBodyProducerHandleError(bodyProducer, t);
          channel.close();
        }
      }
    });
  }

  private void callBodyProducerHandleError(BodyProducer bodyProducer, @Nullable Throwable failureCause) {
    try {
      bodyProducer.handleError(failureCause);
    } catch (Throwable t) {
      LOG.warn("Exception raised from BodyProducer.handleError() for {}", bodyProducer, t);
    }
  }

  private ChannelFutureListener createBodyProducerCompletionListener(final BodyProducer bodyProducer,
                                                                     final boolean responseKeepAlive) {
    return new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          callBodyProducerHandleError(bodyProducer, future.getCause());
          channel.close();
          return;
        }

        try {
          bodyProducer.finished();
          if (!responseKeepAlive) {
            channel.close();
          }
        } catch (Throwable t) {
          callBodyProducerHandleError(bodyProducer, t);
          channel.close();
        }
      }
    };
  }

  private void setCustomHeaders(HttpResponse response, @Nullable Multimap<String, String> headers) {
    // Add headers. They will override all headers set by the framework
    if (headers != null) {
      for (Map.Entry<String, Collection<String>> entry : headers.asMap().entrySet()) {
        response.setHeader(entry.getKey(), entry.getValue());
      }
    }
  }

  private boolean setResponseKeepAlive(HttpResponse response) {
    boolean closeConn = HttpHeaders.Values.CLOSE.equalsIgnoreCase(response.getHeader(HttpHeaders.Names.CONNECTION));
    boolean responseKeepAlive = this.keepAlive && !closeConn;

    if (responseKeepAlive) {
      response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
    } else {
      response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
    }

    return responseKeepAlive;
  }

  /**
   * Returns true if the {@code Content-Length} header is set.
   */
  private boolean hasContentLength(@Nullable Multimap<String, String> headers) {
    if (headers == null) {
      return false;
    }
    for (String key : headers.keySet()) {
      if (HttpHeaders.Names.CONTENT_LENGTH.equalsIgnoreCase(key)) {
        return true;
      }
    }
    return false;
  }
}

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

import io.cdap.http.AbstractHttpResponder;
import io.cdap.http.BodyProducer;
import io.cdap.http.ChunkResponder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * InternalHttpResponder is used when a handler is being called internally by some other handler, and thus there
 * is no need to go through the network.  It stores the status code and content in memory and returns them when asked.
 */
public class InternalHttpResponder extends AbstractHttpResponder {

  private static final Logger LOG = LoggerFactory.getLogger(InternalHttpResponder.class);

  private InternalHttpResponse response;

  @Override
  public ChunkResponder sendChunkStart(final HttpResponseStatus status, HttpHeaders headers) {
    return new ChunkResponder() {

      private ByteBuf contentChunks = Unpooled.EMPTY_BUFFER;
      private boolean closed;

      @Override
      public void sendChunk(ByteBuffer chunk) throws IOException {
        sendChunk(Unpooled.wrappedBuffer(chunk));
      }

      @Override
      public synchronized void sendChunk(ByteBuf chunk) throws IOException {
        if (closed) {
          throw new IOException("ChunkResponder already closed.");
        }
        contentChunks = Unpooled.wrappedBuffer(contentChunks, chunk);
      }

      @Override
      public synchronized void close() throws IOException {
        if (closed) {
          return;
        }
        closed = true;
        response = new AbstractInternalResponse(status.code()) {
          @Override
          public InputStream openInputStream() throws IOException {
            return new ByteBufInputStream(contentChunks.duplicate());
          }
        };
      }
    };
  }

  @Override
  public void sendContent(HttpResponseStatus status, final ByteBuf content, HttpHeaders headers) {
    response = new AbstractInternalResponse(status.code()) {
      @Override
      public InputStream openInputStream() throws IOException {
        return new ByteBufInputStream(content.duplicate());
      }
    };
  }

  @Override
  public void sendFile(final File file, HttpHeaders headers) {
    response = new AbstractInternalResponse(HttpResponseStatus.OK.code()) {
      @Override
      public InputStream openInputStream() throws IOException {
        return new FileInputStream(file);
      }
    };
  }

  @Override
  public void sendContent(HttpResponseStatus status, BodyProducer bodyProducer, HttpHeaders headers) {
    // Buffer all contents produced by the body producer
    ByteBuf contentChunks = Unpooled.EMPTY_BUFFER;
    try {
      ByteBuf chunk = bodyProducer.nextChunk();
      while (chunk.isReadable()) {
        contentChunks = Unpooled.wrappedBuffer(contentChunks, chunk);
        chunk = bodyProducer.nextChunk();
      }

      bodyProducer.finished();
      final ByteBuf finalContentChunks = contentChunks;
      response = new AbstractInternalResponse(status.code()) {
        @Override
        public InputStream openInputStream() throws IOException {
          return new ByteBufInputStream(finalContentChunks.duplicate());
        }
      };
    } catch (Throwable t) {
      try {
        bodyProducer.handleError(t);
      } catch (Throwable et) {
        LOG.warn("Exception raised from BodyProducer.handleError() for {}", bodyProducer, et);
      }
    }
  }

  /**
   * Returns the the last {@link InternalHttpResponse} created via one of the send methods.
   *
   * @throws IllegalStateException if there is no {@link InternalHttpResponse} available
   */
  public InternalHttpResponse getResponse() {
    if (response == null) {
      throw new IllegalStateException("No InternalHttpResponse available");
    }
    return response;
  }

  /**
   * Abstract implementation of {@link InternalHttpResponse}.
   */
  private abstract static class AbstractInternalResponse implements InternalHttpResponse {
    private final int statusCode;

    AbstractInternalResponse(int statusCode) {
      this.statusCode = statusCode;
    }

    @Override
    public int getStatusCode() {
      return statusCode;
    }
  }
}

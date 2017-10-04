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
import com.google.common.collect.Multimap;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;

/**
 * InternalHttpResponder is used when a handler is being called internally by some other handler, and thus there
 * is no need to go through the network.  It stores the status code and content in memory and returns them when asked.
 */
public class InternalHttpResponder extends AbstractHttpResponder {

  private static final Logger LOG = LoggerFactory.getLogger(InternalHttpResponder.class);

  private int statusCode;
  private InputSupplier<? extends InputStream> inputSupplier;

  public InternalHttpResponder() {
    statusCode = 0;
  }

  @Override
  public ChunkResponder sendChunkStart(HttpResponseStatus status, @Nullable Multimap<String, String> headers) {
    statusCode = status.code();
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
        inputSupplier = createContentSupplier(contentChunks);
      }
    };
  }

  @Override
  public void sendContent(HttpResponseStatus status, @Nullable ByteBuf content, String contentType,
                          @Nullable Multimap<String, String> headers) {
    statusCode = status.code();
    inputSupplier = createContentSupplier(content == null ? Unpooled.EMPTY_BUFFER : content);
  }

  @Override
  public void sendFile(File file, @Nullable Multimap<String, String> headers) {
    statusCode = HttpResponseStatus.OK.code();
    inputSupplier = Files.newInputStreamSupplier(file);
  }

  @Override
  public void sendContent(HttpResponseStatus status, BodyProducer bodyProducer,
                          @Nullable Multimap<String, String> headers) {
    statusCode = status.code();
    // Buffer all contents produced by the body producer
    ByteBuf contentChunks = Unpooled.EMPTY_BUFFER;
    try {
      ByteBuf chunk = bodyProducer.nextChunk();
      while (chunk.isReadable()) {
        contentChunks = Unpooled.wrappedBuffer(contentChunks, chunk);
        chunk = bodyProducer.nextChunk();
      }

      bodyProducer.finished();
      inputSupplier = createContentSupplier(contentChunks);
    } catch (Throwable t) {
      try {
        bodyProducer.handleError(t);
      } catch (Throwable et) {
        LOG.warn("Exception raised from BodyProducer.handleError() for {}", bodyProducer, et);
      }
    }
  }

  public InternalHttpResponse getResponse() {
    return new BasicInternalHttpResponse(statusCode, inputSupplier);
  }

  private InputSupplier<InputStream> createContentSupplier(ByteBuf content) {
    final ByteBuf responseContent = content.duplicate();    // Have independent pointers.
    responseContent.markReaderIndex();
    return new InputSupplier<InputStream>() {
      @Override
      public InputStream getInput() throws IOException {
        responseContent.resetReaderIndex();
        return new ByteBufInputStream(responseContent);
      }
    };
  }
}

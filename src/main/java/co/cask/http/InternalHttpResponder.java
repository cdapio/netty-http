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

import com.google.common.collect.Multimap;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

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

  private int statusCode;
  private InputSupplier<? extends InputStream> inputSupplier;

  public InternalHttpResponder() {
    statusCode = 0;
  }

  @Override
  public ChunkResponder sendChunkStart(HttpResponseStatus status, @Nullable Multimap<String, String> headers) {
    statusCode = status.getCode();
    return new ChunkResponder() {

      private ChannelBuffer contentChunks = ChannelBuffers.EMPTY_BUFFER;
      private boolean closed;

      @Override
      public void sendChunk(ByteBuffer chunk) throws IOException {
        sendChunk(ChannelBuffers.wrappedBuffer(chunk));
      }

      @Override
      public synchronized void sendChunk(ChannelBuffer chunk) throws IOException {
        if (closed) {
          throw new IOException("ChunkResponder already closed.");
        }
        contentChunks = ChannelBuffers.wrappedBuffer(contentChunks, chunk);
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
  public void sendContent(HttpResponseStatus status, @Nullable ChannelBuffer content, String contentType,
                          @Nullable Multimap<String, String> headers) {
    statusCode = status.getCode();
    inputSupplier = createContentSupplier(content == null ? ChannelBuffers.EMPTY_BUFFER : content);
  }

  @Override
  public void sendFile(File file, @Nullable Multimap<String, String> headers) {
    statusCode = HttpResponseStatus.OK.getCode();
    inputSupplier = Files.newInputStreamSupplier(file);
  }

  public InternalHttpResponse getResponse() {
    return new BasicInternalHttpResponse(statusCode, inputSupplier);
  }

  private InputSupplier<InputStream> createContentSupplier(ChannelBuffer content) {
    final ChannelBuffer responseContent = content.duplicate();    // Have independent pointers.
    responseContent.markReaderIndex();
    return new InputSupplier<InputStream>() {
      @Override
      public InputStream getInput() throws IOException {
        responseContent.resetReaderIndex();
        return new ChannelBufferInputStream(responseContent);
      }
    };
  }
}

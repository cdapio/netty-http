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
import io.cdap.http.HandlerHook;
import io.cdap.http.HttpResponder;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Wrap HttpResponder to call post handler hook.
 */
final class WrappedHttpResponder extends AbstractHttpResponder {
  private static final Logger LOG = LoggerFactory.getLogger(WrappedHttpResponder.class);

  private final HttpResponder delegate;
  private final Iterable<? extends HandlerHook> handlerHooks;
  private final HttpRequest httpRequest;
  private final HandlerInfo handlerInfo;

  WrappedHttpResponder(HttpResponder delegate, Iterable<? extends HandlerHook> handlerHooks,
                       HttpRequest httpRequest, HandlerInfo handlerInfo) {
    this.delegate = delegate;
    this.handlerHooks = handlerHooks;
    this.httpRequest = httpRequest;
    this.handlerInfo = handlerInfo;
  }

  @Override
  public ChunkResponder sendChunkStart(final HttpResponseStatus status, HttpHeaders headers) {
    final ChunkResponder chunkResponder = delegate.sendChunkStart(status, headers);
    return new ChunkResponder() {
      @Override
      public void sendChunk(ByteBuffer chunk) throws IOException {
        chunkResponder.sendChunk(chunk);
      }

      @Override
      public void sendChunk(ByteBuf chunk) throws IOException {
        chunkResponder.sendChunk(chunk);
      }

      @Override
      public void close() throws IOException {
        chunkResponder.close();
        runHook(status);
      }
    };
  }

  @Override
  public void sendContent(HttpResponseStatus status, ByteBuf content, HttpHeaders headers) {
    delegate.sendContent(status, content, headers);
    runHook(status);
  }

  @Override
  public void sendFile(File file, HttpHeaders headers) throws Throwable {
    delegate.sendFile(file, headers);
    runHook(HttpResponseStatus.OK);
  }

  @Override
  public void sendContent(HttpResponseStatus status, BodyProducer bodyProducer, HttpHeaders headers) {
    delegate.sendContent(status, bodyProducer, headers);
    runHook(status);
  }

  private void runHook(HttpResponseStatus status) {
    for (HandlerHook hook : handlerHooks) {
      try {
        hook.postCall(httpRequest, status, handlerInfo);
      } catch (Throwable t) {
        LOG.error("Post handler hook threw exception: ", t);
      }
    }
  }
}

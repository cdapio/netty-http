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
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.ByteBuffer;
import javax.annotation.Nullable;

/**
 * Base implementation of {@link HttpResponder} to simplify child implementations.
 */
public abstract class AbstractHttpResponder implements HttpResponder {

  @Override
  public void sendJson(HttpResponseStatus status, String jsonString) {
    sendString(status, jsonString, ImmutableMultimap.of(HttpHeaderNames.CONTENT_TYPE.toString(), "application/json"));
  }

  @Override
  public void sendString(HttpResponseStatus status, String data) {
    sendString(status, data, null);
  }

  @Override
  public void sendString(HttpResponseStatus status, String data, @Nullable Multimap<String, String> headers) {
    if (data == null) {
      sendStatus(status, headers);
      return;
    }
    try {
      ByteBuf buffer = Unpooled.wrappedBuffer(Charsets.UTF_8.encode(data));
      sendContent(status, buffer, "text/plain; charset=utf-8", headers);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void sendStatus(HttpResponseStatus status) {
    sendContent(status, null, null, ImmutableMultimap.<String, String>of());
  }

  @Override
  public void sendStatus(HttpResponseStatus status, @Nullable Multimap<String, String> headers) {
    sendContent(status, null, null, headers);
  }

  @Override
  public void sendByteArray(HttpResponseStatus status, byte[] bytes, @Nullable Multimap<String, String> headers) {
    ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
    sendContent(status, buffer, "application/octet-stream", headers);
  }

  @Override
  public void sendBytes(HttpResponseStatus status, ByteBuffer buffer, @Nullable Multimap<String, String> headers) {
    sendContent(status, Unpooled.wrappedBuffer(buffer), "application/octet-stream", headers);
  }

}

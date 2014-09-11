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
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;

/**
 * Base implementation of {@link HttpResponder} to simplify child implementations.
 */
public abstract class AbstractHttpResponder implements HttpResponder {

  private static final Gson GSON = new Gson();

  @Override
  public void sendJson(HttpResponseStatus status, Object object) {
    sendJson(status, object, object.getClass());
  }

  @Override
  public void sendJson(HttpResponseStatus status, Object object, Type type) {
    sendJson(status, object, type, GSON);
  }

  @Override
  public void sendJson(HttpResponseStatus status, Object object, Type type, Gson gson) {
    try {
      ChannelBuffer channelBuffer = ChannelBuffers.dynamicBuffer();
      JsonWriter jsonWriter = new JsonWriter(new OutputStreamWriter(new ChannelBufferOutputStream(channelBuffer),
                                                                    Charsets.UTF_8));
      try {
        gson.toJson(object, type, jsonWriter);
      } finally {
        jsonWriter.close();
      }

      sendContent(status, channelBuffer, "application/json", ImmutableMultimap.<String, String>of());
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void sendString(HttpResponseStatus status, String data) {
    if (data == null) {
      sendStatus(status);
      return;
    }
    try {
      ChannelBuffer channelBuffer = ChannelBuffers.wrappedBuffer(Charsets.UTF_8.encode(data));
      sendContent(status, channelBuffer, "text/plain; charset=utf-8", ImmutableMultimap.<String, String>of());
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
    ChannelBuffer channelBuffer = ChannelBuffers.wrappedBuffer(bytes);
    sendContent(status, channelBuffer, "application/octet-stream", headers);
  }

  @Override
  public void sendBytes(HttpResponseStatus status, ByteBuffer buffer, @Nullable Multimap<String, String> headers) {
    sendContent(status, ChannelBuffers.wrappedBuffer(buffer), "application/octet-stream", headers);
  }

  @Override
  public final void sendError(HttpResponseStatus status, String errorMessage) {
    sendString(status, errorMessage);
  }
}

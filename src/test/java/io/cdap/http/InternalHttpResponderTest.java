/*
 * Copyright © 2014-2019 Cask Data, Inc.
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

package io.cdap.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.cdap.http.internal.InternalHttpResponder;
import io.cdap.http.internal.InternalHttpResponse;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import javax.annotation.Nullable;

/**
 *
 */
public class InternalHttpResponderTest {

  @Test
  public void testSendJson() throws IOException {
    InternalHttpResponder responder = new InternalHttpResponder();
    JsonObject output = new JsonObject();
    output.addProperty("data", "this is some data");
    responder.sendJson(HttpResponseStatus.OK, output.toString());

    InternalHttpResponse response = responder.getResponse();
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getStatusCode());
  
    Reader reader = null;
    try {
      reader = new InputStreamReader(response.openInputStream(), "UTF-8");
      JsonObject responseData = new Gson().fromJson(reader, JsonObject.class);
      Assert.assertEquals(output, responseData);
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
  }

  @Test
  public void testSendString() throws IOException {
    InternalHttpResponder responder = new InternalHttpResponder();
    responder.sendString(HttpResponseStatus.BAD_REQUEST, "bad request");

    validateResponse(responder.getResponse(), HttpResponseStatus.BAD_REQUEST, "bad request");
  }

  @Test
  public void testSendStatus() throws IOException {
    InternalHttpResponder responder = new InternalHttpResponder();
    responder.sendStatus(HttpResponseStatus.NOT_FOUND);

    validateResponse(responder.getResponse(), HttpResponseStatus.NOT_FOUND, null);
  }

  @Test
  public void testSendByteArray() throws IOException {
    InternalHttpResponder responder = new InternalHttpResponder();
    responder.sendByteArray(HttpResponseStatus.OK, "abc".getBytes(Charset.forName("UTF-8")), EmptyHttpHeaders.INSTANCE);

    validateResponse(responder.getResponse(), HttpResponseStatus.OK, "abc");
  }

  @Test
  public void testSendError() throws IOException {
    InternalHttpResponder responder = new InternalHttpResponder();
    responder.sendString(HttpResponseStatus.NOT_FOUND, "not found");

    validateResponse(responder.getResponse(), HttpResponseStatus.NOT_FOUND, "not found");
  }

  @Test
  public void testChunks() throws IOException {
    InternalHttpResponder responder = new InternalHttpResponder();
    ChunkResponder chunkResponder = responder.sendChunkStart(HttpResponseStatus.OK, null);
    chunkResponder.sendChunk(Unpooled.wrappedBuffer("a".getBytes(Charset.forName("UTF-8"))));
    chunkResponder.sendChunk(Unpooled.wrappedBuffer("b".getBytes(Charset.forName("UTF-8"))));
    chunkResponder.sendChunk(Unpooled.wrappedBuffer("c".getBytes(Charset.forName("UTF-8"))));
    chunkResponder.close();

    validateResponse(responder.getResponse(), HttpResponseStatus.OK, "abc");
  }

  @Test
  public void testSendContent() throws IOException {
    InternalHttpResponder responder = new InternalHttpResponder();
    responder.sendContent(HttpResponseStatus.OK, Unpooled.wrappedBuffer("abc".getBytes(Charset.forName("UTF-8"))),
                          new DefaultHttpHeaders().set(HttpHeaderNames.CONTENT_TYPE, "contentType"));

    validateResponse(responder.getResponse(), HttpResponseStatus.OK, "abc");
  }

  private void validateResponse(InternalHttpResponse response, HttpResponseStatus expectedStatus,
                                @Nullable String expectedData)
    throws IOException {
    int code = response.getStatusCode();
    Assert.assertEquals(expectedStatus.code(), code);
    if (expectedData != null) {
      // read it twice to make sure the input supplier gives the full stream more than once.
      for (int i = 0; i < 2; i++) {
        BufferedReader reader = null;
        try {
          reader = new BufferedReader(new InputStreamReader(response.openInputStream(), "UTF-8"));
          String data = reader.readLine();
          Assert.assertEquals(expectedData, data);
        } finally {
          if (reader != null) {
            reader.close();
          }
        }
      }
    }
  }
}

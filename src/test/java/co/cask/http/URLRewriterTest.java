/*
 * Copyright © 2014-2017 Cask Data, Inc.
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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Test URLRewriter.
 */
public class URLRewriterTest {
  private static final Gson GSON = new Gson();

  private static String hostname = "127.0.0.1";
  private static NettyHttpService service;
  private static URI baseURI;

  @BeforeClass
  public static void setup() throws Exception {

    NettyHttpService.Builder builder = NettyHttpService.builder("test-url-rewrite");
    builder.setHttpHandlers(new TestHandler());
    builder.setUrlRewriter(new TestURLRewriter());
    builder.setHost(hostname);

    service = builder.build();
    service.start();
    int port = service.getBindAddress().getPort();

    baseURI = URI.create(String.format("http://%s:%d", hostname, port));
  }

  @AfterClass
  public static void teardown() throws Exception {
    service.stop();
  }

  @Test
  public void testUrlRewrite() throws Exception {
    int status = doGet("/rewrite/test/v1/resource");
    Assert.assertEquals(HttpResponseStatus.OK.code(), status);

    HttpURLConnection urlConn = request("/rewrite/test/v1/tweets/7648", HttpMethod.PUT);
    Assert.assertEquals(HttpResponseStatus.OK.code(), urlConn.getResponseCode());
    Map<String, String> stringMap = GSON.fromJson(getContent(urlConn),
                                                  new TypeToken<Map<String, String>>() { }.getType());
    Assert.assertEquals(Collections.singletonMap("status", "Handled put in tweets end-point, id: 7648"), stringMap);

    urlConn.disconnect();
  }

  @Test
  public void testUrlRewriteNormalize() throws Exception {
    int status = doGet("/rewrite//test/v1//resource");
    Assert.assertEquals(HttpResponseStatus.OK.code(), status);
  }

  @Test
  public void testRegularCall() throws Exception {
    int status = doGet("/test/v1/resource");
    Assert.assertEquals(HttpResponseStatus.OK.code(), status);
  }

  @Test
  public void testUrlRewriteUnknownPath() throws Exception {
    int status = doGet("/rewrite/unknown/test/v1/resource");
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), status);
  }

  @Test
  public void testUrlRewriteRedirect() throws Exception {
    int status = doGet("/redirect/test/v1/resource");
    Assert.assertEquals(HttpResponseStatus.OK.code(), status);
  }

  private static class TestURLRewriter implements URLRewriter {
    @Override
    public boolean rewrite(HttpRequest request, HttpResponder responder) {
      if (request.uri().startsWith("/rewrite/")) {
        request.setUri(request.uri().replace("/rewrite/", "/"));
      }

      if (request.uri().startsWith("/redirect/")) {
        responder.sendStatus(HttpResponseStatus.MOVED_PERMANENTLY,
                             new DefaultHttpHeaders().set(HttpHeaderNames.LOCATION,
                                                          request.uri().replace("/redirect/", "/rewrite/")));
        return false;
      }
      return true;
    }
  }

  private static int doGet(String resource) throws Exception {
    return doGet(resource, Collections.<String, String>emptyMap());
  }

  private static int doGet(String resource, @Nullable Map<String, String> headers) throws Exception {
    URL url = baseURI.resolve(resource).toURL();
    HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
    try {
      if (headers != null) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
          urlConn.setRequestProperty(entry.getKey(), entry.getValue());
        }
      }
      return urlConn.getResponseCode();
    } finally {
      urlConn.disconnect();
    }
  }


  private HttpURLConnection request(String path, HttpMethod method) throws IOException {
    URL url = baseURI.resolve(path).toURL();
    HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
    if (method == HttpMethod.POST || method == HttpMethod.PUT) {
      urlConn.setDoOutput(true);
    }
    urlConn.setRequestMethod(method.name());

    return urlConn;
  }

  private String getContent(HttpURLConnection urlConn) throws IOException {
    ByteBuf buffer = Unpooled.buffer();
    InputStream is = urlConn.getInputStream();
    while (buffer.writeBytes(is, 1024) > 0) {
      // no-op
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}

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

import io.cdap.http.internal.HandlerInfo;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Tests handler hooks.
 */
public class HandlerHookTest {
  private static final Logger LOG = LoggerFactory.getLogger(HandlerHookTest.class);

  private static String hostname = "127.0.0.1";
  private static URI baseURI;
  private static NettyHttpService service;
  private static final TestHandlerHook handlerHook1 = new TestHandlerHook();
  private static final TestHandlerHook handlerHook2 = new TestHandlerHook();

  @BeforeClass
  public static void setup() throws Throwable {

    NettyHttpService.Builder builder = NettyHttpService.builder("test-hook");
    builder.setHttpHandlers(new TestHandler());
    builder.setHandlerHooks(Arrays.asList(handlerHook1, handlerHook2));
    builder.setHost(hostname);

    service = builder.build();
    service.start();
    int port = service.getBindAddress().getPort();
    baseURI = URI.create(String.format("http://%s:%d", hostname, port));
  }

  @Before
  public void reset() {
    handlerHook1.reset();
    handlerHook2.reset();
  }

  @Test
  public void testHandlerHookCall() throws Exception {
    int status = doGet("/test/v1/resource");
    Assert.assertEquals(HttpResponseStatus.OK.code(), status);

    awaitPostHook();
    Assert.assertEquals(1, handlerHook1.getNumPreCalls());
    Assert.assertEquals(1, handlerHook1.getNumPostCalls());

    Assert.assertEquals(1, handlerHook2.getNumPreCalls());
    Assert.assertEquals(1, handlerHook2.getNumPostCalls());
  }

  @Test
  public void testPreHookReject() throws Exception {
    int status = doGet("/test/v1/resource", "X-Request-Type", "Reject");
    Assert.assertEquals(HttpResponseStatus.NOT_ACCEPTABLE.code(), status);

    // Wait for any post handlers to be called
    TimeUnit.MILLISECONDS.sleep(100);
    Assert.assertEquals(1, handlerHook1.getNumPreCalls());

    // The second pre-call should not have happened due to rejection by the first pre-call
    // None of the post calls should have happened.
    Assert.assertEquals(0, handlerHook1.getNumPostCalls());
    Assert.assertEquals(0, handlerHook2.getNumPreCalls());
    Assert.assertEquals(0, handlerHook2.getNumPostCalls());
  }

  @Test
  public void testHandlerException() throws Exception {
    int status = doGet("/test/v1/exception");
    Assert.assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), status);

    awaitPostHook();
    Assert.assertEquals(1, handlerHook1.getNumPreCalls());
    Assert.assertEquals(1, handlerHook1.getNumPostCalls());

    Assert.assertEquals(1, handlerHook2.getNumPreCalls());
    Assert.assertEquals(1, handlerHook2.getNumPostCalls());
  }

  @Test
  public void testPreException() throws Exception {
    int status = doGet("/test/v1/resource", "X-Request-Type", "PreException");
    Assert.assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), status);

    // Wait for any post handlers to be called
    TimeUnit.MILLISECONDS.sleep(100);
    Assert.assertEquals(1, handlerHook1.getNumPreCalls());

    // The second pre-call should not have happened due to exception in the first pre-call
    // None of the post calls should have happened.
    Assert.assertEquals(0, handlerHook1.getNumPostCalls());
    Assert.assertEquals(0, handlerHook2.getNumPreCalls());
    Assert.assertEquals(0, handlerHook2.getNumPostCalls());
  }

  @Test
  public void testPostException() throws Exception {
    int status = doGet("/test/v1/resource", "X-Request-Type", "PostException");
    Assert.assertEquals(HttpResponseStatus.OK.code(), status);

    awaitPostHook();
    Assert.assertEquals(1, handlerHook1.getNumPreCalls());
    Assert.assertEquals(1, handlerHook1.getNumPostCalls());

    Assert.assertEquals(1, handlerHook2.getNumPreCalls());
    Assert.assertEquals(1, handlerHook2.getNumPostCalls());
  }

  @Test
  public void testUnknownPath() throws Exception {
    int status = doGet("/unknown/path/test/v1/resource");
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), status);

    // Wait for any post handlers to be called
    TimeUnit.MILLISECONDS.sleep(100);
    Assert.assertEquals(0, handlerHook1.getNumPreCalls());
    Assert.assertEquals(0, handlerHook1.getNumPostCalls());

    Assert.assertEquals(0, handlerHook2.getNumPreCalls());
    Assert.assertEquals(0, handlerHook2.getNumPostCalls());
  }

  @AfterClass
  public static void teardown() throws Throwable {
    service.stop();
  }

  private void awaitPostHook() throws Exception {
    handlerHook1.awaitPost();
    handlerHook2.awaitPost();
  }

  private static class TestHandlerHook extends AbstractHandlerHook {
    private volatile int numPreCalls = 0;
    private volatile int numPostCalls = 0;
    private final CyclicBarrier postBarrier = new CyclicBarrier(2);

    public int getNumPreCalls() {
      return numPreCalls;
    }

    public int getNumPostCalls() {
      return numPostCalls;
    }

    public void reset() {
      numPreCalls = 0;
      numPostCalls = 0;
    }

    public void awaitPost() throws Exception {
      postBarrier.await();
    }

    @Override
    public boolean preCall(HttpRequest request, HttpResponder responder, HandlerInfo handlerInfo) {
      ++numPreCalls;

      String header = request.headers().get("X-Request-Type");
      if (header != null && header.equals("Reject")) {
        responder.sendStatus(HttpResponseStatus.NOT_ACCEPTABLE);
        return false;
      }

      if (header != null && header.equals("PreException")) {
        throw new IllegalArgumentException("PreException");
      }

      return true;
    }

    @Override
    public void postCall(HttpRequest request, HttpResponseStatus status, HandlerInfo handlerInfo) {
      try {
        ++numPostCalls;

        String header = request.headers().get("X-Request-Type");
        if (header != null && header.equals("PostException")) {
          throw new IllegalArgumentException("PostException");
        }
      } finally {
        try {
          postBarrier.await();
        } catch (Exception e) {
          LOG.error("Got exception: ", e);
        }
      }
    }
  }

  private static int doGet(String resource) throws Exception {
    return doGet(resource, Collections.<String, String>emptyMap());
  }

  private static int doGet(String resource, String key, String value, String...keyValues) throws Exception {
    Map<String, String> headerMap = new HashMap<String, String>();
    headerMap.put(key, value);

    for (int i = 0; i < keyValues.length; i += 2) {
      headerMap.put(keyValues[i], keyValues[i + 1]);
    }
    return doGet(resource, headerMap);
  }

  private static int doGet(String resource, Map<String, String> headers) throws Exception {
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
}

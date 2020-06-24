/*
 * Copyright © 2014-2020 Cask Data, Inc.
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
import com.google.gson.reflect.TypeToken;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;

/**
 * Test the HttpServer.
 */
public class HttpServerTest {

  static {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
  }

  @ClassRule
  public static final TemporaryFolder TEMP_FOLDER = new TemporaryFolder();
  private static final Logger LOG = LoggerFactory.getLogger(HttpServerTest.class);

  private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();
  private static final Gson GSON = new Gson();
  private static final ExceptionHandler EXCEPTION_HANDLER = new ExceptionHandler() {
    @Override
    public void handle(Throwable t, HttpRequest request, HttpResponder responder) {
      if (t instanceof TestHandler.CustomException) {
        responder.sendStatus(TestHandler.CustomException.HTTP_RESPONSE_STATUS);
      } else {
        super.handle(t, request, responder);
      }
    }
  };

  protected static NettyHttpService service;

  protected static NettyHttpService.Builder createBaseNettyHttpServiceBuilder() {
    return NettyHttpService.builder("test")
      .setHttpHandlers(new TestHandler())
      .setHttpChunkLimit(75 * 1024)
      .setExceptionHandler(EXCEPTION_HANDLER)
      .setAuthHandler(new AuthHandler() {
        @Override
        public boolean isAuthenticated(HttpRequest request) {
          return request.headers().contains(HttpHeaderNames.AUTHORIZATION);
        }

        @Override
        public boolean hasRoles(HttpRequest request, String[] roles) {
          for (String role : roles) {
            if (request.headers().contains(HttpHeaderNames.AUTHORIZATION)) {
              if (!request.headers().get(HttpHeaderNames.AUTHORIZATION).contains(role)) {
                return false;
              }
            } else {
              return false;
            }
          }
          return true;
        }

        @Override
        public String getWWWAuthenticateHeader() {
          return "roles list";
        }
      })
      .setChannelPipelineModifier(new ChannelPipelineModifier() {
      @Override
      public void modify(ChannelPipeline pipeline) {
        pipeline.addAfter("codec", "testhandler", new TestChannelHandler());
      }
    });
  }

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();

  @BeforeClass
  public static void setup() throws Exception {
    service = createBaseNettyHttpServiceBuilder().build();
    service.start();
  }

  @AfterClass
  public static void teardown() throws Exception {
    String serviceName = service.getServiceName();
    service.stop();

    // After service shutdown, there shouldn't be any netty threads (NETTY-10)
    boolean passed = true;
    int count = 0;
    do {
      if (!passed) {
        TimeUnit.MILLISECONDS.sleep(100);
      }
      passed = true;
      for (Thread t : Thread.getAllStackTraces().keySet()) {
        String name = t.getName();
        boolean isNettyThread = name.startsWith(serviceName + "-executor-")
          || name.startsWith(serviceName + "-worker-thread-")
          || name.startsWith(serviceName + "-boss-thread-");
        if (isNettyThread) {
          passed = false;
          LOG.warn("Netty thread still alive in iteration {}: {}", count, t.getName());
          break;
        }
      }
    } while (!passed && ++count < 10);

    Assert.assertTrue("Some netty threads are still alive. Please see logs above", passed);
  }

  protected final URI getBaseURI() {
    return URI.create(String.format("%s://localhost:%d", service.isSSLEnabled() ? "https" : "http",
                                    service.getBindAddress().getPort()));
  }

  @Test
  public void testAuthSecured() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/auth/secured", HttpMethod.GET);
    Assert.assertEquals(401, urlConn.getResponseCode());
    urlConn.disconnect();

    urlConn = requestAuth("/test/v1/auth/secured", HttpMethod.GET, "");
    Assert.assertEquals(200, urlConn.getResponseCode());
    String result = getContent(urlConn);
    Assert.assertEquals("ALL GOOD", result);
    urlConn.disconnect();
  }

  @Test
  public void testAuthRoles() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/auth/roles", HttpMethod.GET);
    Assert.assertEquals(403, urlConn.getResponseCode());
    urlConn.disconnect();

    urlConn = requestAuth("/test/v1/auth/roles", HttpMethod.GET, "mod");
    Assert.assertEquals(403, urlConn.getResponseCode());
    urlConn.disconnect();

    urlConn = requestAuth("/test/v1/auth/roles", HttpMethod.GET, "admin");
    Assert.assertEquals(200, urlConn.getResponseCode());
    String result = getContent(urlConn);
    Assert.assertEquals("ALL GOOD", result);
    urlConn.disconnect();
  }

  @Test
  public void testAuthSecuredRoles() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/auth/secured-roles", HttpMethod.GET);
    Assert.assertEquals(401, urlConn.getResponseCode());
    urlConn.disconnect();

    urlConn = requestAuth("/test/v1/auth/secured-roles", HttpMethod.GET, "mod");
    Assert.assertEquals(403, urlConn.getResponseCode());
    urlConn.disconnect();

    urlConn = requestAuth("/test/v1/auth/secured-roles", HttpMethod.GET, "admin");
    Assert.assertEquals(200, urlConn.getResponseCode());
    String result = getContent(urlConn);
    Assert.assertEquals("ALL GOOD", result);
    urlConn.disconnect();
  }

  @Test
  public void testUploadDisconnect() throws Exception {
    File filePath = new File(tmpFolder.newFolder(), "test.txt");

    URI uri = getBaseURI().resolve("/test/v1/stream/upload/file");
    try (Socket socket = createRawSocket(uri.toURL())) {

      // Make a PUT call through socket, so that we can close it prematurely
      PrintStream printer = new PrintStream(socket.getOutputStream(), true, "UTF-8");
      printer.print("PUT " + uri.getPath() + " HTTP/1.1\r\n");
      printer.printf("Host: %s:%d\r\n", uri.getHost(), uri.getPort());
      printer.print("Transfer-Encoding: chunked\r\n");
      printer.print("File-Path: " + filePath.getAbsolutePath() + "\r\n");
      printer.print("\r\n");

      printer.print("5\r\n");
      printer.print("12345\r\n");
      printer.flush();

      int counter = 0;
      while (!filePath.exists() && counter < 100) {
        TimeUnit.MILLISECONDS.sleep(100);
        counter++;
      }
      Assert.assertTrue(counter < 100);
      // close the socket prematurely
    }

    // The file should get removed because of incomplete request due to connection closed
    int counter = 0;
    while (filePath.exists() && counter < 50) {
      TimeUnit.MILLISECONDS.sleep(100);
      counter++;
    }
    Assert.assertTrue(counter < 50);
  }

  @Test
  public void testSendFile() throws IOException {
    File filePath = new File(tmpFolder.newFolder(), "test.txt");
    HttpURLConnection urlConn = request("/test/v1/stream/file", HttpMethod.POST);
    // Enable accepting compression. For HTTP case, the response won't be compressed since FileRegion is being used.
    // For HTTPs, the response will be compressed.
    // See https://github.com/cdapio/netty-http/issues/68
    urlConn.setRequestProperty(HttpHeaderNames.ACCEPT_ENCODING.toString(), HttpHeaderValues.GZIP_DEFLATE.toString());
    urlConn.setRequestProperty("File-Path", filePath.getAbsolutePath());
    urlConn.getOutputStream().write("content".getBytes(StandardCharsets.UTF_8));
    Assert.assertEquals(200, urlConn.getResponseCode());
    String result = getContent(urlConn);
    Assert.assertEquals("content", result);
  }

  @Test
  public void testValidEndPoints() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/resource?num=10", HttpMethod.GET);
    Assert.assertEquals(200, urlConn.getResponseCode());
    String content = getContent(urlConn);

    Map<String, String> map = GSON.fromJson(content, STRING_MAP_TYPE);
    Assert.assertEquals(1, map.size());
    Assert.assertEquals("Handled get in resource end-point", map.get("status"));
    urlConn.disconnect();

    urlConn = request("/test/v1/tweets/1", HttpMethod.GET);
    Assert.assertEquals(200, urlConn.getResponseCode());
    content = getContent(urlConn);
    map = GSON.fromJson(content, STRING_MAP_TYPE);
    Assert.assertEquals(1, map.size());
    Assert.assertEquals("Handled get in tweets end-point, id: 1", map.get("status"));
    urlConn.disconnect();
  }


  @Test
  public void testSmallFileUpload() throws IOException {
    testStreamUpload(10);
  }

  @Test
  public void testLargeFileUpload() throws IOException {
    testStreamUpload(30 * 1024 * 1024);
  }


  private void testStreamUpload(int size) throws IOException {
    //create a random file to be uploaded.
    File fname = tmpFolder.newFile();
    RandomAccessFile randf = new RandomAccessFile(fname, "rw");
    randf.setLength(size);
    randf.close();

    //test stream upload
    HttpURLConnection urlConn = request("/test/v1/stream/upload", HttpMethod.PUT);
    Files.copy(fname.toPath(), urlConn.getOutputStream());
    Assert.assertEquals(200, urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testStreamUploadFailure() throws IOException {
    //create a random file to be uploaded.
    int size = 20 * 1024;
    File fname = tmpFolder.newFile();
    RandomAccessFile randf = new RandomAccessFile(fname, "rw");
    randf.setLength(size);
    randf.close();

    HttpURLConnection urlConn = request("/test/v1/stream/upload/fail", HttpMethod.PUT);
    Files.copy(fname.toPath(), urlConn.getOutputStream());
    Assert.assertEquals(500, urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testChunkAggregatedUpload() throws IOException {
    //create a random file to be uploaded.
    int size = 69 * 1024;
    File fname = tmpFolder.newFile();
    RandomAccessFile randf = new RandomAccessFile(fname, "rw");
    randf.setLength(size);
    randf.close();

    //test chunked upload
    HttpURLConnection urlConn = request("/test/v1/aggregate/upload", HttpMethod.PUT);
    urlConn.setChunkedStreamingMode(1024);
    Files.copy(fname.toPath(), urlConn.getOutputStream());
    Assert.assertEquals(200, urlConn.getResponseCode());

    Assert.assertEquals(size, Integer.parseInt(getContent(urlConn).split(":")[1].trim()));
    urlConn.disconnect();
  }

  @Test
  public void testChunkAggregatedUploadFailure() throws IOException {
    //create a random file to be uploaded.
    int size = 78 * 1024;
    File fname = tmpFolder.newFile();
    RandomAccessFile randf = new RandomAccessFile(fname, "rw");
    randf.setLength(size);
    randf.close();

    //test chunked upload
    HttpURLConnection urlConn = request("/test/v1/aggregate/upload", HttpMethod.PUT);
    urlConn.setChunkedStreamingMode(1024);
    Files.copy(fname.toPath(), urlConn.getOutputStream());
    Assert.assertEquals(413, urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testPathWithMultipleMethods() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/tweets/1", HttpMethod.GET);
    Assert.assertEquals(200, urlConn.getResponseCode());
    urlConn.disconnect();

    urlConn = request("/test/v1/tweets/1", HttpMethod.PUT);
    writeContent(urlConn, "data");
    Assert.assertEquals(200, urlConn.getResponseCode());
    urlConn.disconnect();
  }


  @Test
  public void testNonExistingEndPoints() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/users", HttpMethod.POST);
    writeContent(urlConn, "data");
    Assert.assertEquals(404, urlConn.getResponseCode());
    urlConn.disconnect();

    // Hit a valid endpoint
    urlConn = request("/test/v1/tweets/1", HttpMethod.GET, true);
    Assert.assertEquals(200, urlConn.getResponseCode());
    urlConn.getInputStream().close();
    urlConn.disconnect();

    // Reuse the connection to hit an invalid endpoint
    urlConn = request("/test/v1/users", HttpMethod.GET);
    Assert.assertEquals(404, urlConn.getResponseCode());
  }

  @Test
  public void testPutWithData() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/facebook/1/message", HttpMethod.PUT);
    writeContent(urlConn, "Hello, World");
    Assert.assertEquals(200, urlConn.getResponseCode());

    String content = getContent(urlConn);

    Map<String, String> map = GSON.fromJson(content, STRING_MAP_TYPE);
    Assert.assertEquals(1, map.size());
    Assert.assertEquals("Handled put in tweets end-point, id: 1. Content: Hello, World", map.get("result"));
    urlConn.disconnect();
  }

  @Test
  public void testPostWithData() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/facebook/1/message", HttpMethod.POST);
    writeContent(urlConn, "Hello, World");
    Assert.assertEquals(200, urlConn.getResponseCode());

    String content = getContent(urlConn);

    Map<String, String> map = GSON.fromJson(content, STRING_MAP_TYPE);
    Assert.assertEquals(1, map.size());
    Assert.assertEquals("Handled post in tweets end-point, id: 1. Content: Hello, World", map.get("result"));
    urlConn.disconnect();
  }

  @Test
  public void testNonExistingMethods() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/facebook/1/message", HttpMethod.GET);
    Assert.assertEquals(405, urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testKeepAlive() throws Exception {
    final URL url = getBaseURI().resolve("/test/v1/tweets/1").toURL();

    ThreadFactory threadFactory = new ThreadFactory() {
      private final AtomicInteger id = new AtomicInteger(0);

      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName("client-thread-" + id.getAndIncrement());
        return t;
      }
    };

    final BlockingQueue<HttpResponse> queue = new ArrayBlockingQueue<>(1);
    Bootstrap bootstrap = new Bootstrap()
      .channel(NioSocketChannel.class)
      .remoteAddress(url.getHost(), url.getPort())
      .group(new NioEventLoopGroup(10, threadFactory))
      .handler(new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel ch) throws Exception {
          ChannelPipeline pipeline = ch.pipeline();
          if ("https".equalsIgnoreCase(url.getProtocol())) {
            pipeline.addLast("ssl", createSslHandler(ch.alloc()));
          }
          pipeline.addLast("codec", new HttpClientCodec());
          pipeline.addLast("aggregator", new HttpObjectAggregator(8192));
          pipeline.addLast("handler", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
              queue.put((HttpResponse) msg);
            }
          });
        }
      });

    Channel channel = bootstrap.connect().sync().channel();

    // Make one request, expects the connection to remain active.
    HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, url.getPath(),
                                                     Unpooled.copiedBuffer("data", StandardCharsets.UTF_8));
    HttpUtil.setContentLength(request, 4);
    channel.writeAndFlush(request);
    HttpResponse response = queue.poll(10, TimeUnit.SECONDS);
    try {
      Assert.assertEquals(200, response.status().code());
      Assert.assertTrue(HttpUtil.isKeepAlive(response));
    } finally {
      ReferenceCountUtil.release(response);
    }

    // Make one more request, the connection should remain open.
    // This request is make with connection: closed
    request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, url.getPath(),
                                         Unpooled.copiedBuffer("data", StandardCharsets.UTF_8));
    HttpUtil.setContentLength(request, 4);
    HttpUtil.setKeepAlive(request, false);
    channel.writeAndFlush(request);
    response = queue.poll(10, TimeUnit.SECONDS);
    try {
      Assert.assertEquals(200, response.status().code());
      Assert.assertFalse(HttpUtil.isKeepAlive(response));
    } finally {
      ReferenceCountUtil.release(response);
    }

    // The channel should be closed.
    channel.closeFuture().await(10, TimeUnit.SECONDS);
    bootstrap.config().group().shutdownGracefully().await();
  }

  @Test
  public void testMultiplePathParameters() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/user/sree/message/12", HttpMethod.GET);
    Assert.assertEquals(200, urlConn.getResponseCode());

    String content = getContent(urlConn);

    Map<String, String> map = GSON.fromJson(content, STRING_MAP_TYPE);
    Assert.assertEquals(1, map.size());
    Assert.assertEquals("Handled multiple path parameters sree 12", map.get("result"));
    urlConn.disconnect();
  }

  //Test the end point where the parameter in path and order of declaration in method signature are different
  @Test
  public void testMultiplePathParametersWithParamterInDifferentOrder() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/message/21/user/sree", HttpMethod.GET);
    Assert.assertEquals(200, urlConn.getResponseCode());

    String content = getContent(urlConn);

    Map<String, String> map = GSON.fromJson(content, STRING_MAP_TYPE);
    Assert.assertEquals(1, map.size());
    Assert.assertEquals("Handled multiple path parameters sree 21", map.get("result"));
    urlConn.disconnect();
  }

  @Test
  public void testNotRoutablePathParamMismatch() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/NotRoutable/sree", HttpMethod.GET);
    Assert.assertEquals(500, urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testMultiMatchParamPut() throws Exception {
    HttpURLConnection urlConn = request("/test/v1/multi-match/bar", HttpMethod.PUT);
    Assert.assertEquals(405, urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testHandlerException() throws Exception {
    HttpURLConnection urlConn = request("/test/v1/uexception", HttpMethod.GET);
    Assert.assertEquals(500, urlConn.getResponseCode());
    Assert.assertEquals("Exception encountered while processing request : User Exception",
                        getContent(urlConn.getErrorStream()));
    urlConn.disconnect();
  }

  /**
   * Test that the TestChannelHandler that was added using the builder adds the correct header field and value.
   */
  @Test
  public void testChannelPipelineModification() throws Exception {
    HttpURLConnection urlConn = request("/test/v1/tweets/1", HttpMethod.GET);
    Assert.assertEquals(200, urlConn.getResponseCode());
    Assert.assertEquals(urlConn.getHeaderField(TestChannelHandler.HEADER_FIELD), TestChannelHandler.HEADER_VALUE);
  }

  @Test
  public void testMultiMatchFoo() throws Exception {
    testContent("/test/v1/multi-match/foo", "multi-match-get-actual-foo");
  }

  @Test
  public void testMultiMatchAll() throws Exception {
    testContent("/test/v1/multi-match/foo/baz/id", "multi-match-*");
  }

  @Test
  public void testMultiMatchParam() throws Exception {
    testContent("/test/v1/multi-match/bar", "multi-match-param-bar");
  }

  @Test
  public void testMultiMatchParamBar() throws Exception {
    testContent("/test/v1/multi-match/id/bar", "multi-match-param-bar-id");
  }

  @Test
  public void testMultiMatchFooParamBar() throws Exception {
    testContent("/test/v1/multi-match/foo/id/bar", "multi-match-foo-param-bar-id");
  }

  @Test
  public void testMultiMatchFooBarParam() throws Exception {
    testContent("/test/v1/multi-match/foo/bar/id", "multi-match-foo-bar-param-id");
  }

  @Test
  public void testMultiMatchFooBarParamId() throws Exception {
    testContent("/test/v1/multi-match/foo/bar/bar/bar", "multi-match-foo-bar-param-bar-id-bar");
  }

  @Test
  public void testMultiMatchFooBarParamId1() throws Exception {
    testContent("/test/v1/multi-match/foo/p/bar/baz", "multi-match-foo-param-bar-baz-p");
  }

  @Test
  public void testAppVersion() throws Exception {
    testContent("/test/v1/apps/app1/versions/v1/create", "new");
    testContent("/test/v1/apps/app1/flows/flow1/start", "old");
  }

  @Test
  public void testMultiMatchFooPut() throws Exception {
    testContent("/test/v1/multi-match/foo", "multi-match-put-actual-foo", HttpMethod.PUT);
  }

  @Test
  public void testChunkResponse() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/chunk", HttpMethod.POST);
    try {
      writeContent(urlConn, "Testing message");
      String response = getContent(urlConn);
      Assert.assertEquals("Testing message", response);
    } finally {
      urlConn.disconnect();
    }
  }

  @Test
  public void testLargeChunkResponse() throws IOException {
    // Chunk limit for test is 75K, so we request for 150 chunks, each is 1K in length
    HttpURLConnection urlConn = request("/test/v1/largeChunk?s=1024&n=150", HttpMethod.GET);
    try {
      String response = getContent(urlConn);
      String expected = String.join("", Collections.nCopies(150 * 1024, "0"));
      Assert.assertEquals(expected, response);
    } finally {
      urlConn.disconnect();
    }
  }

  @Test
  public void testStringQueryParam() throws IOException {
    // First send without query, for String type, should get defaulted to null.
    testContent("/test/v1/stringQueryParam/mypath", "mypath:null", HttpMethod.GET);

    // Then send with query, should response with the given name.
    testContent("/test/v1/stringQueryParam/mypath?name=netty", "mypath:netty", HttpMethod.GET);
  }

  @Test
  public void testPrimitiveQueryParam() throws IOException {
    // For primitive type, if missing parameter, should get defaulted to Java primitive default value.
    testContent("/test/v1/primitiveQueryParam", "0", HttpMethod.GET);

    testContent("/test/v1/primitiveQueryParam?age=20", "20", HttpMethod.GET);
  }

  @Test
  public void testSortedSetQueryParam() throws IOException {
    // For collection, if missing parameter, should get defaulted to empty collection
    SortedSet<Integer> expected = new TreeSet<>();
    testContent("/test/v1/sortedSetQueryParam", GSON.toJson(expected), HttpMethod.GET);

    expected.add(10);
    expected.add(20);
    expected.add(30);
    String expectedContent = GSON.toJson(expected);

    // Try different way of passing the ids, they should end up de-dup and sorted.
    testContent("/test/v1/sortedSetQueryParam?id=30&id=10&id=20&id=30", expectedContent, HttpMethod.GET);
    testContent("/test/v1/sortedSetQueryParam?id=10&id=30&id=20&id=20", expectedContent, HttpMethod.GET);
    testContent("/test/v1/sortedSetQueryParam?id=20&id=30&id=20&id=10", expectedContent, HttpMethod.GET);
  }

  @Test
  public void testListHeaderParam() throws IOException {
    List<String> names = Arrays.asList("name1", "name3", "name2", "name1");

    HttpURLConnection urlConn = request("/test/v1/listHeaderParam", HttpMethod.GET);
    for (String name : names) {
      urlConn.addRequestProperty("name", name);
    }

    Assert.assertEquals(200, urlConn.getResponseCode());
    Assert.assertEquals(names, GSON.fromJson(getContent(urlConn), new TypeToken<List<String>>() { }.getType()));
    urlConn.disconnect();
  }

  @Test
  public void testDefaultQueryParam() throws IOException {
    // Submit with no parameters. Each should get the default values.
    HttpURLConnection urlConn = request("/test/v1/defaultValue", HttpMethod.GET);
    Assert.assertEquals(200, urlConn.getResponseCode());
    JsonObject json = GSON.fromJson(getContent(urlConn), JsonObject.class);

    Type hobbyType = new TypeToken<List<String>>() { }.getType();

    Assert.assertEquals(30, json.get("age").getAsLong());
    Assert.assertEquals("hello", json.get("name").getAsString());
    Assert.assertEquals(Collections.singletonList("casking"),
                        GSON.<List<String>>fromJson(json.get("hobby").getAsJsonArray(), hobbyType));

    urlConn.disconnect();
  }

  @Test (timeout = 5000)
  public void testConnectionClose() throws Exception {
    URL url = getBaseURI().resolve("/test/v1/connectionClose").toURL();

    // Fire http request using raw socket so that we can verify the connection get closed by the server
    // after the response.
    try (Socket socket = createRawSocket(url)) {
      PrintStream printer = new PrintStream(socket.getOutputStream(), false, "UTF-8");
      printer.printf("GET %s HTTP/1.1\r\n", url.getPath());
      printer.printf("Host: %s:%d\r\n", url.getHost(), url.getPort());
      printer.print("\r\n");
      printer.flush();

      // Just read everything from the response. Since the server will close the connection, the read loop should
      // end with an EOF. Otherwise there will be timeout of this test case
      String response = getContent(socket.getInputStream());
      Assert.assertTrue(response.startsWith("HTTP/1.1 200 OK"));
    }
  }

  @Test
  public void testUploadReject() throws Exception {
    HttpURLConnection urlConn = request("/test/v1/uploadReject", HttpMethod.POST, true);
    try {
      urlConn.setChunkedStreamingMode(1024);
      urlConn.getOutputStream().write("Rejected Content".getBytes(StandardCharsets.UTF_8));
      try {
        urlConn.getInputStream();
        Assert.fail();
      } catch (IOException e) {
        // Expect to get exception since server response with 400. Just drain the error stream.
        InputStream errorStream = urlConn.getErrorStream();
        if (errorStream != null) {
          getContent(errorStream);
        }
        Assert.assertEquals(HttpResponseStatus.BAD_REQUEST.code(), urlConn.getResponseCode());
      }
    } finally {
      urlConn.disconnect();
    }
  }

  @Test
  public void testSleep() throws Exception {
    HttpURLConnection urlConn = request("/test/v1/sleep/5", HttpMethod.GET);
    Assert.assertEquals(200, urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testWrongMethod() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/customException", HttpMethod.GET);
    Assert.assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED.code(), urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testExceptionHandler() throws IOException {
    // exception in method
    HttpURLConnection urlConn = request("/test/v1/customException", HttpMethod.POST);
    Assert.assertEquals(TestHandler.CustomException.HTTP_RESPONSE_STATUS.code(), urlConn.getResponseCode());
    urlConn.disconnect();

    //create a random file to be uploaded.
    int size = 20 * 1024;
    File fname = tmpFolder.newFile();
    RandomAccessFile randf = new RandomAccessFile(fname, "rw");
    randf.setLength(size);
    randf.close();

    // exception in streaming method before body consumer is returned
    urlConn = request("/test/v1/stream/customException", HttpMethod.POST);
    urlConn.setRequestProperty("failOn", "start");
    Assert.assertEquals(TestHandler.CustomException.HTTP_RESPONSE_STATUS.code(), urlConn.getResponseCode());
    urlConn.disconnect();

    // exception in body consumer's chunk
    urlConn = request("/test/v1/stream/customException", HttpMethod.POST);
    urlConn.setRequestProperty("failOn", "chunk");
    Files.copy(fname.toPath(), urlConn.getOutputStream());
    Assert.assertEquals(TestHandler.CustomException.HTTP_RESPONSE_STATUS.code(), urlConn.getResponseCode());
    urlConn.disconnect();

    // exception in body consumer's onFinish
    urlConn = request("/test/v1/stream/customException", HttpMethod.POST);
    urlConn.setRequestProperty("failOn", "finish");
    Files.copy(fname.toPath(), urlConn.getOutputStream());
    Assert.assertEquals(TestHandler.CustomException.HTTP_RESPONSE_STATUS.code(), urlConn.getResponseCode());
    urlConn.disconnect();

    // exception in body consumer's handleError
    urlConn = request("/test/v1/stream/customException", HttpMethod.POST);
    urlConn.setRequestProperty("failOn", "error");
    Files.copy(fname.toPath(), urlConn.getOutputStream());
    Assert.assertEquals(TestHandler.CustomException.HTTP_RESPONSE_STATUS.code(), urlConn.getResponseCode());
    urlConn.disconnect();
  }

  @Test
  public void testBodyProducer() throws Exception {
    String chunk = "Message";
    int repeat = 100;
    File resultFolder = TEMP_FOLDER.newFolder();
    File successFile = new File(resultFolder, "success");
    File failureFile = new File(resultFolder, "failure");

    HttpURLConnection urlConn = request("/test/v1/produceBody?chunk=" + URLEncoder.encode(chunk, "UTF-8") +
                                          "&repeat=" + repeat +
                                          "&successFile=" + URLEncoder.encode(successFile.getAbsolutePath(), "UTF-8") +
                                          "&failureFile=" + URLEncoder.encode(failureFile.getAbsolutePath(), "UTF-8"),
                                        HttpMethod.GET);
    Assert.assertEquals(HttpResponseStatus.OK.code(), urlConn.getResponseCode());

    String body = getContent(urlConn);
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < repeat; i++) {
      expected.append(chunk).append(" ").append(i);
    }
    Assert.assertEquals(expected.toString(), body);

    int count = 0;
    while (!successFile.isFile() && count++ < 10) {
      TimeUnit.MILLISECONDS.sleep(10);
    }
    Assert.assertTrue(successFile.isFile());
    Assert.assertFalse(failureFile.isFile());
  }

  @Test
  public void testBodyProducerStatus() throws Exception {
    for (int status : Arrays.asList(200, 400, 404, 500)) {
      HttpURLConnection urlConn = request("/test/v1/produceBodyWithStatus?status=" + status, HttpMethod.GET);
      Assert.assertEquals(status, urlConn.getResponseCode());
    }
  }

  @Test
  public void testCompressResponse() throws Exception {
    HttpURLConnection urlConn = request("/test/v1/compressResponse?message=Testing+message", HttpMethod.GET);
    urlConn.setRequestProperty(HttpHeaderNames.ACCEPT_ENCODING.toString(), HttpHeaderValues.GZIP_DEFLATE.toString());

    Assert.assertEquals(HttpResponseStatus.OK.code(), urlConn.getResponseCode());
    Assert.assertNotNull(urlConn.getHeaderField(HttpHeaderNames.CONTENT_ENCODING.toString()));

    Assert.assertEquals("Testing message", getContent(urlConn));

    // Test with chunk encoding
    urlConn = request("/test/v1/compressResponse?chunk=true&message=Testing+message+chunk", HttpMethod.GET);
    urlConn.setRequestProperty(HttpHeaderNames.ACCEPT_ENCODING.toString(), HttpHeaderValues.GZIP_DEFLATE.toString());

    Assert.assertEquals(HttpResponseStatus.OK.code(), urlConn.getResponseCode());
    Assert.assertNotNull(urlConn.getHeaderField(HttpHeaderNames.CONTENT_ENCODING.toString()));

    Assert.assertEquals("Testing message chunk", getContent(urlConn));
  }

  @Test
  public void testContinueHandler() throws Exception {
    // Send two request with BodyConsumer handling with keep-alive and expect continue header
    // The second request should be using the same connection as the first one
    // This is to test the channel pipeline reconfiguration logic is correct
    for (int i = 0; i < 2; i++) {
      File filePath = new File(tmpFolder.newFolder(), "test.txt");
      HttpURLConnection urlConn = request("/test/v1/stream/file", HttpMethod.POST, true);
      urlConn.setRequestProperty("Expect", "100-continue");
      urlConn.setRequestProperty("File-Path", filePath.getAbsolutePath());

      urlConn.getOutputStream().write("content".getBytes(StandardCharsets.UTF_8));
      Assert.assertEquals(200, urlConn.getResponseCode());
      String result = getContent(urlConn);
      Assert.assertEquals("content", result);
      urlConn.getInputStream().close();
    }

    // Make two non-streaming requests with keep-alive
    for (int i = 0; i < 2; i++) {
      HttpURLConnection urlConn = request("/test/v1/tweets/1", HttpMethod.PUT, true);
      writeContent(urlConn, "data");
      Assert.assertEquals(200, urlConn.getResponseCode());
      urlConn.getInputStream().close();
    }
  }

  @Test
  public void testHeaders() throws IOException {
    HttpURLConnection urlConn = request("/test/v1/echoHeaders", HttpMethod.GET);
    urlConn.addRequestProperty("k1", "v1");
    urlConn.addRequestProperty("k1", "v2");
    urlConn.addRequestProperty("k1", "v3");
    urlConn.addRequestProperty("k2", "v1");

    Assert.assertEquals(200, urlConn.getResponseCode());
    Map<String, List<String>> headers = urlConn.getHeaderFields();

    Assert.assertEquals(new HashSet<>(Arrays.asList("v1", "v2", "v3")), new HashSet<>(headers.get("k1")));
    Assert.assertEquals(Collections.singletonList("v1"), headers.get("k2"));
  }

  protected Socket createRawSocket(URL url) throws IOException {
    return new Socket(url.getHost(), url.getPort());
  }

  protected HttpURLConnection requestAuth(String path, HttpMethod method, String role) throws IOException {
    URL url = getBaseURI().resolve(path).toURL();
    HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
    if (method == HttpMethod.POST || method == HttpMethod.PUT) {
      urlConn.setDoOutput(true);
    }
    urlConn.setRequestMethod(method.name());
    urlConn.setRequestProperty(HttpHeaderNames.AUTHORIZATION.toString(), role);

    return urlConn;
  }

  protected HttpURLConnection request(String path, HttpMethod method, boolean keepAlive) throws IOException {
    URL url = getBaseURI().resolve(path).toURL();
    HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
    if (method == HttpMethod.POST || method == HttpMethod.PUT) {
      urlConn.setDoOutput(true);
    }
    urlConn.setRequestMethod(method.name());
    if (!keepAlive) {
      urlConn.setRequestProperty(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE.toString());
    }

    return urlConn;
  }

  @Nullable
  protected SslHandler createSslHandler(ByteBufAllocator bufAllocator) throws Exception {
    return null;
  }

  private void testContent(String path, String content) throws IOException {
    testContent(path, content, HttpMethod.GET);
  }

  private void testContent(String path, String content, HttpMethod method) throws IOException {
    HttpURLConnection urlConn = request(path, method);
    Assert.assertEquals(200, urlConn.getResponseCode());
    Assert.assertEquals(content, getContent(urlConn));
    urlConn.disconnect();
  }

  private HttpURLConnection request(String path, HttpMethod method) throws IOException {
    return request(path, method, false);
  }

  private String getContent(HttpURLConnection urlConn) throws IOException {
    InputStream is = urlConn.getInputStream();
    String contentEncoding = urlConn.getHeaderField(HttpHeaderNames.CONTENT_ENCODING.toString());
    if (contentEncoding != null) {
      if ("gzip".equalsIgnoreCase(contentEncoding)) {
        is = new GZIPInputStream(is);
      } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
        is = new DeflaterInputStream(is);
      } else {
        throw new IllegalArgumentException("Unsupported content encoding " + contentEncoding);
      }
    }

    return getContent(is);
  }

  private String getContent(InputStream is) throws IOException {
    ByteBuf buffer = Unpooled.buffer();
    while (buffer.writeBytes(is, 1024) > 0) {
      // no-op
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private void writeContent(HttpURLConnection urlConn, String content) throws IOException {
    urlConn.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
  }
}

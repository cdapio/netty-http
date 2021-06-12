/*
 * Copyright © 2021 Cask Data, Inc.
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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.concurrent.EventExecutor;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

/**
 * Unit test for testing the threading behavior when {@link NettyHttpService.Builder#setExecThreadPoolSize(int)} is
 * set to non-zero.
 */
public class ExecutorThreadPoolTest {

  @Test
  public void testSameEventThread() throws Exception {
    AtomicReference<Thread> handlerThread = new AtomicReference<>();

    NettyHttpService httpService = NettyHttpService.builder("test")
      .setExecThreadPoolSize(5)
      .setHttpHandlers(new TestHandler(handlerThread))
      .setChannelPipelineModifier(new ChannelPipelineModifier() {
        @Override
        public void modify(ChannelPipeline pipeline) {
          // Modify the pipeline to insert a handler before the dispatcher with the same EventExecutor
          // This is to test the invocation of the dispatcher would always be in the same thread
          // as the handler inserted before it.
          EventExecutor executor = pipeline.context("dispatcher").executor();
          pipeline.addBefore(executor, "dispatcher", "authenticator", new TestChannelHandler(handlerThread));
        }
      })
      .build();

    httpService.start();
    try {
      InetSocketAddress addr = httpService.getBindAddress();
      URL url = new URL(String.format("http://%s:%d/upload", addr.getHostName(), addr.getPort()));
      HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
      try {
        urlConn.setRequestMethod("POST");
        urlConn.setDoOutput(true);
        urlConn.setChunkedStreamingMode(32);

        // The NonStickyEventExecutorGroup has max run of 1024. So we create a data of size > 32K.
        String data = String.join("", Collections.nCopies(32768, "0123456789"));
        try (OutputStream os = urlConn.getOutputStream()) {
          os.write(data.getBytes(StandardCharsets.UTF_8));
        }

        Assert.assertEquals(HttpURLConnection.HTTP_OK, urlConn.getResponseCode());
      } finally {
        urlConn.disconnect();
      }
    } finally {
      httpService.stop();
    }
  }

  /**
   * A {@link ChannelHandler} for testing executor thread behavior.
   */
  private static final class TestChannelHandler extends ChannelInboundHandlerAdapter {

    private final AtomicReference<Thread> handlerThread;

    private TestChannelHandler(AtomicReference<Thread> handlerThread) {
      this.handlerThread = handlerThread;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      handlerThread.set(Thread.currentThread());
      super.channelRead(ctx, msg);
    }
  }

  /**
   * Test handler for testing executor thread behavior.
   */
  public static final class TestHandler extends AbstractHttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TestHandler.class);

    private final AtomicReference<Thread> handlerThread;

    public TestHandler(AtomicReference<Thread> handlerThread) {
      this.handlerThread = handlerThread;
    }

    @POST
    @Path("/upload")
    public BodyConsumer upload(HttpRequest request, HttpResponder responder) {
      if (!Thread.currentThread().equals(handlerThread.get())) {
        responder.sendStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        return null;
      }

      return new BodyConsumer() {
        @Override
        public void chunk(ByteBuf request, HttpResponder responder) {
          if (!Thread.currentThread().equals(handlerThread.get())) {
            responder.sendStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            throw new IllegalStateException("Wrong thread " + Thread.currentThread() + " " + handlerThread.get());
          }
        }

        @Override
        public void finished(HttpResponder responder) {
          if (Thread.currentThread().equals(handlerThread.get())) {
            responder.sendStatus(HttpResponseStatus.OK);
          } else {
            responder.sendStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
          }
        }

        @Override
        public void handleError(Throwable cause) {
          LOG.error("Exception raised", cause);
        }
      };
    }
  }
}

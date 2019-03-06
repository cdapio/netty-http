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

import io.cdap.http.HttpResponder;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * RequestRouter that uses {@code HttpMethodHandler} to determine the http-handler method Signature of http request. It
 * uses this signature to dynamically configure the Netty Pipeline. Http Handler methods with return-type BodyConsumer
 * will be streamed , while other methods will use HttpChunkAggregator
 */

public class RequestRouter extends ChannelInboundHandlerAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(HttpDispatcher.class);

  private final int chunkMemoryLimit;
  private final HttpResourceHandler httpMethodHandler;
  private final AtomicBoolean exceptionRaised;
  private final boolean sslEnabled;

  private HttpMethodInfo methodInfo;

  public RequestRouter(HttpResourceHandler methodHandler, int chunkMemoryLimit, boolean sslEnabled) {
    this.httpMethodHandler = methodHandler;
    this.chunkMemoryLimit = chunkMemoryLimit;
    this.exceptionRaised = new AtomicBoolean(false);
    this.sslEnabled = sslEnabled;
  }

  /**
   * If the HttpRequest is valid and handled it will be sent upstream, if it cannot be invoked
   * the response will be written back immediately.
   */
  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    try {
      if (exceptionRaised.get()) {
        return;
      }

      if (!(msg instanceof HttpRequest)) {
        // If there is no methodInfo, it means the request was already rejected.
        // What we received here is just residue of the request, which can be ignored.
        if (methodInfo != null) {
          ReferenceCountUtil.retain(msg);
          ctx.fireChannelRead(msg);
        }
        return;
      }
      HttpRequest request = (HttpRequest) msg;
      BasicHttpResponder responder = new BasicHttpResponder(ctx.channel(), sslEnabled);

      // Reset the methodInfo for the incoming request error handling
      methodInfo = null;
      methodInfo = prepareHandleMethod(request, responder, ctx);

      if (methodInfo != null) {
        ReferenceCountUtil.retain(msg);
        ctx.fireChannelRead(msg);
      } else {
        if (!responder.isResponded()) {
          // If not yet responded, just respond with a not found and close the connection
          HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
          HttpUtil.setContentLength(response, 0);
          HttpUtil.setKeepAlive(response, false);
          ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
          // If already responded, just close the connection
          ctx.channel().close();
        }
      }
    } finally {
      ReferenceCountUtil.release(msg);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    String exceptionMessage = "Exception caught in channel processing.";
    if (exceptionRaised.compareAndSet(false, true)) {
      if (methodInfo != null) {
        methodInfo.sendError(HttpResponseStatus.INTERNAL_SERVER_ERROR, cause);
        methodInfo = null;
      } else {
        if (cause instanceof HandlerException) {
          HttpResponse response = ((HandlerException) cause).createFailureResponse();
          HttpUtil.setKeepAlive(response, false);
          // trace logs for user errors, error logs for internal server errors
          if (isUserError(response)) {
            LOG.trace(exceptionMessage, cause);
          } else {
            LOG.error(exceptionMessage, cause);
          }
          ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
          LOG.error(exceptionMessage, cause);
          HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                              HttpResponseStatus.INTERNAL_SERVER_ERROR);
          HttpUtil.setContentLength(response, 0);
          HttpUtil.setKeepAlive(response, false);
          ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
      }
    } else {
      LOG.trace(exceptionMessage, cause);
    }
  }

  /**
   * If return type of the handler http method is BodyConsumer, remove {@link HttpObjectAggregator} class if it exists;
   * otherwise adds the {@link HttpObjectAggregator} to the pipeline if its not present already.
   */
  @Nullable
  private HttpMethodInfo prepareHandleMethod(HttpRequest httpRequest,
                                             HttpResponder responder, ChannelHandlerContext ctx) throws Exception {
    HttpMethodInfo methodInfo = httpMethodHandler.getDestinationMethod(httpRequest, responder);

    if (methodInfo == null) {
      return null;
    }

    ctx.channel().attr(HttpDispatcher.METHOD_INFO_KEY).set(methodInfo);

    ChannelPipeline pipeline = ctx.channel().pipeline();
    if (methodInfo.isStreaming()) {
      if (pipeline.get("aggregator") != null) {
        pipeline.remove("aggregator");
      }
      if (pipeline.get("continue") == null) {
        pipeline.addAfter("router", "continue", new HttpServerExpectContinueHandler());
      }
    } else {
      if (pipeline.get("continue") != null) {
        pipeline.remove("continue");
      }
      if (pipeline.get("aggregator") == null) {
        pipeline.addAfter("router", "aggregator", new HttpObjectAggregator(chunkMemoryLimit));
      }
    }
    return methodInfo;
  }

  private boolean isUserError(HttpResponse response) {
    int code = response.status().code();
    return code == HttpResponseStatus.BAD_REQUEST.code() || code == HttpResponseStatus.NOT_FOUND.code() ||
      code == HttpResponseStatus.METHOD_NOT_ALLOWED.code();
  }
}

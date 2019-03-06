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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

/**
 * HttpDispatcher that invokes the appropriate http-handler method. The handler and the arguments are read
 * from the {@code RequestRouter} context.
 */

public class HttpDispatcher extends ChannelInboundHandlerAdapter {

  public static final AttributeKey<HttpMethodInfo> METHOD_INFO_KEY = AttributeKey.newInstance("methodInfo");

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    HttpMethodInfo methodInfo = ctx.channel().attr(METHOD_INFO_KEY).get();

    try {
      if (methodInfo == null) {
        // This shouldn't happen
        throw new IllegalStateException("No handler dispatch information available");
      }
      if (msg instanceof HttpRequest) {
        methodInfo.invoke((HttpRequest) msg);
      } else if (msg instanceof HttpContent) {
        methodInfo.chunk((HttpContent) msg);
      } else {
        // Since the release will be called in finally, we retain the count before delegating to downstream
        ReferenceCountUtil.retain(msg);
        ctx.fireChannelRead(msg);
      }
    } finally {
      ReferenceCountUtil.release(msg);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    HttpMethodInfo methodInfo = ctx.channel().attr(METHOD_INFO_KEY).getAndSet(null);
    if (methodInfo != null) {
      methodInfo.disconnected();
    }
  }
}

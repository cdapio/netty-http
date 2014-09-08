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

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Test ChannelHandler that adds a default header to every response.
 */
public class TestChannelHandler extends SimpleChannelDownstreamHandler {
  protected static final String HEADER_FIELD = "testHeaderField";
  protected static final String HEADER_VALUE = "testHeaderValue";

  @Override
  public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    Object message = e.getMessage();
    if (!(message instanceof HttpResponse)) {
      super.writeRequested(ctx, e);
      return;
    }
    HttpResponse response = (HttpResponse) message;
    response.addHeader(HEADER_FIELD, HEADER_VALUE);
    super.writeRequested(ctx, e);
  }
}

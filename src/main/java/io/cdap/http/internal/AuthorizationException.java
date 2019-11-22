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

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

/**
 * Exception that gets thrown when a request tries to access a resource that requires roles
 * and {@link io.cdap.http.AuthHandler#hasRoles(HttpRequest, String[])} doesn't accept it.
 */
public class AuthorizationException extends HandlerException {

  public AuthorizationException() {
    super(HttpResponseStatus.UNAUTHORIZED, "Request doesn't satisfy role requirement.");
  }

  @Override
  public HttpResponse createFailureResponse() {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN,
        Unpooled.copiedBuffer("FORBIDDEN", StandardCharsets.UTF_8));
    HttpUtil.setContentLength(response, response.content().readableBytes());
    return response;
  }
}

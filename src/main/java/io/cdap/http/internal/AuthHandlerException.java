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
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

/**
 * Exception that gets thrown when a request tries to access a secured resource
 * and the AuthHandler doesn't accept it.
 */
public class AuthHandlerException extends HandlerException {

    public AuthHandlerException(String message) {
        super(HttpResponseStatus.UNAUTHORIZED, message);
    }

    public HttpResponse createFailureResponse() {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED,
                Unpooled.copiedBuffer("UNAUTHORIZED", StandardCharsets.UTF_8));
        response.headers().add("WWW-Authenticate", getMessage());
        HttpUtil.setContentLength(response, response.content().readableBytes());
        return response;
    }
}

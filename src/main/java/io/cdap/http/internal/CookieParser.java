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

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses cookies from a request.
 */
public class CookieParser {
  private final boolean strict;

  public CookieParser(boolean strict) {
    this.strict = strict;
  }

  public Map<String, Cookie> parseCookies(HttpRequest request) {
    List<String> headers = request.headers().getAll(HttpHeaderNames.COOKIE);
    if (headers == null || headers.isEmpty()) {
      return Collections.emptyMap();
    }
    ServerCookieDecoder decoder = getCookieDecoder();
    Map<String, Cookie> cookies = new LinkedHashMap<>();
    for (String value : headers) {
      for (Cookie cookie : decoder.decode(value)) {
        cookies.put(cookie.name(), cookie);
      }
    }
    return cookies;
  }

  private ServerCookieDecoder getCookieDecoder() {
    return strict ? ServerCookieDecoder.STRICT : ServerCookieDecoder.LAX;
  }
}

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

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;


public class CookieParserTest {
  private CookieParser cookieParser = new CookieParser(false);

  @Test
  public void testCookieDecode() {
    List<Cookie> cookies = Arrays.asList(
        new DefaultCookie("c1", "v1"),
        new DefaultCookie("c2", "v2")
    );
    String cookieHeader = ClientCookieEncoder.LAX.encode(cookies);
    HttpHeaders headers = new DefaultHttpHeaders()
        .set(HttpHeaderNames.COOKIE, cookieHeader);
    DefaultHttpRequest request = new DefaultHttpRequest(HTTP_1_1, GET, "test", headers);
    Map<String, Cookie> parsed = cookieParser.parseCookies(request);
    Assert.assertEquals(new HashSet<>(Arrays.asList("c1", "c2")), parsed.keySet());
    Assert.assertEquals(new HashSet<>(cookies), new HashSet<>(parsed.values()));
  }
}

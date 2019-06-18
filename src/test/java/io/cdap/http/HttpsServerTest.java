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

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslHandler;
import org.junit.BeforeClass;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

/**
 * Test the HttpsServer.
 */
public class HttpsServerTest extends HttpServerTest {

  private static SSLClientContext sslClientContext;

  @BeforeClass
  public static void setup() throws Throwable {
    File keyStore = tmpFolder.newFile();
  
    InputStream is = null;
    try {
      is = SSLKeyStoreTest.class.getClassLoader().getResourceAsStream("cert.jks");
      FileUtils.copy(is, keyStore.getPath());
    } finally {
      if (is != null) {
        is.close();
      }
    }

    /* IMPORTANT
     * Provide Certificate Configuration Here * *
     * enableSSL(<SSLConfig>)
     * KeyStore : SSL certificate
     * KeyStorePassword : Key Store Password
     * CertificatePassword : Certificate password if different from Key Store password or null
    */

    NettyHttpService.Builder builder = createBaseNettyHttpServiceBuilder();
    builder.enableSSL(SSLConfig.builder(keyStore, "secret").setCertificatePassword("secret")
                        .build());

    sslClientContext = new SSLClientContext();
    service = builder.build();
    service.start();
  }

  @Override
  protected HttpURLConnection request(String path, HttpMethod method, boolean keepAlive) throws IOException {
    URL url = getBaseURI().resolve(path).toURL();
    HttpsURLConnection.setDefaultSSLSocketFactory(sslClientContext.getClientContext().getSocketFactory());
    HostnameVerifier allHostsValid = new HostnameVerifier() {
      @Override
      public boolean verify(String hostname, SSLSession session) {
        return true;
      }
    };

    // Install the all-trusting host verifier
    HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
    HttpsURLConnection urlConn = (HttpsURLConnection) url.openConnection();
    if (method == HttpMethod.POST || method == HttpMethod.PUT) {
      urlConn.setDoOutput(true);
    }
    urlConn.setRequestMethod(method.name());
    if (!keepAlive) {
      urlConn.setRequestProperty(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE.toString());
    }
    return urlConn;
  }

  @Override
  protected Socket createRawSocket(URL url) throws IOException {
    return sslClientContext.getClientContext().getSocketFactory().createSocket(url.getHost(), url.getPort());
  }

  @Nullable
  @Override
  protected SslHandler createSslHandler(ByteBufAllocator bufAllocator) throws Exception {
    SSLEngine sslEngine = sslClientContext.getClientContext().createSSLEngine();
    sslEngine.setUseClientMode(true);
    return new SslHandler(sslEngine);
  }

  static void setSslClientContext(SSLClientContext sslClientContext) {
    HttpsServerTest.sslClientContext = sslClientContext;
  }
}

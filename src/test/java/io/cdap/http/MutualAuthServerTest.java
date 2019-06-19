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

import org.junit.BeforeClass;

import java.io.File;
import java.io.InputStream;
import java.net.URI;

/**
 * Test the HttpsServer with mutual authentication.
 */
public class MutualAuthServerTest extends HttpsServerTest {

  @BeforeClass
  public static void setup() throws Throwable {
    NettyHttpService.Builder builder = createBaseNettyHttpServiceBuilder();

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

    File trustKeyStore = tmpFolder.newFile();
    is = null;
    try {
      is = SSLKeyStoreTest.class.getClassLoader().getResourceAsStream("client.jks");
      FileUtils.copy(is, trustKeyStore.getPath());
    } finally {
      if (is != null) {
        is.close();
      }
    }

    String keyStorePassword = "secret";
    String trustKeyStorePassword = "password";
    builder.enableSSL(SSLConfig.builder(keyStore, keyStorePassword).setTrustKeyStore(trustKeyStore)
                        .setTrustKeyStorePassword(trustKeyStorePassword)
                        .build());

    setSslClientContext(new SSLClientContext(trustKeyStore, trustKeyStorePassword));
    service = builder.build();
    service.start();

    int port = service.getBindAddress().getPort();
    baseURI = URI.create(String.format("https://localhost:%d", port));
  }
}

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
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;

/**
 * Tests SSL KeyStore behaviour
 */
public class SSLKeyStoreTest {
  private static File keyStore;

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();

  @BeforeClass
  public static void setup() throws Exception {
    keyStore = tmpFolder.newFile();
    InputStream is = null;
    try {
      is = SSLKeyStoreTest.class.getClassLoader().getResourceAsStream("cert.jks");
      FileUtils.copy(is, keyStore.getPath());
    } finally {
      if (is != null) {
        is.close();
      }
    }
  }

  @Test (expected = IllegalArgumentException.class)
  public void testSslCertPathConfiguration1() throws IllegalArgumentException {
    //Bad Certificate Path
    new SSLHandlerFactory(SSLConfig.builder(new File("badCertificate"), "secret").setCertificatePassword("secret")
                            .build());
  }

  @Test (expected = IllegalArgumentException.class)
  public void testSslCertPathConfiguration2() throws IllegalArgumentException {
    //Null Certificate Path
    new SSLHandlerFactory(SSLConfig.builder(null, "secret").setCertificatePassword("secret").build());
  }

  @Test (expected = IllegalArgumentException.class)
  public void testSslKeyStorePassConfiguration2() throws IllegalArgumentException {
    //Missing Key Pass
    new SSLHandlerFactory(SSLConfig.builder(keyStore, null).setCertificatePassword("secret").build());
  }

  @Test
  public void testSslCertPassConfiguration() throws IllegalArgumentException {
    //Bad Cert Pass
    new SSLHandlerFactory(SSLConfig.builder(keyStore, "secret").build());
  }
}

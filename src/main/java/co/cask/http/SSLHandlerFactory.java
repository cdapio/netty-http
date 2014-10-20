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

import org.jboss.netty.handler.ssl.SslHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

/**
 * A class that encapsulates SSL Certificate Information.
 */
public class SSLHandlerFactory {
  private static final String protocol = "TLS";
  private final SSLContext serverContext;
  private boolean needClientAuth;

  public SSLHandlerFactory(SSLConfig sslConfig) {
    if (sslConfig.getKeyStore() == null) {
      throw new IllegalArgumentException("Certificate Path Not Configured");
    }
    if (sslConfig.getKeyStorePassword() == null) {
      throw new IllegalArgumentException("KeyStore Password Not Configured");
    }

    String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
    if (algorithm == null) {
      algorithm = "SunX509";
    }

    try {
      KeyStore ks = getKeyStore(sslConfig.getKeyStore(), sslConfig.getKeyStorePassword());
      // Set up key manager factory to use our key store
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
      kmf.init(ks, (sslConfig.getCertificatePassword() != null) ? sslConfig.getCertificatePassword().toCharArray()
        : sslConfig.getKeyStorePassword().toCharArray());
      TrustManagerFactory tmf = null;
      if (sslConfig.getTrustKeyStore() != null) {
        if (sslConfig.getTrustKeyStorePassword() == null) {
          throw new IllegalArgumentException("KeyStore Password Not Configured");
        }
        this.needClientAuth = true;
        KeyStore tks = getKeyStore(sslConfig.getTrustKeyStore(), sslConfig.getTrustKeyStorePassword());
        tmf = TrustManagerFactory.getInstance(algorithm);
        tmf.init(tks);
      }

      serverContext = SSLContext.getInstance(protocol);
      serverContext.init(kmf.getKeyManagers(), tmf != null ? tmf.getTrustManagers() : null, null);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to initialize the server-side SSLContext", e);
    }
  }

  private static KeyStore getKeyStore(File keyStore, String keyStorePassword) throws Exception {
    KeyStore ks = KeyStore.getInstance("JKS");
    InputStream inputStream = new FileInputStream(keyStore);
    try {
      ks.load(inputStream, keyStorePassword.toCharArray());
    } finally {
      inputStream.close();
    }
    return ks;
  }

  public SslHandler create() {
    SSLEngine engine = serverContext.createSSLEngine();
    engine.setNeedClientAuth(needClientAuth);
    engine.setUseClientMode(false);
    return new SslHandler(engine);
  }

}

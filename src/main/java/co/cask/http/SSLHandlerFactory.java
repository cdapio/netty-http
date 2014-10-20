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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.CertificateException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

/**
 * A class that encapsulates SSL Certificate Information.
 */
public class SSLHandlerFactory {
  private static final Logger LOG = LoggerFactory.getLogger(SSLHandlerFactory.class);

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
      kmf.init(ks, sslConfig.getCertificatePassword() != null ? sslConfig.getCertificatePassword().toCharArray()
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

  private static KeyStore getKeyStore(File keyStore, String keyStorePassword) throws KeyStoreLoadException {
    InputStream inputStream = null;
    KeyStore ks = null;
    try {
      ks = KeyStore.getInstance("JKS");
      inputStream = new FileInputStream(keyStore);
      ks.load(inputStream, keyStorePassword.toCharArray());
    } catch (FileNotFoundException e) {
      throw new KeyStoreLoadException(e);
    } catch (CertificateException e) {
      throw new KeyStoreLoadException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new KeyStoreLoadException(e);
    } catch (KeyStoreException e) {
      throw new KeyStoreLoadException(e);
    } catch (IOException e) {
      throw new KeyStoreLoadException(e);
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          LOG.error("Failed to close an input stream: {}", e);
        }
      }
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

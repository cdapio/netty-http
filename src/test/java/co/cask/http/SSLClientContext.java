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

import com.google.common.base.Throwables;
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
import java.security.cert.CertificateException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Provides Client Context
 * Used by HttpsServerTest
 */
public class SSLClientContext {

  private static final Logger LOG = LoggerFactory.getLogger(SSLClientContext.class);

  private SSLContext clientContext;
  private String protocol = "TLS";

  public SSLClientContext() {
    this(null, null);
  }

  public SSLClientContext(File keyStore, String keyStorePassword) {

    try {
      KeyManagerFactory kmf = null;
      if (keyStore != null && keyStorePassword != null) {
        KeyStore ks = getKeyStore(keyStore, keyStorePassword);
        kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, keyStorePassword.toCharArray());
      }
      clientContext = SSLContext.getInstance(protocol);
      clientContext.init(kmf == null ? null : kmf.getKeyManagers(), TrustManagerFactory.getTrustManagers(), null);
    } catch (Exception e) {
      throw Throwables.propagate(new Exception("Failed to initialize the client-side SSLContext", e));
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

  public SSLContext getClientContext() {
    return clientContext;
  }
}

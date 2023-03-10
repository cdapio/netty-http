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

import java.io.File;

/**
 * A class that encapsulates SSLContext configuration.
 */
public class SSLConfig {
  private final File keyStore;
  private final String keyStorePassword;
  private final String certificatePassword;
  private final File trustKeyStore;
  private final String trustKeyStorePassword;
  private final long sessionTimeoutInSeconds;
  private final long sessionCacheSize;

  private SSLConfig(File keyStore, String keyStorePassword,
                    String certificatePassword, File trustKeyStore, String trustKeyStorePassword,
                    long sessionCacheSize, long sessionTimeoutInSeconds) {
    this.keyStore = keyStore;
    this.keyStorePassword = keyStorePassword;
    this.certificatePassword = certificatePassword;
    this.trustKeyStore = trustKeyStore;
    this.trustKeyStorePassword = trustKeyStorePassword;
    this.sessionCacheSize = sessionCacheSize;
    this.sessionTimeoutInSeconds = sessionTimeoutInSeconds;
  }

  /**
   * @return KeyStore file
   */
  public File getKeyStore() {
    return keyStore;
  }

  /**
   * @return KeyStore password.
   */
  public String getKeyStorePassword() {
    return keyStorePassword;
  }

  /**
   * @return certificate password
   */
  public String getCertificatePassword() {
    return certificatePassword;
  }

  /**
   * @return trust KeyStore file
   */
  public File getTrustKeyStore() {
    return trustKeyStore;
  }

  /**
   * @return trust KeyStore password.
   */
  public String getTrustKeyStorePassword() {
    return trustKeyStorePassword;
  }

  /**
   * @return size of cache used for storing SSL session objects.
   */
  public long getSessionCacheSize() {
    return sessionCacheSize;
  }

  /**
   * @return timeout for the cached SSL session objects, in seconds.
   */
  public long getSessionTimeoutInSeconds() {
    return sessionTimeoutInSeconds;
  }

  /**
   * Creates a builder for the SSLConfig.
   *
   * @param keyStore the keystore
   * @param keyStorePassword the password for the keystore
   * @return instance of {@code Builder}
   */
  public static Builder builder(File keyStore, String keyStorePassword) {
    return new Builder(keyStore, keyStorePassword);
  }

  /**
   * Builder to help create the SSLConfig.
   */
  public static class Builder {
    private final File keyStore;
    private final String keyStorePassword;
    private String certificatePassword;
    private File trustKeyStore;
    private String trustKeyStorePassword;
    private long sessionTimeoutInSeconds;
    private long sessionCacheSize;

    private Builder(File keyStore, String keyStorePassword) {
      this.keyStore = keyStore;
      this.keyStorePassword = keyStorePassword;
      this.sessionCacheSize = 10000L;
      this.sessionTimeoutInSeconds = 60L;
    }

    /**
     * Set the certificate password for KeyStore.
     *
     * @param certificatePassword certificate password
     * @return instance of {@code Builder}.
     */
    public Builder setCertificatePassword(String certificatePassword) {
      this.certificatePassword = certificatePassword;
      return this;
    }

    /**
     * Set trust KeyStore file.
     *
     * @param trustKeyStore trust KeyStore file.
     * @return an instance of {@code Builder}.
     */
    public Builder setTrustKeyStore(File trustKeyStore) {
      this.trustKeyStore = trustKeyStore;
      return this;
    }

    /**
     * Set the SSL session object timeout in seconds.
     *
     * @param sessionTimeoutInSeconds time in seconds.
     * @return an instance of {@code Builder}.
     */
    public Builder setSessionTimeoutInSecond(long sessionTimeoutInSeconds) {
      this.sessionTimeoutInSeconds = sessionTimeoutInSeconds;
      return this;
    }

    /**
     * Set the SSL session object cache.
     *
     * @param sessionCacheSize size of SSL session object to be cached.
     * @return an instance of {@code Builder}.
     */
    public Builder setSessionCacheSize(long sessionCacheSize) {
      this.sessionCacheSize = sessionCacheSize;
      return this;
    }

    /**
     * Set trust KeyStore password.
     *
     * @param trustKeyStorePassword trust KeyStore password.
     * @return an instance of {@code Builder}.
     */
    public Builder setTrustKeyStorePassword(String trustKeyStorePassword) {
      if (trustKeyStorePassword == null) {
        throw new IllegalArgumentException("KeyStore Password Not Configured");
      }
      this.trustKeyStorePassword = trustKeyStorePassword;
      return this;
    }

    /**
     * @return instance of {@code SSLConfig}
     */
    public SSLConfig build() {
      if (keyStore == null) {
        throw new IllegalArgumentException("Certificate File Not Configured");
      }
      if (keyStorePassword == null) {
        throw new IllegalArgumentException("KeyStore Password Not Configured");
      }
      return new SSLConfig(keyStore, keyStorePassword, certificatePassword, trustKeyStore, trustKeyStorePassword,
              sessionCacheSize, sessionTimeoutInSeconds);
    }
  }
}

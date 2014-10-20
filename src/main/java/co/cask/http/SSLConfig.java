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

import java.io.File;

/**
 * A class that encapsulates SSLContext configuration.
 */
public class SSLConfig {
  private File keyStore;
  private String keyStorePassword;
  private String certificatePassword;
  private File trustKeyStore;
  private String trustKeyStorePassword;

  private SSLConfig(File keyStore, String keyStorePassword,
                    String certificatePassword, File trustKeyStore, String trustKeyStorePassword) {
    this.keyStore = keyStore;
    this.keyStorePassword = keyStorePassword;
    this.certificatePassword = certificatePassword;
    this.trustKeyStore = trustKeyStore;
    this.trustKeyStorePassword = trustKeyStorePassword;
  }

  public File getKeyStore() {
    return keyStore;
  }

  public String getKeyStorePassword() {
    return keyStorePassword;
  }

  public String getCertificatePassword() {
    return certificatePassword;
  }

  public File getTrustKeyStore() {
    return trustKeyStore;
  }

  public String getTrustKeyStorePassword() {
    return trustKeyStorePassword;
  }

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

    private Builder(File keyStore, String keyStorePassword) {
      this.keyStore = keyStore;
      this.keyStorePassword = keyStorePassword;
    }

    public Builder setCertificatePassword(String certificatePassword) {
      this.certificatePassword = certificatePassword;
      return this;
    }

    public Builder setTrustKeyStore(File trustKeyStore) {
      this.trustKeyStore = trustKeyStore;
      return this;
    }

    public Builder setTrustKeyStorePassword(String trustKeyStorePassword) {
      this.trustKeyStorePassword = trustKeyStorePassword;
      return this;
    }

    public SSLConfig build() {
      return new SSLConfig(keyStore, keyStorePassword, certificatePassword, trustKeyStore, trustKeyStorePassword);
    }
  }
}

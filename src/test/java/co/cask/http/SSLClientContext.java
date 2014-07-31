/*
 * Copyright 2014 Cask, Inc.
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

import javax.net.ssl.SSLContext;

/**
 * Provides Client Context
 * Used by HttpsServerTest
 */
public class SSLClientContext {
  private SSLContext clientContext;
  private String protocol = "TLS";

  public SSLClientContext() throws Exception {

    try {
      clientContext = SSLContext.getInstance(protocol);
      clientContext.init(null, TrustManagerFactory.getTrustManagers(), null);
    } catch (Exception e) {
      throw Throwables.propagate(new Exception("Failed to initialize the client-side SSLContext", e));
    }
  }

  public SSLContext getClientContext() {
    return clientContext;
  }
}

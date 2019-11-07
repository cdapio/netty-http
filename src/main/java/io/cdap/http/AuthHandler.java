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
package io.cdap.http;

import io.netty.handler.codec.http.HttpRequest;

/**
 * Basic interface for implementing an AuthHandler.
 */
public interface AuthHandler {
    /**
     * This method is responsible for deciding whether a request is authenticated.
     *
     * @param request the HttpRequest in question.
     * @return <code>true</code> if this request should be handled.
     * @see Secured
     */
    public boolean isAuthenticated(HttpRequest request);

    /**
     * This method is responsible for deciding whether a request meets the role requirement.
     *
     * @param request the HttpRequest in question.
     * @param roles the roles that are required for this request to be handled.
     * @return <code>true</code> if this request should be handled.
     * @see RequiredRoles
     */
    public boolean hasRoles(HttpRequest request, String[] roles);

    /**
     * Returns the value for the <code>WWW-Authenticate</code> header field that will be
     * set for requests which were rejected by {@link #isAuthenticated(HttpRequest)}
     * or {@link #hasRoles(HttpRequest, String[])}.
     */
    public String getWWWAuthenticateHeader();
}

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

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import org.apache.commons.beanutils.ConvertUtils;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import javax.ws.rs.PathParam;

/**
 * HttpResourceModel contains information needed to handle Http call for a given path. Used as a destination in
 * {@code PatternPathRouterWithGroups} to route URI paths to right Http end points.
 */
public final class HttpResourceModel {

  private static final Logger LOG = LoggerFactory.getLogger(HttpResourceModel.class);
  private final Set<HttpMethod> httpMethods;
  private final String path;
  private final Method method;
  private final HttpHandler handler;

  /**
   * Construct a resource model with HttpMethod, method that handles httprequest, Object that contains the method.
   *
   * @param httpMethods Set of http methods that is handled by the resource.
   * @param path path associated with this model.
   * @param method handler that handles the http request.
   * @param handler instance {@code HttpHandler}.
   */
  public HttpResourceModel(Set<HttpMethod> httpMethods, String path, Method method, HttpHandler handler) {
    this.httpMethods = httpMethods;
    this.path = path;
    this.method = method;
    this.handler = handler;
  }

  /**
   * @return httpMethods.
   */
  public Set<HttpMethod> getHttpMethod() {
    return httpMethods;
  }

  /**
   * @return path associated with this model.
   */
  public String getPath() {
    return path;
  }

  /**
   * @return handler method that handles an http end-point.
   */
  public Method getMethod() {
    return method;
  }

  /**
   * @return instance of {@code HttpHandler}.
   */
  public HttpHandler getHttpHandler() {
    return handler;
  }

  /**
   * Handle http Request.
   *
   * @param request  HttpRequest to be handled.
   * @param responder HttpResponder to write the response.
   * @param groupValues Values needed for the invocation.
   */

  public HttpMethodInfo handle(HttpRequest request, HttpResponder responder, Map<String, String> groupValues)
    throws Exception {

    //TODO: Refactor group values.
    try {
      if (httpMethods.contains(request.getMethod())) {
        //Setup args for reflection call
        Object [] args = new Object[method.getParameterTypes().length - 2];

        if (method.getParameterTypes().length > 2) {
          int parameterIndex = 0;
          Class<?>[] parameterTypes = method.getParameterTypes();
          Annotation[][] parameterAnnotations = method.getParameterAnnotations();
          for (int i = 2; i < parameterAnnotations.length; i++) {
            Annotation[] annotations = parameterAnnotations[i];

            boolean foundPathParam = false;
            for (Annotation annotation : annotations) {
              if (annotation.annotationType().isAssignableFrom(PathParam.class)) {
                PathParam param = (PathParam) annotation;
                String value = groupValues.get(param.value());
                Preconditions.checkArgument(value != null, "Could not resolve value for parameter %s", param.value());
                args[parameterIndex] = ConvertUtils.convert(value, parameterTypes[parameterIndex + 2]);
                parameterIndex++;
                foundPathParam = true;
                break;
              }
            }
            Preconditions.checkArgument(foundPathParam, "Missing @PathParam annotation for parameter in method %s.",
                                        method.getName());
          }
        }
        return new HttpMethodInfo(method, handler, request, responder, args);
      } else {
        //Found a matching resource but could not find the right HttpMethod so return 405
        throw new HandlerException(HttpResponseStatus.METHOD_NOT_ALLOWED, String.format
          ("Problem accessing: %s. Reason: Method Not Allowed", request.getUri()));
      }
    } catch (Throwable e) {
      throw new HandlerException(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                 String.format("Error in executing path:"));
    }
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
      .add("httpMethods", httpMethods)
      .add("path", path)
      .add("method", method)
      .add("handler", handler)
      .toString();
  }
}

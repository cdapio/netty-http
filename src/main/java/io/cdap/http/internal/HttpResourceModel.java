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

package io.cdap.http.internal;

import io.cdap.http.ExceptionHandler;
import io.cdap.http.HttpHandler;
import io.cdap.http.HttpResponder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

/**
 * HttpResourceModel contains information needed to handle Http call for a given path. Used as a destination in
 * {@code PatternPathRouterWithGroups} to route URI paths to right Http end points.
 */
public final class HttpResourceModel {

  private static final Set<Class<? extends Annotation>> SUPPORTED_PARAM_ANNOTATIONS =
    Collections.unmodifiableSet(new HashSet<Class<? extends Annotation>>
            (Arrays.asList(PathParam.class, QueryParam.class, HeaderParam.class)));

  private final Set<HttpMethod> httpMethods;
  private final String path;
  private final Method method;
  private final HttpHandler handler;
  private final List<Map<Class<? extends Annotation>, ParameterInfo<?>>> paramsInfo;
  private final ExceptionHandler exceptionHandler;

  /**
   * Construct a resource model with HttpMethod, method that handles httprequest, Object that contains the method.
   *
   * @param httpMethods Set of http methods that is handled by the resource.
   * @param path path associated with this model.
   * @param method handler that handles the http request.
   * @param handler instance {@code HttpHandler}.
   */
  public HttpResourceModel(Set<HttpMethod> httpMethods, String path, Method method, HttpHandler handler,
                           ExceptionHandler exceptionHandler) {
    this.httpMethods = httpMethods;
    this.path = path;
    this.method = method;
    this.handler = handler;
    this.paramsInfo = createParametersInfos(method);
    this.exceptionHandler = exceptionHandler;
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
  @SuppressWarnings("unchecked")
  public HttpMethodInfo handle(HttpRequest request,
                               HttpResponder responder, Map<String, String> groupValues) throws Exception {
    //TODO: Refactor group values.
    try {
      if (httpMethods.contains(request.method())) {
        //Setup args for reflection call
        Object [] args = new Object[paramsInfo.size()];

        int idx = 0;
        for (Map<Class<? extends Annotation>, ParameterInfo<?>> info : paramsInfo) {
          if (info.containsKey(PathParam.class)) {
            args[idx] = getPathParamValue(info, groupValues);
          }
          if (info.containsKey(QueryParam.class)) {
            args[idx] = getQueryParamValue(info, request.uri());
          }
          if (info.containsKey(HeaderParam.class)) {
            args[idx] = getHeaderParamValue(info, request);
          }
          idx++;
        }

        return new HttpMethodInfo(method, handler, responder, args, exceptionHandler);
      } else {
        //Found a matching resource but could not find the right HttpMethod so return 405
        throw new HandlerException(HttpResponseStatus.METHOD_NOT_ALLOWED, String.format
          ("Problem accessing: %s. Reason: Method Not Allowed", request.uri()));
      }
    } catch (Throwable e) {
      throw new HandlerException(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                 String.format("Error in executing request: %s %s", request.method(),
                                               request.uri()), e);
    }
  }

  @Override
  public String toString() {
    return "HttpResourceModel{" +
      "httpMethods=" + httpMethods +
      ", path='" + path + '\'' +
      ", method=" + method +
      ", handler=" + handler +
      '}';
  }

  @SuppressWarnings("unchecked")
  private Object getPathParamValue(Map<Class<? extends Annotation>, ParameterInfo<?>> annotations,
                                   Map<String, String> groupValues) throws Exception {
    ParameterInfo<String> info = (ParameterInfo<String>) annotations.get(PathParam.class);
    PathParam pathParam = info.getAnnotation();
    String value = groupValues.get(pathParam.value());
    if (value == null) {
      throw new IllegalArgumentException("Could not resolve value for path parameter " + pathParam.value());
    }
    return info.convert(value);
  }

  @SuppressWarnings("unchecked")
  private Object getQueryParamValue(Map<Class<? extends Annotation>, ParameterInfo<?>> annotations,
                                    String uri) throws Exception {
    ParameterInfo<List<String>> info = (ParameterInfo<List<String>>) annotations.get(QueryParam.class);
    QueryParam queryParam = info.getAnnotation();
    List<String> values = new QueryStringDecoder(uri).parameters().get(queryParam.value());

    return (values == null) ? info.convert(defaultValue(annotations)) : info.convert(values);
  }

  @SuppressWarnings("unchecked")
  private Object getHeaderParamValue(Map<Class<? extends Annotation>, ParameterInfo<?>> annotations,
                                     HttpRequest request) throws Exception {
    ParameterInfo<List<String>> info = (ParameterInfo<List<String>>) annotations.get(HeaderParam.class);
    HeaderParam headerParam = info.getAnnotation();
    String headerName = headerParam.value();
    boolean hasHeader = request.headers().contains(headerName);
    return hasHeader ? info.convert(request.headers().getAll(headerName)) : info.convert(defaultValue(annotations));
  }

  /**
   * Returns a List of String created based on the {@link DefaultValue} if it is presented in the annotations Map.
   *
   * @return a List of String or an empty List if {@link DefaultValue} is not presented
   */
  private List<String> defaultValue(Map<Class<? extends Annotation>, ParameterInfo<?>> annotations) {
    ParameterInfo<?> defaultInfo = annotations.get(DefaultValue.class);
    if (defaultInfo == null) {
      return Collections.emptyList();
    }

    DefaultValue defaultValue = defaultInfo.getAnnotation();
    return Collections.singletonList(defaultValue.value());
  }

  /**
   * Gathers all parameters' annotations for the given method, starting from the third parameter.
   */
  private List<Map<Class<? extends Annotation>, ParameterInfo<?>>> createParametersInfos(Method method) {
    if (method.getParameterTypes().length <= 2) {
      return Collections.emptyList();
    }

    List<Map<Class<? extends Annotation>, ParameterInfo<?>>> result =
            new ArrayList<Map<Class<? extends Annotation>, ParameterInfo<?>>>();
    Type[] parameterTypes = method.getGenericParameterTypes();
    Annotation[][] parameterAnnotations = method.getParameterAnnotations();

    for (int i = 2; i < parameterAnnotations.length; i++) {
      Annotation[] annotations = parameterAnnotations[i];
      Map<Class<? extends Annotation>, ParameterInfo<?>> paramAnnotations =
              new IdentityHashMap<Class<? extends Annotation>, ParameterInfo<?>>();

      for (Annotation annotation : annotations) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        ParameterInfo<?> parameterInfo;

        if (PathParam.class.isAssignableFrom(annotationType)) {
          parameterInfo = ParameterInfo.create(annotation,
                                               ParamConvertUtils.createPathParamConverter(parameterTypes[i]));
        } else if (QueryParam.class.isAssignableFrom(annotationType)) {
          parameterInfo = ParameterInfo.create(annotation,
                                               ParamConvertUtils.createQueryParamConverter(parameterTypes[i]));
        } else if (HeaderParam.class.isAssignableFrom(annotationType)) {
          parameterInfo = ParameterInfo.create(annotation,
                                               ParamConvertUtils.createHeaderParamConverter(parameterTypes[i]));
        } else {
          parameterInfo = ParameterInfo.create(annotation, null);
        }

        paramAnnotations.put(annotationType, parameterInfo);
      }

      // Must have either @PathParam, @QueryParam or @HeaderParam, but not two or more.
      int presence = 0;
      for (Class<? extends Annotation> annotationClass : paramAnnotations.keySet()) {
        if (SUPPORTED_PARAM_ANNOTATIONS.contains(annotationClass)) {
          presence++;
        }
      }
      if (presence != 1) {
        throw new IllegalArgumentException(
          String.format("Must have exactly one annotation from %s for parameter %d in method %s",
                        SUPPORTED_PARAM_ANNOTATIONS, i, method));
      }

      result.add(Collections.unmodifiableMap(paramAnnotations));
    }

    return Collections.unmodifiableList(result);
  }

  /**
   * A container class to hold information about a handler method parameters.
   */
  private static final class ParameterInfo<T> {
    private final Annotation annotation;
    private final Converter<T, Object> converter;

    static <V> ParameterInfo<V> create(Annotation annotation, @Nullable Converter<V, Object> converter) {
      return new ParameterInfo<V>(annotation, converter);
    }

    private ParameterInfo(Annotation annotation, @Nullable Converter<T, Object> converter) {
      this.annotation = annotation;
      this.converter = converter;
    }

    @SuppressWarnings("unchecked")
    <V extends Annotation> V getAnnotation() {
      return (V) annotation;
    }

    Object convert(T input) throws Exception {
      return (converter == null) ? null : converter.convert(input);
    }
  }
}

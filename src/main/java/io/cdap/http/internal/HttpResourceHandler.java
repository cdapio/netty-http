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

import io.cdap.http.BodyConsumer;
import io.cdap.http.ExceptionHandler;
import io.cdap.http.HandlerContext;
import io.cdap.http.HandlerHook;
import io.cdap.http.HttpHandler;
import io.cdap.http.HttpResponder;
import io.cdap.http.URLRewriter;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nullable;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;

/**
 * HttpResourceHandler handles the http request. HttpResourceHandler looks up all Jax-rs annotations in classes
 * and dispatches to appropriate method on receiving requests.
 */
public final class HttpResourceHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(HttpResourceHandler.class);
  // Limit the number of parts of the path so that match score calculation during runtime does not overflow
  private static final int MAX_PATH_PARTS = 25;

  private final PatternPathRouterWithGroups<HttpResourceModel> patternRouter =
    PatternPathRouterWithGroups.create(MAX_PATH_PARTS);
  private final Iterable<HttpHandler> handlers;
  private final Iterable<HandlerHook> handlerHooks;
  private final URLRewriter urlRewriter;

  /**
   * Construct HttpResourceHandler. Reads all annotations from all the handler classes and methods passed in, constructs
   * patternPathRouter which is routable by path to {@code HttpResourceModel} as destination of the route.
   *
   * @param handlers Iterable of HttpHandler.
   * @param handlerHooks Iterable of HandlerHook.
   * @param urlRewriter URL re-writer.
   * @param exceptionHandler Exception handler
   */
  public HttpResourceHandler(Iterable<? extends HttpHandler> handlers, Iterable<? extends HandlerHook> handlerHooks,
                             URLRewriter urlRewriter, ExceptionHandler exceptionHandler) {
    //Store the handlers to call init and destroy on all handlers.
    this.handlers = copyOf(handlers);
    this.handlerHooks = copyOf(handlerHooks);
    this.urlRewriter = urlRewriter;

    for (HttpHandler handler : handlers) {
      LOG.trace("Parsing handler {}", handler.getClass().getName());
      String basePath = "";
      if (handler.getClass().isAnnotationPresent(Path.class)) {
        basePath =  handler.getClass().getAnnotation(Path.class).value();
      }

      for (Method method : handler.getClass().getDeclaredMethods()) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length >= 2
          && (params[0].isAssignableFrom(HttpRequest.class) || params[0].isAssignableFrom(FullHttpRequest.class))
          && params[1].isAssignableFrom(HttpResponder.class)
          && Modifier.isPublic(method.getModifiers())) {

          // For streaming consumption, the first param cannot be FullHttpMessage
          if (BodyConsumer.class.isAssignableFrom(method.getReturnType())
            && params[0].isAssignableFrom(FullHttpMessage.class)) {
            throw new IllegalArgumentException(
              "Method with return type as BodyConsumer cannot have FullHttpMessage as the first argument: " + method);
          }

          String relativePath = "";
          if (method.getAnnotation(Path.class) != null) {
            relativePath = method.getAnnotation(Path.class).value();
          }
          String absolutePath = String.format("%s/%s", basePath, relativePath);
          Set<HttpMethod> httpMethods = getHttpMethods(method);
          if (httpMethods.isEmpty()) {
            throw new IllegalArgumentException("No HttpMethod found for handler method " + method);
          }
          try {
            HttpResourceModel resourceModel = new HttpResourceModel(httpMethods, absolutePath, method,
                                                                    handler, exceptionHandler);
            LOG.trace("Adding resource model {}", resourceModel);
            patternRouter.add(absolutePath, resourceModel);
          } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create http handler from method "
                                                 + method + " in handler class " + handler.getClass().getName(), e);
          }
        } else {
          LOG.trace("Not adding method {}({}) to path routing like. HTTP calls will not be routed to this method",
                    method.getName(), params);
        }
      }
    }
  }

  /**
   * Fetches the HttpMethod from annotations and returns String representation of HttpMethod.
   * Return emptyString if not present.
   *
   * @param method Method handling the http request.
   * @return String representation of HttpMethod from annotations or emptyString as a default.
   */
  private Set<HttpMethod> getHttpMethods(Method method) {
    Set<HttpMethod> httpMethods = new HashSet<HttpMethod>();

    if (method.isAnnotationPresent(GET.class)) {
      httpMethods.add(HttpMethod.GET);
    }
    if (method.isAnnotationPresent(PUT.class)) {
      httpMethods.add(HttpMethod.PUT);
    }
    if (method.isAnnotationPresent(POST.class)) {
      httpMethods.add(HttpMethod.POST);
    }
    if (method.isAnnotationPresent(DELETE.class)) {
      httpMethods.add(HttpMethod.DELETE);
    }
    if (method.isAnnotationPresent(OPTIONS.class)) {
      httpMethods.add(HttpMethod.OPTIONS);
    }

    return Collections.unmodifiableSet(httpMethods);
  }

  /**
   * Call the appropriate handler for handling the httprequest. 404 if path is not found. 405 if path is found but
   * httpMethod does not match what's configured.
   *
   * @param request instance of {@code HttpRequest}
   * @param responder instance of {@code HttpResponder} to handle the request.
   */
  public void handle(HttpRequest request, HttpResponder responder) {
    if (urlRewriter != null) {
      try {
        request.setUri(URI.create(request.uri()).normalize().toString());
        if (!urlRewriter.rewrite(request, responder)) {
          return;
        }
      } catch (Throwable t) {
        responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                             String.format("Caught exception processing request. Reason: %s",
                                          t.getMessage()));
        LOG.error("Exception thrown during rewriting of uri {}", request.uri(), t);
        return;
      }
    }

    try {
      String path = URI.create(request.uri()).normalize().getPath();

      List<PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel>> routableDestinations
        = patternRouter.getDestinations(path);

      PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel> matchedDestination =
        getMatchedDestination(routableDestinations, request.method(), path);

      if (matchedDestination != null) {
        //Found a httpresource route to it.
        HttpResourceModel httpResourceModel = matchedDestination.getDestination();

        // Call preCall method of handler hooks.
        boolean terminated = false;
        HandlerInfo info = new HandlerInfo(httpResourceModel.getMethod().getDeclaringClass().getName(),
                                           httpResourceModel.getMethod().getName());
        for (HandlerHook hook : handlerHooks) {
          if (!hook.preCall(request, responder, info)) {
            // Terminate further request processing if preCall returns false.
            terminated = true;
            break;
          }
        }

        // Call httpresource method
        if (!terminated) {
          // Wrap responder to make post hook calls.
          responder = new WrappedHttpResponder(responder, handlerHooks, request, info);
          if (httpResourceModel.handle(request, responder, matchedDestination.getGroupNameValues()).isStreaming()) {
            responder.sendString(HttpResponseStatus.METHOD_NOT_ALLOWED,
                                 String.format("Body Consumer not supported for internalHttpResponder: %s",
                                               request.uri()));
          }
        }
      } else if (routableDestinations.size() > 0)  {
        //Found a matching resource but could not find the right HttpMethod so return 405
        responder.sendString(HttpResponseStatus.METHOD_NOT_ALLOWED,
                             String.format("Problem accessing: %s. Reason: Method Not Allowed", request.uri()));
      } else {
        responder.sendString(HttpResponseStatus.NOT_FOUND, String.format("Problem accessing: %s. Reason: Not Found",
                                                                         request.uri()));
      }
    } catch (Throwable t) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           String.format("Caught exception processing request. Reason: %s", t.getMessage()));
      LOG.error("Exception thrown during request processing for uri {}", request.uri(), t);
    }
  }

  /**
   * Call the appropriate handler for handling the httprequest. 404 if path is not found. 405 if path is found but
   * httpMethod does not match what's configured.
   *
   * @param request instance of {@code HttpRequest}
   * @param responder instance of {@code HttpResponder} to handle the request.
   * @return HttpMethodInfo object, null if urlRewriter rewrite returns false, also when method cannot be invoked.
   */
  @Nullable
  public HttpMethodInfo getDestinationMethod(HttpRequest request, HttpResponder responder) throws Exception {
    if (urlRewriter != null) {
      try {
        request.setUri(URI.create(request.uri()).normalize().toString());
        if (!urlRewriter.rewrite(request, responder)) {
          return null;
        }
      } catch (Throwable t) {
        LOG.error("Exception thrown during rewriting of uri {}", request.uri(), t);
        throw new HandlerException(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                   String.format("Caught exception processing request. Reason: %s", t.getMessage()));
      }
    }

    try {
      String path = URI.create(request.uri()).normalize().getPath();

      List<PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel>> routableDestinations =
        patternRouter.getDestinations(path);

      PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel> matchedDestination =
        getMatchedDestination(routableDestinations, request.method(), path);

      if (matchedDestination != null) {
        HttpResourceModel httpResourceModel = matchedDestination.getDestination();

        // Call preCall method of handler hooks.
        boolean terminated = false;
        HandlerInfo info = new HandlerInfo(httpResourceModel.getMethod().getDeclaringClass().getName(),
                                           httpResourceModel.getMethod().getName());
        for (HandlerHook hook : handlerHooks) {
          if (!hook.preCall(request, responder, info)) {
            // Terminate further request processing if preCall returns false.
            terminated = true;
            break;
          }
        }

        // Call httpresource handle method, return the HttpMethodInfo Object.
        if (!terminated) {
          // Wrap responder to make post hook calls.
          responder = new WrappedHttpResponder(responder, handlerHooks, request, info);
          return httpResourceModel.handle(request, responder, matchedDestination.getGroupNameValues());
        }
      } else if (routableDestinations.size() > 0)  {
        //Found a matching resource but could not find the right HttpMethod so return 405
        throw new HandlerException(HttpResponseStatus.METHOD_NOT_ALLOWED, request.uri());
      } else {
        throw new HandlerException(HttpResponseStatus.NOT_FOUND,
                                   String.format("Problem accessing: %s. Reason: Not Found", request.uri()));
      }
    } catch (Throwable t) {
      if (t instanceof HandlerException) {
        throw (HandlerException) t;
      }
      throw new HandlerException(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                 String.format("Caught exception processing request. Reason: %s", t.getMessage()), t);
    }
    return null;
  }

  /**
   * Get HttpResourceModel which matches the HttpMethod of the request.
   *
   * @param routableDestinations List of ResourceModels.
   * @param targetHttpMethod HttpMethod.
   * @param requestUri request URI.
   * @return RoutableDestination that matches httpMethod that needs to be handled. null if there are no matches.
   */
  private PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel>
  getMatchedDestination(List<PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel>> routableDestinations,
                        HttpMethod targetHttpMethod, String requestUri) {

    LOG.trace("Routable destinations for request {}: {}", requestUri, routableDestinations);
    Iterable<String> requestUriParts = splitAndOmitEmpty(requestUri, '/');
    List<PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel>> matchedDestinations =
            new ArrayList<PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel>>();
    long maxScore = 0;

    for (PatternPathRouterWithGroups.RoutableDestination<HttpResourceModel> destination : routableDestinations) {
      HttpResourceModel resourceModel = destination.getDestination();

      for (HttpMethod httpMethod : resourceModel.getHttpMethod()) {
        if (targetHttpMethod.equals(httpMethod)) {
          long score = getWeightedMatchScore(requestUriParts, splitAndOmitEmpty(resourceModel.getPath(), '/'));
          LOG.trace("Max score = {}. Weighted score for {} is {}. ", maxScore, destination, score);
          if (score > maxScore) {
            maxScore = score;
            matchedDestinations.clear();
            matchedDestinations.add(destination);
          } else if (score == maxScore) {
            matchedDestinations.add(destination);
          }
        }
      }
    }

    if (matchedDestinations.size() > 1) {
      throw new IllegalStateException(String.format("Multiple matched handlers found for request uri %s: %s",
                                                    requestUri, matchedDestinations));
    } else if (matchedDestinations.size() == 1) {
      return matchedDestinations.get(0);
    }
    return null;
  }

  /**
   * Generate a weighted score based on position for matches of URI parts.
   * The matches are weighted in descending order from left to right.
   * Exact match is weighted higher than group match, and group match is weighted higher than wildcard match.
   *
   * @param requestUriParts the parts of request URI
   * @param destUriParts the parts of destination URI
   * @return weighted score
   */
  private long getWeightedMatchScore(Iterable<String> requestUriParts, Iterable<String> destUriParts) {
    // The score calculated below is a base 5 number
    // The score will have one digit for one part of the URI
    // This will allow for 27 parts in the path since log (Long.MAX_VALUE) to base 5 = 27.13
    // We limit the number of parts in the path to 25 using MAX_PATH_PARTS constant above to avoid overflow during
    // score calculation
    long score = 0;
    for (Iterator<String> rit = requestUriParts.iterator(), dit = destUriParts.iterator();
         rit.hasNext() && dit.hasNext(); ) {
      String requestPart = rit.next();
      String destPart = dit.next();
      if (requestPart.equals(destPart)) {
        score = (score * 5) + 4;
      } else if (PatternPathRouterWithGroups.GROUP_PATTERN.matcher(destPart).matches()) {
        score = (score * 5) + 3;
      } else {
        score = (score * 5) + 2;
      }
    }
    return score;
  }

  @Override
  public void init(HandlerContext context) {
    for (HttpHandler handler : handlers) {
      handler.init(context);
    }
  }

  @Override
  public void destroy(HandlerContext context) {
    for (HttpHandler handler : handlers) {
      try {
        handler.destroy(context);
      } catch (Throwable t) {
        LOG.warn("Exception raised in calling handler.destroy() for handler {}", handler, t);
      }
    }
  }

  private static <T> List<T> copyOf(Iterable<? extends T> iterable) {
    List<T> list = new ArrayList<T>();
    for (T item : iterable) {
      list.add(item);
    }
    return Collections.unmodifiableList(list);
  }

  /**
   * Helper method to split a string by a given character, with empty parts omitted.
   */
  private static Iterable<String> splitAndOmitEmpty(final String str, final char splitChar) {
    return new Iterable<String>() {
      @Override
      public Iterator<String> iterator() {
        return new Iterator<String>() {

          int startIdx = 0;
          String next = null;

          @Override
          public boolean hasNext() {
            while (next == null && startIdx < str.length()) {
              int idx = str.indexOf(splitChar, startIdx);
              if (idx == startIdx) {
                // Omit empty string
                startIdx++;
                continue;
              }
              if (idx >= 0) {
                // Found the next part
                next = str.substring(startIdx, idx);
                startIdx = idx;
              } else {
                // The last part
                if (startIdx < str.length()) {
                  next = str.substring(startIdx);
                  startIdx = str.length();
                }
                break;
              }
            }
            return next != null;
          }

          @Override
          public String next() {
            if (hasNext()) {
              String next = this.next;
              this.next = null;
              return next;
            }
            throw new NoSuchElementException("No more element");
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException("Remove not supported");
          }
        };
      }
    };
  }
}

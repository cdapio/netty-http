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
import io.cdap.http.HttpHandler;
import io.cdap.http.HttpResponder;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.ClosedChannelException;

/**
 * HttpMethodInfo is a helper class having state information about the http handler method to be invoked, the handler
 * and arguments required for invocation by the Dispatcher. RequestRouter populates this class and stores in its
 * context as attachment.
 */
class HttpMethodInfo {

  private static final Logger LOG = LoggerFactory.getLogger(HttpMethodInfo.class);

  private final Method method;
  private final HttpHandler handler;
  private final HttpResponder responder;
  private final Object[] args;
  private final boolean isStreaming;
  private final ExceptionHandler exceptionHandler;

  private HttpRequest request;
  private BodyConsumer bodyConsumer;

  HttpMethodInfo(Method method, HttpHandler handler,
                 HttpResponder responder, Object[] args, ExceptionHandler exceptionHandler) {
    this.method = method;
    this.handler = handler;
    this.isStreaming = BodyConsumer.class.isAssignableFrom(method.getReturnType());
    this.responder = responder;
    this.exceptionHandler = exceptionHandler;

    // The actual arguments list to invoke handler method
    this.args = new Object[args.length + 2];
    // The actual HttpRequest object will be provided to the invoke method, since
    // the HttpObjectAggregator may create a different instance
    this.args[0] = null;
    this.args[1] = responder;
    System.arraycopy(args, 0, this.args, 2, args.length);
  }

  /**
   * Calls the httpHandler method.
   */
  void invoke(HttpRequest request) throws Exception {
    bodyConsumer = null;
    Object invokeResult;
    try {
      args[0] = this.request = request;
      invokeResult = method.invoke(handler, args);
    } catch (InvocationTargetException e) {
      exceptionHandler.handle(e.getTargetException(), request, responder);
      return;
    } catch (Throwable t) {
      exceptionHandler.handle(t, request, responder);
      return;
    }

    if (isStreaming) {
      // Casting guarantee to be succeeded.
      bodyConsumer = (BodyConsumer) invokeResult;
    }
  }

  void chunk(HttpContent chunk) throws Exception {
    if (bodyConsumer == null) {
      // If the handler method doesn't want to handle chunk request, the bodyConsumer will be null.
      // It applies to case when the handler method inspects the request and decides to decline it.
      // Usually the handler also closes the connection after declining the request.
      // However, depending on the closing time and the request,
      // there may be some chunk of data already sent by the client.
      return;
    }

    if (chunk.content().isReadable()) {
      bodyConsumerChunk(chunk.content());
    }

    if (chunk instanceof LastHttpContent) {
      bodyConsumerFinish();
    }
  }

  void disconnected() {
    if (bodyConsumer != null) {
      bodyConsumerError(new ClosedChannelException());
    }
  }

  /**
   * Calls the {@link BodyConsumer#chunk(ByteBuf, HttpResponder)} method. If the chunk method call
   * throws exception, the {@link BodyConsumer#handleError(Throwable)} will be called and this method will
   * throw {@link HandlerException}.
   */
  private void bodyConsumerChunk(ByteBuf buffer) throws HandlerException {
    try {
      bodyConsumer.chunk(buffer, responder);
    } catch (Throwable t) {
      try {
        bodyConsumerError(t);
      } catch (Throwable t2) {
        exceptionHandler.handle(t2, request, responder);
        // log original throwable since we'll lose it otherwise
        LOG.debug("Handled exception thrown from handleError. original exception from chunk call was:", t);
        return;
      }
      exceptionHandler.handle(t, request, responder);
    }
  }

  /**
   * Calls {@link BodyConsumer#finished(HttpResponder)} method. The current bodyConsumer will be set to {@code null}
   * after the call.
   */
  private void bodyConsumerFinish() {
    BodyConsumer consumer = bodyConsumer;
    bodyConsumer = null;
    try {
      consumer.finished(responder);
    } catch (Throwable t) {
      exceptionHandler.handle(t, request, responder);
    }
  }

  /**
   * Calls {@link BodyConsumer#handleError(Throwable)}. The current
   * bodyConsumer will be set to {@code null} after the call.
   */
  private void bodyConsumerError(Throwable cause) {
    BodyConsumer consumer = bodyConsumer;
    bodyConsumer = null;
    consumer.handleError(cause);
  }

  /**
   * Sends the error to responder.
   */
  void sendError(HttpResponseStatus status, Throwable ex) {
    String msg;

    if (ex instanceof InvocationTargetException) {
      msg = String.format("Exception Encountered while processing request : %s", ex.getCause().getMessage());
    } else {
      msg = String.format("Exception Encountered while processing request: %s", ex.getMessage());
    }

    // Send the status and message, followed by closing of the connection.
    responder.sendString(status, msg, new DefaultHttpHeaders().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE));
    if (bodyConsumer != null) {
      bodyConsumerError(ex);
    }
  }

  /**
   * Returns true if the handler method's return type is BodyConsumer.
   */
  boolean isStreaming() {
    return isStreaming;
  }
}

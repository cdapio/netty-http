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

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMultimap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
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
  private final boolean isChunkedRequest;
  private final ChannelBuffer requestContent;
  private final HttpRequest request;
  private final HttpResponder responder;
  private final Object[] args;
  private final boolean isStreaming;
  private final ExceptionHandler exceptionHandler;

  private BodyConsumer bodyConsumer;

  HttpMethodInfo(Method method, HttpHandler handler, HttpRequest request, HttpResponder responder, Object[] args,
                 ExceptionHandler exceptionHandler) {
    this.method = method;
    this.handler = handler;
    this.isChunkedRequest = request.isChunked();
    this.requestContent = request.getContent();
    this.isStreaming = BodyConsumer.class.isAssignableFrom(method.getReturnType());
    this.request = rewriteRequest(request, isStreaming);
    this.responder = responder;
    this.exceptionHandler = exceptionHandler;

    // The actual arguments list to invoke handler method
    this.args = new Object[args.length + 2];
    this.args[0] = request;
    this.args[1] = responder;
    System.arraycopy(args, 0, this.args, 2, args.length);
  }

  /**
   * Calls the httpHandler method.
   */
  void invoke() throws Exception {
    bodyConsumer = null;
    Object invokeResult;
    try {
      invokeResult = method.invoke(handler, args);
    } catch (InvocationTargetException e) {
      exceptionHandler.handle(e.getTargetException(), request, responder);
      return;
    }

    if (isStreaming) {
      // Casting guarantee to be succeeded.
      bodyConsumer = (BodyConsumer) invokeResult;
      if (bodyConsumer != null && requestContent.readable()) {
        bodyConsumerChunk(requestContent);
      }
      if (bodyConsumer != null && !isChunkedRequest) {
        bodyConsumerFinish();
      }
    }
  }

  void chunk(HttpChunk chunk) throws Exception {
    if (bodyConsumer == null) {
      // If the handler method doesn't want to handle chunk request, the bodyConsumer will be null.
      // It applies to case when the handler method inspects the request and decides to decline it.
      // Usually the handler also closes the connection after declining the request.
      // However, depending on the closing time and the request,
      // there may be some chunk of data already sent by the client.
      return;
    }
    if (chunk.isLast()) {
      bodyConsumerFinish();
    } else {
      bodyConsumerChunk(chunk.getContent());
    }
  }

  void disconnected() {
    if (bodyConsumer != null) {
      bodyConsumerError(new ClosedChannelException());
    }
  }


  void exceptionCaught(Throwable cause) {
    if (bodyConsumer != null) {
      bodyConsumerError(cause);
    }
  }

  /**
   * Calls the {@link BodyConsumer#chunk(ChannelBuffer, HttpResponder)} method. If the chunk method call
   * throws exception, the {@link BodyConsumer#handleError(Throwable)} will be called and this method will
   * throw {@link HandlerException}.
   */
  private void bodyConsumerChunk(ChannelBuffer buffer) throws HandlerException {
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
      msg = String.format("Exception Encountered while processing request : %s",
                          Objects.firstNonNull(ex.getCause(), ex).getMessage());
    } else {
      msg = String.format("Exception Encountered while processing request: %s", ex.getMessage());
    }

    // Send the status and message, followed by closing of the connection.
    responder.sendString(status, msg, ImmutableMultimap.of(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE));
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

  private HttpRequest rewriteRequest(HttpRequest request, boolean isStreaming) {
    if (!isStreaming) {
      return request;
    }

    if (!request.isChunked() || request.getContent().readable()) {
      request.setChunked(true);
      request.setContent(ChannelBuffers.EMPTY_BUFFER);
    }
    return request;
  }
}

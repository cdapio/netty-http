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

import io.netty.buffer.ByteBuf;

/**
 * HttpHandler would extend this abstract class and implement methods to stream the body directly.
 * chunk method would receive the http-chunks of the body and finished would be called
 * on receipt of the last chunk.
 */
public abstract class BodyConsumer {
  /**
   * Http request content will be streamed directly to this method.
   *
   * @param request the next chunk to be consumed
   * @param responder a {@link HttpResponder} for sending response back to client.
   */
  public abstract void chunk(ByteBuf request, HttpResponder responder);

  /**
   * This is called on the receipt of the last HttpChunk.
   *
   * @param responder a {@link HttpResponder} for sending response back to client.
   */
  public abstract void finished(HttpResponder responder);

  /**
   * When there is exception on netty while streaming, it will be propagated to handler
   * so the handler can do the cleanup. Implementations should not write to an HttpResponder.
   * Instead, use a {@link ExceptionHandler}.
   *
   * @param cause the reaons of the failure.
   */
  public abstract void handleError(Throwable cause);
}

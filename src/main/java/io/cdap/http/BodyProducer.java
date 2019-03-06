/*
 * Copyright © 2015-2019 Cask Data, Inc.
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

import javax.annotation.Nullable;

/**
 * Class for producing response body in streaming fashion.
 */
public abstract class BodyProducer {

  /**
   * Returns the size of the content in bytes that this producer is going to produce.
   * <p>
   * If a negative number is returned, the size is unknown and the {@code Content-Length} header
   * won't be set and {@code Transfer-Encoding: chunked} will be used.
   * </p>
   * By default, {@code -1L} is returned.
   *
   * @return the size of the content in bytes
   */
  public long getContentLength() {
    return -1L;
  }

  /**
   * Returns a {@link ByteBuf} representing the next chunk of bytes to send. If the returned
   * {@link ByteBuf} is an empty buffer, it signals the end of the streaming.
   *
   * @return the next chunk of bytes to send
   * @throws Exception if there is any error
   */
  public abstract ByteBuf nextChunk() throws Exception;

  /**
   * This method will get called after the last chunk of the body get sent successfully.
   *
   * @throws Exception if there is any error
   */
  public abstract void finished() throws Exception;

  /**
   * This method will get called if there is any error raised when sending body chunks. This method will
   * also get called if there is exception raised from the {@link #nextChunk()} or {@link #finished()} method.
   *
   * @param cause the reason of the failure or {@code null} if the reason of failure is unknown.
   */
  public abstract void handleError(@Nullable Throwable cause);
}

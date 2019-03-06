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

import javax.annotation.Nullable;

/**
 * Converts an object of one type to another.
 *
 * @param <F> the source object type
 * @param <T> the target object type
 */
public interface Converter<F, T> {

  /**
   * Converts an object.
   *
   * @throws Exception if the conversion failed
   */
  @Nullable
  T convert(F from) throws Exception;
}

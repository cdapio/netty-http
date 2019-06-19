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

import org.apache.commons.beanutils.ConvertUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.annotation.Nullable;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.ext.ParamConverterProvider;

/**
 * Util class to convert request parameters.
 */
public final class ParamConvertUtils {

  private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS;

  static {
    Map<Class<?>, Object> defaults = new IdentityHashMap<Class<?>, Object>();
    defaults.put(Boolean.TYPE, false);
    defaults.put(Character.TYPE, '\0');
    defaults.put(Byte.TYPE, (byte) 0);
    defaults.put(Short.TYPE, (short) 0);
    defaults.put(Integer.TYPE, 0);
    defaults.put(Long.TYPE, 0L);
    defaults.put(Float.TYPE, 0f);
    defaults.put(Double.TYPE, 0d);
    PRIMITIVE_DEFAULTS = Collections.unmodifiableMap(defaults);
  }

  /**
   * Creates a converter function that converts a path segment into the given result type.
   * Current implementation doesn't follow the {@link PathParam} specification to maintain backward compatibility.
   */
  public static Converter<String, Object> createPathParamConverter(final Type resultType) {
    if (!(resultType instanceof Class)) {
      throw new IllegalArgumentException("Unsupported @PathParam type " + resultType);
    }
    return new Converter<String, Object>() {
      @Override
      public Object convert(String value) {
        return ConvertUtils.convert(value, (Class<?>) resultType);
      }
    };
  }

  /**
   * Creates a converter function that converts header value into an object of the given result type.
   * It follows the supported types of {@link HeaderParam} with the following exceptions:
   * <ol>
   *   <li>Does not support types registered with {@link ParamConverterProvider}</li>
   * </ol>
   */
  public static Converter<List<String>, Object> createHeaderParamConverter(Type resultType) {
    return createListConverter(resultType);
  }

  /**
   * Creates a converter function that converts query parameter into an object of the given result type.
   * It follows the supported types of {@link QueryParam} with the following exceptions:
   * <ol>
   *   <li>Does not support types registered with {@link ParamConverterProvider}</li>
   * </ol>
   */
  public static Converter<List<String>, Object> createQueryParamConverter(Type resultType) {
    return createListConverter(resultType);
  }

  /**
   * Common helper method to convert value for {@link HeaderParam} and {@link QueryParam}.
   *
   * @see #createHeaderParamConverter(Type)
   * @see #createQueryParamConverter(Type)
   */
  private static Converter<List<String>, Object> createListConverter(Type resultType) {
    // Use boxed type if raw type is primitive type. Otherwise the type won't change.
    Class<?> resultClass = getRawClass(resultType);

    // For string, just return the first value
    if (resultClass == String.class) {
      return new BasicConverter(defaultValue(resultClass)) {
        @Override
        protected Object convert(String value) throws Exception {
          return value;
        }
      };
    }

    // Creates converter based on the type

    // Primitive
    Converter<List<String>, Object> converter = createPrimitiveTypeConverter(resultClass);
    if (converter != null) {
      return converter;
    }

    // String constructor
    converter = createStringConstructorConverter(resultClass);
    if (converter != null) {
      return converter;
    }

    // Static string argument methods
    converter = createStringMethodConverter(resultClass);
    if (converter != null) {
      return converter;
    }

    // Collection
    converter = createCollectionConverter(resultType);
    if (converter != null) {
      return converter;
    }

    throw new IllegalArgumentException("Unsupported type " + resultType + " of type class " + resultType.getClass());
  }


  /**
   * Creates a converter function that converts value into primitive type.
   *
   * @return A converter function or {@code null} if the given type is not primitive type or boxed type
   */
  @Nullable
  private static Converter<List<String>, Object> createPrimitiveTypeConverter(final Class<?> resultClass) {
    Object defaultValue = defaultValue(resultClass);

    if (defaultValue == null) {
      // For primitive type, the default value shouldn't be null
      return null;
    }

    return new BasicConverter(defaultValue) {
      @Override
      protected Object convert(String value) throws Exception {
        return valueOf(value, resultClass);
      }
    };
  }


  /**
   * Creates a converter function that converts value using a constructor that accepts a single String argument.
   *
   * @return A converter function or {@code null} if the given type doesn't have a public constructor that accepts
   *         a single String argument.
   */
  private static Converter<List<String>, Object> createStringConstructorConverter(Class<?> resultClass) {
    try {
      final Constructor<?> constructor = resultClass.getConstructor(String.class);
      return new BasicConverter(defaultValue(resultClass)) {
        @Override
        protected Object convert(String value) throws Exception {
          return constructor.newInstance(value);
        }
      };
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Creates a converter function that converts value using a public static method named
   * {@code valueOf} or {@code fromString} that accepts a single String argument.
   *
   * @return A converter function or {@code null} if the given type doesn't have a public static method
   *         named {@code valueOf} or {@code fromString} that accepts a single String argument.
   */
  private static Converter<List<String>, Object> createStringMethodConverter(Class<?> resultClass) {
    // A special case for Character type, as it doesn't have a valueOf(String) method
    if (resultClass == Character.class) {
      return new BasicConverter(defaultValue(resultClass)) {
        @Nullable
        @Override
        Object convert(String value) throws Exception {
          return value.length() >= 1 ? value.charAt(0) : null;
        }
      };
    }


    Method method;
    try {
      method = resultClass.getMethod("valueOf", String.class);
    } catch (Exception e) {
      try {
        method = resultClass.getMethod("fromString", String.class);
      } catch (Exception ex) {
        return null;
      }
    }

    final Method convertMethod = method;
    return new BasicConverter(defaultValue(resultClass)) {
      @Override
      protected Object convert(String value) throws Exception {
        return convertMethod.invoke(null, value);
      }
    };
  }

  /**
   * Creates a converter function that converts value into a {@link List}, {@link Set} or {@link SortedSet}.
   *
   * @return A converter function or {@code null} if the given type is not a {@link ParameterizedType} with raw type as
   *         {@link List}, {@link Set} or {@link SortedSet}. Also, for {@link SortedSet} type, if the element type
   *         doesn't implements {@link Comparable}, {@code null} is returned.
   */
  private static Converter<List<String>, Object> createCollectionConverter(Type resultType) {
    final Class<?> rawType = getRawClass(resultType);

    // Collection. It must be List or Set
    if (rawType != List.class && rawType != Set.class && rawType != SortedSet.class) {
      return null;
    }

    // Must be ParameterizedType
    if (!(resultType instanceof ParameterizedType)) {
      return null;
    }

    // Must have 1 type parameter
    ParameterizedType type = (ParameterizedType) resultType;
    if (type.getActualTypeArguments().length != 1) {
      return null;
    }

    // For SortedSet, the entry type must be Comparable.
    Type elementType = type.getActualTypeArguments()[0];
    if (rawType == SortedSet.class && !Comparable.class.isAssignableFrom(getRawClass(elementType))) {
      return null;
    }

    // Get the converter for the collection element.
    final Converter<List<String>, Object> elementConverter = createQueryParamConverter(elementType);
    if (elementConverter == null) {
      return null;
    }

    return new Converter<List<String>, Object>() {
      @Override
      public Object convert(List<String> values) throws Exception {
        Collection<? extends Comparable> collection;
        if (rawType == List.class) {
          collection = new ArrayList<Comparable>();
        } else if (rawType == Set.class) {
          collection = new LinkedHashSet<Comparable>();
        } else {
          collection = new TreeSet<Comparable>();
        }

        for (String value : values) {
          add(collection, elementConverter.convert(Collections.singletonList(value)));
        }
        return collection;
      }

      @SuppressWarnings("unchecked")
      private <T> void add(Collection<T> builder, Object element) {
        builder.add((T) element);
      }
    };
  }

  /**
   * Returns the default value for the given class type based on the Java language definition.
   */
  @Nullable
  private static Object defaultValue(Class<?> cls) {
    return PRIMITIVE_DEFAULTS.get(cls);
  }

  /**
   * Returns the value of the primitive type from the given string value.
   *
   * @param value the value to parse
   * @param cls the primitive type class
   * @return the boxed type value or {@code null} if the given class is not a primitive type
   */
  @Nullable
  private static Object valueOf(String value, Class<?> cls) {
    if (cls == Boolean.TYPE) {
      return Boolean.valueOf(value);
    }
    if (cls == Character.TYPE) {
      return value.length() >= 1 ? value.charAt(0) : defaultValue(char.class);
    }
    if (cls == Byte.TYPE) {
      return Byte.valueOf(value);
    }
    if (cls == Short.TYPE) {
      return Short.valueOf(value);
    }
    if (cls == Integer.TYPE) {
      return Integer.valueOf(value);
    }
    if (cls == Long.TYPE) {
      return Long.valueOf(value);
    }
    if (cls == Float.TYPE) {
      return Float.valueOf(value);
    }
    if (cls == Double.TYPE) {
      return Double.valueOf(value);
    }
    return null;
  }

  /**
   * Returns the raw class of the given type.
   */
  private static Class<?> getRawClass(Type type) {
    if (type instanceof Class) {
      return (Class<?>) type;
    }
    if (type instanceof ParameterizedType) {
      return getRawClass(((ParameterizedType) type).getRawType());
    }
    // For TypeVariable and WildcardType, returns the first upper bound.
    if (type instanceof TypeVariable) {
      return getRawClass(((TypeVariable) type).getBounds()[0]);
    }
    if (type instanceof WildcardType) {
      return getRawClass(((WildcardType) type).getUpperBounds()[0]);
    }
    if (type instanceof GenericArrayType) {
      Class<?> componentClass = getRawClass(((GenericArrayType) type).getGenericComponentType());
      return Array.newInstance(componentClass, 0).getClass();
    }
    // This shouldn't happen as we captured all implementations of Type above (as or Java 8)
    throw new IllegalArgumentException("Unsupported type " + type + " of type class " + type.getClass());
  }

  /**
   * A converter that converts first String value from a List of String.
   */
  private abstract static class BasicConverter implements Converter<List<String>, Object> {

    private final Object defaultValue;

    BasicConverter(Object defaultValue) {
      this.defaultValue = defaultValue;
    }

    @Nullable
    @Override
    public final Object convert(List<String> values) throws Exception {
      if (values.isEmpty()) {
        return getDefaultValue();
      }
      return convert(values.get(0));
    }

    Object getDefaultValue() {
      return defaultValue;
    }

    @Nullable
    abstract Object convert(String value) throws Exception;
  }

  private ParamConvertUtils() {
  }
}

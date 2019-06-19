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

import java.util.Arrays;

/**
 * An {@link ImmutablePair} consists of two elements within. The elements once set in the ImmutablePair cannot
 * be modified. The class itself is final, so that it cannot be subclassed. This is general norm for creating
 * Immutable classes. Please note that the {@link ImmutablePair} cannot be modified once set, but the objects
 * within them can be, so in general it means that if there are mutable objects within the pair then the pair
 * itself is effectively mutable.
 *
 * <pre>
 *   ImmutablePair<Tuple, TupleInputStreamIdentifier> tupleStreamPair= new
 *    ImmutablePair<Tuple, TupleInputStreamIdentifier> (tuple, identifier);
 *   ...
 *   ...
 *   Tuple t = tupleStreamPair.getFirst();
 *   TupleInputStreamIdentifier identifier = tupleStreamPair.getSecond();
 *   ...
 * </pre>
 *
 * @param <A> type A
 * @param <B> type B
 */
final class ImmutablePair<A, B> {
    private final A first;
    private final B second;

    public static <A, B> ImmutablePair<A, B> of(A first, B second) {
        return new ImmutablePair<A, B>(first, second);
    }

    /**
     * Constructs a Immutable Pair.
     * 
     * @param first  object in pair
     * @param second object in pair
     */
    private ImmutablePair(A first, B second) {
        this.first = first;
        this.second = second;
    }

    /**
     * Returns first object from pair.
     * 
     * @return first object from pair.
     */
    public A getFirst() {
        return first;
    }

    /**
     * Return second object from pair.
     * 
     * @return second object from pair.
     */
    public B getSecond() {
        return second;
    }

    /**
     * Returns a string representation of {@link ImmutablePair} object.
     * 
     * @return string representation of this object.
     */
    @Override
    public String toString() {
        return "ImmutablePair{" + "first=" + first + ", second=" + second + '}';
    }

    /**
     * Returns a hash code value for this object.
     * 
     * @return hash code value of this object.
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[] { first, second });
    }

    /**
     * Helper method that replaces Objects.equals(first, second)
     * 
     * @param first
     * @param otherFirst
     * @return True if equals
     */
    private boolean equals(Object first, Object otherFirst) {
        boolean fst;
        if (first == null) {
            if (otherFirst == null) {
                fst = true;
            } else {
                return false;
            }
        } else if (otherFirst == null) {
            return false;
        } else {
            fst = first.equals(otherFirst);
        }
        return fst;
    }

    /**
     * Returns whether some other object "is equal" to this object.
     * 
     * @param o reference object with which to compare
     * @return true if object is the same as the obj argument; false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (!(o instanceof ImmutablePair)) {
            return false;
        }
        ImmutablePair<?, ?> other = (ImmutablePair<?, ?>) o;
        return equals(first, other.first) && equals(second, other.second);
    }
}

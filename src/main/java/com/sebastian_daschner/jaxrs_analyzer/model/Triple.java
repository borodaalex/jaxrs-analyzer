/*
 * Copyright (C) 2015 Sebastian Daschner, sebastian-daschner.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sebastian_daschner.jaxrs_analyzer.model;

/**
 * Represents a tuple of two elements.
 *
 * @param <T> The type of the left element
 * @param <U> The type of the right element
 * @param <M> The type of the right element
 */
public class Triple<T, M, U> {

    private final T left;
    private final M middle;
    private final U right;

    private Triple(final T left, final M middle, final U right) {
        this.left = left;
        this.middle = middle;
        this.right = right;
    }

    public T getLeft() {
        return left;
    }

    public U getRight() {
        return right;
    }

    public M getMiddle() {
        return middle;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        final Triple pair = (Triple) o;

        if (left != null ? !left.equals(pair.left) : pair.left != null) return false;
        if (middle != null ? !middle.equals(pair.left) : pair.middle != null) return false;
        return !(right != null ? !right.equals(pair.right) : pair.right != null);
    }

    @Override
    public int hashCode() {
        int result = left != null ? left.hashCode() : 0;
        result += 31 * result + (middle != null ? middle.hashCode() : 0);
        result = 31 * result + (right != null ? right.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Pair{" +
                "left=" + left +
                ", middle=" + middle +
                ", right=" + right +
                '}';
    }

    /**
     * Creates a new pair with left and right value.
     *
     * @param left  The left value (can be {@code null})
     * @param right The right value (can be {@code null})
     * @param <V>   The type of the left value
     * @param <M>   The type of the middle value
     * @param <W>   The type of the right value
     * @return The constructed pair
     */
    public static <V, M, W> Triple<V, M, W> of(final V left, final M middle, final W right) {
        return new Triple<>(left, middle, right);
    }

}

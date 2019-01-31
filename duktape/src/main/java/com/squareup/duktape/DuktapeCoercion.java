package com.squareup.duktape;

/**
 * Coerce a value passing through Duktape to the desired output class.
 */
public interface DuktapeCoercion<T, F> {
  T coerce(Class clazz, F o);
}
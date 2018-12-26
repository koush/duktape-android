/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.duktape;

import java.io.Closeable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** A simple EMCAScript (Javascript) interpreter. */
public final class Duktape implements Closeable {
  private static final Map<Class, DuktapeCoercion> JavaCoercions = new LinkedHashMap<>();
  private static final Map<Class, DuktapeCoercion> JavascriptCoercions = new LinkedHashMap<>();

  static {
    System.loadLibrary("duktape");

    // coercing javascript string into an enum for java
    JavaCoercions.put(Enum.class, new DuktapeCoercion<Enum, Object>() {
      @Override
      public Enum coerce(Object o, Class clazz) {
        if (o == null)
          return null;
        return Enum.valueOf(clazz, o.toString());
      }
    });

    // coercing a java enum into javascript string
    JavascriptCoercions.put(Enum.class, new DuktapeCoercion<Object, Enum>() {
      @Override
      public Object coerce(Enum o, Class clazz) {
        if (o == null)
          return null;
        return o.toString();
      }
    });
  }

  public static <T> void putJavaCoercion(Class<T> clazz, DuktapeCoercion<T, Object> coercion) {
    JavaCoercions.put(clazz, coercion);
  }

  public static <F> void putJavascriptCoercion(Class<F> clazz, DuktapeCoercion<Object, F> coercion) {
    JavascriptCoercions.put(clazz, coercion);
  }

  /**
   * Coerce a value passing through Duktape to the desired output class.
   * @param <T>
   */
  public interface DuktapeCoercion<T, F> {
    T coerce(F o, Class clazz);
  }

  public static Object coerceToJavascript(Object o) {
    if (o == null)
      return null;
    return coerce(JavascriptCoercions, o, o.getClass());
  }

  public static Object coerceToJava(Object o, Class<?> clazz) {
    if (o == null)
      return null;
    while (o instanceof DuktapeJavaObject) {
      o = ((DuktapeJavaObject)o).getObject();
    }
    if (clazz == null)
      return o;
    if (clazz.isInstance(o))
      return o;
    if (clazz == boolean.class && o instanceof Boolean)
      return o;
    if (clazz == byte.class && o instanceof Byte)
      return o;
    if (clazz == short.class && o instanceof Short)
      return o;
    if (clazz == int.class && o instanceof Integer)
      return o;
    if (clazz == long.class && o instanceof Long)
      return o;
    if (clazz == float.class && o instanceof Float)
      return o;
    if (clazz == double.class && o instanceof Double)
      return o;
    if (clazz.isArray() && o instanceof JavaScriptObject) {
      JavaScriptObject jo = (JavaScriptObject)o;
      int length = ((Number)jo.get("length")).intValue();
      Class componentType = clazz.getComponentType();
      Object ret = Array.newInstance(componentType, length);
      for (int i = 0; i < length; i++) {
        Array.set(ret, i, coerceToJava(jo.get(i), componentType));
      }
      return ret;
    }

    return coerce(JavaCoercions, o, clazz);
  }

  public interface JavaMethodReference<T> {
    Object invoke(T thiz);
  }
  public interface JavaMethodReference0<T, A> {
    Object invoke(T thiz, A arg0);
  }
  public interface JavaMethodReference1<T, A, B> {
    Object invoke(T thiz, A arg0, B arg1);
  }
  public interface JavaMethodReference2<T, A, B, C> {
    Object invoke(T thiz, A arg0, B arg1, C arg2);
  }
  public interface JavaMethodReference3<T, A, B, C, D> {
    Object invoke(T thiz, A arg0, B arg1, C arg2, D arg3);
  }
  public interface JavaMethodReference4<T, A, B, C, D, E> {
    Object invoke(T thiz, A arg0, B arg1, C arg2, D arg3, E arg4);
  }

  public static <T> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference<T> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference0<T, A> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A, B> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference1<T, A, B> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A, B, C> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference2<T, A, B, C> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A, B, C, D> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference3<T, A, B, C, D> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A, B, C, D, E> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference4<T, A, B, C, D, E> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }

  public static class MethodException extends Exception {
    Method method;
    MethodException(Method method) {
      this.method = method;
    }
  }

  private static Object throwInvokedMethod(Object proxy, Method method, Object[] args) throws MethodException {
    throw new MethodException(method);
  }

  private static <T> T createMethodInterceptProxy(Class<T> clazz) {
    return (T)Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz }, Duktape::throwInvokedMethod);
  }

  private static <T> Method invokeMethodReferenceProxy(Class<T> clazz, Object ref) {
    try {
      Method method = ref.getClass().getDeclaredMethods()[0];
      Object[] args = new Object[method.getParameterTypes().length];
      // first arg is "this" for the lambda
      args[0] = createMethodInterceptProxy(clazz);
      method.invoke(ref, args);
    }
    catch (Exception e) {
      if (e instanceof InvocationTargetException) {
        InvocationTargetException invocationTargetException = (InvocationTargetException)e;
        if (invocationTargetException.getTargetException() instanceof UndeclaredThrowableException) {
          UndeclaredThrowableException undeclaredThrowableException = (UndeclaredThrowableException)invocationTargetException.getTargetException();
          if (undeclaredThrowableException.getUndeclaredThrowable() instanceof MethodException) {

            Method calledMethod = ((MethodException)undeclaredThrowableException.getUndeclaredThrowable()).method;
            return calledMethod;
          }
        }
      }
    }
    return null;
  }

  private static Object coerce(Map<Class, DuktapeCoercion> coerce, Object o, Class<?> clazz) {
    DuktapeCoercion coercion = coerce.get(clazz);
    if (coercion != null) {
      return coercion.coerce(o, clazz);
    }

    // check to see if there exists a superclass converter.
    for (Map.Entry<Class, DuktapeCoercion> check: coerce.entrySet()) {
      if (clazz.isAssignableFrom(check.getKey()))
        return check.getValue().coerce(o, clazz);
    }

    // check to see if there is a subclass converter (ie, Enum.class as a catch all).
    for (Map.Entry<Class, DuktapeCoercion> check: coerce.entrySet()) {
      if (check.getKey().isAssignableFrom(clazz))
        return check.getValue().coerce(o, clazz);
    }

    return o;
  }

  /**
   * Create a new interpreter instance. Calls to this method <strong>must</strong> matched with
   * calls to {@link #close()} on the returned instance to avoid leaking native memory.
   */
  public static Duktape create() {
    Duktape duktape = new Duktape();
    // context will hold a weak ref, so this doesn't matter if it fails.
    long context = createContext(duktape);
    if (context == 0) {
      throw new OutOfMemoryError("Cannot create Duktape instance");
    }
    duktape.context = context;
    duktape.evaluate(
            "var __proxyHandler = {\n" +
                    "\thas: function(f, key){ return key == '__java_this' || !!get(key); },\n" +
                    "\tget: function(f, prop, receiver) { return f.target.__duktape_get(f.target, prop, receiver); },\n" +
                    "\tapply: function(f, thisArg, argumentsList) { return f.target.__duktape_apply(f.target, thisArg, argumentsList); },\n" +
                    "};\n" +
                    "function __makeProxy(obj) {\n" +
                    "\tfunction f() {}; f.target = obj;\n" +
                    "\treturn new Proxy(f, __proxyHandler);\n" +
                    "};\n"
    );
    return duktape;
  }

  private long context;

  private Duktape() {
  }

  /**
   * Evaluate {@code script} and return a result. {@code fileName} will be used in error
   * reporting. Note that the result must be one of the supported Java types or the call will
   * return null.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized Object evaluate(String script, String fileName) {
    return evaluate(context, script, fileName);
  }
  /**
   * Evaluate {@code script} and return a result. Note that the result must be one of the
   * supported Java types or the call will return null.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized Object evaluate(String script) {
    return evaluate(context, script, "?");
  }

  public synchronized Object compile(String script, String fileName) {
    return compile(context, script, fileName);
  }

  /**
   * Release the native resources associated with this object. You <strong>must</strong> call this
   * method for each instance to avoid leaking native memory.
   */
  @Override public synchronized void close() {
    if (context != 0) {
      long contextToClose = context;
      context = 0;
      destroyContext(contextToClose);
    }
  }

  @Override protected synchronized void finalize() throws Throwable {
    // this isn't THAT bad, as JavaScriptObjects may be passed around without concern for the
    // Duktape collection.
    if (context != 0) {
      Logger.getLogger(getClass().getName()).warning("Duktape instance leaked!");
    }
    // definitely close it though.
    close();
  }

  public synchronized void setGlobalProperty(Object property, Object value) {
    setGlobalProperty(context, property, value);
  }

  public synchronized void cooperateDebugger() {
    cooperateDebugger(context);
  }
  public void waitForDebugger() {
    waitForDebugger(context);
  }

  public boolean isDebugging() {
    return isDebugging(context);
  }

  public synchronized void debuggerAppNotify(Object... args) {
    debuggerAppNotify(context, args);
  }

  synchronized Object getKeyObject(long object, Object key) {
    return getKeyObject(context, object, key);
  }
  synchronized Object getKeyString(long object, String key) {
    return getKeyString(context, object, key);
  }
  synchronized Object getKeyInteger(long object, int index) {
    return getKeyInteger(context, object, index);
  }
  synchronized Object callSelf(long object, Object... args) {
    return callSelf(context, object, args);
  }
  synchronized Object callProperty(long object, Object property, Object... args) {
    return callProperty(context, object, property, args);
  }

  private static native long createContext(Duktape duktape);
  private static native void destroyContext(long context);
  private static native Object evaluate(long context, String sourceCode, String fileName);
  private static native Object compile(long context, String sourceCode, String fileName);

  private static native void cooperateDebugger(long context);
  private static native void waitForDebugger(long context);
  private static native boolean isDebugging(long context);
  private static native void debuggerAppNotify(long context, Object... args);
  private static native Object getKeyObject(long context, long object, Object key);
  private static native Object getKeyString(long context, long object, String key);
  private static native Object getKeyInteger(long context, long object, int index);
  private static native Object callSelf(long context, long object, Object... args);
  private static native Object callProperty(long context, long object, Object property, Object... args);
  private static native void setGlobalProperty(long context, Object property, Object value);
}

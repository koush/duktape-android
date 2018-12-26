/*
 * Copyright (C) 2016 Square, Inc.
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
#include <memory>
#include <mutex>
#include <chrono>
#include <jni.h>
#include "DuktapeContext.h"
#include "java/GlobalRef.h"
#include "java/JavaExceptions.h"

namespace {

std::unique_ptr<GlobalRef> duktapeClass;
std::function<int(double)> getTimeZoneOffset = [](double d) { return 0; };

void initialize(JNIEnv* env, jclass type) {
  duktapeClass.reset(new GlobalRef(env, type));

  auto tzClass = env->FindClass("java/util/TimeZone");
  auto getDefaultTimeZone = env->GetStaticMethodID(tzClass, "getDefault", "()Ljava/util/TimeZone;");
  auto getOffset = env->GetMethodID(tzClass, "getOffset", "(J)I");

  const GlobalRef timeZoneClass(env, tzClass);
  getTimeZoneOffset = [timeZoneClass, getDefaultTimeZone, getOffset](double time) {
    auto theEnv = timeZoneClass.getJniEnv();
    auto timeZone = theEnv->CallStaticObjectMethod(static_cast<jclass>(timeZoneClass.get()),
                                                   getDefaultTimeZone);
    const std::chrono::milliseconds offsetMillis(theEnv->CallIntMethod(timeZone, getOffset, time));
    return static_cast<int>(std::chrono::duration_cast<std::chrono::seconds>(offsetMillis).count());
  };
}

} // anonymous namespace

extern "C" {

duk_int_t android__get_local_tzoffset(duk_double_t time) {
  return getTimeZoneOffset(time);
}

/**
 * Overload the default Duktape parser (which only does "%c"/ISO8601) to handle other date formats
 * that tend to appear in JavaScript docs around parsing dates.
 */
duk_bool_t android__date_parse_string(duk_context* ctx, const char* str) {
  // Ordered by likelihood (ideally %c/ISO8601 is the format we're given).
  static const char* dateFormats[] = {
      "%c",             // 2015-03-25T23:45:12
      "%Y/%m/%d %T",    // 2015/03/25 23:45:12
      "%Y/%m/%d",
      "%m/%d/%Y %T",    // 03/25/2015 23:45:12
      "%m/%d/%Y",
      "%b %d %Y %T",    // Mar[ch] 25 2015 23:45:12
      "%b %d %Y",
      "%d %b %Y %T",    // 25 Mar[ch] 2015 23:45:12
      "%d %b %Y",
      "%a %b %d %Y %T", // Wed[nesday] Mar[ch] 25 2015 23:45:12
      "%a %b %d %Y",
  };
  tm tm;
  int timezoneOffset = android__get_local_tzoffset(0);
  for (const auto dateFormat : dateFormats) {
    memset(&tm, 0, sizeof(tm));
    if (!strptime(str, dateFormat, &tm)) {
      // No dice.
      continue;
    }
    tm.tm_isdst = -1; // Not set by strptime - unknown if DST.
    const auto t = timegm(&tm);
    if (t >= 0) {
      duk_push_number(ctx, (t - timezoneOffset) * 1000.0);
      return true;
    }
  }
  return false;
}

JNIEXPORT jlong JNICALL
Java_com_squareup_duktape_Duktape_createContext(JNIEnv* env, jclass type, jobject javaDuktape) {
  static std::once_flag initialized;
  std::call_once(initialized, initialize, std::ref(env), type);

  JavaVM* javaVM;
  env->GetJavaVM(&javaVM);
  try {
    return reinterpret_cast<jlong>(new DuktapeContext(javaVM, javaDuktape));
  } catch (std::bad_alloc&) {
    return 0L;
  }
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_destroyContext(JNIEnv *env, jclass type, jlong context) {
  delete reinterpret_cast<DuktapeContext*>(context);
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_waitForDebugger(JNIEnv *env, jclass type, jlong context) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  duktape->waitForDebugger();
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_cooperateDebugger(JNIEnv *env, jclass type, jlong context) {
    DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
    duktape->cooperateDebugger();
}

JNIEXPORT jboolean JNICALL
Java_com_squareup_duktape_Duktape_isDebugging(JNIEnv *env, jclass type, jlong context) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return (jboolean)duktape->isDebugging();
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_debuggerAppNotify(JNIEnv *env, jclass type,
                                           jlong context,
                                           jobjectArray args) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->debuggerAppNotify(env, args);
}

JNIEXPORT void JNICALL
Java_com_squareup_duktape_Duktape_setGlobalProperty(JNIEnv *env, jclass type, jlong context,
                                                    jobject property, jobject value) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  duktape->setGlobalProperty(env, property, value);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_callSelf(JNIEnv *env, jclass type,
                                           jlong context, jlong object,
                                           jobjectArray args) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->call(env, object, args);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_callProperty(JNIEnv *env, jclass type,
                                           jlong context, jlong object,
                                           jobject property,
                                           jobjectArray args) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  return duktape->callProperty(env, object, property, args);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyObject(JNIEnv *env, jclass type, jlong context,
                                               jlong object, jobject key) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return nullptr;
  }
  return duktape->getKeyObject(env, object, key);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyInteger(JNIEnv *env, jclass type, jlong context, jlong object, jint index) {
    DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
    if (duktape == nullptr) {
        queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
        return nullptr;
    }
    return duktape->getKeyInteger(env, object, index);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_getKeyString(JNIEnv *env, jclass type, jlong context, jlong object, jstring key) {
    DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
    if (duktape == nullptr) {
        queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
        return nullptr;
    }
    return duktape->getKeyString(env, object, key);
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_compile__JLjava_lang_String_2Ljava_lang_String_2(
        JNIEnv* env, jclass type, jlong context, jstring code, jstring fname) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return nullptr;
  }
  try {
    return duktape->compile(env, code, fname);
  } catch (const std::invalid_argument& e) {
    queueIllegalArgumentException(env, e.what());
  } catch (const std::exception& e) {
    queueDuktapeException(env, e.what());
  }
  return nullptr;
}

JNIEXPORT jobject JNICALL
Java_com_squareup_duktape_Duktape_evaluate__JLjava_lang_String_2Ljava_lang_String_2(
    JNIEnv* env, jclass type, jlong context, jstring code, jstring fname) {
  DuktapeContext* duktape = reinterpret_cast<DuktapeContext*>(context);
  if (duktape == nullptr) {
    queueNullPointerException(env, "Null Duktape context - did you close your Duktape?");
    return nullptr;
  }
  try {
    return duktape->evaluate(env, code, fname);
  } catch (const std::invalid_argument& e) {
    queueIllegalArgumentException(env, e.what());
  } catch (const std::exception& e) {
    queueDuktapeException(env, e.what());
  }
  return nullptr;
}

} // extern "C"

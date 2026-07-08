/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Fixed-arity shims over libcurl's variadic curl_easy_setopt / curl_easy_getinfo.
 *
 * Scala Native cannot reliably call these C variadic functions: a fixed-arity @extern uses the wrong
 * calling convention for the variadic argument (it lands in a register instead of on the stack on
 * arm64-apple-darwin), and a CVarArg* extern does not pass the argument through at all on this
 * toolchain. Both make curl read garbage for the value (e.g. a valid URL reported as
 * CURLE_URL_MALFORMAT). Routing through these real, fixed-arity C functions lets the C compiler emit
 * the correct variadic call, while Scala Native sees ordinary fixed-arity symbols. What matters is
 * the *declared* type at the call site — variadic function-pointer typedefs give us the right ABI
 * regardless of how the pointer was obtained.
 *
 * The libcurl symbols are resolved lazily from the modules already loaded into the running process,
 * rather than declared as C-level `extern`s. Scala Native compiles this file into every downstream
 * project that has the uni-native jar on its classpath, whether or not the project references
 * CurlBindings. With a strong `extern int curl_easy_setopt(...)` the compiled object file carries an
 * unresolved reference to `curl_easy_setopt`; downstream projects that don't reference CurlBindings
 * never get -lcurl (its @link("curl") is dead-code-eliminated) and their link fails with "undefined
 * reference to curl_easy_setopt" (issue #622). Resolving at runtime leaves this object file free of
 * libcurl symbol references, so downstream links succeed. When CurlBindings *is* used, its
 * @link("curl") pulls libcurl into the binary at load time, so the lookup finds it.
 *
 * Everything below is written twice, because the POSIX primitives this needs are the two headers the
 * MSVC toolchain does not ship (<dlfcn.h>, <pthread.h>):
 *
 *   lookup   POSIX:   dlsym(RTLD_DEFAULT, name), which walks every loaded library.
 *            Windows: GetProcAddress over EnumProcessModules(self) — the closest analogue, as there
 *                     is no all-modules handle.
 *   run-once POSIX:   pthread_once.
 *            Windows: InitOnceExecuteOnce.
 *
 * Read adr/2026-07-06-curl-shim-weak-linking.md before changing any of this.
 */

#if defined(_WIN32)

/* InitOnceExecuteOnce needs Vista+. Modern SDKs already target well above that; this only fills in a
 * baseline for toolchains that leave _WIN32_WINNT unset (some MinGW header sets default lower). */
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#define WIN32_LEAN_AND_MEAN
/* PSAPI_VERSION 2 redirects EnumProcessModules to K32EnumProcessModules, which lives in kernel32 and
 * is therefore always linked. Version 1 resolves it out of psapi.lib instead, and a jar-resource .c
 * file cannot make downstream binaries add a linker flag — the same constraint that rules out
 * -undefined dynamic_lookup for the weak-symbol approach on macOS. */
#define PSAPI_VERSION 2
#include <windows.h>
#include <psapi.h>

#else

/* On glibc, RTLD_DEFAULT is exposed by <dlfcn.h> only under _GNU_SOURCE. macOS libSystem exposes it
 * unconditionally; defining the macro there is harmless. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pthread.h>

#endif

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/*
 * Variadic function-pointer typedefs. Calling through these yields the correct variadic ABI at the
 * call site (arg on stack on arm64-apple-darwin), which is the whole reason this shim exists.
 */
typedef int (*uni_curl_setopt_fn)(void *handle, int option, ...);
typedef int (*uni_curl_getinfo_fn)(void *handle, int info, ...);

static uni_curl_setopt_fn  uni_curl_setopt_p  = NULL;
static uni_curl_getinfo_fn uni_curl_getinfo_p = NULL;

/* Find `name` among the symbols already loaded into this process, or NULL if it is absent. */
#if defined(_WIN32)

static void *uni_curl_shim_lookup(const char *name) {
  HMODULE modules[512];
  DWORD   bytesNeeded = 0;

  if (!EnumProcessModules(GetCurrentProcess(), modules, (DWORD)sizeof(modules), &bytesNeeded)) {
    return NULL;
  }

  /* bytesNeeded reports what a complete listing would take, so it can exceed the buffer. */
  DWORD capacity = (DWORD)(sizeof(modules) / sizeof(modules[0]));
  DWORD loaded   = bytesNeeded / (DWORD)sizeof(HMODULE);
  DWORD count    = loaded < capacity ? loaded : capacity;

  for (DWORD i = 0; i < count; i++) {
    FARPROC symbol = GetProcAddress(modules[i], name);
    if (symbol != NULL) {
      /* Via uintptr_t: casting FARPROC straight to void* trips -Wcast-function-type. */
      return (void *)(uintptr_t)symbol;
    }
  }
  return NULL;
}

#else

static void *uni_curl_shim_lookup(const char *name) { return dlsym(RTLD_DEFAULT, name); }

#endif

static void *uni_curl_shim_resolve(const char *name) {
  void *symbol = uni_curl_shim_lookup(name);
  if (symbol == NULL) {
    fprintf(
        stderr,
        "uni_curl_shim: libcurl symbol '%s' not found in this process. Add "
        "wvlet.uni.http.CurlBindings to your Scala Native build (which links "
        "libcurl via @link(\"curl\")), or install libcurl. On Windows, libcurl must be linked as a "
        "DLL (through its import library): a statically linked copy exports no symbols to look up. "
        "See wvlet/uni#622.\n",
        name);
    abort();
  }
  return symbol;
}

static void uni_curl_shim_init(void) {
  uni_curl_setopt_p  = (uni_curl_setopt_fn)uni_curl_shim_resolve("curl_easy_setopt");
  uni_curl_getinfo_p = (uni_curl_getinfo_fn)uni_curl_shim_resolve("curl_easy_getinfo");
}

/*
 * Run uni_curl_shim_init exactly once, even under concurrent first calls. Without the guard, racing
 * writes to the static function pointers are a data race under C11 (undefined behaviour) regardless
 * of the fact that every racing thread would write the same value.
 */
#if defined(_WIN32)

static INIT_ONCE uni_curl_shim_init_once = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK uni_curl_shim_init_cb(PINIT_ONCE once, PVOID parameter, PVOID *context) {
  (void)once;
  (void)parameter;
  (void)context;
  uni_curl_shim_init();
  return TRUE;
}

static void uni_curl_shim_ensure_init(void) {
  InitOnceExecuteOnce(&uni_curl_shim_init_once, uni_curl_shim_init_cb, NULL, NULL);
}

#else

static pthread_once_t uni_curl_shim_init_once = PTHREAD_ONCE_INIT;

static void uni_curl_shim_ensure_init(void) {
  pthread_once(&uni_curl_shim_init_once, uni_curl_shim_init);
}

#endif

int uni_curl_easy_setopt_ptr(void *handle, int option, void *value) {
  uni_curl_shim_ensure_init();
  return uni_curl_setopt_p(handle, option, value);
}

int uni_curl_easy_setopt_long(void *handle, int option, long value) {
  uni_curl_shim_ensure_init();
  return uni_curl_setopt_p(handle, option, value);
}

/* Typed long* out-parameter for CURLINFO_*_RESPONSE_CODE and other `long` infos. */
int uni_curl_easy_getinfo_long(void *handle, int info, long *value) {
  uni_curl_shim_ensure_init();
  return uni_curl_getinfo_p(handle, info, value);
}

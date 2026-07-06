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
 * The libcurl symbols are resolved lazily via dlsym(RTLD_DEFAULT, ...) inside a pthread_once init,
 * rather than declared as C-level `extern`s. Scala Native compiles this file into every downstream
 * project that has the uni-native jar on its classpath, whether or not the project references
 * CurlBindings. With a strong `extern int curl_easy_setopt(...)` the compiled .o carries an
 * unresolved reference to `curl_easy_setopt`; downstream projects that don't reference CurlBindings
 * never get -lcurl (its @link("curl") is dead-code-eliminated) and their link fails with "undefined
 * reference to curl_easy_setopt" (issue #622). Using dlsym leaves this .o free of libcurl symbol
 * references, so downstream links succeed. When CurlBindings *is* used, its @link("curl") pulls
 * libcurl into the binary at load time, so dlsym(RTLD_DEFAULT, "curl_easy_setopt") finds it —
 * RTLD_DEFAULT walks all libraries loaded into the process. libdl and libpthread are already linked
 * by Scala Native's nativelib, so no extra linker flag is needed.
 */

/* On glibc, RTLD_DEFAULT is exposed by <dlfcn.h> only under _GNU_SOURCE. macOS libSystem exposes it
 * unconditionally; defining the macro there is harmless. */
#define _GNU_SOURCE

#include <dlfcn.h>
#include <pthread.h>
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

/* pthread_once guarantees the resolver runs exactly once even under concurrent first calls; without
 * it, racing writes to the static function pointers are a data race under C11 (undefined behaviour)
 * regardless of whether the racing threads write the same value. */
static pthread_once_t uni_curl_shim_init_once = PTHREAD_ONCE_INIT;

static void *uni_curl_shim_resolve(const char *name) {
  void *sym = dlsym(RTLD_DEFAULT, name);
  if (sym == NULL) {
    fprintf(
        stderr,
        "uni_curl_shim: libcurl symbol '%s' not found in this process. Add "
        "wvlet.uni.http.CurlBindings to your Scala Native build (which links "
        "libcurl via @link(\"curl\")), or install libcurl. See wvlet/uni#622.\n",
        name);
    abort();
  }
  return sym;
}

static void uni_curl_shim_init(void) {
  uni_curl_setopt_p  = (uni_curl_setopt_fn)uni_curl_shim_resolve("curl_easy_setopt");
  uni_curl_getinfo_p = (uni_curl_getinfo_fn)uni_curl_shim_resolve("curl_easy_getinfo");
}

int uni_curl_easy_setopt_ptr(void *handle, int option, void *value) {
  pthread_once(&uni_curl_shim_init_once, uni_curl_shim_init);
  return uni_curl_setopt_p(handle, option, value);
}

int uni_curl_easy_setopt_long(void *handle, int option, long value) {
  pthread_once(&uni_curl_shim_init_once, uni_curl_shim_init);
  return uni_curl_setopt_p(handle, option, value);
}

/* Typed long* out-parameter for CURLINFO_*_RESPONSE_CODE and other `long` infos. */
int uni_curl_easy_getinfo_long(void *handle, int info, long *value) {
  pthread_once(&uni_curl_shim_init_once, uni_curl_shim_init);
  return uni_curl_getinfo_p(handle, info, value);
}

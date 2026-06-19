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
 * the correct variadic call, while Scala Native sees ordinary fixed-arity symbols.
 *
 * The curl prototypes are declared locally so the shim needs no libcurl headers at build time; the
 * symbols are resolved from the linked libcurl (-lcurl). CURLoption/CURLINFO are C enums (int).
 */

extern int curl_easy_setopt(void *handle, int option, ...);
extern int curl_easy_getinfo(void *handle, int info, ...);

int uni_curl_easy_setopt_ptr(void *handle, int option, void *value) {
  return curl_easy_setopt(handle, option, value);
}

int uni_curl_easy_setopt_long(void *handle, int option, long value) {
  return curl_easy_setopt(handle, option, value);
}

int uni_curl_easy_getinfo_ptr(void *handle, int info, void *value) {
  return curl_easy_getinfo(handle, info, value);
}

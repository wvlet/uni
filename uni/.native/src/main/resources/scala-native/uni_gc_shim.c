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
 * Run Boehm's collector with one marker thread unless the app asks for more.
 *
 * Boehm starts one marker thread per core (15 on 18 cores). A uni server keeps little live data,
 * so its heap stays a few MB and Boehm collects often; each collection then stops every worker
 * while 15 markers hand around under 1 MB of marking work. With one marker, the rpc-native
 * benchmark serves 77k-89k req/s instead of 24k-32k (ADR 2026-10-30).
 *
 * GC_set_markers_count takes effect only before GC_init, which Scala Native calls from its startup
 * code, after C constructors such as this one run. The GC_MARKERS environment variable still
 * wins, since Boehm reads it at GC_init (GC_NPROCS does not: Boehm uses it only when no marker
 * count is set). The symbol is looked up with dlsym rather
 * than linked, so builds with Immix, Commix, or no GC (no libgc) link and run unchanged, as the
 * curl shim does with libcurl (ADR 2026-07-06). A libgc linked statically without exporting its
 * symbols isn't found, nor is the function in libgc before bdwgc 8.2; both keep Boehm's default.
 */
#if !defined(_WIN32)

/* On glibc, RTLD_DEFAULT is exposed by <dlfcn.h> only under _GNU_SOURCE */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>
#include <stdlib.h>

typedef void (*uni_gc_set_markers_count_fn)(unsigned);

__attribute__((constructor)) static void uni_gc_default_markers(void) {
  uni_gc_set_markers_count_fn set_markers =
      (uni_gc_set_markers_count_fn)dlsym(RTLD_DEFAULT, "GC_set_markers_count");
  if (set_markers != NULL) {
    set_markers(1);
  }
}

#endif

/* The number of Boehm marker threads in use (GC_get_parallel() + 1), or -1 when this shim can't
 * set it (no Boehm, or one before bdwgc 8.2). For tests: marker threads start with the first
 * thread other than main. */
int uni_gc_marker_count(void) {
#if defined(_WIN32)
  return -1;
#else
  typedef int (*get_parallel_fn)(void);
  get_parallel_fn get_parallel = (get_parallel_fn)dlsym(RTLD_DEFAULT, "GC_get_parallel");
  if (get_parallel == NULL || dlsym(RTLD_DEFAULT, "GC_set_markers_count") == NULL) {
    return -1;
  }
  return get_parallel() + 1;
#endif
}

/*
 * Grow Boehm's heap to at least `target` bytes, for a server about to take load; returns the heap
 * size afterwards, or -1 without Boehm. A server keeps little live data, so Boehm's heap stays a
 * few MB and it collects every few hundred KB of allocation; a 16 MB heap made the rpc-native
 * benchmark serve ~45% more on a 3.4 KB response (ADR 2026-10-30). Does nothing when
 * GC_INITIAL_HEAP_SIZE is set: the app chose its heap.
 */
long long uni_gc_expand_heap_to(long long target) {
#if defined(_WIN32)
  (void)target;
  return -1;
#else
  typedef size_t (*get_heap_size_fn)(void);
  typedef int (*expand_hp_fn)(size_t);
  get_heap_size_fn get_heap_size = (get_heap_size_fn)dlsym(RTLD_DEFAULT, "GC_get_heap_size");
  expand_hp_fn expand_hp = (expand_hp_fn)dlsym(RTLD_DEFAULT, "GC_expand_hp");
  if (get_heap_size == NULL || expand_hp == NULL) {
    return -1;
  }
  size_t current = get_heap_size();
  if (getenv("GC_INITIAL_HEAP_SIZE") == NULL && target > 0 && current < (size_t)target) {
    expand_hp((size_t)target - current);
  }
  return (long long)get_heap_size();
#endif
}

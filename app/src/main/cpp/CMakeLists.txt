# libbj_llm.so: llama.cpp (static) plus the app's JNI bridge, one library per ABI.
# Built by scripts/build-llama-android.sh, not by Gradle, so a normal app build stays fast
# and CI caches it per llama.cpp commit (scripts/llama.lock) and these sources.
cmake_minimum_required(VERSION 3.22)
project(bj_llm CXX C)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE) # libomp aborts on Android when it can't pin threads (see bj_runner.py)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} llama EXCLUDE_FROM_ALL)

add_library(bj_llm SHARED bj_llm.cpp)
target_link_libraries(bj_llm PRIVATE llama ggml android log)
# Only the JNI entry points are exported; everything else stays private to the library.
target_link_options(bj_llm PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384)
set_target_properties(bj_llm PROPERTIES CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)

# libwhisper_gpu.so — whisper.cpp (upstream, pinned in ci.yml) + ggml with the OpenCL backend and
# Qualcomm's Adreno-tuned kernels. (A Vulkan build was tried first and was far slower than the CPU
# on the user's Snapdragon phone; OpenCL is the path Qualcomm optimises for in ggml.)
#
# OPENWISPR_GPU_DEPS layout (prepared by CI):
#   whisper.cpp/       upstream source at the pinned tag
#   opencl/include/    Khronos OpenCL-Headers
#   opencl/lib/        libOpenCL.so built from the Khronos ICD loader with the NDK — only to link
#                      against. It is NOT packaged: at runtime DT_NEEDED "libOpenCL.so" resolves to
#                      the vendor's /vendor/lib64/libOpenCL.so, which the app may load because the
#                      manifest declares <uses-native-library android:name="libOpenCL.so">.
cmake_minimum_required(VERSION 3.19)

set(WG ${OPENWISPR_GPU_DEPS}/whisper.cpp)

# Static ggml folded into libwhisper_gpu.so: nothing extra to package. OpenMP off: the NDK's
# libomp.so would need packaging; ggml's own threadpool is fine. CPU kernels (for the ops
# OpenCL doesn't take) target dotprod/fp16, which every Snapdragon with Adreno 6xx+ has.
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(GGML_OPENCL ON)
set(GGML_OPENCL_EMBED_KERNELS ON)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON)
set(GGML_OPENMP OFF)
set(GGML_NATIVE OFF)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16")
set(GGML_BUILD_TESTS OFF)
set(GGML_BUILD_EXAMPLES OFF)
set(OpenCL_INCLUDE_DIR "${OPENWISPR_GPU_DEPS}/opencl/include" CACHE PATH "" FORCE)
set(OpenCL_LIBRARY "${OPENWISPR_GPU_DEPS}/opencl/lib/libOpenCL.so" CACHE FILEPATH "" FORCE)
# The kernel embedder is a host Python script.
find_program(OPENWISPR_PY NAMES python3 python NO_CMAKE_FIND_ROOT_PATH)
set(Python3_EXECUTABLE "${OPENWISPR_PY}" CACHE FILEPATH "" FORCE)

add_subdirectory(${WG}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

add_library(whisper_gpu SHARED
    ${WG}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../whisper/jni.c
)
target_compile_definitions(whisper_gpu PRIVATE OPENWISPR_JNI_GPU WHISPER_VERSION="gpu")
target_include_directories(whisper_gpu PRIVATE ${WG}/include ${WG}/src ${WG}/ggml/include)
target_compile_options(whisper_gpu PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden
                                           -ffunction-sections -fdata-sections)
target_link_options(whisper_gpu PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
find_library(LOG_LIB log)
target_link_libraries(whisper_gpu PRIVATE ggml ${LOG_LIB} android)

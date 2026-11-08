# libwhisper_gpu.so — whisper.cpp (upstream, pinned in ci.yml) + ggml with the Vulkan backend.
#
# OPENWISPR_GPU_DEPS layout (prepared by CI):
#   whisper.cpp/          upstream source at the pinned tag
#   Vulkan-Headers/       include/vulkan/vulkan.hpp — the NDK sysroot has the C headers only
#   spirv/                installed SPIRV-Headers (headers + CMake config ggml-vulkan asks for)
# Vulkan_GLSLC_EXECUTABLE must point at a host glslc new enough for ggml's shaders.
# The shader generator is a host tool; ggml-vulkan builds it with the host gcc via ExternalProject.
cmake_minimum_required(VERSION 3.19)

set(WG ${OPENWISPR_GPU_DEPS}/whisper.cpp)

# Static ggml folded into libwhisper_gpu.so: nothing extra to package, and no libggml.so to
# collide with anything. OpenMP off: the NDK's libomp.so would need packaging; ggml's own
# threadpool is fine. CPU kernels (used for the ops Vulkan doesn't take) target the
# dotprod/fp16 cores every Vulkan 1.2-class phone has.
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(GGML_VULKAN ON)
set(GGML_OPENMP OFF)
set(GGML_NATIVE OFF)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16")
set(GGML_BUILD_TESTS OFF)
set(GGML_BUILD_EXAMPLES OFF)
# The API-24 libvulkan.so stub only has Vulkan 1.0 entry points; ggml calls 1.1+ ones
# (vkGetPhysicalDeviceFeatures2, ...) directly. Link the API-33 stub: the library then needs a
# Vulkan 1.3-era loader at runtime, and on an older phone System.loadLibrary just fails and
# the app stays on the CPU build.
set(_sysroot "${CMAKE_SYSROOT}")
if (NOT _sysroot)
    set(_sysroot "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot")
endif()
set(Vulkan_LIBRARY "${_sysroot}/usr/lib/aarch64-linux-android/33/libvulkan.so" CACHE FILEPATH "" FORCE)
set(Vulkan_INCLUDE_DIR "${OPENWISPR_GPU_DEPS}/Vulkan-Headers/include" CACHE PATH "" FORCE)
set(SPIRV-Headers_DIR "${OPENWISPR_GPU_DEPS}/spirv/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)

# ggml-vulkan.cpp and the generated shader tables are huge translation units; compiled six at a
# time they starve the runner, and the shader generator's fork() of glslc then fails
# ("Failed to fork process"), silently dropping shaders until the link fails. Two at a time.
set_property(GLOBAL APPEND PROPERTY JOB_POOLS gpu_compile=2)
set(CMAKE_JOB_POOL_COMPILE gpu_compile)

add_subdirectory(${WG}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)
# ggml-vulkan.cpp includes <spirv/unified1/spirv.hpp> but doesn't add the path itself.
target_include_directories(ggml-vulkan PRIVATE ${OPENWISPR_GPU_DEPS}/spirv/include ${Vulkan_INCLUDE_DIR})

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

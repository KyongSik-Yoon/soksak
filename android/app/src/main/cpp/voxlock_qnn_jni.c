#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpMem.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"

#define TAG "VoxLockQnn"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define EXPECTED_WAV_ELEMS 3584u
#define EXPECTED_ENROLL_N_ELEMS 96632u
#define EXPECTED_KV_ELEMS 1220608u
#define EXPECTED_STATE_ELEMS 49152u
#define EXPECTED_FRAMES_ELEMS 12800u

typedef Qnn_ErrorHandle_t (*QnnGetProvidersFn)(const QnnInterface_t***, uint32_t*);
typedef Qnn_ErrorHandle_t (*QnnSysGetProvidersFn)(const QnnSystemInterface_t***, uint32_t*);
typedef void* (*RpcMemAllocFn)(int, uint32_t, int);
typedef int (*RpcMemToFdFn)(void*);
typedef void (*RpcMemFreeFn)(void*);

typedef struct {
    pthread_mutex_t lock;
    void* htp_lib;
    void* sys_lib;
    QNN_INTERFACE_VER_TYPE qnn;
    bool has_qnn;

    Qnn_LogHandle_t log_handle;
    Qnn_BackendHandle_t backend;
    Qnn_DeviceHandle_t device;
    Qnn_ContextHandle_t context;
    Qnn_GraphHandle_t graph;
    char* graph_name;

    uint32_t num_in;
    uint32_t num_out;
    Qnn_Tensor_t* in_tensors;
    Qnn_Tensor_t* out_tensors;
    Qnn_Tensor_t* exec_in;
    Qnn_Tensor_t* exec_out;

    void** host_bufs;
    size_t* buf_bytes;
    Qnn_DataType_t* dtypes;
    Qnn_MemHandle_t* mem_handles;

    bool use_shared;
    void* rpc_lib;
    void* rpc_block;
    RpcMemFreeFn rpc_free;

    int idx_wav;
    int idx_enroll_n;
    int idx_k;
    int idx_v;
    int idx_h;
    int idx_c;
    int idx_frames;
    int idx_h_out;
    int idx_c_out;

    QnnHtpDevice_PerfInfrastructure_t* htp_perf_infra;
    uint32_t power_config_id;
    bool has_power_config;

    float* f32_scratch;
} VoxLockQnnSession;

static void qnn_log_callback(const char* fmt, QnnLog_Level_t level, uint64_t timestamp, va_list args) {
    (void)timestamp;
    int prio = ANDROID_LOG_INFO;
    if (level == QNN_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == QNN_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level >= QNN_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_vprint(prio, "VoxLockQnnNative", fmt ? fmt : "", args);
}

static const QnnSystemContext_GraphInfo_t* extract_graphs(
    const QnnSystemContext_BinaryInfo_t* info, uint32_t* count) {
    if (!info) return NULL;
    if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
        *count = info->contextBinaryInfoV1.numGraphs;
        return info->contextBinaryInfoV1.graphs;
    } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
        *count = info->contextBinaryInfoV2.numGraphs;
        return info->contextBinaryInfoV2.graphs;
    } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
        *count = info->contextBinaryInfoV3.numGraphs;
        return info->contextBinaryInfoV3.graphs;
    }
    return NULL;
}

static int parse_graph_info(
    const QnnSystemContext_GraphInfo_t* gi, const char** name,
    uint32_t* nin, Qnn_Tensor_t** ins,
    uint32_t* nout, Qnn_Tensor_t** outs) {
    if (gi->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
        *name = gi->graphInfoV1.graphName;
        *nin = gi->graphInfoV1.numGraphInputs;
        *ins = gi->graphInfoV1.graphInputs;
        *nout = gi->graphInfoV1.numGraphOutputs;
        *outs = gi->graphInfoV1.graphOutputs;
        return 0;
    } else if (gi->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
        *name = gi->graphInfoV2.graphName;
        *nin = gi->graphInfoV2.numGraphInputs;
        *ins = gi->graphInfoV2.graphInputs;
        *nout = gi->graphInfoV2.numGraphOutputs;
        *outs = gi->graphInfoV2.graphOutputs;
        return 0;
    } else if (gi->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
        *name = gi->graphInfoV3.graphName;
        *nin = gi->graphInfoV3.numGraphInputs;
        *ins = gi->graphInfoV3.graphInputs;
        *nout = gi->graphInfoV3.numGraphOutputs;
        *outs = gi->graphInfoV3.graphOutputs;
        return 0;
    }
    return -1;
}

static const char* tensor_name(const Qnn_Tensor_t* t) {
    return (t->version == QNN_TENSOR_VERSION_2) ? t->v2.name : t->v1.name;
}

static uint32_t tensor_rank(const Qnn_Tensor_t* t) {
    return (t->version == QNN_TENSOR_VERSION_2) ? t->v2.rank : t->v1.rank;
}

static uint32_t* tensor_dims(const Qnn_Tensor_t* t) {
    return (t->version == QNN_TENSOR_VERSION_2) ? t->v2.dimensions : t->v1.dimensions;
}

static Qnn_DataType_t tensor_dtype(const Qnn_Tensor_t* t) {
    return (t->version == QNN_TENSOR_VERSION_2) ? t->v2.dataType : t->v1.dataType;
}

static size_t tensor_elems(const Qnn_Tensor_t* t) {
    uint32_t rank = tensor_rank(t);
    uint32_t* dims = tensor_dims(t);
    if (rank == 0 || !dims) return 0;
    size_t elems = 1;
    for (uint32_t i = 0; i < rank; ++i) elems *= dims[i];
    return elems;
}

static size_t dtype_bpe(Qnn_DataType_t dt) {
    return (dt == QNN_DATATYPE_FLOAT_16) ? sizeof(uint16_t) : sizeof(float);
}

static bool deep_copy_tensor(const Qnn_Tensor_t* src, Qnn_Tensor_t* dst) {
    memset(dst, 0, sizeof(*dst));
    dst->version = src->version;
    if (src->version == QNN_TENSOR_VERSION_1) {
        dst->v1 = src->v1;
        dst->v1.name = strdup(src->v1.name ? src->v1.name : "");
        if (!dst->v1.name) return false;
        if (src->v1.rank > 0 && src->v1.dimensions) {
            dst->v1.dimensions = (uint32_t*)calloc(src->v1.rank, sizeof(uint32_t));
            if (!dst->v1.dimensions) return false;
            memcpy(dst->v1.dimensions, src->v1.dimensions, src->v1.rank * sizeof(uint32_t));
        } else {
            dst->v1.dimensions = NULL;
        }
        dst->v1.memType = QNN_TENSORMEMTYPE_RAW;
        dst->v1.clientBuf.data = NULL;
        dst->v1.clientBuf.dataSize = 0;
        return true;
    } else if (src->version == QNN_TENSOR_VERSION_2) {
        dst->v2 = src->v2;
        dst->v2.name = strdup(src->v2.name ? src->v2.name : "");
        if (!dst->v2.name) return false;
        if (src->v2.rank > 0 && src->v2.dimensions) {
            dst->v2.dimensions = (uint32_t*)calloc(src->v2.rank, sizeof(uint32_t));
            if (!dst->v2.dimensions) return false;
            memcpy(dst->v2.dimensions, src->v2.dimensions, src->v2.rank * sizeof(uint32_t));
        } else {
            dst->v2.dimensions = NULL;
        }
        if (src->v2.rank > 0 && src->v2.isDynamicDimensions) {
            dst->v2.isDynamicDimensions = (uint8_t*)calloc(src->v2.rank, sizeof(uint8_t));
            if (!dst->v2.isDynamicDimensions) return false;
            memcpy(dst->v2.isDynamicDimensions, src->v2.isDynamicDimensions, src->v2.rank * sizeof(uint8_t));
        } else {
            dst->v2.isDynamicDimensions = NULL;
        }
        dst->v2.memType = QNN_TENSORMEMTYPE_RAW;
        dst->v2.clientBuf.data = NULL;
        dst->v2.clientBuf.dataSize = 0;
        return true;
    }
    return false;
}

static void free_copied_tensor(Qnn_Tensor_t* t) {
    if (t->version == QNN_TENSOR_VERSION_1) {
        free((void*)t->v1.name);
        free(t->v1.dimensions);
        t->v1.name = NULL;
        t->v1.dimensions = NULL;
    } else if (t->version == QNN_TENSOR_VERSION_2) {
        free((void*)t->v2.name);
        free(t->v2.dimensions);
        free(t->v2.isDynamicDimensions);
        t->v2.name = NULL;
        t->v2.dimensions = NULL;
        t->v2.isDynamicDimensions = NULL;
    }
}

static uint16_t f32_to_f16(float x) {
    uint32_t bits;
    memcpy(&bits, &x, sizeof(bits));
    uint32_t sign = (bits >> 16) & 0x8000u;
    int32_t exp = (int32_t)((bits >> 23) & 0xFFu) - 127 + 15;
    uint32_t mant = bits & 0x7FFFFFu;
    if (exp <= 0) {
        if (exp < -10) return (uint16_t)sign;
        mant |= 0x800000u;
        uint32_t shift = (uint32_t)(14 - exp);
        uint32_t half = mant >> shift;
        return (uint16_t)(sign | half);
    }
    if (exp >= 31) return (uint16_t)(sign | 0x7C00u);
    uint32_t rounded = (mant + 0x1000u) >> 13;
    if (rounded >= 0x400u) {
        rounded = 0;
        exp += 1;
        if (exp >= 31) return (uint16_t)(sign | 0x7C00u);
    }
    return (uint16_t)(sign | ((uint32_t)exp << 10) | (rounded & 0x3FFu));
}

static float f16_to_f32(uint16_t h) {
    uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x3FFu;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) {
            bits = sign;
        } else {
            uint32_t e = 127 - 15 + 1;
            while ((mant & 0x400u) == 0) {
                mant <<= 1;
                e--;
            }
            mant &= 0x3FFu;
            bits = sign | (e << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7F800000u | (mant << 13);
    } else {
        bits = sign | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float out;
    memcpy(&out, &bits, sizeof(out));
    return out;
}

static bool write_f32_to_slot(VoxLockQnnSession* s, uint32_t slot, JNIEnv* env, jfloatArray arr, size_t elems) {
    void* dst = s->host_bufs[slot];
    if (s->dtypes[slot] == QNN_DATATYPE_FLOAT_32) {
        (*env)->GetFloatArrayRegion(env, arr, 0, (jsize)elems, (jfloat*)dst);
        return true;
    }
    (*env)->GetFloatArrayRegion(env, arr, 0, (jsize)elems, (jfloat*)s->f32_scratch);
    uint16_t* out = (uint16_t*)dst;
    for (size_t i = 0; i < elems; ++i) out[i] = f32_to_f16(s->f32_scratch[i]);
    return true;
}

static void read_slot_to_f32(VoxLockQnnSession* s, uint32_t slot, JNIEnv* env, jfloatArray arr, size_t elems) {
    const void* src = s->host_bufs[slot];
    if (s->dtypes[slot] == QNN_DATATYPE_FLOAT_32) {
        (*env)->SetFloatArrayRegion(env, arr, 0, (jsize)elems, (const jfloat*)src);
        return;
    }
    const uint16_t* inp = (const uint16_t*)src;
    for (size_t i = 0; i < elems; ++i) s->f32_scratch[i] = f16_to_f32(inp[i]);
    (*env)->SetFloatArrayRegion(env, arr, 0, (jsize)elems, (const jfloat*)s->f32_scratch);
}

static void copy_slot_elements(VoxLockQnnSession* s, uint32_t src_slot, uint32_t dst_slot, size_t elems) {
    if (s->dtypes[src_slot] == s->dtypes[dst_slot]) {
        memcpy(s->host_bufs[dst_slot], s->host_bufs[src_slot], s->buf_bytes[src_slot]);
        return;
    }
    if (s->dtypes[src_slot] == QNN_DATATYPE_FLOAT_32) {
        const float* src = (const float*)s->host_bufs[src_slot];
        uint16_t* dst = (uint16_t*)s->host_bufs[dst_slot];
        for (size_t i = 0; i < elems; ++i) dst[i] = f32_to_f16(src[i]);
    } else {
        const uint16_t* src = (const uint16_t*)s->host_bufs[src_slot];
        float* dst = (float*)s->host_bufs[dst_slot];
        for (size_t i = 0; i < elems; ++i) dst[i] = f16_to_f32(src[i]);
    }
}

static void deregister_shared(VoxLockQnnSession* s) {
    uint32_t total = s->num_in + s->num_out;
    if (!s->use_shared) return;
    if (s->has_qnn && s->qnn.memDeRegister && s->mem_handles) {
        Qnn_MemHandle_t* handles = (Qnn_MemHandle_t*)calloc(total, sizeof(Qnn_MemHandle_t));
        if (handles) {
            uint32_t nh = 0;
            for (uint32_t i = 0; i < total; ++i) {
                if (s->mem_handles[i]) handles[nh++] = s->mem_handles[i];
                s->mem_handles[i] = NULL;
            }
            if (nh > 0) s->qnn.memDeRegister(handles, nh);
            free(handles);
        }
    }
    s->use_shared = false;
}

static bool fallback_shared_to_raw(VoxLockQnnSession* s) {
    uint32_t total = s->num_in + s->num_out;
    void** heap_bufs = (void**)calloc(total, sizeof(void*));
    if (!heap_bufs) return false;
    for (uint32_t i = 0; i < total; ++i) {
        heap_bufs[i] = calloc(1, s->buf_bytes[i]);
        if (!heap_bufs[i]) {
            for (uint32_t j = 0; j < i; ++j) free(heap_bufs[j]);
            free(heap_bufs);
            return false;
        }
        // 이미 기록된 등록 텐서·상태(enroll_n, k, v, h, c, wav)를 보존한다.
        if (s->host_bufs[i]) memcpy(heap_bufs[i], s->host_bufs[i], s->buf_bytes[i]);
    }
    deregister_shared(s);
    if (s->rpc_block && s->rpc_free) {
        s->rpc_free(s->rpc_block);
        s->rpc_block = NULL;
    }
    for (uint32_t i = 0; i < total; ++i) s->host_bufs[i] = heap_bufs[i];
    free(heap_bufs);
    LOGW("QNN shared memory fell back to copy buffers");
    return true;
}

static void prepare_exec_tensors(VoxLockQnnSession* s) {
    for (uint32_t i = 0; i < s->num_in; ++i) {
        s->exec_in[i] = s->in_tensors[i];
        uint32_t slot = i;
        if (s->exec_in[i].version == QNN_TENSOR_VERSION_2) {
            if (s->use_shared) {
                s->exec_in[i].v2.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
                s->exec_in[i].v2.memHandle = s->mem_handles[slot];
            } else {
                s->exec_in[i].v2.memType = QNN_TENSORMEMTYPE_RAW;
                s->exec_in[i].v2.clientBuf.data = s->host_bufs[slot];
                s->exec_in[i].v2.clientBuf.dataSize = (uint32_t)s->buf_bytes[slot];
            }
        } else {
            if (s->use_shared) {
                s->exec_in[i].v1.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
                s->exec_in[i].v1.memHandle = s->mem_handles[slot];
            } else {
                s->exec_in[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
                s->exec_in[i].v1.clientBuf.data = s->host_bufs[slot];
                s->exec_in[i].v1.clientBuf.dataSize = (uint32_t)s->buf_bytes[slot];
            }
        }
    }
    for (uint32_t i = 0; i < s->num_out; ++i) {
        s->exec_out[i] = s->out_tensors[i];
        uint32_t slot = s->num_in + i;
        if (s->exec_out[i].version == QNN_TENSOR_VERSION_2) {
            if (s->use_shared) {
                s->exec_out[i].v2.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
                s->exec_out[i].v2.memHandle = s->mem_handles[slot];
            } else {
                s->exec_out[i].v2.memType = QNN_TENSORMEMTYPE_RAW;
                s->exec_out[i].v2.clientBuf.data = s->host_bufs[slot];
                s->exec_out[i].v2.clientBuf.dataSize = (uint32_t)s->buf_bytes[slot];
            }
        } else {
            if (s->use_shared) {
                s->exec_out[i].v1.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
                s->exec_out[i].v1.memHandle = s->mem_handles[slot];
            } else {
                s->exec_out[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
                s->exec_out[i].v1.clientBuf.data = s->host_bufs[slot];
                s->exec_out[i].v1.clientBuf.dataSize = (uint32_t)s->buf_bytes[slot];
            }
        }
    }
}

static void end_perf_locked(VoxLockQnnSession* s) {
    if (s->has_power_config && s->htp_perf_infra && s->htp_perf_infra->destroyPowerConfigId) {
        s->htp_perf_infra->destroyPowerConfigId(s->power_config_id);
    }
    s->has_power_config = false;
    s->power_config_id = 0;
}

static void start_perf_locked(VoxLockQnnSession* s) {
    if (s->has_power_config || !s->has_qnn || !s->qnn.deviceGetInfrastructure) return;
    QnnDevice_Infrastructure_t device_infra = NULL;
    if (s->qnn.deviceGetInfrastructure(&device_infra) != QNN_SUCCESS || !device_infra) return;
    QnnHtpDevice_Infrastructure_t* htp_infra = (QnnHtpDevice_Infrastructure_t*)device_infra;
    s->htp_perf_infra = &htp_infra->perfInfra;
    if (!s->htp_perf_infra || !s->htp_perf_infra->createPowerConfigId || !s->htp_perf_infra->setPowerConfig) return;

    uint32_t pid = 0;
    if (s->htp_perf_infra->createPowerConfigId(0, 0, &pid) != QNN_SUCCESS) return;

    QnnHtpPerfInfrastructure_PowerConfig_t dcvs;
    memset(&dcvs, 0, sizeof(dcvs));
    dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    dcvs.dcvsV3Config.contextId = pid;
    dcvs.dcvsV3Config.setDcvsEnable = 1;
    dcvs.dcvsV3Config.dcvsEnable = 0;
    dcvs.dcvsV3Config.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    dcvs.dcvsV3Config.setSleepLatency = 1;
    dcvs.dcvsV3Config.sleepLatency = 40;
    dcvs.dcvsV3Config.setBusParams = 1;
    dcvs.dcvsV3Config.setCoreParams = 1;
    dcvs.dcvsV3Config.busVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.busVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.coreVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.coreVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;

    QnnHtpPerfInfrastructure_PowerConfig_t rpc_poll;
    memset(&rpc_poll, 0, sizeof(rpc_poll));
    rpc_poll.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
    rpc_poll.rpcPollingTimeConfig = 9999;

    QnnHtpPerfInfrastructure_PowerConfig_t rpc_lat;
    memset(&rpc_lat, 0, sizeof(rpc_lat));
    rpc_lat.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
    rpc_lat.rpcControlLatencyConfig = 100;

    const QnnHtpPerfInfrastructure_PowerConfig_t* configs[] = {&dcvs, &rpc_poll, &rpc_lat, NULL};
    if (s->htp_perf_infra->setPowerConfig(pid, configs) == QNN_SUCCESS) {
        s->power_config_id = pid;
        s->has_power_config = true;
    } else if (s->htp_perf_infra->destroyPowerConfigId) {
        s->htp_perf_infra->destroyPowerConfigId(pid);
    }
}

static void destroy_session(VoxLockQnnSession* s) {
    if (!s) return;
    end_perf_locked(s);
    bool was_shared = s->use_shared;
    deregister_shared(s);
    if (s->rpc_block && s->rpc_free) {
        s->rpc_free(s->rpc_block);
        s->rpc_block = NULL;
    }
    if (s->host_bufs) {
        if (!was_shared) {
            uint32_t total = s->num_in + s->num_out;
            for (uint32_t i = 0; i < total; ++i) free(s->host_bufs[i]);
        }
        free(s->host_bufs);
    }
    free(s->buf_bytes);
    free(s->dtypes);
    free(s->mem_handles);
    free(s->f32_scratch);
    free(s->exec_in);
    free(s->exec_out);
    if (s->in_tensors) {
        for (uint32_t i = 0; i < s->num_in; ++i) free_copied_tensor(&s->in_tensors[i]);
        free(s->in_tensors);
    }
    if (s->out_tensors) {
        for (uint32_t i = 0; i < s->num_out; ++i) free_copied_tensor(&s->out_tensors[i]);
        free(s->out_tensors);
    }
    free(s->graph_name);
    if (s->has_qnn) {
        if (s->context && s->qnn.contextFree) s->qnn.contextFree(s->context, NULL);
        if (s->device && s->qnn.deviceFree) s->qnn.deviceFree(s->device);
        if (s->backend && s->qnn.backendFree) s->qnn.backendFree(s->backend);
        if (s->log_handle && s->qnn.logFree) s->qnn.logFree(s->log_handle);
    }
    if (s->rpc_lib) dlclose(s->rpc_lib);
    if (s->htp_lib) dlclose(s->htp_lib);
    if (s->sys_lib) dlclose(s->sys_lib);
    pthread_mutex_destroy(&s->lock);
    free(s);
}

JNIEXPORT void JNICALL
Java_com_voicerewriter_VoxLockQnn_nativePrependAdspPath(JNIEnv* env, jclass cls, jstring jdir) {
    (void)cls;
    if (!jdir) return;
    const char* dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    if (!dir) return;

    const char* existing = getenv("ADSP_LIBRARY_PATH");
    if (!existing || existing[0] == '\0') {
        size_t need = strlen(dir) + 64;
        char* buf = (char*)malloc(need);
        if (buf) {
            snprintf(buf, need, "%s;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp", dir);
            setenv("ADSP_LIBRARY_PATH", buf, 1);
            free(buf);
        }
    } else if (!strstr(existing, dir)) {
        size_t need = strlen(dir) + strlen(existing) + 4;
        char* buf = (char*)malloc(need);
        if (buf) {
            snprintf(buf, need, "%s;%s", dir, existing);
            setenv("ADSP_LIBRARY_PATH", buf, 1);
            free(buf);
        }
    }
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
}

JNIEXPORT jlong JNICALL
Java_com_voicerewriter_VoxLockQnn_nativeOpen(JNIEnv* env, jclass cls, jstring jbinPath) {
    (void)cls;
    if (!jbinPath) return 0L;
    const char* path = (*env)->GetStringUTFChars(env, jbinPath, NULL);
    if (!path) return 0L;

    FILE* fp = fopen(path, "rb");
    if (!fp) {
        LOGE("cannot open context binary: %s", path);
        (*env)->ReleaseStringUTFChars(env, jbinPath, path);
        return 0L;
    }
    (*env)->ReleaseStringUTFChars(env, jbinPath, path);

    if (fseek(fp, 0, SEEK_END) != 0) {
        fclose(fp);
        return 0L;
    }
    long fsz = ftell(fp);
    if (fsz <= 0) {
        fclose(fp);
        return 0L;
    }
    rewind(fp);
    void* bin_buf = malloc((size_t)fsz);
    if (!bin_buf) {
        fclose(fp);
        return 0L;
    }
    if (fread(bin_buf, 1, (size_t)fsz, fp) != (size_t)fsz) {
        free(bin_buf);
        fclose(fp);
        return 0L;
    }
    fclose(fp);

    VoxLockQnnSession* s = (VoxLockQnnSession*)calloc(1, sizeof(VoxLockQnnSession));
    if (!s) {
        free(bin_buf);
        return 0L;
    }
    pthread_mutex_init(&s->lock, NULL);
    s->idx_wav = s->idx_enroll_n = s->idx_k = s->idx_v = s->idx_h = s->idx_c = -1;
    s->idx_frames = s->idx_h_out = s->idx_c_out = -1;

    s->htp_lib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    s->sys_lib = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
    if (!s->htp_lib || !s->sys_lib) {
        LOGE("dlopen QNN libs failed: %s", dlerror());
        goto fail;
    }

    QnnGetProvidersFn get_p = (QnnGetProvidersFn)dlsym(s->htp_lib, "QnnInterface_getProviders");
    QnnSysGetProvidersFn get_sp = (QnnSysGetProvidersFn)dlsym(s->sys_lib, "QnnSystemInterface_getProviders");
    if (!get_p || !get_sp) {
        LOGE("dlsym Qnn providers failed");
        goto fail;
    }

    const QnnInterface_t** plist = NULL;
    uint32_t pcount = 0;
    if (get_p(&plist, &pcount) != QNN_SUCCESS || !plist || pcount == 0) goto fail;
    s->qnn = plist[0]->QNN_INTERFACE_VER_NAME;
    s->has_qnn = true;

    const QnnSystemInterface_t** splist = NULL;
    uint32_t spcount = 0;
    if (get_sp(&splist, &spcount) != QNN_SUCCESS || !splist || spcount == 0) goto fail;
    const QNN_SYSTEM_INTERFACE_VER_TYPE* sys = &splist[0]->QNN_SYSTEM_INTERFACE_VER_NAME;

    if (s->qnn.logCreate) {
        s->qnn.logCreate(qnn_log_callback, QNN_LOG_LEVEL_WARN, &s->log_handle);
    }
    if (!s->qnn.backendCreate || s->qnn.backendCreate(s->log_handle, NULL, &s->backend) != QNN_SUCCESS) {
        LOGE("backendCreate failed");
        goto fail;
    }
    if (s->qnn.deviceCreate && s->qnn.deviceCreate(s->log_handle, NULL, &s->device) != QNN_SUCCESS) {
        LOGE("deviceCreate failed");
        goto fail;
    }

    QnnSystemContext_Handle_t sys_ctx = NULL;
    if (!sys->systemContextCreate || sys->systemContextCreate(&sys_ctx) != QNN_SUCCESS) goto fail;
    const QnnSystemContext_BinaryInfo_t* binfo = NULL;
    Qnn_ContextBinarySize_t binfo_sz = 0;
    if (sys->systemContextGetBinaryInfo(sys_ctx, bin_buf, (uint64_t)fsz, &binfo, &binfo_sz) != QNN_SUCCESS) {
        sys->systemContextFree(sys_ctx);
        LOGE("systemContextGetBinaryInfo failed");
        goto fail;
    }

    uint32_t ngraphs = 0;
    const QnnSystemContext_GraphInfo_t* graphs = extract_graphs(binfo, &ngraphs);
    if (!graphs || ngraphs == 0) {
        sys->systemContextFree(sys_ctx);
        goto fail;
    }

    const char* gname = NULL;
    Qnn_Tensor_t* ins = NULL;
    Qnn_Tensor_t* outs = NULL;
    if (parse_graph_info(&graphs[0], &gname, &s->num_in, &ins, &s->num_out, &outs) != 0) {
        sys->systemContextFree(sys_ctx);
        goto fail;
    }
    s->graph_name = strdup(gname ? gname : "");
    s->in_tensors = (Qnn_Tensor_t*)calloc(s->num_in, sizeof(Qnn_Tensor_t));
    s->out_tensors = (Qnn_Tensor_t*)calloc(s->num_out, sizeof(Qnn_Tensor_t));
    s->exec_in = (Qnn_Tensor_t*)calloc(s->num_in, sizeof(Qnn_Tensor_t));
    s->exec_out = (Qnn_Tensor_t*)calloc(s->num_out, sizeof(Qnn_Tensor_t));
    if (!s->graph_name || !s->in_tensors || !s->out_tensors || !s->exec_in || !s->exec_out) {
        sys->systemContextFree(sys_ctx);
        goto fail;
    }
    for (uint32_t i = 0; i < s->num_in; ++i) {
        if (!deep_copy_tensor(&ins[i], &s->in_tensors[i])) {
            sys->systemContextFree(sys_ctx);
            goto fail;
        }
    }
    for (uint32_t i = 0; i < s->num_out; ++i) {
        if (!deep_copy_tensor(&outs[i], &s->out_tensors[i])) {
            sys->systemContextFree(sys_ctx);
            goto fail;
        }
    }
    sys->systemContextFree(sys_ctx);

    Qnn_ErrorHandle_t crc = s->qnn.contextCreateFromBinary(
        s->backend, s->device, NULL, bin_buf, (Qnn_ContextBinarySize_t)fsz, &s->context, NULL);
    free(bin_buf);
    bin_buf = NULL;
    if (crc != QNN_SUCCESS) {
        LOGE("contextCreateFromBinary failed: %lu", (unsigned long)crc);
        goto fail;
    }
    if (s->qnn.graphRetrieve(s->context, s->graph_name, &s->graph) != QNN_SUCCESS) {
        LOGE("graphRetrieve failed for %s", s->graph_name);
        goto fail;
    }

    uint32_t total = s->num_in + s->num_out;
    s->host_bufs = (void**)calloc(total, sizeof(void*));
    s->buf_bytes = (size_t*)calloc(total, sizeof(size_t));
    s->dtypes = (Qnn_DataType_t*)calloc(total, sizeof(Qnn_DataType_t));
    s->mem_handles = (Qnn_MemHandle_t*)calloc(total, sizeof(Qnn_MemHandle_t));
    if (!s->host_bufs || !s->buf_bytes || !s->dtypes || !s->mem_handles) goto fail;

    bool has_fp16 = false;
    for (uint32_t i = 0; i < s->num_in; ++i) {
        const Qnn_Tensor_t* t = &s->in_tensors[i];
        const char* name = tensor_name(t);
        size_t elems = tensor_elems(t);
        Qnn_DataType_t dt = tensor_dtype(t);
        if (dt != QNN_DATATYPE_FLOAT_32 && dt != QNN_DATATYPE_FLOAT_16) goto fail;
        if (dt == QNN_DATATYPE_FLOAT_16) has_fp16 = true;
        s->dtypes[i] = dt;
        s->buf_bytes[i] = elems * dtype_bpe(dt);
        if (strcmp(name, "wav") == 0 && elems == EXPECTED_WAV_ELEMS) s->idx_wav = (int)i;
        else if (strcmp(name, "enroll_n") == 0 && elems == EXPECTED_ENROLL_N_ELEMS) s->idx_enroll_n = (int)i;
        else if (strcmp(name, "k") == 0 && elems == EXPECTED_KV_ELEMS) s->idx_k = (int)i;
        else if (strcmp(name, "v") == 0 && elems == EXPECTED_KV_ELEMS) s->idx_v = (int)i;
        else if (strcmp(name, "h") == 0 && elems == EXPECTED_STATE_ELEMS) s->idx_h = (int)i;
        else if (strcmp(name, "c") == 0 && elems == EXPECTED_STATE_ELEMS) s->idx_c = (int)i;
    }
    for (uint32_t i = 0; i < s->num_out; ++i) {
        const Qnn_Tensor_t* t = &s->out_tensors[i];
        const char* name = tensor_name(t);
        size_t elems = tensor_elems(t);
        Qnn_DataType_t dt = tensor_dtype(t);
        if (dt != QNN_DATATYPE_FLOAT_32 && dt != QNN_DATATYPE_FLOAT_16) goto fail;
        if (dt == QNN_DATATYPE_FLOAT_16) has_fp16 = true;
        uint32_t slot = s->num_in + i;
        s->dtypes[slot] = dt;
        s->buf_bytes[slot] = elems * dtype_bpe(dt);
        if (strcmp(name, "frames") == 0 && elems == EXPECTED_FRAMES_ELEMS) s->idx_frames = (int)slot;
        else if (strcmp(name, "h_out") == 0 && elems == EXPECTED_STATE_ELEMS) s->idx_h_out = (int)slot;
        else if (strcmp(name, "c_out") == 0 && elems == EXPECTED_STATE_ELEMS) s->idx_c_out = (int)slot;
    }
    if (s->idx_wav < 0 || s->idx_enroll_n < 0 || s->idx_k < 0 || s->idx_v < 0 ||
        s->idx_h < 0 || s->idx_c < 0 || s->idx_frames < 0 || s->idx_h_out < 0 || s->idx_c_out < 0) {
        LOGE("unexpected VoxLock QNN tensor signature");
        goto fail;
    }
    if (has_fp16) {
        s->f32_scratch = (float*)malloc(EXPECTED_KV_ELEMS * sizeof(float));
        if (!s->f32_scratch) goto fail;
    }

    // libcdsprpc.so rpcmem 0-복사 공유 버퍼 시도 (sherpa-qnn.patch 동일 패턴)
    s->rpc_lib = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (s->rpc_lib && s->qnn.memRegister && s->qnn.memDeRegister) {
        RpcMemAllocFn ralloc = (RpcMemAllocFn)dlsym(s->rpc_lib, "rpcmem_alloc");
        RpcMemToFdFn rfd = (RpcMemToFdFn)dlsym(s->rpc_lib, "rpcmem_to_fd");
        s->rpc_free = (RpcMemFreeFn)dlsym(s->rpc_lib, "rpcmem_free");
        if (ralloc && rfd && s->rpc_free) {
            size_t total_rpc = 0;
            for (uint32_t i = 0; i < total; ++i) {
                total_rpc += ((s->buf_bytes[i] + 4095u) / 4096u) * 4096u;
            }
            s->rpc_block = ralloc(25, 1, (int)total_rpc);
            int fd = s->rpc_block ? rfd(s->rpc_block) : -1;
            if (fd >= 0) {
                size_t off = 0;
                bool ok = true;
                for (uint32_t i = 0; i < total; ++i) {
                    const Qnn_Tensor_t* t = (i < s->num_in) ? &s->in_tensors[i] : &s->out_tensors[i - s->num_in];
                    QnnMemHtp_Descriptor_t htp;
                    memset(&htp, 0, sizeof(htp));
                    htp.type = QNN_HTP_MEM_SHARED_BUFFER;
                    htp.size = total_rpc;
                    htp.sharedBufferConfig.fd = fd;
                    htp.sharedBufferConfig.offset = (uint32_t)off;

                    Qnn_MemDescriptor_t desc = QNN_MEM_DESCRIPTOR_INIT;
                    desc.memShape.numDim = tensor_rank(t);
                    desc.memShape.dimSize = tensor_dims(t);
                    desc.dataType = s->dtypes[i];
                    desc.memType = QNN_MEM_TYPE_CUSTOM;
                    desc.customInfo = &htp;
                    if (s->qnn.memRegister(s->context, &desc, 1, &s->mem_handles[i]) != QNN_SUCCESS) {
                        ok = false;
                        break;
                    }
                    s->host_bufs[i] = (uint8_t*)s->rpc_block + off;
                    off += ((s->buf_bytes[i] + 4095u) / 4096u) * 4096u;
                }
                if (ok) {
                    s->use_shared = true;
                    LOGI("QNN shared memory ready: %.2f MB", (double)total_rpc / (1024.0 * 1024.0));
                } else {
                    s->use_shared = true; // deregister_shared가 부분 등록된 핸들을 해제하게 함
                    deregister_shared(s);
                    memset(s->host_bufs, 0, total * sizeof(void*));
                    s->rpc_free(s->rpc_block);
                    s->rpc_block = NULL;
                }
            }
        }
    }

    if (!s->use_shared) {
        for (uint32_t i = 0; i < total; ++i) {
            s->host_bufs[i] = calloc(1, s->buf_bytes[i]);
            if (!s->host_bufs[i]) goto fail;
        }
        LOGI("QNN copy buffer mode ready");
    }

    return (jlong)(intptr_t)s;

fail:
    free(bin_buf);
    destroy_session(s);
    return 0L;
}

JNIEXPORT jboolean JNICALL
Java_com_voicerewriter_VoxLockQnn_nativeBindStream(
    JNIEnv* env, jclass cls, jlong handle,
    jfloatArray jenrollN, jfloatArray jk, jfloatArray jv, jboolean burstPerf) {
    (void)cls;
    VoxLockQnnSession* s = (VoxLockQnnSession*)(intptr_t)handle;
    if (!s || !jenrollN || !jk || !jv) return JNI_FALSE;
    if ((*env)->GetArrayLength(env, jenrollN) != (jsize)EXPECTED_ENROLL_N_ELEMS ||
        (*env)->GetArrayLength(env, jk) != (jsize)EXPECTED_KV_ELEMS ||
        (*env)->GetArrayLength(env, jv) != (jsize)EXPECTED_KV_ELEMS) {
        return JNI_FALSE;
    }

    pthread_mutex_lock(&s->lock);
    write_f32_to_slot(s, (uint32_t)s->idx_enroll_n, env, jenrollN, EXPECTED_ENROLL_N_ELEMS);
    write_f32_to_slot(s, (uint32_t)s->idx_k, env, jk, EXPECTED_KV_ELEMS);
    write_f32_to_slot(s, (uint32_t)s->idx_v, env, jv, EXPECTED_KV_ELEMS);
    memset(s->host_bufs[s->idx_h], 0, s->buf_bytes[s->idx_h]);
    memset(s->host_bufs[s->idx_c], 0, s->buf_bytes[s->idx_c]);
    if (burstPerf == JNI_TRUE) start_perf_locked(s);
    else end_perf_locked(s);
    pthread_mutex_unlock(&s->lock);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_voicerewriter_VoxLockQnn_nativeStep(
    JNIEnv* env, jclass cls, jlong handle, jfloatArray jseg, jfloatArray joutFrames) {
    (void)cls;
    VoxLockQnnSession* s = (VoxLockQnnSession*)(intptr_t)handle;
    if (!s || !jseg || !joutFrames) return -1;
    if ((*env)->GetArrayLength(env, jseg) != (jsize)EXPECTED_WAV_ELEMS ||
        (*env)->GetArrayLength(env, joutFrames) < (jsize)EXPECTED_FRAMES_ELEMS) {
        return -1;
    }

    pthread_mutex_lock(&s->lock);
    write_f32_to_slot(s, (uint32_t)s->idx_wav, env, jseg, EXPECTED_WAV_ELEMS);
    prepare_exec_tensors(s);
    Qnn_ErrorHandle_t rc = s->qnn.graphExecute(
        s->graph, s->exec_in, s->num_in, s->exec_out, s->num_out, NULL, NULL);
    if (rc != QNN_SUCCESS && s->use_shared) {
        if (fallback_shared_to_raw(s)) {
            prepare_exec_tensors(s);
            rc = s->qnn.graphExecute(
                s->graph, s->exec_in, s->num_in, s->exec_out, s->num_out, NULL, NULL);
        }
    }
    if (rc != QNN_SUCCESS) {
        LOGE("graphExecute failed: %lu", (unsigned long)rc);
        pthread_mutex_unlock(&s->lock);
        return -2;
    }

    read_slot_to_f32(s, (uint32_t)s->idx_frames, env, joutFrames, EXPECTED_FRAMES_ELEMS);
    copy_slot_elements(s, (uint32_t)s->idx_h_out, (uint32_t)s->idx_h, EXPECTED_STATE_ELEMS);
    copy_slot_elements(s, (uint32_t)s->idx_c_out, (uint32_t)s->idx_c, EXPECTED_STATE_ELEMS);
    pthread_mutex_unlock(&s->lock);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_voicerewriter_VoxLockQnn_nativeEndStream(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    VoxLockQnnSession* s = (VoxLockQnnSession*)(intptr_t)handle;
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    end_perf_locked(s);
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT void JNICALL
Java_com_voicerewriter_VoxLockQnn_nativeClose(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    VoxLockQnnSession* s = (VoxLockQnnSession*)(intptr_t)handle;
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    pthread_mutex_unlock(&s->lock);
    destroy_session(s);
}

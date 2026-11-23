LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := coro_midi
LOCAL_SRC_FILES := midi_synth_jni.c
LOCAL_CFLAGS    := -O3 -Wall
LOCAL_LDLIBS    := -lm -llog
include $(BUILD_SHARED_LIBRARY)

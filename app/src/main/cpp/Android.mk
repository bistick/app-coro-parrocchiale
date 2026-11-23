LOCAL_PATH := $(call my-dir)
FLUIDSYNTH_DIR := $(LOCAL_PATH)/../../../build/fluidsynth/v2.6.2

include $(CLEAR_VARS)
LOCAL_MODULE     := coro_midi
LOCAL_SRC_FILES  := midi_synth_jni.c
LOCAL_C_INCLUDES := $(FLUIDSYNTH_DIR)/include
LOCAL_CFLAGS     := -O3 -Wall
LOCAL_LDFLAGS    := -L$(FLUIDSYNTH_DIR)/jniLibs/$(TARGET_ARCH_ABI) -Wl,-rpath-link,$(FLUIDSYNTH_DIR)/jniLibs/$(TARGET_ARCH_ABI) -lfluidsynth
LOCAL_LDLIBS     := -lm -llog
include $(BUILD_SHARED_LIBRARY)

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>

#define TSF_IMPLEMENTATION
#include "tsf.h"

#define TML_IMPLEMENTATION
#include "tml.h"

#define LOG_TAG "CoroMidiSynth"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define DRUM_CHANNEL 9
#define RENDER_SUBBLOCK_FRAMES 64
#define MAX_TAIL_MS 3000.0

typedef struct {
    tsf* synth;
    tml_message* midiHead;
    tml_message* currentMsg;
    int sampleRate;
    int transposeSemitones;
    double playbackTimeMs;
    unsigned int totalDurationMs;
    double endReachedAtMs;
} MidiSession;

static inline int clamp_midi_key(int key) {
    if (key < 0) return 0;
    if (key > 127) return 127;
    return key;
}

static inline int transpose_key(int channel, int key, int semitones) {
    if (channel == DRUM_CHANNEL) {
        return key;
    }
    return clamp_midi_key(key + semitones);
}

static void reset_gm_channels(tsf* synth) {
    if (!synth) return;
    tsf_reset(synth);
    for (int ch = 0; ch < 16; ch++) {
        if (ch == DRUM_CHANNEL) {
            tsf_channel_set_bank_preset(synth, ch, 128, 0);
        } else {
            tsf_channel_set_bank_preset(synth, ch, 0, 0);
        }
        tsf_channel_midi_control(synth, ch, 7, 100);   // Main volume
        tsf_channel_midi_control(synth, ch, 10, 64);   // Center pan
        tsf_channel_midi_control(synth, ch, 11, 127);  // Expression
        tsf_channel_set_pitchwheel(synth, ch, 8192);   // Center pitch wheel
    }
}

static void apply_state_message(tsf* synth, const tml_message* msg) {
    switch (msg->type) {
        case TML_PROGRAM_CHANGE:
            tsf_channel_set_presetnumber(
                synth,
                msg->channel,
                msg->program,
                (msg->channel == DRUM_CHANNEL) ? 1 : 0
            );
            break;
        case TML_CONTROL_CHANGE:
            tsf_channel_midi_control(synth, msg->channel, msg->control, msg->control_value);
            break;
        case TML_PITCH_BEND:
            tsf_channel_set_pitchwheel(synth, msg->channel, msg->pitch_bend);
            break;
        default:
            break;
    }
}

static void dispatch_message(MidiSession* session, const tml_message* msg) {
    tsf* synth = session->synth;
    switch (msg->type) {
        case TML_PROGRAM_CHANGE:
        case TML_CONTROL_CHANGE:
        case TML_PITCH_BEND:
            apply_state_message(synth, msg);
            break;
        case TML_NOTE_ON: {
            int key = transpose_key(msg->channel, msg->key, session->transposeSemitones);
            if (msg->velocity == 0) {
                tsf_channel_note_off(synth, msg->channel, key);
            } else {
                tsf_channel_note_on(synth, msg->channel, key, (float)msg->velocity / 127.0f);
            }
            break;
        }
        case TML_NOTE_OFF: {
            int key = transpose_key(msg->channel, msg->key, session->transposeSemitones);
            tsf_channel_note_off(synth, msg->channel, key);
            break;
        }
        default:
            break;
    }
}

JNIEXPORT jlong JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeCreate(
    JNIEnv* env,
    jobject thiz,
    jstring soundFontPath,
    jint sampleRate
) {
    if (!soundFontPath) return 0L;
    const char* path = (*env)->GetStringUTFChars(env, soundFontPath, NULL);
    if (!path) return 0L;

    tsf* synth = tsf_load_filename(path);
    (*env)->ReleaseStringUTFChars(env, soundFontPath, path);

    if (!synth) {
        LOGE("Impossibile caricare il SoundFont");
        return 0L;
    }

    int sr = (sampleRate > 0) ? (int)sampleRate : 44100;
    tsf_set_output(synth, TSF_STEREO_INTERLEAVED, sr, 0.0f);
    tsf_set_max_voices(synth, 256);
    reset_gm_channels(synth);

    MidiSession* session = (MidiSession*)calloc(1, sizeof(MidiSession));
    if (!session) {
        tsf_close(synth);
        return 0L;
    }
    session->synth = synth;
    session->sampleRate = sr;
    session->endReachedAtMs = -1.0;

    return (jlong)(intptr_t)session;
}

JNIEXPORT jlong JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeLoadMidi(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jbyteArray midiBytes
) {
    MidiSession* session = (MidiSession*)(intptr_t)handle;
    if (!session || !midiBytes) return -1L;

    if (session->midiHead) {
        tml_free(session->midiHead);
        session->midiHead = NULL;
        session->currentMsg = NULL;
    }

    jsize length = (*env)->GetArrayLength(env, midiBytes);
    if (length <= 0) return -1L;

    jbyte* bytes = (*env)->GetByteArrayElements(env, midiBytes, NULL);
    if (!bytes) return -1L;

    tml_message* head = tml_load_memory(bytes, (int)length);
    (*env)->ReleaseByteArrayElements(env, midiBytes, bytes, JNI_ABORT);

    if (!head) {
        LOGE("Impossibile effettuare il parsing del file MIDI");
        return -1L;
    }

    unsigned int durationMs = 0;
    tml_get_info(head, NULL, NULL, NULL, NULL, &durationMs);

    session->midiHead = head;
    session->currentMsg = head;
    session->totalDurationMs = durationMs;
    session->playbackTimeMs = 0.0;
    session->endReachedAtMs = -1.0;
    reset_gm_channels(session->synth);

    return (jlong)durationMs;
}

JNIEXPORT void JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeSeek(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong positionMs
) {
    MidiSession* session = (MidiSession*)(intptr_t)handle;
    if (!session || !session->synth) return;

    double target = (positionMs < 0) ? 0.0 : (double)positionMs;
    reset_gm_channels(session->synth);

    tml_message* msg = session->midiHead;
    while (msg && (double)msg->time < target) {
        apply_state_message(session->synth, msg);
        msg = msg->next;
    }

    session->currentMsg = msg;
    session->playbackTimeMs = target;
    session->endReachedAtMs = -1.0;
}

JNIEXPORT void JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeSetTranspose(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint semitones
) {
    MidiSession* session = (MidiSession*)(intptr_t)handle;
    if (!session || !session->synth) return;

    int clamped = semitones;
    if (clamped < -12) clamped = -12;
    if (clamped > 12) clamped = 12;

    if (session->transposeSemitones != clamped) {
        session->transposeSemitones = clamped;
        // Evita note bloccate quando si cambia tonalita' in anteprima
        for (int ch = 0; ch < 16; ch++) {
            if (ch != DRUM_CHANNEL) {
                tsf_channel_note_off_all(session->synth, ch);
            }
        }
    }
}

JNIEXPORT void JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeAllNotesOff(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    MidiSession* session = (MidiSession*)(intptr_t)handle;
    if (!session || !session->synth) return;
    tsf_note_off_all(session->synth);
}

JNIEXPORT jboolean JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeRender(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jfloatArray outBuffer,
    jint frameCount
) {
    MidiSession* session = (MidiSession*)(intptr_t)handle;
    if (!session || !session->synth || !outBuffer || frameCount <= 0) {
        return JNI_TRUE;
    }

    jsize arrLen = (*env)->GetArrayLength(env, outBuffer);
    if (arrLen < frameCount * 2) {
        return JNI_TRUE;
    }

    jfloat* buf = (*env)->GetFloatArrayElements(env, outBuffer, NULL);
    if (!buf) {
        return JNI_TRUE;
    }

    int framesRemaining = (int)frameCount;
    float* writePtr = (float*)buf;

    while (framesRemaining > 0) {
        int block = (framesRemaining > RENDER_SUBBLOCK_FRAMES)
            ? RENDER_SUBBLOCK_FRAMES
            : framesRemaining;

        session->playbackTimeMs += ((double)block * 1000.0) / (double)session->sampleRate;

        while (session->currentMsg && (double)session->currentMsg->time <= session->playbackTimeMs) {
            dispatch_message(session, session->currentMsg);
            session->currentMsg = session->currentMsg->next;
        }

        tsf_render_float(session->synth, writePtr, block, 0);
        writePtr += (block * 2);
        framesRemaining -= block;
    }

    (*env)->ReleaseFloatArrayElements(env, outBuffer, buf, 0);

    if (session->currentMsg == NULL) {
        if (session->endReachedAtMs < 0.0) {
            session->endReachedAtMs = session->playbackTimeMs;
        }
        int activeVoices = tsf_active_voice_count(session->synth);
        double tailElapsed = session->playbackTimeMs - session->endReachedAtMs;
        if (activeVoices == 0 || tailElapsed >= MAX_TAIL_MS) {
            return JNI_TRUE;
        }
    }

    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeGetPositionMs(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    MidiSession* session = (MidiSession*)(intptr_t)handle;
    if (!session) return 0L;
    return (jlong)session->playbackTimeMs;
}

JNIEXPORT void JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeDestroy(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    MidiSession* session = (MidiSession*)(intptr_t)handle;
    if (!session) return;

    if (session->midiHead) {
        tml_free(session->midiHead);
        session->midiHead = NULL;
    }
    if (session->synth) {
        tsf_close(session->synth);
        session->synth = NULL;
    }
    free(session);
}

JNIEXPORT jlong JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeProbeMidiDurationMs(
    JNIEnv* env,
    jobject thiz,
    jbyteArray midiBytes
) {
    if (!midiBytes) return 0L;
    jsize length = (*env)->GetArrayLength(env, midiBytes);
    if (length <= 0) return 0L;

    jbyte* bytes = (*env)->GetByteArrayElements(env, midiBytes, NULL);
    if (!bytes) return 0L;

    tml_message* head = tml_load_memory(bytes, (int)length);
    (*env)->ReleaseByteArrayElements(env, midiBytes, bytes, JNI_ABORT);
    if (!head) return 0L;

    unsigned int durationMs = 0;
    tml_get_info(head, NULL, NULL, NULL, NULL, &durationMs);
    tml_free(head);

    return (jlong)durationMs;
}

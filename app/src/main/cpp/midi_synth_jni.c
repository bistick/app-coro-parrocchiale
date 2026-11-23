#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>
#include <sys/stat.h>
#include <android/log.h>

#include <fluidsynth.h>

#define TML_IMPLEMENTATION
#include "tml.h"

#define LOG_TAG "CoroMidiSynth"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define DRUM_CHANNEL 9
#define RENDER_SUBBLOCK_FRAMES 64
#define MAX_TAIL_MS 3200.0
#define MAX_SF_PATH 1024

typedef struct {
    fluid_settings_t* settings;
    fluid_synth_t* synth;
    int ownsSynth;
    tml_message* midiHead;
    tml_message* currentMsg;
    int sampleRate;
    int transposeSemitones;
    double playbackTimeMs;
    unsigned int totalDurationMs;
    double endReachedAtMs;
} MidiSession;

/* Cache globale di un'istanza FluidSynth con SoundFont gia' caricato in RAM,
 * per avviare i brani successivi della messa istantaneamente senza ricaricare da disco. */
static pthread_mutex_t g_cache_mutex = PTHREAD_MUTEX_INITIALIZER;
static fluid_settings_t* g_cached_settings = NULL;
static fluid_synth_t* g_cached_synth = NULL;
static char g_cached_path[MAX_SF_PATH] = {0};
static int g_cached_sample_rate = 0;
static off_t g_cached_file_size = 0;
static time_t g_cached_file_mtime = 0;
static int g_cache_in_use = 0;
static int g_cache_dirty = 0;

static inline int clamp_midi_key(int key) {
    if (key < 0) return 0;
    if (key > 127) return 127;
    return key;
}

static inline int transpose_key(int channel, int key, int semitones) {
    if (channel == DRUM_CHANNEL) {
        return clamp_midi_key(key);
    }
    return clamp_midi_key(key + semitones);
}

static void configure_synth_effects(fluid_synth_t* synth) {
    if (!synth) return;

    /* Headroom polifonico calibrato per evitare saturazione sugli accordi pieni */
    fluid_synth_set_gain(synth, 0.45f);
    fluid_synth_set_interp_method(synth, -1, FLUID_INTERP_4THORDER);

    /* Riverbero caldo e naturale adatto a organo, piano e archi */
    fluid_synth_reverb_on(synth, -1, 1);
    fluid_synth_set_reverb_group_roomsize(synth, -1, 0.58);
    fluid_synth_set_reverb_group_damp(synth, -1, 0.35);
    fluid_synth_set_reverb_group_width(synth, -1, 0.85);
    fluid_synth_set_reverb_group_level(synth, -1, 0.55);

    /* Chorus morbido per archi e pad */
    fluid_synth_chorus_on(synth, -1, 1);
    fluid_synth_set_chorus_group_nr(synth, -1, 3);
    fluid_synth_set_chorus_group_level(synth, -1, 1.0);
    fluid_synth_set_chorus_group_speed(synth, -1, 0.3);
    fluid_synth_set_chorus_group_depth(synth, -1, 6.0);
    fluid_synth_set_chorus_group_type(synth, -1, FLUID_CHORUS_MOD_SINE);
}

static void flush_effects_tail(fluid_synth_t* synth, int sampleRate) {
    if (!synth) return;
    /* Con 0 voci attive, scaricare ~0.8s di delay-line reverb/chorus richiede <1ms di CPU
     * ed elimina qualsiasi coda residua del brano precedente. */
    float scratch[512];
    int totalFrames = (sampleRate > 0) ? (int)(sampleRate * 0.8) : 38400;
    int remaining = totalFrames;
    while (remaining > 0) {
        int block = (remaining > 256) ? 256 : remaining;
        fluid_synth_write_float(synth, block, scratch, 0, 2, scratch, 1, 2);
        remaining -= block;
    }
}

static void reset_gm_channels(fluid_synth_t* synth) {
    if (!synth) return;
    fluid_synth_all_sounds_off(synth, -1);
    fluid_synth_system_reset(synth);
    configure_synth_effects(synth);
}

static int create_fluid_instance(
    const char* path,
    int sampleRate,
    fluid_settings_t** outSettings,
    fluid_synth_t** outSynth
) {
    fluid_settings_t* settings = new_fluid_settings();
    if (!settings) {
        LOGE("Impossibile allocare fluid_settings_t");
        return 0;
    }

    fluid_settings_setnum(settings, "synth.sample-rate", (double)sampleRate);
    fluid_settings_setint(settings, "synth.polyphony", 256);
    fluid_settings_setint(settings, "synth.cpu-cores", 1);
    fluid_settings_setint(settings, "synth.threadsafe-api", 0);
    fluid_settings_setint(settings, "synth.reverb.active", 1);
    fluid_settings_setint(settings, "synth.chorus.active", 1);
    fluid_settings_setint(settings, "synth.dynamic-sample-loading", 0);
    fluid_settings_setnum(settings, "synth.gain", 0.45);
    fluid_settings_setstr(settings, "synth.midi-bank-select", "gs");

    fluid_synth_t* synth = new_fluid_synth(settings);
    if (!synth) {
        LOGE("Impossibile creare fluid_synth_t");
        delete_fluid_settings(settings);
        return 0;
    }

    int sfId = fluid_synth_sfload(synth, path, 1);
    if (sfId == FLUID_FAILED) {
        LOGE("Impossibile caricare il SoundFont con FluidSynth: %s", path);
        delete_fluid_synth(synth);
        delete_fluid_settings(settings);
        return 0;
    }

    configure_synth_effects(synth);
    *outSettings = settings;
    *outSynth = synth;
    LOGI("FluidSynth pronto: %s @ %d Hz (sfId=%d)", path, sampleRate, sfId);
    return 1;
}

static void free_cached_synth_locked(void) {
    if (g_cached_synth) {
        delete_fluid_synth(g_cached_synth);
        g_cached_synth = NULL;
    }
    if (g_cached_settings) {
        delete_fluid_settings(g_cached_settings);
        g_cached_settings = NULL;
    }
    g_cached_path[0] = '\0';
    g_cached_sample_rate = 0;
    g_cached_file_size = 0;
    g_cached_file_mtime = 0;
    g_cache_dirty = 0;
}

static void apply_state_message(fluid_synth_t* synth, const tml_message* msg) {
    int ch = (int)(unsigned char)msg->channel & 0x0F;
    switch (msg->type) {
        case TML_PROGRAM_CHANGE:
            fluid_synth_program_change(synth, ch, (int)(unsigned char)msg->program & 0x7F);
            break;
        case TML_CONTROL_CHANGE:
            fluid_synth_cc(
                synth,
                ch,
                (int)(unsigned char)msg->control & 0x7F,
                (int)(unsigned char)msg->control_value & 0x7F
            );
            break;
        case TML_PITCH_BEND:
            fluid_synth_pitch_bend(synth, ch, (int)msg->pitch_bend);
            break;
        case TML_CHANNEL_PRESSURE:
            fluid_synth_channel_pressure(synth, ch, (int)(unsigned char)msg->channel_pressure & 0x7F);
            break;
        default:
            break;
    }
}

static void dispatch_message(MidiSession* session, const tml_message* msg) {
    fluid_synth_t* synth = session->synth;
    int ch = (int)(unsigned char)msg->channel & 0x0F;
    switch (msg->type) {
        case TML_PROGRAM_CHANGE:
        case TML_CONTROL_CHANGE:
        case TML_PITCH_BEND:
        case TML_CHANNEL_PRESSURE:
            apply_state_message(synth, msg);
            break;
        case TML_KEY_PRESSURE: {
            int key = transpose_key(ch, (int)(unsigned char)msg->key & 0x7F, session->transposeSemitones);
            fluid_synth_key_pressure(synth, ch, key, (int)(unsigned char)msg->key_pressure & 0x7F);
            break;
        }
        case TML_NOTE_ON: {
            int key = transpose_key(ch, (int)(unsigned char)msg->key & 0x7F, session->transposeSemitones);
            int vel = (int)(unsigned char)msg->velocity & 0x7F;
            if (vel == 0) {
                fluid_synth_noteoff(synth, ch, key);
            } else {
                fluid_synth_noteon(synth, ch, key, vel);
            }
            break;
        }
        case TML_NOTE_OFF: {
            int key = transpose_key(ch, (int)(unsigned char)msg->key & 0x7F, session->transposeSemitones);
            fluid_synth_noteoff(synth, ch, key);
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

    int sr = (sampleRate >= 22050 && sampleRate <= 96000) ? (int)sampleRate : 48000;
    struct stat st;
    off_t fileSize = 0;
    time_t fileMtime = 0;
    if (stat(path, &st) == 0) {
        fileSize = st.st_size;
        fileMtime = st.st_mtime;
    }

    fluid_settings_t* settings = NULL;
    fluid_synth_t* synth = NULL;
    int ownsSynth = 0;

    pthread_mutex_lock(&g_cache_mutex);
    if (!g_cache_in_use) {
        int cacheHit = (g_cached_synth != NULL) &&
            (g_cached_sample_rate == sr) &&
            (g_cached_file_size == fileSize) &&
            (g_cached_file_mtime == fileMtime) &&
            (strcmp(g_cached_path, path) == 0);

        if (cacheHit) {
            settings = g_cached_settings;
            synth = g_cached_synth;
            g_cache_in_use = 1;
            int needsFlush = g_cache_dirty;
            g_cache_dirty = 1;
            pthread_mutex_unlock(&g_cache_mutex);

            reset_gm_channels(synth);
            if (needsFlush) {
                flush_effects_tail(synth, sr);
            }
            LOGI("FluidSynth cache hit: %s @ %d Hz", path, sr);
        } else {
            /* Libera il vecchio banco prima di caricare il nuovo per ridurre il picco RAM */
            free_cached_synth_locked();
            g_cache_in_use = 1;
            pthread_mutex_unlock(&g_cache_mutex);

            if (!create_fluid_instance(path, sr, &settings, &synth)) {
                pthread_mutex_lock(&g_cache_mutex);
                g_cache_in_use = 0;
                pthread_mutex_unlock(&g_cache_mutex);
                (*env)->ReleaseStringUTFChars(env, soundFontPath, path);
                return 0L;
            }

            pthread_mutex_lock(&g_cache_mutex);
            g_cached_settings = settings;
            g_cached_synth = synth;
            strncpy(g_cached_path, path, MAX_SF_PATH - 1);
            g_cached_path[MAX_SF_PATH - 1] = '\0';
            g_cached_sample_rate = sr;
            g_cached_file_size = fileSize;
            g_cached_file_mtime = fileMtime;
            g_cache_dirty = 1;
            pthread_mutex_unlock(&g_cache_mutex);
        }
    } else {
        /* Fallback nel caso raro di due sessioni sovrapposte: istanza dedicata */
        pthread_mutex_unlock(&g_cache_mutex);
        ownsSynth = 1;
        if (!create_fluid_instance(path, sr, &settings, &synth)) {
            (*env)->ReleaseStringUTFChars(env, soundFontPath, path);
            return 0L;
        }
    }

    (*env)->ReleaseStringUTFChars(env, soundFontPath, path);

    MidiSession* session = (MidiSession*)calloc(1, sizeof(MidiSession));
    if (!session) {
        if (ownsSynth) {
            delete_fluid_synth(synth);
            delete_fluid_settings(settings);
        } else {
            pthread_mutex_lock(&g_cache_mutex);
            g_cache_in_use = 0;
            pthread_mutex_unlock(&g_cache_mutex);
        }
        return 0L;
    }

    session->settings = settings;
    session->synth = synth;
    session->ownsSynth = ownsSynth;
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
        /* Evita note bloccate quando si cambia tonalita' in anteprima */
        for (int ch = 0; ch < 16; ch++) {
            if (ch != DRUM_CHANNEL) {
                fluid_synth_all_notes_off(session->synth, ch);
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
    fluid_synth_all_notes_off(session->synth, -1);
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

        fluid_synth_write_float(session->synth, block, writePtr, 0, 2, writePtr, 1, 2);
        writePtr += (block * 2);
        framesRemaining -= block;
    }

    (*env)->ReleaseFloatArrayElements(env, outBuffer, buf, 0);

    if (session->currentMsg == NULL) {
        if (session->endReachedAtMs < 0.0) {
            session->endReachedAtMs = session->playbackTimeMs;
        }
        int activeVoices = fluid_synth_get_active_voice_count(session->synth);
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
        fluid_synth_all_sounds_off(session->synth, -1);
        if (session->ownsSynth) {
            delete_fluid_synth(session->synth);
            if (session->settings) {
                delete_fluid_settings(session->settings);
            }
        } else {
            pthread_mutex_lock(&g_cache_mutex);
            g_cache_in_use = 0;
            pthread_mutex_unlock(&g_cache_mutex);
        }
        session->synth = NULL;
        session->settings = NULL;
    }

    free(session);
}

JNIEXPORT void JNICALL
Java_it_parrocchia_coro_audio_midi_NativeMidiSynth_nativeClearSoundFontCache(
    JNIEnv* env,
    jobject thiz
) {
    pthread_mutex_lock(&g_cache_mutex);
    if (!g_cache_in_use) {
        free_cached_synth_locked();
        LOGI("Cache SoundFont FluidSynth svuotata");
    } else {
        /* Se una riproduzione e' in corso, forza il ricaricamento alla prossima sessione */
        g_cached_path[0] = '\0';
    }
    pthread_mutex_unlock(&g_cache_mutex);
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

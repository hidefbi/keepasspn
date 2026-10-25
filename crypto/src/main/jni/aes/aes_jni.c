/*
  This is a JNI wrapper for AES & SHA source code on Android.
  Copyright (C) 2010 Michael Mohr

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

#include <stdio.h>
#include <stdlib.h>
#include <inttypes.h>
#include <string.h>
#include <pthread.h>
#include <jni.h>

/* Tune as desired */
#undef KPD_PROFILE
//#define KPD_DEBUG

#if defined(KPD_PROFILE)
#include <time.h>
#endif

#if defined(KPD_DEBUG)
#include <android/log.h>
#endif

#include "aes.h"
#include "sha2.h"

static void secure_wipe_memory(void *v, size_t n) {
  if (v == NULL || n == 0) return;
  static void *(*const volatile memset_sec)(void *, int, size_t) = &memset;
  memset_sec(v, 0, n);
}

static jclass bad_arg;

JNIEXPORT jint JNICALL JNI_OnLoad( JavaVM *vm, void *reserved ) {
  JNIEnv *env;
  jclass cls;

  if((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6))
    return JNI_ERR;

  cls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
  if( cls == NULL )
    return JNI_ERR;
  bad_arg = (*env)->NewGlobalRef(env, cls);
  if( bad_arg == NULL )
    return JNI_ERR;

  aes_init();

  return JNI_VERSION_1_6;
}

// called on garbage collection
JNIEXPORT void JNICALL JNI_OnUnload( JavaVM *vm, void *reserved ) {
  JNIEnv *env;
  if((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6)) {
    return;
  }
  (*env)->DeleteGlobalRef(env, bad_arg);
}

#define MASTER_KEY_SIZE 32

typedef struct master_key {
  uint64_t rounds;
  uint32_t done[2];
  pthread_mutex_t lock1, lock2; // these lock the two halves of the key material
  uint8_t c_seed[MASTER_KEY_SIZE] __attribute__ ((aligned (16)));
  uint8_t key1[MASTER_KEY_SIZE] __attribute__ ((aligned (16)));
  uint8_t key2[MASTER_KEY_SIZE] __attribute__ ((aligned (16)));
} master_key;


uint32_t generate_key_material(void *arg) {
  #if defined(KPD_PROFILE)
  struct timespec start, end;
  #endif
  uint64_t i;
  uint32_t flip = 0;
  uint8_t *key1, *key2;
  master_key *mk = (master_key *)arg;
  aes_encrypt_ctx e_ctx[1] __attribute__ ((aligned (16)));

  if( mk->done[0] == 0 && pthread_mutex_trylock(&mk->lock1) == 0 ) {
    key1 = mk->key1;
    key2 = mk->key2;
  } else if( mk->done[1] == 0 && pthread_mutex_trylock(&mk->lock2) == 0 ) {
    key1 = mk->key1 + (MASTER_KEY_SIZE/2);
    key2 = mk->key2 + (MASTER_KEY_SIZE/2);
  } else {
    // this can only be scaled to two threads
    pthread_exit( (void *)(-1) );
  }

  #if defined(KPD_PROFILE)
  clock_gettime(CLOCK_THREAD_CPUTIME_ID, &start);
  #endif

  aes_encrypt_key256(mk->c_seed, e_ctx);
  for (i = 0; i < mk->rounds; i++) {
    if ( flip ) {
      aes_encrypt(key2, key1, e_ctx);
      flip = 0;
    } else {
      aes_encrypt(key1, key2, e_ctx);
      flip = 1;
    }
  }

  #if defined(KPD_PROFILE)
  clock_gettime(CLOCK_THREAD_CPUTIME_ID, &end);
  if( key1 == mk->key1 )
    __android_log_print(ANDROID_LOG_INFO, "aes_jni.c/nTransformMasterKey", "Thread 1 master key transformation took ~%d seconds", (int)(end.tv_sec-start.tv_sec));
  else
    __android_log_print(ANDROID_LOG_INFO, "aes_jni.c/nTransformMasterKey", "Thread 2 master key transformation took ~%d seconds", (int)(end.tv_sec-start.tv_sec));
  #endif

  if( key1 == mk->key1 ) {
    mk->done[0] = 1;
    pthread_mutex_unlock(&mk->lock1);
  } else {
    mk->done[1] = 1;
    pthread_mutex_unlock(&mk->lock2);
  }

  secure_wipe_memory(e_ctx, sizeof(e_ctx));

  return flip;
}

JNIEXPORT jbyteArray JNICALL Java_com_keepasspn_encrypt_aes_NativeAESKeyTransformer_nTransformKey(JNIEnv *env, jobject this, jbyteArray seed, jbyteArray key, jlong rounds) {
  master_key mk;
  uint32_t flip;
  pthread_t t1, t2;
  int iret;
  void *vret1, *vret2;
  jbyteArray result;
  sha256_ctx h_ctx[1] __attribute__ ((aligned (16)));

  // step 1: housekeeping - sanity checks and fetch data from the JVM
  if( (*env)->GetArrayLength(env, seed) != MASTER_KEY_SIZE ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: the seed is not the correct size");
    return NULL;
  }
  if( (*env)->GetArrayLength(env, key) != MASTER_KEY_SIZE ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: the key is not the correct size");
    return NULL;
  }
  if( rounds < 0 ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: illegal number of encryption rounds");
    return NULL;
  }
  mk.rounds = (uint64_t)rounds;
  mk.done[0] = mk.done[1] = 0;
  if( pthread_mutex_init(&mk.lock1, NULL) != 0 ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: failed to initialize the mutex for thread 1"); // FIXME: get a better exception class for this...
    return NULL;
  }
  if( pthread_mutex_init(&mk.lock2, NULL) != 0 ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: failed to initialize the mutex for thread 2"); // FIXME: get a better exception class for this...
    return NULL;
  }
  (*env)->GetByteArrayRegion(env, seed, 0, MASTER_KEY_SIZE, (jbyte *)mk.c_seed);
  (*env)->GetByteArrayRegion(env, key, 0, MASTER_KEY_SIZE, (jbyte *)mk.key1);

  // step 2: encrypt the hash "rounds"
  iret = pthread_create( &t1, NULL, (void*)(uintptr_t)generate_key_material, (void*)&mk );
  if( iret != 0 ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: failed to launch thread 1"); // FIXME: get a better exception class for this...
    return NULL;
  }
  iret = pthread_create( &t2, NULL, (void*)(uintptr_t)generate_key_material, (void*)&mk );
  if( iret != 0 ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: failed to launch thread 2"); // FIXME: get a better exception class for this...
    return NULL;
  }
  iret = pthread_join( t1, &vret1 );
  if( iret != 0 ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: failed to join thread 1"); // FIXME: get a better exception class for this...
    return NULL;
  }
  iret = pthread_join( t2, &vret2 );
  if( iret != 0 ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: failed to join thread 2"); // FIXME: get a better exception class for this...
    return NULL;
  }
  if( vret1 == (void *)(-1) || vret2 == (void *)(-1) || vret1 != vret2 ) {
    (*env)->ThrowNew(env, bad_arg, "TransformMasterKey: invalid flip value(s) from completed thread(s)"); // FIXME: get a better exception class for this...
    return NULL;
  } else {
    flip = (uint32_t)(uintptr_t)vret1;
  }

  // step 3: final SHA256 hash
  sha256_begin(h_ctx);
  if( flip ) {
    sha256_hash(mk.key2, MASTER_KEY_SIZE, h_ctx);
    sha256_end(mk.key1, h_ctx);
    flip = 0;
  } else {
    sha256_hash(mk.key1, MASTER_KEY_SIZE, h_ctx);
    sha256_end(mk.key2, h_ctx);
    flip = 1;
  }

  // step 4: send the hash into the JVM
  result = (*env)->NewByteArray(env, MASTER_KEY_SIZE);
  if( flip )
    (*env)->SetByteArrayRegion(env, result, 0, MASTER_KEY_SIZE, (jbyte *)mk.key2);
  else
    (*env)->SetByteArrayRegion(env, result, 0, MASTER_KEY_SIZE, (jbyte *)mk.key1);

  secure_wipe_memory(&mk, sizeof(mk));
  secure_wipe_memory(h_ctx, sizeof(h_ctx));

  return result;
}
#undef MASTER_KEY_SIZE

/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.drm;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.media.DeniedByServerException;
import android.media.MediaDrm;
import android.media.NotProvisionedException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.drm.ExoMediaDrm.KeyRequest;
import com.google.android.exoplayer2.drm.ExoMediaDrm.OnEventListener;
import com.google.android.exoplayer2.drm.ExoMediaDrm.ProvisionRequest;
import com.google.android.exoplayer2.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import java.util.HashMap;
import java.util.UUID;

/**
 * A {@link DrmSessionManager} that supports streaming playbacks using {@link MediaDrm}.
 */
@TargetApi(18)
public class StreamingDrmSessionManager<T extends ExoMediaCrypto> implements DrmSessionManager,
    DrmSession<T> {

  /**
   * Listener of {@link StreamingDrmSessionManager} events.
   */
  public interface EventListener {

    /**
     * Called each time keys are loaded.
     */
    void onDrmKeysLoaded();

    /**
     * Called when a drm error occurs.
     *
     * @param e The corresponding exception.
     */
    void onDrmSessionManagerError(Exception e);

  }

  /**
   * The key to use when passing CustomData to a PlayReady instance in an optional parameter map.
   */
  public static final String PLAYREADY_CUSTOM_DATA_KEY = "PRCustomData";

  private static final int MSG_PROVISION = 0;
  private static final int MSG_KEYS = 1;

  private final Handler eventHandler;
  private final EventListener eventListener;
  private final ExoMediaDrm<T> mediaDrm;
  private final HashMap<String, String> optionalKeyRequestParameters;

  /* package */ final MediaDrmCallback callback;
  /* package */ final UUID uuid;

  /* package */ MediaDrmHandler mediaDrmHandler;
  /* package */ PostResponseHandler postResponseHandler;

  private Looper playbackLooper;
  private HandlerThread requestHandlerThread;
  private Handler postRequestHandler;

  private int openCount;
  private boolean provisioningInProgress;
  private int state;
  private T mediaCrypto;
  private Exception lastException;
  private SchemeData schemeData;
  private byte[] sessionId;

  /**
   * Instantiates a new instance using the Widevine scheme.
   *
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
   */
  public static StreamingDrmSessionManager<FrameworkMediaCrypto> newWidevineInstance(
      MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters,
      Handler eventHandler, EventListener eventListener) throws UnsupportedDrmException {
    return newFrameworkInstance(C.WIDEVINE_UUID, callback, optionalKeyRequestParameters,
        eventHandler, eventListener);
  }

  /**
   * Instantiates a new instance using the PlayReady scheme.
   * <p>
   * Note that PlayReady is unsupported by most Android devices, with the exception of Android TV
   * devices, which do provide support.
   *
   * @param callback Performs key and provisioning requests.
   * @param customData Optional custom data to include in requests generated by the instance.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
   */
  public static StreamingDrmSessionManager<FrameworkMediaCrypto> newPlayReadyInstance(
      MediaDrmCallback callback, String customData, Handler eventHandler,
      EventListener eventListener) throws UnsupportedDrmException {
    HashMap<String, String> optionalKeyRequestParameters;
    if (!TextUtils.isEmpty(customData)) {
      optionalKeyRequestParameters = new HashMap<>();
      optionalKeyRequestParameters.put(PLAYREADY_CUSTOM_DATA_KEY, customData);
    } else {
      optionalKeyRequestParameters = null;
    }
    return newFrameworkInstance(C.PLAYREADY_UUID, callback, optionalKeyRequestParameters,
        eventHandler, eventListener);
  }

  /**
   * Instantiates a new instance.
   *
   * @param uuid The UUID of the drm scheme.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
   */
  public static StreamingDrmSessionManager<FrameworkMediaCrypto> newFrameworkInstance(
      UUID uuid, MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters,
      Handler eventHandler, EventListener eventListener) throws UnsupportedDrmException {
    return new StreamingDrmSessionManager<>(uuid, FrameworkMediaDrm.newInstance(uuid), callback,
        optionalKeyRequestParameters, eventHandler, eventListener);
  }

  /**
   * @param uuid The UUID of the drm scheme.
   * @param mediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   */
  public StreamingDrmSessionManager(UUID uuid, ExoMediaDrm<T> mediaDrm, MediaDrmCallback callback,
      HashMap<String, String> optionalKeyRequestParameters, Handler eventHandler,
      EventListener eventListener) {
    this.uuid = uuid;
    this.mediaDrm = mediaDrm;
    this.callback = callback;
    this.optionalKeyRequestParameters = optionalKeyRequestParameters;
    this.eventHandler = eventHandler;
    this.eventListener = eventListener;
    mediaDrm.setOnEventListener(new MediaDrmEventListener());
    state = STATE_CLOSED;
  }

  /**
   * Provides access to {@link MediaDrm#getPropertyString(String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The key to request.
   * @return The retrieved property.
   */
  public final String getPropertyString(String key) {
    return mediaDrm.getPropertyString(key);
  }

  /**
   * Provides access to {@link MediaDrm#setPropertyString(String, String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The property to write.
   * @param value The value to write.
   */
  public final void setPropertyString(String key, String value) {
    mediaDrm.setPropertyString(key, value);
  }

  /**
   * Provides access to {@link MediaDrm#getPropertyByteArray(String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The key to request.
   * @return The retrieved property.
   */
  public final byte[] getPropertyByteArray(String key) {
    return mediaDrm.getPropertyByteArray(key);
  }

  /**
   * Provides access to {@link MediaDrm#setPropertyByteArray(String, byte[])}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The property to write.
   * @param value The value to write.
   */
  public final void setPropertyByteArray(String key, byte[] value) {
    mediaDrm.setPropertyByteArray(key, value);
  }

  // DrmSessionManager implementation.

  @Override
  public DrmSession<T> acquireSession(Looper playbackLooper, DrmInitData drmInitData) {
    Assertions.checkState(this.playbackLooper == null || this.playbackLooper == playbackLooper);
    if (++openCount != 1) {
      return this;
    }

    if (this.playbackLooper == null) {
      this.playbackLooper = playbackLooper;
      mediaDrmHandler = new MediaDrmHandler(playbackLooper);
      postResponseHandler = new PostResponseHandler(playbackLooper);
    }

    requestHandlerThread = new HandlerThread("DrmRequestHandler");
    requestHandlerThread.start();
    postRequestHandler = new PostRequestHandler(requestHandlerThread.getLooper());

    schemeData = drmInitData.get(uuid);
    if (schemeData == null) {
      onError(new IllegalStateException("Media does not support uuid: " + uuid));
      return this;
    }
    if (Util.SDK_INT < 21) {
      // Prior to L the Widevine CDM required data to be extracted from the PSSH atom.
      byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(schemeData.data, C.WIDEVINE_UUID);
      if (psshData == null) {
        // Extraction failed. schemeData isn't a Widevine PSSH atom, so leave it unchanged.
      } else {
        schemeData = new SchemeData(C.WIDEVINE_UUID, schemeData.mimeType, psshData);
      }
    }
    state = STATE_OPENING;
    openInternal(true);
    return this;
  }

  @Override
  public void releaseSession(DrmSession session) {
    if (--openCount != 0) {
      return;
    }
    state = STATE_CLOSED;
    provisioningInProgress = false;
    mediaDrmHandler.removeCallbacksAndMessages(null);
    postResponseHandler.removeCallbacksAndMessages(null);
    postRequestHandler.removeCallbacksAndMessages(null);
    postRequestHandler = null;
    requestHandlerThread.quit();
    requestHandlerThread = null;
    schemeData = null;
    mediaCrypto = null;
    lastException = null;
    if (sessionId != null) {
      mediaDrm.closeSession(sessionId);
      sessionId = null;
    }
  }

  // DrmSession implementation.

  @Override
  public final int getState() {
    return state;
  }

  @Override
  public final T getMediaCrypto() {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      throw new IllegalStateException();
    }
    return mediaCrypto;
  }

  @Override
  public boolean requiresSecureDecoderComponent(String mimeType) {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      throw new IllegalStateException();
    }
    return mediaCrypto.requiresSecureDecoderComponent(mimeType);
  }

  @Override
  public final Exception getError() {
    return state == STATE_ERROR ? lastException : null;
  }

  // Internal methods.

  private void openInternal(boolean allowProvisioning) {
    try {
      sessionId = mediaDrm.openSession();
      mediaCrypto = mediaDrm.createMediaCrypto(uuid, sessionId);
      state = STATE_OPENED;
      postKeyRequest();
    } catch (NotProvisionedException e) {
      if (allowProvisioning) {
        postProvisionRequest();
      } else {
        onError(e);
      }
    } catch (Exception e) {
      onError(e);
    }
  }

  private void postProvisionRequest() {
    if (provisioningInProgress) {
      return;
    }
    provisioningInProgress = true;
    ProvisionRequest request = mediaDrm.getProvisionRequest();
    postRequestHandler.obtainMessage(MSG_PROVISION, request).sendToTarget();
  }

  private void onProvisionResponse(Object response) {
    provisioningInProgress = false;
    if (state != STATE_OPENING && state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      // This event is stale.
      return;
    }

    if (response instanceof Exception) {
      onError((Exception) response);
      return;
    }

    try {
      mediaDrm.provideProvisionResponse((byte[]) response);
      if (state == STATE_OPENING) {
        openInternal(false);
      } else {
        postKeyRequest();
      }
    } catch (DeniedByServerException e) {
      onError(e);
    }
  }

  private void postKeyRequest() {
    KeyRequest keyRequest;
    try {
      keyRequest = mediaDrm.getKeyRequest(sessionId, schemeData.data, schemeData.mimeType,
          MediaDrm.KEY_TYPE_STREAMING, optionalKeyRequestParameters);
      postRequestHandler.obtainMessage(MSG_KEYS, keyRequest).sendToTarget();
    } catch (NotProvisionedException e) {
      onKeysError(e);
    }
  }

  private void onKeyResponse(Object response) {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      // This event is stale.
      return;
    }

    if (response instanceof Exception) {
      onKeysError((Exception) response);
      return;
    }

    try {
      mediaDrm.provideKeyResponse(sessionId, (byte[]) response);
      state = STATE_OPENED_WITH_KEYS;
      if (eventHandler != null && eventListener != null) {
        eventHandler.post(new Runnable() {
          @Override
          public void run() {
            eventListener.onDrmKeysLoaded();
          }
        });
      }
    } catch (Exception e) {
      onKeysError(e);
    }
  }

  private void onKeysError(Exception e) {
    if (e instanceof NotProvisionedException) {
      postProvisionRequest();
    } else {
      onError(e);
    }
  }

  private void onError(final Exception e) {
    lastException = e;
    if (eventHandler != null && eventListener != null) {
      eventHandler.post(new Runnable() {
        @Override
        public void run() {
          eventListener.onDrmSessionManagerError(e);
        }
      });
    }
    if (state != STATE_OPENED_WITH_KEYS) {
      state = STATE_ERROR;
    }
  }

  @SuppressLint("HandlerLeak")
  private class MediaDrmHandler extends Handler {

    public MediaDrmHandler(Looper looper) {
      super(looper);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handleMessage(Message msg) {
      if (openCount == 0 || (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS)) {
        return;
      }
      switch (msg.what) {
        case MediaDrm.EVENT_KEY_REQUIRED:
          postKeyRequest();
          return;
        case MediaDrm.EVENT_KEY_EXPIRED:
          state = STATE_OPENED;
          onError(new KeysExpiredException());
          return;
        case MediaDrm.EVENT_PROVISION_REQUIRED:
          state = STATE_OPENED;
          postProvisionRequest();
          return;
      }
    }

  }

  private class MediaDrmEventListener implements OnEventListener<T> {

    @Override
    public void onEvent(ExoMediaDrm<? extends T> md, byte[] sessionId, int event, int extra,
        byte[] data) {
      mediaDrmHandler.sendEmptyMessage(event);
    }

  }

  @SuppressLint("HandlerLeak")
  private class PostResponseHandler extends Handler {

    public PostResponseHandler(Looper looper) {
      super(looper);
    }

    @Override
    public void handleMessage(Message msg) {
      switch (msg.what) {
        case MSG_PROVISION:
          onProvisionResponse(msg.obj);
          return;
        case MSG_KEYS:
          onKeyResponse(msg.obj);
          return;
      }
    }

  }

  @SuppressLint("HandlerLeak")
  private class PostRequestHandler extends Handler {

    public PostRequestHandler(Looper backgroundLooper) {
      super(backgroundLooper);
    }

    @Override
    public void handleMessage(Message msg) {
      Object response;
      try {
        switch (msg.what) {
          case MSG_PROVISION:
            response = callback.executeProvisionRequest(uuid, (ProvisionRequest) msg.obj);
            break;
          case MSG_KEYS:
            response = callback.executeKeyRequest(uuid, (KeyRequest) msg.obj);
            break;
          default:
            throw new RuntimeException();
        }
      } catch (Exception e) {
        response = e;
      }
      postResponseHandler.obtainMessage(msg.what, response).sendToTarget();
    }

  }

}

/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.phone;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.ContactsContract.Contacts;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;

import com.android.internal.telephony.ITelephonyService;
import com.android.internal.telephony.ITelephonyServiceCallBack;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.EventLog;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * We call this class TelephonyService since we think it should handle more than just calls
 * eventually. We think the framework should be initialized here instead of in PhoneGlobals (I.E
 * the call to makeDefaultPhone) and that also most other things in PhoneGlobals should be moved
 * here and PhoneGlobals should be removed.
 *
 * TODO: We are only handling single call use cases so far. One active and one on hold and
 *       conference call is not supported yet.
 *
 * TODO: Current API is still our initial design (with a onActive(), onHold()...) but we believe a
 *       better API design would be to just provide a call list for the clients and whenever
 *       anything changes in any call in the call list we send out a onCallListUpdated().
 *
 * TODO: This service has much similarities with CallManager in telephony framework. I might be a
 *       good idea to merge call manager into this service intead.
 *
 * TODO: BluetoothPhoneService used by Bluetooth headset should probably also be merged to this
 *       service. Bluetooths could use the new API's in this service instead.
 *
 * TODO: There are way to many logs but it's okay during development.
 */
public class TelephonyService extends Service implements
        CallerInfoAsyncQuery.OnQueryCompleteListener, CallTime.OnTickListener {

    private final static String LOG_TAG = "TelephonyService";

    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // Sticky intent broadcasted to notify the state of the service (idle, active or stopped).
    static final String ACTION_TELEPHONY_SERVICE_STATE_CHANGED =
            "com.android.telephony.TELEPHONY_SERVICE_STATE_CHANGED";
    static final String EXTRA_TELEPHONY_SERVICE_STATE = "TELEPHONY_SERVICE_STATE";

    // Event used to indicate a query timeout.
    private static final int RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT = 100;

    // Maximum time we allow the CallerInfo query to run,
    // before giving up and falling back to the default ringtone.
    private static final int RINGTONE_QUERY_WAIT_TIME = 500;  // msec

    // Message codes for mHandler.
    private static final int PHONE_PRECISE_CALL_STATE_CHANGED = 1;
    private static final int PHONE_NEW_RINGING_CONNECTION = 2;
    private static final int PHONE_DISCONNECT = 3;
    private static final int PHONE_UNKNOWN_CONNECTION_APPEARED = 4;
    private static final int PHONE_INCOMING_RING = 5;

    // TODO: Move this handling into another individual handler
    private static final int MSG_UPDATE_ELAPSED_TIME = 11;
    private static final int MSG_UPDATE_SOUND_ROUTE = 12;
    private static final int MSG_UPDATE_MUTE_STATE = 13;

    private static final int MSG_REQUEST_FORGROUND_CALLER_INFO = 20;
    private static final int MSG_REQUEST_BACKGROUND_CALLER_INFO = 21;
    private static final int MSG_NOTIFY_CALL_STATE = 22;

    /* State of TelephonyServcie */
    // The service is started but idle, I.E handles no calls.
    static final int TELEPHONY_SERVICE_STATE_IDLE = 0;
    // The service is handling calls.
    static final int TELEPHONY_SERVICE_STATE_ACTIVE = 1;
    // The service is stopped.
    static final int TELEPHONY_SERVICE_STATE_STOPPED = 2;

    private int mCurrentServiceState = TELEPHONY_SERVICE_STATE_IDLE;

    private static final int CAUSE_NO_CONNECTION = -1;

    /**
     * TODO: There are some similarities between CallManager and CallService and eventually they
     * could problably merge into one. I.E we could move CallManager from telephony framework here.
     */
    private CallManager mCallManager;

    // Plays and stops the ringsignal for incoming calls.
    private Ringer mRinger;

    /**
     * The call controller.
     *
     * TODO: Currently this is class handles only call setup (as it is in old Phone where it is
     * moved from) but the static call operations functions in PhoneUtils like answerCall and so on
     * should also be moved into CallController.
     */
    private CallController mCallController;

    /**
     * Helper class for contact lookup.
     */
    private CallerInfoQueryHelper mCallerInfoHelper;

    /**
     * Tracks call time for the active call. We track call time here and notifies the clients
     * whenever call time changes.
     */
    private CallTime mCallTime;

    /**
     * Handles call audio related things like setting the correct audio mode and turning speaker on
     * or off.
     */
    private CallAudioManager mCallAudioManager;

    /**
     * Manager for controlling the proximity sensor based on call state, connected accessories and
     * so on.
     */
    private ProximitySensorManager mProximitySensorManager;

    /**
     * This is a list of callbacks that have been registered with the
     * service.
     */
    final RemoteCallbackList<ITelephonyServiceCallBack> mCallbacks
            = new RemoteCallbackList<ITelephonyServiceCallBack>();

    // Object used to synchronize access to mCallerInfoQueryState
    private Object mCallerInfoQueryStateGuard = new Object();

    // The state of the CallerInfo Query.
    private int mCallerInfoQueryState;

    // Values used to track the query state
    private static final int CALLERINFO_QUERY_READY = 0;
    private static final int CALLERINFO_QUERYING = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        log("onCreate.");

        mCallManager = CallManager.getInstance();
        mRinger = Ringer.init(this);
        mCallController = CallController.init(PhoneGlobals.getInstance());
        mCallerInfoHelper = CallerInfoQueryHelper.init(this);
        mProximitySensorManager = new ProximitySensorManager(this);
        mCallTime = new CallTime(this);
        mCallAudioManager = new CallAudioManager(this);

        // Make sure the audio mode (along with some
        // audio-mode-related state of our own) is initialized
        // correctly, given the current state of the phone.
        mCallAudioManager.setAudioMode();

        // Start listening to call states.
        registerForPhoneStates();

        IntentFilter intentFilter =
              new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        intentFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        registerReceiver(new TelephonyBroadcastReceiver(), intentFilter);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        log("onStart: " + this);
    }

    @Override
    public void onDestroy() {
        log("onDestroy: " + this);

        // Unregister all callbacks.
        mCallbacks.kill();

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Proximity sensor manager needs to know if hard keyboard is open or not.
        mProximitySensorManager.setIsHardKeyboardOpen(
                newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO);

        super.onConfigurationChanged(newConfig);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Return the interface.
        log("onBind: " + intent);
        return mBinder;
    }

    /**
     *
     */
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case PHONE_PRECISE_CALL_STATE_CHANGED:
                    onPreciseCallStateChanged();
                    break;
                case PHONE_NEW_RINGING_CONNECTION:
                    onNewRingingConnection((AsyncResult) msg.obj);
                    break;
                case PHONE_DISCONNECT:
                    onDisconnect((AsyncResult) msg.obj);
                    break;
                case PHONE_INCOMING_RING:
                    onIncomingRing((AsyncResult) msg.obj);
                    break;
                case MSG_UPDATE_ELAPSED_TIME:
                    notifyElapsedTimeUpdate((String) msg.obj);
                    break;
                case MSG_UPDATE_SOUND_ROUTE:
                    notifySoundRouteUpdate(msg.arg1);
                    break;
                case MSG_UPDATE_MUTE_STATE:
                    notifyMuteStateUpdate();
                    break;
                case MSG_REQUEST_FORGROUND_CALLER_INFO:
                    if (msg.obj instanceof ITelephonyServiceCallBack) {
                        ITelephonyServiceCallBack cb = (ITelephonyServiceCallBack)msg.obj;
                        internalRequestForegroundCallerInfo(cb);
                    }
                    break;
                case MSG_REQUEST_BACKGROUND_CALLER_INFO:
                    if (msg.obj instanceof ITelephonyServiceCallBack) {
                        ITelephonyServiceCallBack cb = (ITelephonyServiceCallBack)msg.obj;
                        internalRequestBackgroundCallerInfo(cb);
                    }
                    break;
                case MSG_NOTIFY_CALL_STATE:
                    if (msg.obj instanceof ITelephonyServiceCallBack) {
                        ITelephonyServiceCallBack cb = (ITelephonyServiceCallBack)msg.obj;
                        notifyCallStateToRegister(cb);
                    }
                    break;
                case RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT:
                    onCustomRingtoneQueryTimeout((String) msg.obj);
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * Register with call manager to be notified of call states.
     *
     * TODO: Consider to move CallManager from telephony framework to here.
     */
    private void registerForPhoneStates() {
        mCallManager.registerForNewRingingConnection(mHandler, PHONE_NEW_RINGING_CONNECTION, null);
        mCallManager.registerForPreciseCallStateChanged(mHandler, PHONE_PRECISE_CALL_STATE_CHANGED, null);
        mCallManager.registerForDisconnect(mHandler, PHONE_DISCONNECT, null);
        mCallManager.registerForIncomingRing(mHandler, PHONE_INCOMING_RING, null);
    }

    /**
     * Called everytime there is a call state change.
     *
     * Watch out: certain state changes are actually handled by their own
     * specific methods:
     *   - see onNewRingingConnection() for new incoming calls
     *   - see onDisconnect() for calls being hung up or disconnected
     *
     * TODO: We only handle single call use cases here so far (Not one active
     * and one on hold or conference call).
     */
    private void onPreciseCallStateChanged() {
        // Get call state (Idle, off hook or ringing).
        PhoneConstants.State state = mCallManager.getState();

        log("onPreciseCallStateChange(): state: " + state + ".");
        PhoneGlobals.getInstance().updatePhoneState(state);
        mCallAudioManager.handlePhoneStateChange();
        // Update proximity sensor. It's safe to call this for every call state. It's only if we
        // send in a different value that something will happen.
        mProximitySensorManager.setIsInCall(state == PhoneConstants.State.OFFHOOK);

        Call ringingCall = mCallManager.getFirstActiveRingingCall();
        Call foregroundCall = mCallManager.getActiveFgCall();
        Call backgroundCall = mCallManager.getFirstActiveBgCall();

        log("- Ringing call state: " + ringingCall.getState());
        log("- Foreground call state: " + foregroundCall.getState());
        log("- Background call state: " + backgroundCall.getState());


        if ((ringingCall.getState() != Call.State.IDLE)
                && !foregroundCall.getState().isDialing()) {
            // A phone call is ringing, call waiting *or* being rejected
            // (ie. another call may also be active as well.)
            updateCallTime(ringingCall);
            if (ringingCall.getState() == Call.State.DISCONNECTING) {
                notifyDisconnecting(null);
            }
        } else if (foregroundCall.getState() != Call.State.IDLE) {
            // A phone call is active/connected.
            updateCallTime(foregroundCall);
            mCallerInfoHelper.startObtainCallerDescription(foregroundCall,
                    mFgCallerInfoQueryListener, null);
            if (foregroundCall.getState() == Call.State.ACTIVE) {
                notifyActiveCall(foregroundCall, !backgroundCall.isIdle(), null);
            } else if (foregroundCall.getState() == Call.State.DIALING ||
                    foregroundCall.getState() == Call.State.ALERTING) {
                notifyOutgoingCall(foregroundCall.getState() == Call.State.ALERTING, null);
            } else if (foregroundCall.getState() == Call.State.DISCONNECTING) {
                notifyDisconnecting(null);
            }
        } else if (backgroundCall.getState() != Call.State.IDLE) {
            // A phone call is on hold.
            updateCallTime(backgroundCall);
            mCallerInfoHelper.startObtainCallerDescription(backgroundCall,
                    mBgCallerInfoQueryListener, null);
            if (backgroundCall.getState() == Call.State.HOLDING) {
                notifyHoldCall(backgroundCall, null);
            } else if (backgroundCall.getState() == Call.State.DISCONNECTING) {
                notifyDisconnecting(null);
            }
        }

        if (state == PhoneConstants.State.OFFHOOK) {
            // make sure audio is in in-call mode now
            mCallAudioManager.setAudioMode();

            // Since we're now in-call, the Ringer should definitely *not*
            // be ringing any more.  (This is just a sanity-check; we
            // already stopped the ringer explicitly back in
            // PhoneUtils.answerCall(), before the call to phone.acceptCall().)
            // TODO: Confirm that this call really *is* unnecessary, and if so,
            // remove it!
            log("- Stopping ring signal.");
            mRinger.stopRing();
        }

        updateServiceState();
    }

    /**
     * Handles a new ringing connection event from the telephony layer. This means there is a
     * incoming call. As opposed to onIncomingRing below that is just to notify us that we should
     * repeat the ring signal.
     */
    private void onNewRingingConnection(AsyncResult r) {
        Connection c = (Connection) r.result;
        log("onNewRingingConnection(): state = " + mCallManager.getState() + ", conn = { " + c + " }");

        Call.State state = c.getState();

        // - don't ring for call waiting connections
        // - do this before showing the incoming call panel
        if (PhoneUtils.isRealIncomingCall(state)) {
            startIncomingCallQuery(c);
        } else {
            // in this case, just fall through like before, and call
            // notifyIncomingCall().
            log("- showing incoming call (this is a WAITING call)...");
            notifyIncommingCall(true, null);
        }

        mRinger.setSilentRingerFlag(false);
        log("- onNewRingingConnection() done.");
    }

    /**
     * Repeats the ringsignal when requested by RIL, if the user has not requested it to be silent.
     */
    private void onIncomingRing(AsyncResult r) {
        log("onIncomingRing()");

        if (r != null && (r.result != null)) {
            PhoneBase pb =  (PhoneBase)r.result;

            if ((pb.getState() == PhoneConstants.State.RINGING)
                    && (mRinger.getSilentRingerFlag() == false)) {
                log("- Repeating ring.");
                mRinger.ring();
            } else {
                log("- Ring before onNewRingingConnection, skipping");
            }
        }
    }

    /**
     * Called when a call is disconnected.
     */
    private void onDisconnect(AsyncResult r) {
        Connection c = (Connection) r.result;
        log("onDisconnect(): state = " + mCallManager.getState() + ", conn = { " + c + " }");

        int cause = CAUSE_NO_CONNECTION;
        if (c != null) {
            log("onDisconnect: cause = " + c.getDisconnectCause()
                  + ", incoming = " + c.isIncoming()
                  + ", date = " + c.getCreateTime());
            cause = c.getDisconnectCause().ordinal();
        } else {
            Log.w(LOG_TAG, "onDisconnect: null connection");
        }

        log("stopRing()... (onDisconnect)");
        mRinger.stopRing();

        //TODO this is just temp solution to update the phone state,
        // in the future we need to check some states to make sure we can clear it here,
        // if we need to show end call ui after call is ended, then the clear should be avoid.
        mCallManager.clearDisconnected();

        updateServiceState();
        if (mCallManager.getState() == PhoneConstants.State.IDLE) {
            resetAudioStateAfterDisconnect();
            notifyAllCallsDisconnected(null, cause);
        }
    }

    /**
     * This function is called from various places within this service. It checks the current state
     * of the service and sends out a sticky broadcast to let clients know the state. It broadcasts
     * a new intent if the state has changed.
     *
     * States can be:
     *
     * STOPPED .- The service is not started yet. TODO: Do we realy need this?
     * IDLE     - The service is started but there are no active calls.
     * ACTIVE   - The service is started and there are currently one or more active call in any
     *            state.
     *
     * The reson we have this is because currently the UI is implemnted to be triggered when the
     * service state change to active. This wakes up the in call UI and only after that the in call
     * UI will bind to this service. Performance is a concern with this design. It might be better
     * to also have the in call UI as a persistent process and always bound to this service.
     *
     * TODO: If we keep this and also add other functionality in this service then we need to
     *       update this to support more states. Or if we keep it only for calls it needs to be
     *       renamed to something else.
     */
    private void updateServiceState() {
        int serviceState = TELEPHONY_SERVICE_STATE_IDLE;

        Call ringingCall = mCallManager.getFirstActiveRingingCall();
        Call fgCall = mCallManager.getActiveFgCall();
        Call bgCall = mCallManager.getFirstActiveBgCall();

        if (!ringingCall.isIdle() || ringingCall.getState() == Call.State.DISCONNECTING
                || !fgCall.isIdle() || !bgCall.isIdle()) {
            serviceState = TELEPHONY_SERVICE_STATE_ACTIVE;
        }

        log("updateServiceState(), current state: " + mCurrentServiceState
                + " new state: " + serviceState);

        if (mCurrentServiceState != serviceState) {
            mCurrentServiceState = serviceState;
            Intent broadcastIntent = new Intent(ACTION_TELEPHONY_SERVICE_STATE_CHANGED);
            broadcastIntent.putExtra(EXTRA_TELEPHONY_SERVICE_STATE, mCurrentServiceState);

            // This is just temporary to easier demo separe UI's without having to uninstall and
            // install APK's.
            // TODO: Remove.
            SharedPreferences prefs = this.getSharedPreferences("selected_ui", Context.MODE_PRIVATE);
            int ui = prefs.getInt("ui", 0);
            broadcastIntent.putExtra("ui", ui);

            sendStickyBroadcast(broadcastIntent);
        }
    }

    /**
     * Helper method to manage the start of incoming call queries
     */
    private void startIncomingCallQuery(Connection c) {
        log("startIncomingCallQuery()");

        // TODO: cache the custom ringer object so that subsequent
        // calls will not need to do this query work.  We can keep
        // the MRU ringtones in memory.  We'll still need to hit
        // the database to get the callerinfo to act as a key,
        // but at least we can save the time required for the
        // Media player setup.  The only issue with this is that
        // we may need to keep an eye on the resources the Media
        // player uses to keep these ringtones around.

        // make sure we're in a state where we can be ready to
        // query a ringtone uri.
        boolean shouldStartQuery = false;
        synchronized (mCallerInfoQueryStateGuard) {
            if (mCallerInfoQueryState == CALLERINFO_QUERY_READY) {
                mCallerInfoQueryState = CALLERINFO_QUERYING;
                shouldStartQuery = true;
            }
        }
        if (shouldStartQuery) {
            // Reset the ringtone to the default first.
            mRinger.setCustomRingtoneUri(Settings.System.DEFAULT_RINGTONE_URI);

            // query the callerinfo to try to get the ringer.
            PhoneUtils.CallerInfoToken cit = PhoneUtils.startGetCallerInfo(
                    this, c, this, c);

            // if this has already been queried then just ring, otherwise
            // we wait for the alloted time before ringing.
            if (cit.isFinal) {
                log("- CallerInfo already up to date, using available data");
                onQueryComplete(0, c.getCall(), cit.currentInfo);
            } else {
                log("- Starting query, posting timeout message.");

                // Phone number (via getAddress()) is stored in the message to remember which
                // number is actually used for the look up.
                mHandler.sendMessageDelayed(
                        Message.obtain(mHandler, RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT,
                                c.getAddress()), RINGTONE_QUERY_WAIT_TIME);
            }
            // The call to notifyIncomingCall() will happen after the
            // queries are complete (or time out).
        } else {
            // This should never happen; its the case where an incoming call
            // arrives at the same time that the query is still being run,
            // and before the timeout window has closed.
            // EventLog.writeEvent(EventLogTags.PHONE_UI_MULTIPLE_QUERY);

            // In this case, just log the request and ring.
            log("- Starting ring signal (request to ring arrived while query is running)");
            mRinger.ring();

            // in this case, just fall through like before, and call
            // notifyIncomingCall().
            log("Notify incoming call (couldn't start query)...");
            notifyIncommingCall((c.getState() == Call.State.WAITING), null);
        }
    }

    /**
     * Implemented for CallerInfoAsyncQuery.OnQueryCompleteListener interface.
     * refreshes the CallCard data when it called.  If called with this
     * class itself, it is assumed that we have been waiting for the ringtone
     * and direct to voicemail settings to update.
     */
    @Override
    public void onQueryComplete(int token, Object cookie, CallerInfo ci) {
        log("CallerInfo query complete");
        if (cookie instanceof Connection) {
            log("-updating state for call..");

            if (mCallManager.getState() == PhoneConstants.State.RINGING) {
                // get rid of the timeout messages
                mHandler.removeMessages(RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT);

                boolean isQueryExecutionTimeOK = false;
                synchronized (mCallerInfoQueryStateGuard) {
                    if (mCallerInfoQueryState == CALLERINFO_QUERYING) {
                        mCallerInfoQueryState = CALLERINFO_QUERY_READY;
                        isQueryExecutionTimeOK = true;
                    }
                }
                //if we're in the right state
                if (isQueryExecutionTimeOK) {

                    // send directly to voicemail.
                    if (ci.shouldSendToVoicemail) {
                        log("Send to voicemail flag detected. Hanging up.");
                        PhoneUtils.hangupRingingCall(mCallManager.getFirstActiveRingingCall());
                        return;
                    }

                    // set the ringtone uri to prepare for the ring.
                    if (ci.contactRingtoneUri != null) {
                        log("Custom ringtone found, setting up ringer.");
                        mRinger.setCustomRingtoneUri(ci.contactRingtoneUri);
                    }
                    // ring, and other post-ring actions.
                    onCustomRingQueryComplete();
                }

                Connection conn = (Connection)cookie;
                PhoneUtils.CallerInfoToken cit =
                       PhoneUtils.startGetCallerInfo(this, conn, null, null);

                if (ci.contactExists) {
                    mCallerInfoHelper.getCallerDescription(ci, PhoneConstants.PRESENTATION_ALLOWED,
                            false, conn, mFgCallerInfoQueryListener, null);
                } else {
                    mCallerInfoHelper.getCallerDescription(cit.currentInfo,
                            PhoneConstants.PRESENTATION_ALLOWED, false, conn,
                            mFgCallerInfoQueryListener, null);
                }
            }
        }
    }

    /**
     * Called when asynchronous CallerInfo query is taking too long (more than
     * {@link #RINGTONE_QUERY_WAIT_TIME} msec), but we cannot wait any more.
     *
     * This looks up in-memory fallback cache and use it when available. If not, it just calls
     * {@link #onCustomRingQueryComplete()} with default ringtone ("Send to voicemail" flag will
     * be just ignored).
     *
     * @param number The phone number used for the async query. This method will take care of
     * formatting or normalization of the number.
     */
    private void onCustomRingtoneQueryTimeout(String number) {
        // First of all, this case itself should be rare enough, though we cannot avoid it in
        // some situations (e.g. IPC is slow due to system overload, database is in sync, etc.)
        Log.w(LOG_TAG, "CallerInfo query took too long; look up local fallback cache.");

        // This method is intentionally verbose for now to detect possible bad side-effect for it.
        // TODO: Remove the verbose log when it looks stable and reliable enough.

        final CallerInfoCache.CacheEntry entry =
                PhoneGlobals.getInstance().callerInfoCache.getCacheEntry(number);
        if (entry != null) {
            if (entry.sendToVoicemail) {
                log("send to voicemail flag detected (in fallback cache). hanging up.");
                PhoneUtils.hangupRingingCall(mCallManager.getFirstActiveRingingCall());
                return;
            }

            if (entry.customRingtone != null) {
                log("custom ringtone found (in fallback cache), setting up ringer: "
                        + entry.customRingtone);
                mRinger.setCustomRingtoneUri(Uri.parse(entry.customRingtone));
            }
        } else {
            // In this case we call onCustomRingQueryComplete(), just
            // like if the query had completed normally.  (But we're
            // going to get the default ringtone, since we never got
            // the chance to call Ringer.setCustomRingtoneUri()).
            log("Failed to find fallback cache. Use default ringer tone.");
        }

        onCustomRingQueryComplete();
    }

    /**
     * Performs the final steps of the onNewRingingConnection sequence:
     * starts the ringer, and sends out a notification that there is a
     * new incoming call.
     *
     * Normally, this is called when the CallerInfo query completes (see
     * onQueryComplete()).  In this case, onQueryComplete() has already
     * configured the Ringer object to use the custom ringtone (if there
     * is one) for this caller.  So we just tell the Ringer to start.
     *
     * But this method can *also* be called if the
     * RINGTONE_QUERY_WAIT_TIME timeout expires, which means that the
     * CallerInfo query is taking too long.  In that case, we log a
     * warning but otherwise we behave the same as in the normal case.
     * (We still tell the Ringer to start, but it's going to use the
     * default ringtone.)
     */
    private void onCustomRingQueryComplete() {
        log("onCustomRingQueryComplete()");

        boolean isQueryExecutionTimeExpired = false;
        synchronized (mCallerInfoQueryStateGuard) {
            if (mCallerInfoQueryState == CALLERINFO_QUERYING) {
                mCallerInfoQueryState = CALLERINFO_QUERY_READY;
                isQueryExecutionTimeExpired = true;
            }
        }
        if (isQueryExecutionTimeExpired) {
            // There may be a problem with the query here, since the
            // default ringtone is playing instead of the custom one.
            log("- CallerInfo query took too long; falling back to default ringtone");
            // EventLog.writeEvent(EventLogTags.PHONE_UI_RINGER_QUERY_ELAPSED);
        }

        // Make sure we still have an incoming call!
        //
        // (It's possible for the incoming call to have been disconnected
        // while we were running the query.  In that case we better not
        // start the ringer here, since there won't be any future
        // DISCONNECT event to stop it!)
        //
        // Note we don't have to worry about the incoming call going away
        // *after* this check but before we call mRinger.ring() below,
        // since in that case we *will* still get a DISCONNECT message sent
        // to our handler.  (And we will correctly stop the ringer when we
        // process that event.)
        if (mCallManager.getState() != PhoneConstants.State.RINGING) {
            Log.i(LOG_TAG, "- No incoming call! Bailing out...");
            // Don't start the ringer *or* send out any notify that there is
            // a incomign call. Just bail out.
            return;
        }

        // Ring, either with the queried ringtone or default one.
        log("RINGING... (onCustomRingQueryComplete)");
        mRinger.ring();

        // ...and display the incoming call to the user:
        log("- showing incoming call (custom ring query complete)...");
        notifyIncommingCall(false, null);
    }

    /**
     * Resets the audio mode and speaker state when a call ends.
     */
    private void resetAudioStateAfterDisconnect() {
        log("resetAudioStateAfterDisconnect()...");

        // call turnOnSpeaker() with state=false and store=true even if speaker
        // is already off to reset user requested speaker state.
        mCallAudioManager.turnOnSpeaker(false, true);

        mCallAudioManager.setAudioMode();
    }

    /**
     *
     */
    public void onMuteStateChanged() {
        log("onMuteStateChanged(): " + mCallAudioManager.getMute());
        mHandler.sendEmptyMessage(MSG_UPDATE_MUTE_STATE);
    }

    /**
     *
     */
    public void onSoundRouteChanged(int audioRoute) {
        log("onSoundRouteChanged(): " + audioRoute);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_SOUND_ROUTE, audioRoute, 0));
    }

    /**
     * Implement of CallTime.OnTickListener
     *
     * @param timeElapsed
     */
    public void onTickForCallTimeElapsed(long timeElapsed) {
        String elapsedTime = DateUtils.formatElapsedTime(timeElapsed);
        log("onTickForCallTimeElapsed(): " + elapsedTime);
        Message msg = mHandler.obtainMessage(MSG_UPDATE_ELAPSED_TIME, elapsedTime);
        mHandler.sendMessage(msg);
    }

    /**
     *
     */
    private void updateCallTime(Call call) {
        Call.State state = call.getState();
        log("updateCallTime(): call state: " + call.getState());

        if (state == Call.State.ACTIVE || state == Call.State.DISCONNECTING) {
            log("- Start call timer.");
            mCallTime.setActiveCallMode(call);
            mCallTime.reset();
            mCallTime.periodicUpdateTimer();
        } else {
            log("- Stop call timer.");
            mCallTime.cancelTimer();
        }
    }

    /**
     *
     */
    private final ITelephonyService.Stub mBinder = new ITelephonyService.Stub() {

        /**
         *
         */
        public void registerCallback(ITelephonyServiceCallBack cb) {
            log("Binder API: registerCallback()");
            if (cb != null) mCallbacks.register(cb);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_NOTIFY_CALL_STATE, cb));
        }

        /**
         *
         */
        public void unregisterCallback(ITelephonyServiceCallBack cb) {
            log("Binder API: unregisterCallback()");
            if (cb != null) mCallbacks.unregister(cb);
        }

        /**
         *
         */
        public void placeCall(Intent intent) {
            log("Binder API: placeCall()" + intent);
            mCallController.placeCall(intent);
            mProximitySensorManager.setIsInCall(true);
            mCallAudioManager.switchInCallAudio(CallAudioManager.InCallAudioMode.EARPIECE);
        }

        /**
         *
         */
        public void answerCall() {
            log("Binder API: answerCall()");
            //enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            mRinger.silenceRinger();
            mRinger.setSilentRingerFlag(false);
            PhoneUtils.answerCall(mCallManager.getFirstActiveRingingCall());
            mCallAudioManager.switchInCallAudio(CallAudioManager.InCallAudioMode.EARPIECE);
        }

        /**
         *
         */
        public void hangupCall() {
            log("Binder API: hangupCall()");
            //enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            if (mCallManager.hasActiveFgCall()) {
                PhoneUtils.hangupActiveCall(mCallManager.getActiveFgCall());
            } else if (mCallManager.hasActiveRingingCall()) {
                PhoneUtils.hangupRingingCall(mCallManager.getFirstActiveRingingCall());
            } else if (mCallManager.hasActiveBgCall()) {
                PhoneUtils.hangupHoldingCall(mCallManager.getFirstActiveBgCall());
            }
        }

        /**
         * TODO: Investigate if we really need a separate API for hanging up ringing call? Won't
         *       hangupCall work?
         */
        public void hangupRingingCall() {
            log("Binder API: hangupRingingCall()");
            if (mCallManager.hasActiveRingingCall()) {
                PhoneUtils.hangupRingingCall(mCallManager.getFirstActiveRingingCall());
            }
        }

        /**
         *
         */
        public boolean answerAndEndActive() {
            log("Binder API: answerAndEndActive()");
            if (!PhoneUtils.hangupActiveCall(mCallManager.getActiveFgCall())) {
                Log.w(LOG_TAG, "- End active call failed!");
                return false;
            }

            if (mCallManager.hasActiveRingingCall()) {
                log("- Answer ringing call");
                return PhoneUtils.answerCall(mCallManager.getFirstActiveRingingCall());
            }

            return true;
        }

        /**
         *
         */
        public void holdCall() {
            final boolean hasActiveCall = mCallManager.hasActiveFgCall();
            final boolean hasHoldingCall = mCallManager.hasActiveBgCall();
            log("Binder API: holdCall(): hasActiveCall = " + hasActiveCall
                    + ", hasHoldingCall = " + hasHoldingCall);

            if (hasHoldingCall || !hasActiveCall) {
                log("- There is a hold call or there is no active call, ignore the hold request");
                return;
            }

            // There's only one line in use, and that line is active.
            PhoneUtils.switchHoldingAndActive(
                    mCallManager.getFirstActiveBgCall());  // Really means "hold" in this state
        }

        /**
         *
         */
        public void retrieveCall() {
            final boolean hasActiveCall = mCallManager.hasActiveFgCall();
            final boolean hasHoldingCall = mCallManager.hasActiveBgCall();
            log("Binder API: retrieveCall(): hasActiveCall = " + hasActiveCall
                    + ", hasHoldingCall = " + hasHoldingCall);

            if (!hasHoldingCall || hasActiveCall) {
                log("- There is no hold call or there is an active call, ignore the unhold request");
                return;
            }

            // There's only one line in use, and that line is active.
            PhoneUtils.switchHoldingAndActive(
                    mCallManager.getFirstActiveBgCall());  // Really means "retrieve" in this state
        }

        /**
         *
         */
        public void switchCalls() {
            log("Binder API: switchCalls()");
            //TODO
        }

        /**
         * Merges calls to a conference.
         */
        public void mergeCalls() {
            log("Binder API: mergeCalls()");
        }

        /**
         * Separate one call from a conference.
         */
        public void separateCall(int connectionId) {
            log("Binder API: separateCall():  Connection ID = " + connectionId);
        }

        /**
         * Hangs up a part in conference.
         */
        public void hangupConnection(int connectionId) {
            log("Binder API: hangupConnection(): Connection ID = " + connectionId);
        }

        /**
         * Stops the current ring, and tells the notifier that future
         * ring requests should be ignored.
         */
        public void silenceRinger() {
            log("Binder API: silenceRinger()");
            mRinger.setSilentRingerFlag(true);
            mRinger.stopRing();
        }

        /**
         * Routes the sounds to earpiece (same as wired headset if connected), speaker or bluetooth
         * headset.
         */
        public void routeSound(int audioRoute) {
            log("Binder API: routeSound(): audioRoute = " + audioRoute);
            if (mCallAudioManager.getSoundRoute() == audioRoute) {
                log("- new state is the same as current, return");
                return;
            }
            switch(audioRoute) {
                case 0:
                    mCallAudioManager.switchInCallAudio(CallAudioManager.InCallAudioMode.SPEAKER);
                    break;
                case 1:
                    mCallAudioManager.switchInCallAudio(CallAudioManager.InCallAudioMode.BLUETOOTH);
                    break;
                case 2:
                    mCallAudioManager.switchInCallAudio(CallAudioManager.InCallAudioMode.EARPIECE);
                    break;
            }

            onSoundRouteChanged(audioRoute);
        }

        /**
         * TODO all those states should be bind to one object in furture.
         */
        public int getSoundRoute() {
            log("Binder API: getSoundRoute()");
            return mCallAudioManager.getSoundRoute();
        }

        /**
         * TODO: This is used for the proximity sensor. It needs to know if the UI is showing but
         * we need a better not UI related name for this.
         */
        public void callUIActived(boolean active) {
            log("Binder API: callUIActivated(): active = " + active);
            mProximitySensorManager.callUIActived(active);
        }

        /**
         *
         */
        public void muteMic() {
            log("Binder API: muteMic()");
            mCallAudioManager.switchMuteState();
            onMuteStateChanged();
        }

        /**
         * Plays the local tone based the phone type.
         */
        public void startDtmf(char c) {
            log("Binder API: startDtmf()");
            if (!okToDialDTMFTones()) return;
            mCallManager.startDtmf(c);
        }


        /**
         * Stops the local tone based on the phone type.
         */
        public void stopDtmf() {
            log("Binder API: stopDtmf()");
            mCallManager.stopDtmf();
        }

        /**
         *
         */
        public void requestForegroundCallerInfo(ITelephonyServiceCallBack cb) {
            log("Binder API: requestForegroundCallerInfo()");
            mHandler.sendMessage(mHandler.obtainMessage(MSG_REQUEST_FORGROUND_CALLER_INFO, cb));
        }

        /**
         *
         */
        public void requestBackgroundCallerInfo(ITelephonyServiceCallBack cb) {
            log("Binder API: requestBackgroundCallerInfo()");
            mHandler.sendMessage(mHandler.obtainMessage(MSG_REQUEST_BACKGROUND_CALLER_INFO, cb));
        }

        /**
         *
         */
        public void requestConferenceCallersInfo(ITelephonyServiceCallBack cb) {
            log("Binder API: requestConferenceCallersInfo()");
        }
    };


    /**
     * Determines if we can dial DTMF tones.
     */
    private boolean okToDialDTMFTones() {
        final boolean hasRingingCall = mCallManager.hasActiveRingingCall();
        final Call.State fgCallState = mCallManager.getActiveFgCallState();

        // We're allowed to send DTMF tones when there's an ACTIVE
        // foreground call, and not when an incoming call is ringing
        // (since DTMF tones are useless in that state), or if the
        // Manage Conference UI is visible (since the tab interferes
        // with the "Back to call" button.)

        // We can also dial while in ALERTING state because there are
        // some connections that never update to an ACTIVE state (no
        // indication from the network).
        boolean canDial = (fgCallState == Call.State.ACTIVE
                || fgCallState == Call.State.ALERTING) && !hasRingingCall;
        // TODO: Add the blow checks back again.
        // && (mApp.inCallUiState.inCallScreenMode !=
        // InCallScreenMode.MANAGE_CONFERENCE);

        if (VDBG)
            log("okToDialDTMFTones(): foreground state: " + fgCallState + ", ringing state: "
                    + hasRingingCall + ", result: " + canDial);

        return canDial;
    }

    /**
     *
     */
    private void internalRequestForegroundCallerInfo(ITelephonyServiceCallBack cb) {
        Call ringingCall = mCallManager.getFirstActiveRingingCall();
        Call foregroundCall = mCallManager.getActiveFgCall();
        log("===requestForegroudCallerInfo===");
        log("--ring call state: " + ringingCall.getState());
        log("--foreground call state: " + foregroundCall.getState());
        log("================================");
        if ((ringingCall.getState() != Call.State.IDLE)
                && !foregroundCall.getState().isDialing()) {
            // A phone call is ringing, call waiting *or* being rejected
            // (ie. another call may also be active as well.)
            mCallerInfoHelper.startObtainCallerDescription(ringingCall,
                    mFgCallerInfoQueryListener, cb);
        } else if (foregroundCall.getState() != Call.State.IDLE) {
            mCallerInfoHelper.startObtainCallerDescription(foregroundCall,
                    mFgCallerInfoQueryListener, cb);
        } else {
            log("No foreground call");
        }
    }

    /**
     *
     */
    private void internalRequestBackgroundCallerInfo(ITelephonyServiceCallBack cb) {
        Call backgroundCall = mCallManager.getFirstActiveBgCall();
        log("===requestBackgroundCallerInfo===");
        log("--background call state: " + backgroundCall.getState());
        log("=================================");
        if (backgroundCall.getState() != Call.State.IDLE) {
            mCallerInfoHelper.startObtainCallerDescription(backgroundCall,
                    mBgCallerInfoQueryListener, cb);
            updateCallTime(backgroundCall);
        } else {
            log("No background call");
        }
    }

    private CallerInfoQueryListener mFgCallerInfoQueryListener = new CallerInfoQueryListener(true);
    private CallerInfoQueryListener mBgCallerInfoQueryListener = new CallerInfoQueryListener(false);

    /**
     *
     */
    private class CallerInfoQueryListener
        implements CallerInfoQueryHelper.OnCallerInfoQueryListener {

        private boolean mIsForegroundCall;

        public CallerInfoQueryListener(boolean isForegroundCall) {
            mIsForegroundCall = isForegroundCall;
        }

        public void onCallerInfoQueryComplete(String name, String number, String typeofnumber,
                Drawable photo, int presentation, ITelephonyServiceCallBack cb) {
            log("onCallerInfoQueryComplete");
            Bitmap bitmap = null;
            if (photo != null) {
                bitmap = (Bitmap)((BitmapDrawable)photo).getBitmap();
            }
            notifyCallerInfoUpdate(mIsForegroundCall, name, number,
                    typeofnumber, bitmap, presentation, cb);
        }

    }

    /**
    *
    */
   private void notifyCallStateToRegister(ITelephonyServiceCallBack cb) {
       Call ringingCall = mCallManager.getFirstActiveRingingCall();
       Call foregroundCall = mCallManager.getActiveFgCall();
       Call backgroundCall = mCallManager.getFirstActiveBgCall();

       if ((ringingCall.getState() != Call.State.IDLE)
               && !foregroundCall.getState().isDialing()) {
           notifyIncommingCall(mCallManager.hasActiveFgCall(), cb);
           if (ringingCall.getState() == Call.State.DISCONNECTING) {
               notifyDisconnecting(cb);
           }
       } else if (foregroundCall.getState() != Call.State.IDLE) {
           if (foregroundCall.getState() == Call.State.ACTIVE) {
               notifyActiveCall(foregroundCall, !backgroundCall.isIdle(), cb);
           } else if (foregroundCall.getState() == Call.State.DIALING ||
                   foregroundCall.getState() == Call.State.ALERTING) {
               notifyOutgoingCall(foregroundCall.getState() == Call.State.ALERTING, cb);
           } else if (foregroundCall.getState() == Call.State.DISCONNECTING) {
               notifyDisconnecting(cb);
           }
       } else if (backgroundCall.getState() != Call.State.IDLE) {
           if (backgroundCall.getState() == Call.State.ACTIVE) {
               notifyHoldCall(backgroundCall, cb);
           } else if (backgroundCall.getState() == Call.State.DISCONNECTING) {
               notifyDisconnecting(cb);
           }
       } else if (mCallManager.getState() == PhoneConstants.State.IDLE) {
           Call disconnectedCall = null;
           if (ringingCall.getState() == Call.State.DISCONNECTED) {
               disconnectedCall = ringingCall;
           } else if (foregroundCall.getState() == Call.State.DISCONNECTED) {
               disconnectedCall = foregroundCall;
           } else if (backgroundCall.getState() == Call.State.DISCONNECTED) {
               disconnectedCall = backgroundCall;
           }
           if (disconnectedCall != null) {
               Connection c = disconnectedCall.getEarliestConnection();
               int cause = (c == null) ? CAUSE_NO_CONNECTION : c.getDisconnectCause().ordinal();
               notifyAllCallsDisconnected(cb, cause);
           }
       }
   }

    /**
     *
     */
    private void notifyIncommingCall(boolean isWaiting, ITelephonyServiceCallBack cb) {
        log("notifyIncommingCall: " + isWaiting);
        if (cb != null) {
            try {
                cb.onIncomming(isWaiting);
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        } else {
            // Broadcast to all clients the new value.
            final int count = mCallbacks.beginBroadcast();
            for (int i=0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onIncomming(isWaiting);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    /**
     *
     */
    private void notifyOutgoingCall(boolean isAlerting, ITelephonyServiceCallBack cb) {
        log("notifyOutgoingCall: " + isAlerting);
        if (cb != null) {
            try {
                cb.onOutgoing(isAlerting);
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        } else {
            // Broadcast to all clients the new value.
            final int count = mCallbacks.beginBroadcast();
            for (int i=0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onOutgoing(isAlerting);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    private void notifyActiveCall(Call call, boolean hasBackgroundCall,
            ITelephonyServiceCallBack cb) {
        log("notifyActiveCall");

        CallerInfo ci = PhoneUtils.getCallerInfo(this, PhoneUtils.getConnectionFromCall(call));

        boolean isEmergency = ci.isEmergencyNumber();
        boolean isVoiceMail = ci.isVoiceMailNumber();
        boolean isConference = PhoneUtils.isConferenceCall(call);

        log("-isEmergency: " + isEmergency);
        log("-isVoiceMail: " + isVoiceMail);
        log("-isConference: " + isConference);
        log("-callback: " + cb);
        if (cb != null) {
            log("notifyActiveCall, specified call back");
            try {
                cb.onActive(hasBackgroundCall, isEmergency,
                        isVoiceMail, isConference);
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        } else {
            // Broadcast to all clients the new value.
            final int count = mCallbacks.beginBroadcast();
            for (int i=0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onActive(hasBackgroundCall, isEmergency,
                            isVoiceMail, isConference);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    private void notifyHoldCall(Call call, ITelephonyServiceCallBack cb) {
        log("notifyHoldCall");

        CallerInfo ci = PhoneUtils.getCallerInfo(this, PhoneUtils.getConnectionFromCall(call));

        boolean isVoiceMail = ci.isVoiceMailNumber();
        boolean isConference = PhoneUtils.isConferenceCall(call);

        log("-isVoiceMail: " + isVoiceMail);
        log("-isConference: " + isConference);
        log("-callback: " + cb);
        if (cb != null) {
            log("notifyHoldCall, specified call back");
            try {
                cb.onHold(isVoiceMail, isConference);
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        } else {
            // Broadcast to all clients the new value.
            final int count = mCallbacks.beginBroadcast();
            for (int i=0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onHold(isVoiceMail, isConference);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    /**
     * Notify that the call is being disconnected (but not yet disconnected). This is called
     * right after the user has requested to end the call but we are still waiting for a
     * confirmation from the modem that it is disconnected. When the call is really disconnected
     * then onAllCallsDisconnected will be called.
     *
     * All this only happens if it there are no other calls ongoing.
     */
    private void notifyDisconnecting(ITelephonyServiceCallBack cb) {
        log("notifyDisconnecting");
        if (cb != null) {
            log("notifyDisconnecting, specified call back");
            try {
                cb.onDisconnecting();
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        } else {
            // Broadcast to all clients the new value.
            final int count = mCallbacks.beginBroadcast();
            for (int i=0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onDisconnecting();
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    /**
     * Notify that there are no more calls.
     */
    private void notifyAllCallsDisconnected(ITelephonyServiceCallBack cb, int cause) {
        log("notifyAllCallsDisconnected, cause: " + cause);
        if (cb != null) {
            log("notifyAllCallsDisconnected, specified call back");
            try {
                cb.onAllCallsDisconnected(cause);
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        } else {
            // Broadcast to all clients the new value.
            final int count = mCallbacks.beginBroadcast();
            for (int i=0; i < count; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onAllCallsDisconnected(cause);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    /**
     *
     */
    private void notifyCallerInfoUpdate(boolean isForegroundCall, String name, String number,
            String typeofnumber, Bitmap photo, int presentation, ITelephonyServiceCallBack cb) {
        log("notifyCallerInfoUpdate: \n-isForegroundCall" + isForegroundCall
                + "\n-name: " + name + "\n-number: " + number
                + "\n-typeofnumber:" + typeofnumber + "\n-photo:" + photo
                + "\n-presentation:" + presentation + "\n-callback:" + cb);
        if (cb != null) {
            try {
                if (isForegroundCall) {
                    cb.onForegroundCallerInfoUpdated(name, number,
                            typeofnumber, photo, presentation);
                } else {
                    cb.onBackgroundCallerInfoUpdated(name, number,
                            typeofnumber, photo, presentation);
                }
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        } else {
            // Broadcast to all clients the new value.
            final int count = mCallbacks.beginBroadcast();
            for (int i=0; i < count; i++) {
                try {
                    if (isForegroundCall) {
                        mCallbacks.getBroadcastItem(i).onForegroundCallerInfoUpdated(name, number,
                                typeofnumber, photo, presentation);
                    } else {
                        mCallbacks.getBroadcastItem(i).onBackgroundCallerInfoUpdated(name, number,
                                typeofnumber, photo, presentation);
                    }
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    /**
     * Notify current call time. When in active call this will be called every second.
     *
     * TODO: Investigate if there is any performance issue with this.
     */
    private void notifyElapsedTimeUpdate(String elapsedTime) {
        log("notifyElapsedTimeUpdate");
        // Broadcast to all clients the new value.
        final int count = mCallbacks.beginBroadcast();
        for (int i=0; i < count; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onElapsedTimeUpdated(elapsedTime);
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        mCallbacks.finishBroadcast();
    }

    private void notifySoundRouteUpdate(int audioRoute) {
        log("notifySoundRouteUpdate");
        // Broadcast to all clients the new value.
        final int count = mCallbacks.beginBroadcast();
        for (int i=0; i < count; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onSoundRouted(audioRoute);
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        mCallbacks.finishBroadcast();
    }

    private void notifyMuteStateUpdate() {
        log("notifyMuteStateUpdate");
        // Broadcast to all clients the new value.
        final int count = mCallbacks.beginBroadcast();
        for (int i=0; i < count; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onMicMuteStateChange(mCallAudioManager.getMute());
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        mCallbacks.finishBroadcast();
    }

    /**
     *
     */
    class TelephonyBroadcastReceiver extends BroadcastReceiver {
        private static final String TAG = "TelephonyBroadcastReceiver";
        private static final boolean DEBUG = true;
        private static final boolean VDEBUG = true;

        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO Auto-generated method stub
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                // TODO: Add a setIsHeadsetConnected(boolean) function in ProximitySensorManager.
                //mProximitySensorManager.updateProximitySensorMode();
            } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {

            }
        }
    }

    private void log(String msg) {
        if (DBG)Log.d(LOG_TAG, msg);
    }

}

/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.internal.telephony.cdma.SignalToneUtil;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaDisplayInfoRec;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.CallManager;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.CallLog.Calls;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;


/**
 * Phone app module that listens for phone state changes and various other
 * events from the telephony layer, and triggers any resulting UI behavior
 * (like starting the Ringer and Incoming Call UI, playing in-call tones,
 * updating notifications, writing call log entries, etc.)
 */
public class CallNotifier extends Handler
        implements CallerInfoAsyncQuery.OnQueryCompleteListener {
    private static final String LOG_TAG = "CallNotifier";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneApp.DBG_LEVEL >= 2);

    // Maximum time we allow the CallerInfo query to run,
    // before giving up and falling back to the default ringtone.
    private static final int RINGTONE_QUERY_WAIT_TIME = 500;  // msec

    // Timers related to CDMA Call Waiting
    // 1) For displaying Caller Info
    // 2) For disabling "Add Call" menu option once User selects Ignore or CW Timeout occures
    private static final int CALLWAITING_CALLERINFO_DISPLAY_TIME = 20000; // msec
    private static final int CALLWAITING_ADDCALL_DISABLE_TIME = 30000; // msec

    // Time to display the  DisplayInfo Record sent by CDMA network
    private static final int DISPLAYINFO_NOTIFICATION_TIME = 2000; // msec

    /** The singleton instance. */
    private static CallNotifier sInstance;

    // Boolean to keep track of whether or not a CDMA Call Waiting call timed out.
    //
    // This is CDMA-specific, because with CDMA we *don't* get explicit
    // notification from the telephony layer that a call-waiting call has
    // stopped ringing.  Instead, when a call-waiting call first comes in we
    // start a 20-second timer (see CALLWAITING_CALLERINFO_DISPLAY_DONE), and
    // if the timer expires we clean up the call and treat it as a missed call.
    //
    // If this field is true, that means that the current Call Waiting call
    // "timed out" and should be logged in Call Log as a missed call.  If it's
    // false when we reach onCdmaCallWaitingReject(), we can assume the user
    // explicitly rejected this call-waiting call.
    //
    // This field is reset to false any time a call-waiting call first comes
    // in, and after cleaning up a missed call-waiting call.  It's only ever
    // set to true when the CALLWAITING_CALLERINFO_DISPLAY_DONE timer fires.
    //
    // TODO: do we really need a member variable for this?  Don't we always
    // know at the moment we call onCdmaCallWaitingReject() whether this is an
    // explicit rejection or not?
    // (Specifically: when we call onCdmaCallWaitingReject() from
    // PhoneUtils.hangupRingingCall() that means the user deliberately rejected
    // the call, and if we call onCdmaCallWaitingReject() because of a
    // CALLWAITING_CALLERINFO_DISPLAY_DONE event that means that it timed
    // out...)
    private boolean mCallWaitingTimeOut = false;

    // values used to track the query state
    private static final int CALLERINFO_QUERY_READY = 0;
    private static final int CALLERINFO_QUERYING = -1;

    // the state of the CallerInfo Query.
    private int mCallerInfoQueryState;

    // object used to synchronize access to mCallerInfoQueryState
    private Object mCallerInfoQueryStateGuard = new Object();

    // Event used to indicate a query timeout.
    private static final int RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT = 100;

    // Events from the Phone object:
    private static final int PHONE_STATE_CHANGED = 1;
    private static final int PHONE_NEW_RINGING_CONNECTION = 2;
    private static final int PHONE_DISCONNECT = 3;
    private static final int PHONE_UNKNOWN_CONNECTION_APPEARED = 4;
    private static final int PHONE_INCOMING_RING = 5;
    private static final int PHONE_STATE_DISPLAYINFO = 6;
    private static final int PHONE_STATE_SIGNALINFO = 7;
    private static final int PHONE_CDMA_CALL_WAITING = 8;
    private static final int PHONE_ENHANCED_VP_ON = 9;
    private static final int PHONE_ENHANCED_VP_OFF = 10;
    private static final int PHONE_RINGBACK_TONE = 11;
    private static final int PHONE_RESEND_MUTE = 12;

    // Events generated internally:
    private static final int PHONE_MWI_CHANGED = 21;
    private static final int PHONE_BATTERY_LOW = 22;
    private static final int CALLWAITING_CALLERINFO_DISPLAY_DONE = 23;
    private static final int CALLWAITING_ADDCALL_DISABLE_TIMEOUT = 24;
    private static final int DISPLAYINFO_NOTIFICATION_DONE = 25;
    private static final int EVENT_OTA_PROVISION_CHANGE = 26;
    private static final int CDMA_CALL_WAITING_REJECT = 27;
    private static final int UPDATE_IN_CALL_NOTIFICATION = 28;

    // Emergency call related defines:
    private static final int EMERGENCY_TONE_OFF = 0;
    private static final int EMERGENCY_TONE_ALERT = 1;
    private static final int EMERGENCY_TONE_VIBRATE = 2;

    private PhoneApp mApplication;
    private CallManager mCM;
    private Ringer mRinger;
    private BluetoothHandsfree mBluetoothHandsfree;
    private CallLogAsync mCallLog;
    private boolean mSilentRingerRequested;

    // ToneGenerator instance for playing SignalInfo tones
    private ToneGenerator mSignalInfoToneGenerator;

    // The tone volume relative to other sounds in the stream SignalInfo
    private static final int TONE_RELATIVE_VOLUME_SIGNALINFO = 80;

    private Call.State mPreviousCdmaCallState;
    private boolean mVoicePrivacyState = false;
    private boolean mIsCdmaRedialCall = false;

    // Emergency call tone and vibrate:
    private int mIsEmergencyToneOn;
    private int mCurrentEmergencyToneState = EMERGENCY_TONE_OFF;
    private EmergencyTonePlayerVibrator mEmergencyTonePlayerVibrator;

    // Ringback tone player
    private InCallTonePlayer mInCallRingbackTonePlayer;

    // Call waiting tone player
    private InCallTonePlayer mCallWaitingTonePlayer;

    // Cached AudioManager
    private AudioManager mAudioManager;

    /**
     * Initialize the singleton CallNotifier instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static CallNotifier init(PhoneApp app, Phone phone, Ringer ringer,
                                           BluetoothHandsfree btMgr, CallLogAsync callLog) {
        synchronized (CallNotifier.class) {
            if (sInstance == null) {
                sInstance = new CallNotifier(app, phone, ringer, btMgr, callLog);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private CallNotifier(PhoneApp app, Phone phone, Ringer ringer,
                         BluetoothHandsfree btMgr, CallLogAsync callLog) {
        mApplication = app;
        mCM = app.mCM;
        mCallLog = callLog;

        mAudioManager = (AudioManager) mApplication.getSystemService(Context.AUDIO_SERVICE);

        registerForNotifications();

        // Instantiate the ToneGenerator for SignalInfo and CallWaiting
        // TODO: We probably don't need the mSignalInfoToneGenerator instance
        // around forever. Need to change it so as to create a ToneGenerator instance only
        // when a tone is being played and releases it after its done playing.
        try {
            mSignalInfoToneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL,
                    TONE_RELATIVE_VOLUME_SIGNALINFO);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "CallNotifier: Exception caught while creating " +
                    "mSignalInfoToneGenerator: " + e);
            mSignalInfoToneGenerator = null;
        }

        mRinger = ringer;
        mBluetoothHandsfree = btMgr;

        TelephonyManager telephonyManager = (TelephonyManager)app.getSystemService(
                Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR
                | PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case PHONE_NEW_RINGING_CONNECTION:
                log("RINGING... (new)");
                onNewRingingConnection((AsyncResult) msg.obj);
                mSilentRingerRequested = false;
                break;

            case PHONE_INCOMING_RING:
                // repeat the ring when requested by the RIL, and when the user has NOT
                // specifically requested silence.
                if (msg.obj != null && ((AsyncResult) msg.obj).result != null) {
                    PhoneBase pb =  (PhoneBase)((AsyncResult)msg.obj).result;

                    if ((pb.getState() == Phone.State.RINGING)
                            && (mSilentRingerRequested == false)) {
                        if (DBG) log("RINGING... (PHONE_INCOMING_RING event)");
                        mRinger.ring();
                    } else {
                        if (DBG) log("RING before NEW_RING, skipping");
                    }
                }
                break;

            case PHONE_STATE_CHANGED:
                onPhoneStateChanged((AsyncResult) msg.obj);
                break;

            case PHONE_DISCONNECT:
                if (DBG) log("DISCONNECT");
                onDisconnect((AsyncResult) msg.obj);
                break;

            case PHONE_UNKNOWN_CONNECTION_APPEARED:
                onUnknownConnectionAppeared((AsyncResult) msg.obj);
                break;

            case RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT:
                // CallerInfo query is taking too long!  But we can't wait
                // any more, so start ringing NOW even if it means we won't
                // use the correct custom ringtone.
                Log.w(LOG_TAG, "CallerInfo query took too long; manually starting ringer");

                // In this case we call onCustomRingQueryComplete(), just
                // like if the query had completed normally.  (But we're
                // going to get the default ringtone, since we never got
                // the chance to call Ringer.setCustomRingtoneUri()).
                onCustomRingQueryComplete();
                break;

            case PHONE_MWI_CHANGED:
                onMwiChanged(mApplication.phone.getMessageWaitingIndicator());
                break;

            case PHONE_BATTERY_LOW:
                onBatteryLow();
                break;

            case PHONE_CDMA_CALL_WAITING:
                if (DBG) log("Received PHONE_CDMA_CALL_WAITING event");
                onCdmaCallWaiting((AsyncResult) msg.obj);
                break;

            case CDMA_CALL_WAITING_REJECT:
                Log.i(LOG_TAG, "Received CDMA_CALL_WAITING_REJECT event");
                onCdmaCallWaitingReject();
                break;

            case CALLWAITING_CALLERINFO_DISPLAY_DONE:
                Log.i(LOG_TAG, "Received CALLWAITING_CALLERINFO_DISPLAY_DONE event");
                mCallWaitingTimeOut = true;
                onCdmaCallWaitingReject();
                break;

            case CALLWAITING_ADDCALL_DISABLE_TIMEOUT:
                if (DBG) log("Received CALLWAITING_ADDCALL_DISABLE_TIMEOUT event ...");
                // Set the mAddCallMenuStateAfterCW state to true
                mApplication.cdmaPhoneCallState.setAddCallMenuStateAfterCallWaiting(true);
                mApplication.updateInCallScreen();
                break;

            case PHONE_STATE_DISPLAYINFO:
                if (DBG) log("Received PHONE_STATE_DISPLAYINFO event");
                onDisplayInfo((AsyncResult) msg.obj);
                break;

            case PHONE_STATE_SIGNALINFO:
                if (DBG) log("Received PHONE_STATE_SIGNALINFO event");
                onSignalInfo((AsyncResult) msg.obj);
                break;

            case DISPLAYINFO_NOTIFICATION_DONE:
                if (DBG) log("Received Display Info notification done event ...");
                CdmaDisplayInfo.dismissDisplayInfoRecord();
                break;

            case EVENT_OTA_PROVISION_CHANGE:
                if (DBG) log("EVENT_OTA_PROVISION_CHANGE...");
                mApplication.handleOtaspEvent(msg);
                break;

            case PHONE_ENHANCED_VP_ON:
                if (DBG) log("PHONE_ENHANCED_VP_ON...");
                if (!mVoicePrivacyState) {
                    int toneToPlay = InCallTonePlayer.TONE_VOICE_PRIVACY;
                    new InCallTonePlayer(toneToPlay).start();
                    mVoicePrivacyState = true;
                    // Update the VP icon:
                    if (DBG) log("- updating notification for VP state...");
                    mApplication.notificationMgr.updateInCallNotification();
                }
                break;

            case PHONE_ENHANCED_VP_OFF:
                if (DBG) log("PHONE_ENHANCED_VP_OFF...");
                if (mVoicePrivacyState) {
                    int toneToPlay = InCallTonePlayer.TONE_VOICE_PRIVACY;
                    new InCallTonePlayer(toneToPlay).start();
                    mVoicePrivacyState = false;
                    // Update the VP icon:
                    if (DBG) log("- updating notification for VP state...");
                    mApplication.notificationMgr.updateInCallNotification();
                }
                break;

            case PHONE_RINGBACK_TONE:
                onRingbackTone((AsyncResult) msg.obj);
                break;

            case PHONE_RESEND_MUTE:
                onResendMute();
                break;

            case UPDATE_IN_CALL_NOTIFICATION:
                mApplication.notificationMgr.updateInCallNotification();
                break;

            default:
                // super.handleMessage(msg);
        }
    }

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onMessageWaitingIndicatorChanged(boolean mwi) {
            onMwiChanged(mwi);
        }

        @Override
        public void onCallForwardingIndicatorChanged(boolean cfi) {
            onCfiChanged(cfi);
        }
    };

    /**
     * Handles a "new ringing connection" event from the telephony layer.
     */
    private void onNewRingingConnection(AsyncResult r) {
        Connection c = (Connection) r.result;
        log("onNewRingingConnection(): state = " + mCM.getState() + ", conn = { " + c + " }");
        Call ringing = c.getCall();
        Phone phone = ringing.getPhone();

        // Check for a few cases where we totally ignore incoming calls.
        if (ignoreAllIncomingCalls(phone)) {
            // Immediately reject the call, without even indicating to the user
            // that an incoming call occurred.  (This will generally send the
            // caller straight to voicemail, just as if we *had* shown the
            // incoming-call UI and the user had declined the call.)
            PhoneUtils.hangupRingingCall(ringing);
            return;
        }

        if (c == null) {
            Log.w(LOG_TAG, "CallNotifier.onNewRingingConnection(): null connection!");
            // Should never happen, but if it does just bail out and do nothing.
            return;
        }

        if (!c.isRinging()) {
            Log.i(LOG_TAG, "CallNotifier.onNewRingingConnection(): connection not ringing!");
            // This is a very strange case: an incoming call that stopped
            // ringing almost instantly after the onNewRingingConnection()
            // event.  There's nothing we can do here, so just bail out
            // without doing anything.  (But presumably we'll log it in
            // the call log when the disconnect event comes in...)
            return;
        }

        // Stop any signalInfo tone being played on receiving a Call
        stopSignalInfoTone();

        Call.State state = c.getState();
        // State will be either INCOMING or WAITING.
        if (VDBG) log("- connection is ringing!  state = " + state);
        // if (DBG) PhoneUtils.dumpCallState(mPhone);

        // No need to do any service state checks here (like for
        // "emergency mode"), since in those states the SIM won't let
        // us get incoming connections in the first place.

        // TODO: Consider sending out a serialized broadcast Intent here
        // (maybe "ACTION_NEW_INCOMING_CALL"), *before* starting the
        // ringer and going to the in-call UI.  The intent should contain
        // the caller-id info for the current connection, and say whether
        // it would be a "call waiting" call or a regular ringing call.
        // If anybody consumed the broadcast, we'd bail out without
        // ringing or bringing up the in-call UI.
        //
        // This would give 3rd party apps a chance to listen for (and
        // intercept) new ringing connections.  An app could reject the
        // incoming call by consuming the broadcast and doing nothing, or
        // it could "pick up" the call (without any action by the user!)
        // via some future TelephonyManager API.
        //
        // See bug 1312336 for more details.
        // We'd need to protect this with a new "intercept incoming calls"
        // system permission.

        // Obtain a partial wake lock to make sure the CPU doesn't go to
        // sleep before we finish bringing up the InCallScreen.
        // (This will be upgraded soon to a full wake lock; see
        // showIncomingCall().)
        if (VDBG) log("Holding wake lock on new incoming connection.");
        mApplication.requestWakeState(PhoneApp.WakeState.PARTIAL);

        // - don't ring for call waiting connections
        // - do this before showing the incoming call panel
        if (PhoneUtils.isRealIncomingCall(state)) {
            startIncomingCallQuery(c);
        } else {
            if (VDBG) log("- starting call waiting tone...");
            if (mCallWaitingTonePlayer == null) {
                mCallWaitingTonePlayer = new InCallTonePlayer(InCallTonePlayer.TONE_CALL_WAITING);
                mCallWaitingTonePlayer.start();
            }
            // in this case, just fall through like before, and call
            // showIncomingCall().
            if (DBG) log("- showing incoming call (this is a WAITING call)...");
            showIncomingCall();
        }

        // Note we *don't* post a status bar notification here, since
        // we're not necessarily ready to actually show the incoming call
        // to the user.  (For calls in the INCOMING state, at least, we
        // still need to run a caller-id query, and we may not even ring
        // at all if the "send directly to voicemail" flag is set.)
        //
        // Instead, we update the notification (and potentially launch the
        // InCallScreen) from the showIncomingCall() method, which runs
        // when the caller-id query completes or times out.

        if (VDBG) log("- onNewRingingConnection() done.");
    }

    /**
     * Determines whether or not we're allowed to present incoming calls to the
     * user, based on the capabilities and/or current state of the device.
     *
     * If this method returns true, that means we should immediately reject the
     * current incoming call, without even indicating to the user that an
     * incoming call occurred.
     *
     * (We only reject incoming calls in a few cases, like during an OTASP call
     * when we can't interrupt the user, or if the device hasn't completed the
     * SetupWizard yet.  We also don't allow incoming calls on non-voice-capable
     * devices.  But note that we *always* allow incoming calls while in ECM.)
     *
     * @return true if we're *not* allowed to present an incoming call to
     * the user.
     */
    private boolean ignoreAllIncomingCalls(Phone phone) {
        // Incoming calls are totally ignored on non-voice-capable devices.
        if (!PhoneApp.sVoiceCapable) {
            // ...but still log a warning, since we shouldn't have gotten this
            // event in the first place!  (Incoming calls *should* be blocked at
            // the telephony layer on non-voice-capable capable devices.)
            Log.w(LOG_TAG, "Got onNewRingingConnection() on non-voice-capable device! Ignoring...");
            return true;
        }

        // In ECM (emergency callback mode), we ALWAYS allow incoming calls
        // to get through to the user.  (Note that ECM is applicable only to
        // voice-capable CDMA devices).
        if (PhoneUtils.isPhoneInEcm(phone)) {
            if (DBG) log("Incoming call while in ECM: always allow...");
            return false;
        }

        // Incoming calls are totally ignored if the device isn't provisioned yet.
        boolean provisioned = Settings.Secure.getInt(mApplication.getContentResolver(),
            Settings.Secure.DEVICE_PROVISIONED, 0) != 0;
        if (!provisioned) {
            Log.i(LOG_TAG, "Ignoring incoming call: not provisioned");
            return true;
        }

        // Incoming calls are totally ignored if an OTASP call is active.
        if (TelephonyCapabilities.supportsOtasp(phone)) {
            boolean activateState = (mApplication.cdmaOtaScreenState.otaScreenState
                    == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION);
            boolean dialogState = (mApplication.cdmaOtaScreenState.otaScreenState
                    == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_SUCCESS_FAILURE_DLG);
            boolean spcState = mApplication.cdmaOtaProvisionData.inOtaSpcState;

            if (spcState) {
                Log.i(LOG_TAG, "Ignoring incoming call: OTA call is active");
                return true;
            } else if (activateState || dialogState) {
                // We *are* allowed to receive incoming calls at this point.
                // But clear out any residual OTASP UI first.
                // TODO: It's an MVC violation to twiddle the OTA UI state here;
                // we should instead provide a higher-level API via OtaUtils.
                if (dialogState) mApplication.dismissOtaDialogs();
                mApplication.clearOtaState();
                mApplication.clearInCallScreenMode();
                return false;
            }
        }

        // Normal case: allow this call to be presented to the user.
        return false;
    }

    /**
     * Helper method to manage the start of incoming call queries
     */
    private void startIncomingCallQuery(Connection c) {
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
            // create a custom ringer using the default ringer first
            mRinger.setCustomRingtoneUri(Settings.System.DEFAULT_RINGTONE_URI);

            // query the callerinfo to try to get the ringer.
            PhoneUtils.CallerInfoToken cit = PhoneUtils.startGetCallerInfo(
                    mApplication, c, this, this);

            // if this has already been queried then just ring, otherwise
            // we wait for the alloted time before ringing.
            if (cit.isFinal) {
                if (VDBG) log("- CallerInfo already up to date, using available data");
                onQueryComplete(0, this, cit.currentInfo);
            } else {
                if (VDBG) log("- Starting query, posting timeout message.");
                sendEmptyMessageDelayed(RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT,
                        RINGTONE_QUERY_WAIT_TIME);
            }
            // The call to showIncomingCall() will happen after the
            // queries are complete (or time out).
        } else {
            // This should never happen; its the case where an incoming call
            // arrives at the same time that the query is still being run,
            // and before the timeout window has closed.
            EventLog.writeEvent(EventLogTags.PHONE_UI_MULTIPLE_QUERY);

            // In this case, just log the request and ring.
            if (VDBG) log("RINGING... (request to ring arrived while query is running)");
            mRinger.ring();

            // in this case, just fall through like before, and call
            // showIncomingCall().
            if (DBG) log("- showing incoming call (couldn't start query)...");
            showIncomingCall();
        }
    }

    /**
     * Performs the final steps of the onNewRingingConnection sequence:
     * starts the ringer, and brings up the "incoming call" UI.
     *
     * Normally, this is called when the CallerInfo query completes (see
     * onQueryComplete()).  In this case, onQueryComplete() has already
     * configured the Ringer object to use the custom ringtone (if there
     * is one) for this caller.  So we just tell the Ringer to start, and
     * proceed to the InCallScreen.
     *
     * But this method can *also* be called if the
     * RINGTONE_QUERY_WAIT_TIME timeout expires, which means that the
     * CallerInfo query is taking too long.  In that case, we log a
     * warning but otherwise we behave the same as in the normal case.
     * (We still tell the Ringer to start, but it's going to use the
     * default ringtone.)
     */
    private void onCustomRingQueryComplete() {
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
            Log.w(LOG_TAG, "CallerInfo query took too long; falling back to default ringtone");
            EventLog.writeEvent(EventLogTags.PHONE_UI_RINGER_QUERY_ELAPSED);
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
        if (mCM.getState() != Phone.State.RINGING) {
            Log.i(LOG_TAG, "onCustomRingQueryComplete: No incoming call! Bailing out...");
            // Don't start the ringer *or* bring up the "incoming call" UI.
            // Just bail out.
            return;
        }

        // Ring, either with the queried ringtone or default one.
        if (VDBG) log("RINGING... (onCustomRingQueryComplete)");
        mRinger.ring();

        // ...and display the incoming call to the user:
        if (DBG) log("- showing incoming call (custom ring query complete)...");
        showIncomingCall();
    }

    private void onUnknownConnectionAppeared(AsyncResult r) {
        Phone.State state = mCM.getState();

        if (state == Phone.State.OFFHOOK) {
            // basically do onPhoneStateChanged + display the incoming call UI
            onPhoneStateChanged(r);
            if (DBG) log("- showing incoming call (unknown connection appeared)...");
            showIncomingCall();
        }
    }

    /**
     * Informs the user about a new incoming call.
     *
     * In most cases this means "bring up the full-screen incoming call
     * UI".  However, if an immersive activity is running, the system
     * NotificationManager will instead pop up a small notification window
     * on top of the activity.
     *
     * Watch out: be sure to call this method only once per incoming call,
     * or otherwise we may end up launching the InCallScreen multiple
     * times (which can lead to slow responsiveness and/or visible
     * glitches.)
     *
     * Note this method handles only the onscreen UI for incoming calls;
     * the ringer and/or vibrator are started separately (see the various
     * calls to Ringer.ring() in this class.)
     *
     * @see NotificationMgr.updateNotificationAndLaunchIncomingCallUi()
     */
    private void showIncomingCall() {
        log("showIncomingCall()...  phone state = " + mCM.getState());

        // Before bringing up the "incoming call" UI, force any system
        // dialogs (like "recent tasks" or the power dialog) to close first.
        try {
            ActivityManagerNative.getDefault().closeSystemDialogs("call");
        } catch (RemoteException e) {
        }

        // Go directly to the in-call screen.
        // (No need to do anything special if we're already on the in-call
        // screen; it'll notice the phone state change and update itself.)

        // But first, grab a full wake lock.  We do this here, before we
        // even fire off the InCallScreen intent, to make sure the
        // ActivityManager doesn't try to pause the InCallScreen as soon
        // as it comes up.  (See bug 1648751.)
        //
        // And since the InCallScreen isn't visible yet (we haven't even
        // fired off the intent yet), we DON'T want the screen to actually
        // come on right now.  So *before* acquiring the wake lock we need
        // to call preventScreenOn(), which tells the PowerManager that
        // the screen should stay off even if someone's holding a full
        // wake lock.  (This prevents any flicker during the "incoming
        // call" sequence.  The corresponding preventScreenOn(false) call
        // will come from the InCallScreen when it's finally ready to be
        // displayed.)
        //
        // TODO: this is all a temporary workaround.  The real fix is to add
        // an Activity attribute saying "this Activity wants to wake up the
        // phone when it's displayed"; that way the ActivityManager could
        // manage the wake locks *and* arrange for the screen to come on at
        // the exact moment that the InCallScreen is ready to be displayed.
        // (See bug 1648751.)
        //
        // TODO: also, we should probably *not* do any of this if the
        // screen is already on(!)

        mApplication.preventScreenOn(true);
        mApplication.requestWakeState(PhoneApp.WakeState.FULL);

        // Post the "incoming call" notification *and* include the
        // fullScreenIntent that'll launch the incoming-call UI.
        // (This will usually take us straight to the incoming call
        // screen, but if an immersive activity is running it'll just
        // appear as a notification.)
        if (DBG) log("- updating notification from showIncomingCall()...");
        mApplication.notificationMgr.updateNotificationAndLaunchIncomingCallUi();
    }

    /**
     * Updates the phone UI in response to phone state changes.
     *
     * Watch out: certain state changes are actually handled by their own
     * specific methods:
     *   - see onNewRingingConnection() for new incoming calls
     *   - see onDisconnect() for calls being hung up or disconnected
     */
    private void onPhoneStateChanged(AsyncResult r) {
        Phone.State state = mCM.getState();
        if (VDBG) log("onPhoneStateChanged: state = " + state);

        // Turn status bar notifications on or off depending upon the state
        // of the phone.  Notification Alerts (audible or vibrating) should
        // be on if and only if the phone is IDLE.
        mApplication.notificationMgr.statusBarHelper
                .enableNotificationAlerts(state == Phone.State.IDLE);

        Phone fgPhone = mCM.getFgPhone();
        if (fgPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            if ((fgPhone.getForegroundCall().getState() == Call.State.ACTIVE)
                    && ((mPreviousCdmaCallState == Call.State.DIALING)
                    ||  (mPreviousCdmaCallState == Call.State.ALERTING))) {
                if (mIsCdmaRedialCall) {
                    int toneToPlay = InCallTonePlayer.TONE_REDIAL;
                    new InCallTonePlayer(toneToPlay).start();
                }
                // Stop any signal info tone when call moves to ACTIVE state
                stopSignalInfoTone();
            }
            mPreviousCdmaCallState = fgPhone.getForegroundCall().getState();
        }

        // Have the PhoneApp recompute its mShowBluetoothIndication
        // flag based on the (new) telephony state.
        // There's no need to force a UI update since we update the
        // in-call notification ourselves (below), and the InCallScreen
        // listens for phone state changes itself.
        mApplication.updateBluetoothIndication(false);

        // Update the proximity sensor mode (on devices that have a
        // proximity sensor).
        mApplication.updatePhoneState(state);

        if (state == Phone.State.OFFHOOK) {
            // stop call waiting tone if needed when answering
            if (mCallWaitingTonePlayer != null) {
                mCallWaitingTonePlayer.stopTone();
                mCallWaitingTonePlayer = null;
            }

            if (VDBG) log("onPhoneStateChanged: OFF HOOK");
            // make sure audio is in in-call mode now
            PhoneUtils.setAudioMode(mCM);

            // if the call screen is showing, let it handle the event,
            // otherwise handle it here.
            if (!mApplication.isShowingCallScreen()) {
                mApplication.setScreenTimeout(PhoneApp.ScreenTimeoutDuration.DEFAULT);
                mApplication.requestWakeState(PhoneApp.WakeState.SLEEP);
            }

            // Since we're now in-call, the Ringer should definitely *not*
            // be ringing any more.  (This is just a sanity-check; we
            // already stopped the ringer explicitly back in
            // PhoneUtils.answerCall(), before the call to phone.acceptCall().)
            // TODO: Confirm that this call really *is* unnecessary, and if so,
            // remove it!
            if (DBG) log("stopRing()... (OFFHOOK state)");
            mRinger.stopRing();

            // Post a request to update the "in-call" status bar icon.
            //
            // We don't call NotificationMgr.updateInCallNotification()
            // directly here, for two reasons:
            // (1) a single phone state change might actually trigger multiple
            //   onPhoneStateChanged() callbacks, so this prevents redundant
            //   updates of the notification.
            // (2) we suppress the status bar icon while the in-call UI is
            //   visible (see updateInCallNotification()).  But when launching
            //   an outgoing call the phone actually goes OFFHOOK slightly
            //   *before* the InCallScreen comes up, so the delay here avoids a
            //   brief flicker of the icon at that point.

            if (DBG) log("- posting UPDATE_IN_CALL_NOTIFICATION request...");
            // Remove any previous requests in the queue
            removeMessages(UPDATE_IN_CALL_NOTIFICATION);
            final int IN_CALL_NOTIFICATION_UPDATE_DELAY = 1000;  // msec
            sendEmptyMessageDelayed(UPDATE_IN_CALL_NOTIFICATION,
                                    IN_CALL_NOTIFICATION_UPDATE_DELAY);
        }

        if (fgPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            Connection c = fgPhone.getForegroundCall().getLatestConnection();
            if ((c != null) && (PhoneNumberUtils.isLocalEmergencyNumber(c.getAddress(),
                                                                        mApplication))) {
                if (VDBG) log("onPhoneStateChanged: it is an emergency call.");
                Call.State callState = fgPhone.getForegroundCall().getState();
                if (mEmergencyTonePlayerVibrator == null) {
                    mEmergencyTonePlayerVibrator = new EmergencyTonePlayerVibrator();
                }

                if (callState == Call.State.DIALING || callState == Call.State.ALERTING) {
                    mIsEmergencyToneOn = Settings.System.getInt(
                            mApplication.getContentResolver(),
                            Settings.System.EMERGENCY_TONE, EMERGENCY_TONE_OFF);
                    if (mIsEmergencyToneOn != EMERGENCY_TONE_OFF &&
                        mCurrentEmergencyToneState == EMERGENCY_TONE_OFF) {
                        if (mEmergencyTonePlayerVibrator != null) {
                            mEmergencyTonePlayerVibrator.start();
                        }
                    }
                } else if (callState == Call.State.ACTIVE) {
                    if (mCurrentEmergencyToneState != EMERGENCY_TONE_OFF) {
                        if (mEmergencyTonePlayerVibrator != null) {
                            mEmergencyTonePlayerVibrator.stop();
                        }
                    }
                }
            }
        }

        if ((fgPhone.getPhoneType() == Phone.PHONE_TYPE_GSM)
                || (fgPhone.getPhoneType() == Phone.PHONE_TYPE_SIP)) {
            Call.State callState = mCM.getActiveFgCallState();
            if (!callState.isDialing()) {
                // If call get activated or disconnected before the ringback
                // tone stops, we have to stop it to prevent disturbing.
                if (mInCallRingbackTonePlayer != null) {
                    mInCallRingbackTonePlayer.stopTone();
                    mInCallRingbackTonePlayer = null;
                }
            }
        }
    }

    void updateCallNotifierRegistrationsAfterRadioTechnologyChange() {
        if (DBG) Log.d(LOG_TAG, "updateCallNotifierRegistrationsAfterRadioTechnologyChange...");
        // Unregister all events from the old obsolete phone
        mCM.unregisterForNewRingingConnection(this);
        mCM.unregisterForPreciseCallStateChanged(this);
        mCM.unregisterForDisconnect(this);
        mCM.unregisterForUnknownConnection(this);
        mCM.unregisterForIncomingRing(this);
        mCM.unregisterForCallWaiting(this);
        mCM.unregisterForDisplayInfo(this);
        mCM.unregisterForSignalInfo(this);
        mCM.unregisterForCdmaOtaStatusChange(this);
        mCM.unregisterForRingbackTone(this);
        mCM.unregisterForResendIncallMute(this);

        // Release the ToneGenerator used for playing SignalInfo and CallWaiting
        if (mSignalInfoToneGenerator != null) {
            mSignalInfoToneGenerator.release();
        }

        // Clear ringback tone player
        mInCallRingbackTonePlayer = null;

        // Clear call waiting tone player
        mCallWaitingTonePlayer = null;

        mCM.unregisterForInCallVoicePrivacyOn(this);
        mCM.unregisterForInCallVoicePrivacyOff(this);

        // Register all events new to the new active phone
        registerForNotifications();
    }

    private void registerForNotifications() {
        mCM.registerForNewRingingConnection(this, PHONE_NEW_RINGING_CONNECTION, null);
        mCM.registerForPreciseCallStateChanged(this, PHONE_STATE_CHANGED, null);
        mCM.registerForDisconnect(this, PHONE_DISCONNECT, null);
        mCM.registerForUnknownConnection(this, PHONE_UNKNOWN_CONNECTION_APPEARED, null);
        mCM.registerForIncomingRing(this, PHONE_INCOMING_RING, null);
        mCM.registerForCdmaOtaStatusChange(this, EVENT_OTA_PROVISION_CHANGE, null);
        mCM.registerForCallWaiting(this, PHONE_CDMA_CALL_WAITING, null);
        mCM.registerForDisplayInfo(this, PHONE_STATE_DISPLAYINFO, null);
        mCM.registerForSignalInfo(this, PHONE_STATE_SIGNALINFO, null);
        mCM.registerForInCallVoicePrivacyOn(this, PHONE_ENHANCED_VP_ON, null);
        mCM.registerForInCallVoicePrivacyOff(this, PHONE_ENHANCED_VP_OFF, null);
        mCM.registerForRingbackTone(this, PHONE_RINGBACK_TONE, null);
        mCM.registerForResendIncallMute(this, PHONE_RESEND_MUTE, null);
    }

    /**
     * Implemented for CallerInfoAsyncQuery.OnQueryCompleteListener interface.
     * refreshes the CallCard data when it called.  If called with this
     * class itself, it is assumed that we have been waiting for the ringtone
     * and direct to voicemail settings to update.
     */
    public void onQueryComplete(int token, Object cookie, CallerInfo ci) {
        if (cookie instanceof Long) {
            if (VDBG) log("CallerInfo query complete, posting missed call notification");

            mApplication.notificationMgr.notifyMissedCall(ci.name, ci.phoneNumber,
                    ci.phoneLabel, ((Long) cookie).longValue());
        } else if (cookie instanceof CallNotifier) {
            if (VDBG) log("CallerInfo query complete (for CallNotifier), "
                          + "updating state for incoming call..");

            // get rid of the timeout messages
            removeMessages(RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT);

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
                    if (DBG) log("send to voicemail flag detected. hanging up.");
                    PhoneUtils.hangupRingingCall(mCM.getFirstActiveRingingCall());
                    return;
                }

                // set the ringtone uri to prepare for the ring.
                if (ci.contactRingtoneUri != null) {
                    if (DBG) log("custom ringtone found, setting up ringer.");
                    Ringer r = ((CallNotifier) cookie).mRinger;
                    r.setCustomRingtoneUri(ci.contactRingtoneUri);
                }
                // ring, and other post-ring actions.
                onCustomRingQueryComplete();
            }
        }
    }

    private void onDisconnect(AsyncResult r) {
        if (VDBG) log("onDisconnect()...  CallManager state: " + mCM.getState());

        mVoicePrivacyState = false;
        Connection c = (Connection) r.result;
        if (c != null) {
            log("onDisconnect: cause = " + c.getDisconnectCause()
                  + ", incoming = " + c.isIncoming()
                  + ", date = " + c.getCreateTime());
        } else {
            Log.w(LOG_TAG, "onDisconnect: null connection");
        }

        int autoretrySetting = 0;
        if ((c != null) && (c.getCall().getPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA)) {
            autoretrySetting = android.provider.Settings.System.getInt(mApplication.
                    getContentResolver(),android.provider.Settings.System.CALL_AUTO_RETRY, 0);
        }

        // Stop any signalInfo tone being played when a call gets ended
        stopSignalInfoTone();

        if ((c != null) && (c.getCall().getPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA)) {
            // Resetting the CdmaPhoneCallState members
            mApplication.cdmaPhoneCallState.resetCdmaPhoneCallState();

            // Remove Call waiting timers
            removeMessages(CALLWAITING_CALLERINFO_DISPLAY_DONE);
            removeMessages(CALLWAITING_ADDCALL_DISABLE_TIMEOUT);
        }

        // Stop the ringer if it was ringing (for an incoming call that
        // either disconnected by itself, or was rejected by the user.)
        //
        // TODO: We technically *shouldn't* stop the ringer if the
        // foreground or background call disconnects while an incoming call
        // is still ringing, but that's a really rare corner case.
        // It's safest to just unconditionally stop the ringer here.

        // CDMA: For Call collision cases i.e. when the user makes an out going call
        // and at the same time receives an Incoming Call, the Incoming Call is given
        // higher preference. At this time framework sends a disconnect for the Out going
        // call connection hence we should *not* be stopping the ringer being played for
        // the Incoming Call
        Call ringingCall = mCM.getFirstActiveRingingCall();
        if (ringingCall.getPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            if (PhoneUtils.isRealIncomingCall(ringingCall.getState())) {
                // Also we need to take off the "In Call" icon from the Notification
                // area as the Out going Call never got connected
                if (DBG) log("cancelCallInProgressNotifications()... (onDisconnect)");
                mApplication.notificationMgr.cancelCallInProgressNotifications();
            } else {
                if (DBG) log("stopRing()... (onDisconnect)");
                mRinger.stopRing();
            }
        } else { // GSM
            if (DBG) log("stopRing()... (onDisconnect)");
            mRinger.stopRing();
        }

        // stop call waiting tone if needed when disconnecting
        if (mCallWaitingTonePlayer != null) {
            mCallWaitingTonePlayer.stopTone();
            mCallWaitingTonePlayer = null;
        }

        // If this is the end of an OTASP call, pass it on to the PhoneApp.
        if (c != null && TelephonyCapabilities.supportsOtasp(c.getCall().getPhone())) {
            final String number = c.getAddress();
            if (c.getCall().getPhone().isOtaSpNumber(number)) {
                if (DBG) log("onDisconnect: this was an OTASP call!");
                mApplication.handleOtaspDisconnect();
            }
        }

        // Check for the various tones we might need to play (thru the
        // earpiece) after a call disconnects.
        int toneToPlay = InCallTonePlayer.TONE_NONE;

        // The "Busy" or "Congestion" tone is the highest priority:
        if (c != null) {
            Connection.DisconnectCause cause = c.getDisconnectCause();
            if (cause == Connection.DisconnectCause.BUSY) {
                if (DBG) log("- need to play BUSY tone!");
                toneToPlay = InCallTonePlayer.TONE_BUSY;
            } else if (cause == Connection.DisconnectCause.CONGESTION) {
                if (DBG) log("- need to play CONGESTION tone!");
                toneToPlay = InCallTonePlayer.TONE_CONGESTION;
            } else if (((cause == Connection.DisconnectCause.NORMAL)
                    || (cause == Connection.DisconnectCause.LOCAL))
                    && (mApplication.isOtaCallInActiveState())) {
                if (DBG) log("- need to play OTA_CALL_END tone!");
                toneToPlay = InCallTonePlayer.TONE_OTA_CALL_END;
            } else if (cause == Connection.DisconnectCause.CDMA_REORDER) {
                if (DBG) log("- need to play CDMA_REORDER tone!");
                toneToPlay = InCallTonePlayer.TONE_REORDER;
            } else if (cause == Connection.DisconnectCause.CDMA_INTERCEPT) {
                if (DBG) log("- need to play CDMA_INTERCEPT tone!");
                toneToPlay = InCallTonePlayer.TONE_INTERCEPT;
            } else if (cause == Connection.DisconnectCause.CDMA_DROP) {
                if (DBG) log("- need to play CDMA_DROP tone!");
                toneToPlay = InCallTonePlayer.TONE_CDMA_DROP;
            } else if (cause == Connection.DisconnectCause.OUT_OF_SERVICE) {
                if (DBG) log("- need to play OUT OF SERVICE tone!");
                toneToPlay = InCallTonePlayer.TONE_OUT_OF_SERVICE;
            } else if (cause == Connection.DisconnectCause.UNOBTAINABLE_NUMBER) {
                if (DBG) log("- need to play TONE_UNOBTAINABLE_NUMBER tone!");
                toneToPlay = InCallTonePlayer.TONE_UNOBTAINABLE_NUMBER;
            } else if (cause == Connection.DisconnectCause.ERROR_UNSPECIFIED) {
                if (DBG) log("- DisconnectCause is ERROR_UNSPECIFIED: play TONE_CALL_ENDED!");
                toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
            }
        }

        // If we don't need to play BUSY or CONGESTION, then play the
        // "call ended" tone if this was a "regular disconnect" (i.e. a
        // normal call where one end or the other hung up) *and* this
        // disconnect event caused the phone to become idle.  (In other
        // words, we *don't* play the sound if one call hangs up but
        // there's still an active call on the other line.)
        // TODO: We may eventually want to disable this via a preference.
        if ((toneToPlay == InCallTonePlayer.TONE_NONE)
            && (mCM.getState() == Phone.State.IDLE)
            && (c != null)) {
            Connection.DisconnectCause cause = c.getDisconnectCause();
            if ((cause == Connection.DisconnectCause.NORMAL)  // remote hangup
                || (cause == Connection.DisconnectCause.LOCAL)) {  // local hangup
                if (VDBG) log("- need to play CALL_ENDED tone!");
                toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
                mIsCdmaRedialCall = false;
            }
        }

        if (mCM.getState() == Phone.State.IDLE) {
            // Don't reset the audio mode or bluetooth/speakerphone state
            // if we still need to let the user hear a tone through the earpiece.
            if (toneToPlay == InCallTonePlayer.TONE_NONE) {
                resetAudioStateAfterDisconnect();
            }

            mApplication.notificationMgr.cancelCallInProgressNotifications();

            // If the InCallScreen is *not* in the foreground, forcibly
            // dismiss it to make sure it won't still be in the activity
            // history.  (But if it *is* in the foreground, don't mess
            // with it; it needs to be visible, displaying the "Call
            // ended" state.)
            if (!mApplication.isShowingCallScreen()) {
                if (VDBG) log("onDisconnect: force InCallScreen to finish()");
                mApplication.dismissCallScreen();
            } else {
                if (VDBG) log("onDisconnect: In call screen. Set short timeout.");
                mApplication.clearUserActivityTimeout();
            }
        }

        if (c != null) {
            final String number = c.getAddress();
            final long date = c.getCreateTime();
            final long duration = c.getDurationMillis();
            final Connection.DisconnectCause cause = c.getDisconnectCause();
            final Phone phone = c.getCall().getPhone();
            final boolean isEmergencyNumber =
                    PhoneNumberUtils.isLocalEmergencyNumber(number, mApplication);
            // Set the "type" to be displayed in the call log (see constants in CallLog.Calls)
            final int callLogType;
            if (c.isIncoming()) {
                callLogType = (cause == Connection.DisconnectCause.INCOMING_MISSED ?
                               Calls.MISSED_TYPE : Calls.INCOMING_TYPE);
            } else {
                callLogType = Calls.OUTGOING_TYPE;
            }
            if (VDBG) log("- callLogType: " + callLogType + ", UserData: " + c.getUserData());


            {
                final CallerInfo ci = getCallerInfoFromConnection(c);  // May be null.
                final String logNumber = getLogNumber(c, ci);

                if (DBG) log("- onDisconnect(): logNumber set to: " + /*logNumber*/ "xxxxxxx");

                // TODO: In getLogNumber we use the presentation from
                // the connection for the CNAP. Should we use the one
                // below instead? (comes from caller info)

                // For international calls, 011 needs to be logged as +
                final int presentation = getPresentation(c, ci);

                if (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                    if ((isEmergencyNumber)
                            && (mCurrentEmergencyToneState != EMERGENCY_TONE_OFF)) {
                        if (mEmergencyTonePlayerVibrator != null) {
                            mEmergencyTonePlayerVibrator.stop();
                        }
                    }
                }

                // On some devices, to avoid accidental redialing of
                // emergency numbers, we *never* log emergency calls to
                // the Call Log.  (This behavior is set on a per-product
                // basis, based on carrier requirements.)
                final boolean okToLogEmergencyNumber =
                        mApplication.getResources().getBoolean(
                                R.bool.allow_emergency_numbers_in_call_log);

                // Don't call isOtaSpNumber() on phones that don't support OTASP.
                final boolean isOtaspNumber = TelephonyCapabilities.supportsOtasp(phone)
                        && phone.isOtaSpNumber(number);

                // Don't log emergency numbers if the device doesn't allow it,
                // and never log OTASP calls.
                final boolean okToLogThisCall =
                        (!isEmergencyNumber || okToLogEmergencyNumber)
                        && !isOtaspNumber;

                if (okToLogThisCall) {
                    CallLogAsync.AddCallArgs args =
                            new CallLogAsync.AddCallArgs(
                                mApplication, ci, logNumber, presentation,
                                callLogType, date, duration);
                    mCallLog.addCall(args);
                }
            }

            if (callLogType == Calls.MISSED_TYPE) {
                // Show the "Missed call" notification.
                // (Note we *don't* do this if this was an incoming call that
                // the user deliberately rejected.)
                showMissedCallNotification(c, date);
            }

            // Possibly play a "post-disconnect tone" thru the earpiece.
            // We do this here, rather than from the InCallScreen
            // activity, since we need to do this even if you're not in
            // the Phone UI at the moment the connection ends.
            if (toneToPlay != InCallTonePlayer.TONE_NONE) {
                if (VDBG) log("- starting post-disconnect tone (" + toneToPlay + ")...");
                new InCallTonePlayer(toneToPlay).start();

                // TODO: alternatively, we could start an InCallTonePlayer
                // here with an "unlimited" tone length,
                // and manually stop it later when this connection truly goes
                // away.  (The real connection over the network was closed as soon
                // as we got the BUSY message.  But our telephony layer keeps the
                // connection open for a few extra seconds so we can show the
                // "busy" indication to the user.  We could stop the busy tone
                // when *that* connection's "disconnect" event comes in.)
            }

            if (mCM.getState() == Phone.State.IDLE) {
                // Release screen wake locks if the in-call screen is not
                // showing. Otherwise, let the in-call screen handle this because
                // it needs to show the call ended screen for a couple of
                // seconds.
                if (!mApplication.isShowingCallScreen()) {
                    if (VDBG) log("- NOT showing in-call screen; releasing wake locks!");
                    mApplication.setScreenTimeout(PhoneApp.ScreenTimeoutDuration.DEFAULT);
                    mApplication.requestWakeState(PhoneApp.WakeState.SLEEP);
                } else {
                    if (VDBG) log("- still showing in-call screen; not releasing wake locks.");
                }
            } else {
                if (VDBG) log("- phone still in use; not releasing wake locks.");
            }

            if (((mPreviousCdmaCallState == Call.State.DIALING)
                    || (mPreviousCdmaCallState == Call.State.ALERTING))
                    && (!isEmergencyNumber)
                    && (cause != Connection.DisconnectCause.INCOMING_MISSED )
                    && (cause != Connection.DisconnectCause.NORMAL)
                    && (cause != Connection.DisconnectCause.LOCAL)
                    && (cause != Connection.DisconnectCause.INCOMING_REJECTED)) {
                if (!mIsCdmaRedialCall) {
                    if (autoretrySetting == InCallScreen.AUTO_RETRY_ON) {
                        // TODO: (Moto): The contact reference data may need to be stored and use
                        // here when redialing a call. For now, pass in NULL as the URI parameter.
                        PhoneUtils.placeCall(mApplication, phone, number, null, false, null);
                        mIsCdmaRedialCall = true;
                    } else {
                        mIsCdmaRedialCall = false;
                    }
                } else {
                    mIsCdmaRedialCall = false;
                }
            }
        }
    }

    /**
     * Resets the audio mode and speaker state when a call ends.
     */
    private void resetAudioStateAfterDisconnect() {
        if (VDBG) log("resetAudioStateAfterDisconnect()...");

        if (mBluetoothHandsfree != null) {
            mBluetoothHandsfree.audioOff();
        }

        // call turnOnSpeaker() with state=false and store=true even if speaker
        // is already off to reset user requested speaker state.
        PhoneUtils.turnOnSpeaker(mApplication, false, true);

        PhoneUtils.setAudioMode(mCM);
    }

    private void onMwiChanged(boolean visible) {
        if (VDBG) log("onMwiChanged(): " + visible);

        // "Voicemail" is meaningless on non-voice-capable devices,
        // so ignore MWI events.
        if (!PhoneApp.sVoiceCapable) {
            // ...but still log a warning, since we shouldn't have gotten this
            // event in the first place!
            // (PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR events
            // *should* be blocked at the telephony layer on non-voice-capable
            // capable devices.)
            Log.w(LOG_TAG, "Got onMwiChanged() on non-voice-capable device! Ignoring...");
            return;
        }

        mApplication.notificationMgr.updateMwi(visible);
    }

    /**
     * Posts a delayed PHONE_MWI_CHANGED event, to schedule a "retry" for a
     * failed NotificationMgr.updateMwi() call.
     */
    /* package */ void sendMwiChangedDelayed(long delayMillis) {
        Message message = Message.obtain(this, PHONE_MWI_CHANGED);
        sendMessageDelayed(message, delayMillis);
    }

    private void onCfiChanged(boolean visible) {
        if (VDBG) log("onCfiChanged(): " + visible);
        mApplication.notificationMgr.updateCfi(visible);
    }

    /**
     * Indicates whether or not this ringer is ringing.
     */
    boolean isRinging() {
        return mRinger.isRinging();
    }

    /**
     * Stops the current ring, and tells the notifier that future
     * ring requests should be ignored.
     */
    void silenceRinger() {
        mSilentRingerRequested = true;
        if (DBG) log("stopRing()... (silenceRinger)");
        mRinger.stopRing();
    }

    /**
     * Restarts the ringer after having previously silenced it.
     *
     * (This is a no-op if the ringer is actually still ringing, or if the
     * incoming ringing call no longer exists.)
     */
    /* package */ void restartRinger() {
        if (DBG) log("restartRinger()...");
        if (isRinging()) return;  // Already ringing; no need to restart.

        final Call ringingCall = mCM.getFirstActiveRingingCall();
        // Don't check ringingCall.isRinging() here, since that'll be true
        // for the WAITING state also.  We only allow the ringer for
        // regular INCOMING calls.
        if (DBG) log("- ringingCall state: " + ringingCall.getState());
        if (ringingCall.getState() == Call.State.INCOMING) {
            mRinger.ring();
        }
    }

    /**
     * Posts a PHONE_BATTERY_LOW event, causing us to play a warning
     * tone if the user is in-call.
     */
    /* package */ void sendBatteryLow() {
        Message message = Message.obtain(this, PHONE_BATTERY_LOW);
        sendMessage(message);
    }

    private void onBatteryLow() {
        if (DBG) log("onBatteryLow()...");

        // A "low battery" warning tone is now played by
        // StatusBarPolicy.updateBattery().
    }


    /**
     * Helper class to play tones through the earpiece (or speaker / BT)
     * during a call, using the ToneGenerator.
     *
     * To use, just instantiate a new InCallTonePlayer
     * (passing in the TONE_* constant for the tone you want)
     * and start() it.
     *
     * When we're done playing the tone, if the phone is idle at that
     * point, we'll reset the audio routing and speaker state.
     * (That means that for tones that get played *after* a call
     * disconnects, like "busy" or "congestion" or "call ended", you
     * should NOT call resetAudioStateAfterDisconnect() yourself.
     * Instead, just start the InCallTonePlayer, which will automatically
     * defer the resetAudioStateAfterDisconnect() call until the tone
     * finishes playing.)
     */
    private class InCallTonePlayer extends Thread {
        private int mToneId;
        private int mState;
        // The possible tones we can play.
        public static final int TONE_NONE = 0;
        public static final int TONE_CALL_WAITING = 1;
        public static final int TONE_BUSY = 2;
        public static final int TONE_CONGESTION = 3;
        public static final int TONE_BATTERY_LOW = 4;
        public static final int TONE_CALL_ENDED = 5;
        public static final int TONE_VOICE_PRIVACY = 6;
        public static final int TONE_REORDER = 7;
        public static final int TONE_INTERCEPT = 8;
        public static final int TONE_CDMA_DROP = 9;
        public static final int TONE_OUT_OF_SERVICE = 10;
        public static final int TONE_REDIAL = 11;
        public static final int TONE_OTA_CALL_END = 12;
        public static final int TONE_RING_BACK = 13;
        public static final int TONE_UNOBTAINABLE_NUMBER = 14;

        // The tone volume relative to other sounds in the stream
        private static final int TONE_RELATIVE_VOLUME_EMERGENCY = 100;
        private static final int TONE_RELATIVE_VOLUME_HIPRI = 80;
        private static final int TONE_RELATIVE_VOLUME_LOPRI = 50;

        // Buffer time (in msec) to add on to tone timeout value.
        // Needed mainly when the timeout value for a tone is the
        // exact duration of the tone itself.
        private static final int TONE_TIMEOUT_BUFFER = 20;

        // The tone state
        private static final int TONE_OFF = 0;
        private static final int TONE_ON = 1;
        private static final int TONE_STOPPED = 2;

        InCallTonePlayer(int toneId) {
            super();
            mToneId = toneId;
            mState = TONE_OFF;
        }

        @Override
        public void run() {
            log("InCallTonePlayer.run(toneId = " + mToneId + ")...");

            int toneType = 0;  // passed to ToneGenerator.startTone()
            int toneVolume;  // passed to the ToneGenerator constructor
            int toneLengthMillis;
            int phoneType = mCM.getFgPhone().getPhoneType();

            switch (mToneId) {
                case TONE_CALL_WAITING:
                    toneType = ToneGenerator.TONE_SUP_CALL_WAITING;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    // Call waiting tone is stopped by stopTone() method
                    toneLengthMillis = Integer.MAX_VALUE - TONE_TIMEOUT_BUFFER;
                    break;
                case TONE_BUSY:
                    if (phoneType == Phone.PHONE_TYPE_CDMA) {
                        toneType = ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT;
                        toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                        toneLengthMillis = 1000;
                    } else if ((phoneType == Phone.PHONE_TYPE_GSM)
                            || (phoneType == Phone.PHONE_TYPE_SIP)) {
                        toneType = ToneGenerator.TONE_SUP_BUSY;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 4000;
                    } else {
                        throw new IllegalStateException("Unexpected phone type: " + phoneType);
                    }
                    break;
                case TONE_CONGESTION:
                    toneType = ToneGenerator.TONE_SUP_CONGESTION;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 4000;
                    break;
                case TONE_BATTERY_LOW:
                    // For now, use ToneGenerator.TONE_PROP_ACK (two quick
                    // beeps).  TODO: is there some other ToneGenerator
                    // tone that would be more appropriate here?  Or
                    // should we consider adding a new custom tone?
                    toneType = ToneGenerator.TONE_PROP_ACK;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 1000;
                    break;
                case TONE_CALL_ENDED:
                    toneType = ToneGenerator.TONE_PROP_PROMPT;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 200;
                    break;
                 case TONE_OTA_CALL_END:
                    if (mApplication.cdmaOtaConfigData.otaPlaySuccessFailureTone ==
                            OtaUtils.OTA_PLAY_SUCCESS_FAILURE_TONE_ON) {
                        toneType = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 750;
                    } else {
                        toneType = ToneGenerator.TONE_PROP_PROMPT;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 200;
                    }
                    break;
                case TONE_VOICE_PRIVACY:
                    toneType = ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 5000;
                    break;
                case TONE_REORDER:
                    toneType = ToneGenerator.TONE_CDMA_REORDER;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 4000;
                    break;
                case TONE_INTERCEPT:
                    toneType = ToneGenerator.TONE_CDMA_ABBR_INTERCEPT;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 500;
                    break;
                case TONE_CDMA_DROP:
                case TONE_OUT_OF_SERVICE:
                    toneType = ToneGenerator.TONE_CDMA_CALLDROP_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 375;
                    break;
                case TONE_REDIAL:
                    toneType = ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 5000;
                    break;
                case TONE_RING_BACK:
                    toneType = ToneGenerator.TONE_SUP_RINGTONE;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    // Call ring back tone is stopped by stopTone() method
                    toneLengthMillis = Integer.MAX_VALUE - TONE_TIMEOUT_BUFFER;
                    break;
                case TONE_UNOBTAINABLE_NUMBER:
                    toneType = ToneGenerator.TONE_SUP_ERROR;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 4000;
                    break;
                default:
                    throw new IllegalArgumentException("Bad toneId: " + mToneId);
            }

            // If the mToneGenerator creation fails, just continue without it.  It is
            // a local audio signal, and is not as important.
            ToneGenerator toneGenerator;
            try {
                int stream;
                if (mBluetoothHandsfree != null) {
                    stream = mBluetoothHandsfree.isAudioOn() ? AudioManager.STREAM_BLUETOOTH_SCO:
                        AudioManager.STREAM_VOICE_CALL;
                } else {
                    stream = AudioManager.STREAM_VOICE_CALL;
                }
                toneGenerator = new ToneGenerator(stream, toneVolume);
                // if (DBG) log("- created toneGenerator: " + toneGenerator);
            } catch (RuntimeException e) {
                Log.w(LOG_TAG,
                      "InCallTonePlayer: Exception caught while creating ToneGenerator: " + e);
                toneGenerator = null;
            }

            // Using the ToneGenerator (with the CALL_WAITING / BUSY /
            // CONGESTION tones at least), the ToneGenerator itself knows
            // the right pattern of tones to play; we do NOT need to
            // manually start/stop each individual tone, or manually
            // insert the correct delay between tones.  (We just start it
            // and let it run for however long we want the tone pattern to
            // continue.)
            //
            // TODO: When we stop the ToneGenerator in the middle of a
            // "tone pattern", it sounds bad if we cut if off while the
            // tone is actually playing.  Consider adding API to the
            // ToneGenerator to say "stop at the next silent part of the
            // pattern", or simply "play the pattern N times and then
            // stop."
            boolean needToStopTone = true;
            boolean okToPlayTone = false;

            if (toneGenerator != null) {
                int ringerMode = mAudioManager.getRingerMode();
                if (phoneType == Phone.PHONE_TYPE_CDMA) {
                    if (toneType == ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD) {
                        if ((ringerMode != AudioManager.RINGER_MODE_SILENT) &&
                                (ringerMode != AudioManager.RINGER_MODE_VIBRATE)) {
                            if (DBG) log("- InCallTonePlayer: start playing call tone=" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else if ((toneType == ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT) ||
                            (toneType == ToneGenerator.TONE_CDMA_REORDER) ||
                            (toneType == ToneGenerator.TONE_CDMA_ABBR_REORDER) ||
                            (toneType == ToneGenerator.TONE_CDMA_ABBR_INTERCEPT) ||
                            (toneType == ToneGenerator.TONE_CDMA_CALLDROP_LITE)) {
                        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                            if (DBG) log("InCallTonePlayer:playing call fail tone:" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else if ((toneType == ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE) ||
                               (toneType == ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE)) {
                        if ((ringerMode != AudioManager.RINGER_MODE_SILENT) &&
                                (ringerMode != AudioManager.RINGER_MODE_VIBRATE)) {
                            if (DBG) log("InCallTonePlayer:playing tone for toneType=" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else { // For the rest of the tones, always OK to play.
                        okToPlayTone = true;
                    }
                } else {  // Not "CDMA"
                    okToPlayTone = true;
                }

                synchronized (this) {
                    if (okToPlayTone && mState != TONE_STOPPED) {
                        mState = TONE_ON;
                        toneGenerator.startTone(toneType);
                        try {
                            wait(toneLengthMillis + TONE_TIMEOUT_BUFFER);
                        } catch  (InterruptedException e) {
                            Log.w(LOG_TAG,
                                  "InCallTonePlayer stopped: " + e);
                        }
                        if (needToStopTone) {
                            toneGenerator.stopTone();
                        }
                    }
                    // if (DBG) log("- InCallTonePlayer: done playing.");
                    toneGenerator.release();
                    mState = TONE_OFF;
                }
            }

            // Finally, do the same cleanup we otherwise would have done
            // in onDisconnect().
            //
            // (But watch out: do NOT do this if the phone is in use,
            // since some of our tones get played *during* a call (like
            // CALL_WAITING and BATTERY_LOW) and we definitely *don't*
            // want to reset the audio mode / speaker / bluetooth after
            // playing those!
            // This call is really here for use with tones that get played
            // *after* a call disconnects, like "busy" or "congestion" or
            // "call ended", where the phone has already become idle but
            // we need to defer the resetAudioStateAfterDisconnect() call
            // till the tone finishes playing.)
            if (mCM.getState() == Phone.State.IDLE) {
                resetAudioStateAfterDisconnect();
            }
        }

        public void stopTone() {
            synchronized (this) {
                if (mState == TONE_ON) {
                    notify();
                }
                mState = TONE_STOPPED;
            }
        }
    }

    /**
     * Displays a notification when the phone receives a DisplayInfo record.
     */
    private void onDisplayInfo(AsyncResult r) {
        // Extract the DisplayInfo String from the message
        CdmaDisplayInfoRec displayInfoRec = (CdmaDisplayInfoRec)(r.result);

        if (displayInfoRec != null) {
            String displayInfo = displayInfoRec.alpha;
            if (DBG) log("onDisplayInfo: displayInfo=" + displayInfo);
            CdmaDisplayInfo.displayInfoRecord(mApplication, displayInfo);

            // start a 2 second timer
            sendEmptyMessageDelayed(DISPLAYINFO_NOTIFICATION_DONE,
                    DISPLAYINFO_NOTIFICATION_TIME);
        }
    }

    /**
     * Helper class to play SignalInfo tones using the ToneGenerator.
     *
     * To use, just instantiate a new SignalInfoTonePlayer
     * (passing in the ToneID constant for the tone you want)
     * and start() it.
     */
    private class SignalInfoTonePlayer extends Thread {
        private int mToneId;

        SignalInfoTonePlayer(int toneId) {
            super();
            mToneId = toneId;
        }

        @Override
        public void run() {
            log("SignalInfoTonePlayer.run(toneId = " + mToneId + ")...");

            if (mSignalInfoToneGenerator != null) {
                //First stop any ongoing SignalInfo tone
                mSignalInfoToneGenerator.stopTone();

                //Start playing the new tone if its a valid tone
                mSignalInfoToneGenerator.startTone(mToneId);
            }
        }
    }

    /**
     * Plays a tone when the phone receives a SignalInfo record.
     */
    private void onSignalInfo(AsyncResult r) {
        // Signal Info are totally ignored on non-voice-capable devices.
        if (!PhoneApp.sVoiceCapable) {
            Log.w(LOG_TAG, "Got onSignalInfo() on non-voice-capable device! Ignoring...");
            return;
        }

        if (PhoneUtils.isRealIncomingCall(mCM.getFirstActiveRingingCall().getState())) {
            // Do not start any new SignalInfo tone when Call state is INCOMING
            // and stop any previous SignalInfo tone which is being played
            stopSignalInfoTone();
        } else {
            // Extract the SignalInfo String from the message
            CdmaSignalInfoRec signalInfoRec = (CdmaSignalInfoRec)(r.result);
            // Only proceed if a Signal info is present.
            if (signalInfoRec != null) {
                boolean isPresent = signalInfoRec.isPresent;
                if (DBG) log("onSignalInfo: isPresent=" + isPresent);
                if (isPresent) {// if tone is valid
                    int uSignalType = signalInfoRec.signalType;
                    int uAlertPitch = signalInfoRec.alertPitch;
                    int uSignal = signalInfoRec.signal;

                    if (DBG) log("onSignalInfo: uSignalType=" + uSignalType + ", uAlertPitch=" +
                            uAlertPitch + ", uSignal=" + uSignal);
                    //Map the Signal to a ToneGenerator ToneID only if Signal info is present
                    int toneID = SignalToneUtil.getAudioToneFromSignalInfo
                            (uSignalType, uAlertPitch, uSignal);

                    //Create the SignalInfo tone player and pass the ToneID
                    new SignalInfoTonePlayer(toneID).start();
                }
            }
        }
    }

    /**
     * Stops a SignalInfo tone in the following condition
     * 1 - On receiving a New Ringing Call
     * 2 - On disconnecting a call
     * 3 - On answering a Call Waiting Call
     */
    /* package */ void stopSignalInfoTone() {
        if (DBG) log("stopSignalInfoTone: Stopping SignalInfo tone player");
        new SignalInfoTonePlayer(ToneGenerator.TONE_CDMA_SIGNAL_OFF).start();
    }

    /**
     * Plays a Call waiting tone if it is present in the second incoming call.
     */
    private void onCdmaCallWaiting(AsyncResult r) {
        // Remove any previous Call waiting timers in the queue
        removeMessages(CALLWAITING_CALLERINFO_DISPLAY_DONE);
        removeMessages(CALLWAITING_ADDCALL_DISABLE_TIMEOUT);

        // Set the Phone Call State to SINGLE_ACTIVE as there is only one connection
        // else we would not have received Call waiting
        mApplication.cdmaPhoneCallState.setCurrentCallState(
                CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE);

        // Display the incoming call to the user if the InCallScreen isn't
        // already in the foreground.
        if (!mApplication.isShowingCallScreen()) {
            if (DBG) log("- showing incoming call (CDMA call waiting)...");
            showIncomingCall();
        }

        // Start timer for CW display
        mCallWaitingTimeOut = false;
        sendEmptyMessageDelayed(CALLWAITING_CALLERINFO_DISPLAY_DONE,
                CALLWAITING_CALLERINFO_DISPLAY_TIME);

        // Set the mAddCallMenuStateAfterCW state to false
        mApplication.cdmaPhoneCallState.setAddCallMenuStateAfterCallWaiting(false);

        // Start the timer for disabling "Add Call" menu option
        sendEmptyMessageDelayed(CALLWAITING_ADDCALL_DISABLE_TIMEOUT,
                CALLWAITING_ADDCALL_DISABLE_TIME);

        // Extract the Call waiting information
        CdmaCallWaitingNotification infoCW = (CdmaCallWaitingNotification) r.result;
        int isPresent = infoCW.isPresent;
        if (DBG) log("onCdmaCallWaiting: isPresent=" + isPresent);
        if (isPresent == 1 ) {//'1' if tone is valid
            int uSignalType = infoCW.signalType;
            int uAlertPitch = infoCW.alertPitch;
            int uSignal = infoCW.signal;
            if (DBG) log("onCdmaCallWaiting: uSignalType=" + uSignalType + ", uAlertPitch="
                    + uAlertPitch + ", uSignal=" + uSignal);
            //Map the Signal to a ToneGenerator ToneID only if Signal info is present
            int toneID =
                SignalToneUtil.getAudioToneFromSignalInfo(uSignalType, uAlertPitch, uSignal);

            //Create the SignalInfo tone player and pass the ToneID
            new SignalInfoTonePlayer(toneID).start();
        }
    }

    /**
     * Posts a event causing us to clean up after rejecting (or timing-out) a
     * CDMA call-waiting call.
     *
     * This method is safe to call from any thread.
     * @see onCdmaCallWaitingReject()
     */
    /* package */ void sendCdmaCallWaitingReject() {
        sendEmptyMessage(CDMA_CALL_WAITING_REJECT);
    }

    /**
     * Performs Call logging based on Timeout or Ignore Call Waiting Call for CDMA,
     * and finally calls Hangup on the Call Waiting connection.
     *
     * This method should be called only from the UI thread.
     * @see sendCdmaCallWaitingReject()
     */
    private void onCdmaCallWaitingReject() {
        final Call ringingCall = mCM.getFirstActiveRingingCall();

        // Call waiting timeout scenario
        if (ringingCall.getState() == Call.State.WAITING) {
            // Code for perform Call logging and missed call notification
            Connection c = ringingCall.getLatestConnection();

            if (c != null) {
                String number = c.getAddress();
                int presentation = c.getNumberPresentation();
                final long date = c.getCreateTime();
                final long duration = c.getDurationMillis();
                final int callLogType = mCallWaitingTimeOut ?
                        Calls.MISSED_TYPE : Calls.INCOMING_TYPE;

                // get the callerinfo object and then log the call with it.
                Object o = c.getUserData();
                final CallerInfo ci;
                if ((o == null) || (o instanceof CallerInfo)) {
                    ci = (CallerInfo) o;
                } else {
                    ci = ((PhoneUtils.CallerInfoToken) o).currentInfo;
                }

                // Do final CNAP modifications of logNumber prior to logging [mimicking
                // onDisconnect()]
                final String logNumber = PhoneUtils.modifyForSpecialCnapCases(
                        mApplication, ci, number, presentation);
                final int newPresentation = (ci != null) ? ci.numberPresentation : presentation;
                if (DBG) log("- onCdmaCallWaitingReject(): logNumber set to: " + logNumber
                        + ", newPresentation value is: " + newPresentation);

                CallLogAsync.AddCallArgs args =
                        new CallLogAsync.AddCallArgs(
                            mApplication, ci, logNumber, presentation,
                            callLogType, date, duration);

                mCallLog.addCall(args);

                if (callLogType == Calls.MISSED_TYPE) {
                    // Add missed call notification
                    showMissedCallNotification(c, date);
                } else {
                    // Remove Call waiting 20 second display timer in the queue
                    removeMessages(CALLWAITING_CALLERINFO_DISPLAY_DONE);
                }

                // Hangup the RingingCall connection for CW
                PhoneUtils.hangup(c);
            }

            //Reset the mCallWaitingTimeOut boolean
            mCallWaitingTimeOut = false;
        }
    }

    /**
     * Return the private variable mPreviousCdmaCallState.
     */
    /* package */ Call.State getPreviousCdmaCallState() {
        return mPreviousCdmaCallState;
    }

    /**
     * Return the private variable mVoicePrivacyState.
     */
    /* package */ boolean getVoicePrivacyState() {
        return mVoicePrivacyState;
    }

    /**
     * Return the private variable mIsCdmaRedialCall.
     */
    /* package */ boolean getIsCdmaRedialCall() {
        return mIsCdmaRedialCall;
    }

    /**
     * Helper function used to show a missed call notification.
     */
    private void showMissedCallNotification(Connection c, final long date) {
        PhoneUtils.CallerInfoToken info =
            PhoneUtils.startGetCallerInfo(mApplication, c, this, Long.valueOf(date));
        if (info != null) {
            // at this point, we've requested to start a query, but it makes no
            // sense to log this missed call until the query comes back.
            if (VDBG) log("showMissedCallNotification: Querying for CallerInfo on missed call...");
            if (info.isFinal) {
                // it seems that the query we have actually is up to date.
                // send the notification then.
                CallerInfo ci = info.currentInfo;

                // Check number presentation value; if we have a non-allowed presentation,
                // then display an appropriate presentation string instead as the missed
                // call.
                String name = ci.name;
                String number = ci.phoneNumber;
                if (ci.numberPresentation == Connection.PRESENTATION_RESTRICTED) {
                    name = mApplication.getString(R.string.private_num);
                } else if (ci.numberPresentation != Connection.PRESENTATION_ALLOWED) {
                    name = mApplication.getString(R.string.unknown);
                } else {
                    number = PhoneUtils.modifyForSpecialCnapCases(mApplication,
                            ci, number, ci.numberPresentation);
                }
                mApplication.notificationMgr.notifyMissedCall(name, number,
                        ci.phoneLabel, date);
            }
        } else {
            // getCallerInfo() can return null in rare cases, like if we weren't
            // able to get a valid phone number out of the specified Connection.
            Log.w(LOG_TAG, "showMissedCallNotification: got null CallerInfo for Connection " + c);
        }
    }

    /**
     *  Inner class to handle emergency call tone and vibrator
     */
    private class EmergencyTonePlayerVibrator {
        private final int EMG_VIBRATE_LENGTH = 1000;  // ms.
        private final int EMG_VIBRATE_PAUSE  = 1000;  // ms.
        private final long[] mVibratePattern =
                new long[] { EMG_VIBRATE_LENGTH, EMG_VIBRATE_PAUSE };

        private ToneGenerator mToneGenerator;
        private Vibrator mEmgVibrator;
        private int mInCallVolume;

        /**
         * constructor
         */
        public EmergencyTonePlayerVibrator() {
        }

        /**
         * Start the emergency tone or vibrator.
         */
        private void start() {
            if (VDBG) log("call startEmergencyToneOrVibrate.");
            int ringerMode = mAudioManager.getRingerMode();

            if ((mIsEmergencyToneOn == EMERGENCY_TONE_ALERT) &&
                    (ringerMode == AudioManager.RINGER_MODE_NORMAL)) {
                log("EmergencyTonePlayerVibrator.start(): emergency tone...");
                mToneGenerator = new ToneGenerator (AudioManager.STREAM_VOICE_CALL,
                        InCallTonePlayer.TONE_RELATIVE_VOLUME_EMERGENCY);
                if (mToneGenerator != null) {
                    mInCallVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                    mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                            mAudioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                            0);
                    mToneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK);
                    mCurrentEmergencyToneState = EMERGENCY_TONE_ALERT;
                }
            } else if (mIsEmergencyToneOn == EMERGENCY_TONE_VIBRATE) {
                log("EmergencyTonePlayerVibrator.start(): emergency vibrate...");
                mEmgVibrator = new Vibrator();
                if (mEmgVibrator != null) {
                    mEmgVibrator.vibrate(mVibratePattern, 0);
                    mCurrentEmergencyToneState = EMERGENCY_TONE_VIBRATE;
                }
            }
        }

        /**
         * If the emergency tone is active, stop the tone or vibrator accordingly.
         */
        private void stop() {
            if (VDBG) log("call stopEmergencyToneOrVibrate.");

            if ((mCurrentEmergencyToneState == EMERGENCY_TONE_ALERT)
                    && (mToneGenerator != null)) {
                mToneGenerator.stopTone();
                mToneGenerator.release();
                mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                        mInCallVolume,
                        0);
            } else if ((mCurrentEmergencyToneState == EMERGENCY_TONE_VIBRATE)
                    && (mEmgVibrator != null)) {
                mEmgVibrator.cancel();
            }
            mCurrentEmergencyToneState = EMERGENCY_TONE_OFF;
        }
    }

    private void onRingbackTone(AsyncResult r) {
        boolean playTone = (Boolean)(r.result);

        if (playTone == true) {
            // Only play when foreground call is in DIALING or ALERTING.
            // to prevent a late coming playtone after ALERTING.
            // Don't play ringback tone if it is in play, otherwise it will cut
            // the current tone and replay it
            if (mCM.getActiveFgCallState().isDialing() &&
                mInCallRingbackTonePlayer == null) {
                mInCallRingbackTonePlayer = new InCallTonePlayer(InCallTonePlayer.TONE_RING_BACK);
                mInCallRingbackTonePlayer.start();
            }
        } else {
            if (mInCallRingbackTonePlayer != null) {
                mInCallRingbackTonePlayer.stopTone();
                mInCallRingbackTonePlayer = null;
            }
        }
    }

    /**
     * Toggle mute and unmute requests while keeping the same mute state
     */
    private void onResendMute() {
        boolean muteState = PhoneUtils.getMute();
        PhoneUtils.setMute(!muteState);
        PhoneUtils.setMute(muteState);
    }

    /**
     * Retrieve the phone number from the caller info or the connection.
     *
     * For incoming call the number is in the Connection object. For
     * outgoing call we use the CallerInfo phoneNumber field if
     * present. All the processing should have been done already (CDMA vs GSM numbers).
     *
     * If CallerInfo is missing the phone number, get it from the connection.
     * Apply the Call Name Presentation (CNAP) transform in the connection on the number.
     *
     * @param conn The phone connection.
     * @param info The CallerInfo. Maybe null.
     * @return the phone number.
     */
    private String getLogNumber(Connection conn, CallerInfo callerInfo) {
        String number = null;

        if (conn.isIncoming()) {
            number = conn.getAddress();
        } else {
            // For emergency and voicemail calls,
            // CallerInfo.phoneNumber does *not* contain a valid phone
            // number.  Instead it contains an I18N'd string such as
            // "Emergency Number" or "Voice Mail" so we get the number
            // from the connection.
            if (null == callerInfo || TextUtils.isEmpty(callerInfo.phoneNumber) ||
                callerInfo.isEmergencyNumber() || callerInfo.isVoiceMailNumber()) {
                if (conn.getCall().getPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                    // In cdma getAddress() is not always equals to getOrigDialString().
                    number = conn.getOrigDialString();
                } else {
                    number = conn.getAddress();
                }
            } else {
                number = callerInfo.phoneNumber;
            }
        }

        if (null == number) {
            return null;
        } else {
            int presentation = conn.getNumberPresentation();

            // Do final CNAP modifications.
            number = PhoneUtils.modifyForSpecialCnapCases(mApplication, callerInfo,
                                                          number, presentation);
            if (!PhoneNumberUtils.isUriNumber(number)) {
                number = PhoneNumberUtils.stripSeparators(number);
            }
            if (VDBG) log("getLogNumber: " + number);
            return number;
        }
    }

    /**
     * Get the caller info.
     *
     * @param conn The phone connection.
     * @return The CallerInfo associated with the connection. Maybe null.
     */
    private CallerInfo getCallerInfoFromConnection(Connection conn) {
        CallerInfo ci = null;
        Object o = conn.getUserData();

        if ((o == null) || (o instanceof CallerInfo)) {
            ci = (CallerInfo) o;
        } else {
            ci = ((PhoneUtils.CallerInfoToken) o).currentInfo;
        }
        return ci;
    }

    /**
     * Get the presentation from the callerinfo if not null otherwise,
     * get it from the connection.
     *
     * @param conn The phone connection.
     * @param info The CallerInfo. Maybe null.
     * @return The presentation to use in the logs.
     */
    private int getPresentation(Connection conn, CallerInfo callerInfo) {
        int presentation;

        if (null == callerInfo) {
            presentation = conn.getNumberPresentation();
        } else {
            presentation = callerInfo.numberPresentation;
            if (DBG) log("- getPresentation(): ignoring connection's presentation: " +
                         conn.getNumberPresentation());
        }
        if (DBG) log("- getPresentation: presentation: " + presentation);
        return presentation;
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

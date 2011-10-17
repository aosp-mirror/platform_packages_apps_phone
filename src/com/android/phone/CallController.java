/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Phone;
import com.android.phone.Constants.CallStatusCode;
import com.android.phone.InCallUiState.InCallScreenMode;
import com.android.phone.OtaUtils.CdmaOtaScreenState;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;


/**
 * Phone app module in charge of "call control".
 *
 * This is a singleton object which acts as the interface to the telephony layer
 * (and other parts of the Android framework) for all user-initiated telephony
 * functionality, like making outgoing calls.
 *
 * This functionality includes things like:
 *   - actually running the placeCall() method and handling errors or retries
 *   - running the whole "emergency call in airplane mode" sequence
 *   - running the state machine of MMI sequences
 *   - restoring/resetting mute and speaker state when a new call starts
 *   - updating the prox sensor wake lock state
 *   - resolving what the voicemail: intent should mean (and making the call)
 *
 * The single CallController instance stays around forever; it's not tied
 * to the lifecycle of any particular Activity (like the InCallScreen).
 * There's also no implementation of onscreen UI here (that's all in InCallScreen).
 *
 * Note that this class does not handle asynchronous events from the telephony
 * layer, like reacting to an incoming call; see CallNotifier for that.  This
 * class purely handles actions initiated by the user, like outgoing calls.
 */
public class CallController extends Handler {
    private static final String TAG = "CallController";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    // Do not check in with VDBG = true, since that may write PII to the system log.
    private static final boolean VDBG = false;

    /** The singleton CallController instance. */
    private static CallController sInstance;

    private PhoneApp mApp;
    private CallManager mCM;

    /** Helper object for emergency calls in some rare use cases.  Created lazily. */
    private EmergencyCallHelper mEmergencyCallHelper;


    //
    // Message codes; see handleMessage().
    //

    private static final int THREEWAY_CALLERINFO_DISPLAY_DONE = 1;


    //
    // Misc constants.
    //

    // Amount of time the UI should display "Dialing" when initiating a CDMA
    // 3way call.  (See comments on the THRWAY_ACTIVE case in
    // placeCallInternal() for more info.)
    private static final int THREEWAY_CALLERINFO_DISPLAY_TIME = 3000; // msec


    /**
     * Initialize the singleton CallController instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the CallController instance is available via the
     * PhoneApp's public "callController" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static CallController init(PhoneApp app) {
        synchronized (CallController.class) {
            if (sInstance == null) {
                sInstance = new CallController(app);
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /**
     * Private constructor (this is a singleton).
     * @see init()
     */
    private CallController(PhoneApp app) {
        if (DBG) log("CallController constructor: app = " + app);
        mApp = app;
        mCM = app.mCM;
    }

    @Override
    public void handleMessage(Message msg) {
        if (VDBG) log("handleMessage: " + msg);
        switch (msg.what) {

            case THREEWAY_CALLERINFO_DISPLAY_DONE:
                if (DBG) log("THREEWAY_CALLERINFO_DISPLAY_DONE...");

                if (mApp.cdmaPhoneCallState.getCurrentCallState()
                    == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                    // Reset the mThreeWayCallOrigStateDialing state
                    mApp.cdmaPhoneCallState.setThreeWayCallOrigState(false);

                    // Refresh the in-call UI (based on the current ongoing call)
                    mApp.updateInCallScreen();
                }
                break;

            default:
                Log.wtf(TAG, "handleMessage: unexpected code: " + msg);
                break;
        }
    }

    //
    // Outgoing call sequence
    //

    /**
     * Initiate an outgoing call.
     *
     * Here's the most typical outgoing call sequence:
     *
     *  (1) OutgoingCallBroadcaster receives a CALL intent and sends the
     *      NEW_OUTGOING_CALL broadcast
     *
     *  (2) The broadcast finally reaches OutgoingCallReceiver, which stashes
     *      away a copy of the original CALL intent and launches
     *      SipCallOptionHandler
     *
     *  (3) SipCallOptionHandler decides whether this is a PSTN or SIP call (and
     *      in some cases brings up a dialog to let the user choose), and
     *      ultimately calls CallController.placeCall() (from the
     *      setResultAndFinish() method) with the stashed-away intent from step
     *      (2) as the "intent" parameter.
     *
     *  (4) Here in CallController.placeCall() we read the phone number or SIP
     *      address out of the intent and actually initate the call, and
     *      simultaneously launch the InCallScreen to display the in-call UI.
     *
     *  (5) We handle various errors by directing the InCallScreen to
     *      display error messages or dialogs (via the InCallUiState
     *      "pending call status code" flag), and in some cases we also
     *      sometimes continue working in the background to resolve the
     *      problem (like in the case of an emergency call while in
     *      airplane mode).  Any time that some onscreen indication to the
     *      user needs to change, we update the "status dialog" info in
     *      the inCallUiState and (re)launch the InCallScreen to make sure
     *      it's visible.
     */
    public void placeCall(Intent intent) {
        log("placeCall()...  intent = " + intent);
        if (VDBG) log("                extras = " + intent.getExtras());

        final InCallUiState inCallUiState = mApp.inCallUiState;

        // TODO: Do we need to hold a wake lock while this method runs?
        //       Or did we already acquire one somewhere earlier
        //       in this sequence (like when we first received the CALL intent?)

        if (intent == null) {
            Log.wtf(TAG, "placeCall: called with null intent");
            throw new IllegalArgumentException("placeCall: called with null intent");
        }

        String action = intent.getAction();
        Uri uri = intent.getData();
        if (uri == null) {
            Log.wtf(TAG, "placeCall: intent had no data");
            throw new IllegalArgumentException("placeCall: intent had no data");
        }

        String scheme = uri.getScheme();
        String number = PhoneNumberUtils.getNumberFromIntent(intent, mApp);
        if (VDBG) {
            log("- action: " + action);
            log("- uri: " + uri);
            log("- scheme: " + scheme);
            log("- number: " + number);
        }

        // This method should only be used with the various flavors of CALL
        // intents.  (It doesn't make sense for any other action to trigger an
        // outgoing call!)
        if (!(Intent.ACTION_CALL.equals(action)
              || Intent.ACTION_CALL_EMERGENCY.equals(action)
              || Intent.ACTION_CALL_PRIVILEGED.equals(action))) {
            Log.wtf(TAG, "placeCall: unexpected intent action " + action);
            throw new IllegalArgumentException("Unexpected action: " + action);
        }

        // Check to see if this is an OTASP call (the "activation" call
        // used to provision CDMA devices), and if so, do some
        // OTASP-specific setup.
        Phone phone = mApp.mCM.getDefaultPhone();
        if (TelephonyCapabilities.supportsOtasp(phone)) {
            checkForOtaspCall(intent);
        }

        // Clear out the "restore mute state" flag since we're
        // initiating a brand-new call.
        //
        // (This call to setRestoreMuteOnInCallResume(false) informs the
        // phone app that we're dealing with a new connection
        // (i.e. placing an outgoing call, and NOT handling an aborted
        // "Add Call" request), so we should let the mute state be handled
        // by the PhoneUtils phone state change handler.)
        mApp.setRestoreMuteOnInCallResume(false);

        // If a provider is used, extract the info to build the
        // overlay and route the call.  The overlay will be
        // displayed when the InCallScreen becomes visible.
        if (PhoneUtils.hasPhoneProviderExtras(intent)) {
            inCallUiState.setProviderOverlayInfo(intent);
        } else {
            inCallUiState.clearProviderOverlayInfo();
        }

        CallStatusCode status = placeCallInternal(intent);

        if (status == CallStatusCode.SUCCESS) {
            if (DBG) log("==> placeCall(): success from placeCallInternal(): " + status);
            // There's no "error condition" that needs to be displayed to
            // the user, so clear out the InCallUiState's "pending call
            // status code".
            inCallUiState.clearPendingCallStatusCode();

            // Notify the phone app that a call is beginning so it can
            // enable the proximity sensor
            mApp.setBeginningCall(true);
        } else {
            log("==> placeCall(): failure code from placeCallInternal(): " + status);
            // Handle the various error conditions that can occur when
            // initiating an outgoing call, typically by directing the
            // InCallScreen to display a diagnostic message (via the
            // "pending call status code" flag.)
            handleOutgoingCallError(status);
        }

        // Finally, regardless of whether we successfully initiated the
        // outgoing call or not, force the InCallScreen to come to the
        // foreground.
        //
        // (For successful calls the the user will just see the normal
        // in-call UI.  Or if there was an error, the InCallScreen will
        // notice the InCallUiState pending call status code flag and display an
        // error indication instead.)

        // TODO: double-check the behavior of mApp.displayCallScreen()
        // if the InCallScreen is already visible:
        // - make sure it forces the UI to refresh
        // - make sure it does NOT launch a new InCallScreen on top
        //   of the current one (i.e. the Back button should not take
        //   you back to the previous InCallScreen)
        // - it's probably OK to go thru a fresh pause/resume sequence
        //   though (since that should be fast now)
        // - if necessary, though, maybe PhoneApp.displayCallScreen()
        //   could notice that the InCallScreen is already in the foreground,
        //   and if so simply call updateInCallScreen() instead.

        mApp.displayCallScreen();
    }

    /**
     * Actually make a call to whomever the intent tells us to.
     *
     * Note that there's no need to explicitly update (or refresh) the
     * in-call UI at any point in this method, since a fresh InCallScreen
     * instance will be launched automatically after we return (see
     * placeCall() above.)
     *
     * @param intent the CALL intent describing whom to call
     * @return CallStatusCode.SUCCESS if we successfully initiated an
     *    outgoing call.  If there was some kind of failure, return one of
     *    the other CallStatusCode codes indicating what went wrong.
     */
    private CallStatusCode placeCallInternal(Intent intent) {
        if (DBG) log("placeCallInternal()...  intent = " + intent);

        // TODO: This method is too long.  Break it down into more
        // manageable chunks.

        final InCallUiState inCallUiState = mApp.inCallUiState;
        String number;
        Phone phone = null;

        // Check the current ServiceState to make sure it's OK
        // to even try making a call.
        CallStatusCode okToCallStatus = checkIfOkToInitiateOutgoingCall(
                mCM.getServiceState());

        // TODO: Streamline the logic here.  Currently, the code is
        // unchanged from its original form in InCallScreen.java.  But we
        // should fix a couple of things:
        // - Don't call checkIfOkToInitiateOutgoingCall() more than once
        // - Wrap the try/catch for VoiceMailNumberMissingException
        //   around *only* the call that can throw that exception.

        try {
            number = getInitialNumber(intent);
            if (VDBG) log("- actual number to dial: '" + number + "'");

            // find the phone first
            // TODO Need a way to determine which phone to place the call
            // It could be determined by SIP setting, i.e. always,
            // or by number, i.e. for international,
            // or by user selection, i.e., dialog query,
            // or any of combinations
            Uri uri = intent.getData();
            String scheme = (uri != null) ? uri.getScheme() : null;
            String sipPhoneUri = intent.getStringExtra(
                    OutgoingCallBroadcaster.EXTRA_SIP_PHONE_URI);
            phone = PhoneUtils.pickPhoneBasedOnNumber(mCM, scheme, number, sipPhoneUri);
            if (VDBG) log("- got Phone instance: " + phone + ", class = " + phone.getClass());

            // update okToCallStatus based on new phone
            okToCallStatus = checkIfOkToInitiateOutgoingCall(
                    phone.getServiceState().getState());

        } catch (PhoneUtils.VoiceMailNumberMissingException ex) {
            // If the call status is NOT in an acceptable state, it
            // may effect the way the voicemail number is being
            // retrieved.  Mask the VoiceMailNumberMissingException
            // with the underlying issue of the phone state.
            if (okToCallStatus != CallStatusCode.SUCCESS) {
                if (DBG) log("Voicemail number not reachable in current SIM card state.");
                return okToCallStatus;
            }
            if (DBG) log("VoiceMailNumberMissingException from getInitialNumber()");
            return CallStatusCode.VOICEMAIL_NUMBER_MISSING;
        }

        if (number == null) {
            Log.w(TAG, "placeCall: couldn't get a phone number from Intent " + intent);
            return CallStatusCode.NO_PHONE_NUMBER_SUPPLIED;
        }

        boolean isEmergencyNumber = PhoneNumberUtils.isLocalEmergencyNumber(number, mApp);
        boolean isEmergencyIntent = Intent.ACTION_CALL_EMERGENCY.equals(intent.getAction());

        if (isEmergencyNumber && !isEmergencyIntent) {
            Log.e(TAG, "Non-CALL_EMERGENCY Intent " + intent
                    + " attempted to call emergency number " + number
                    + ".");
            return CallStatusCode.CALL_FAILED;
        } else if (!isEmergencyNumber && isEmergencyIntent) {
            Log.e(TAG, "Received CALL_EMERGENCY Intent " + intent
                    + " with non-emergency number " + number
                    + " -- failing call.");
            return CallStatusCode.CALL_FAILED;
        }

        // If we're trying to call an emergency number, then it's OK to
        // proceed in certain states where we'd otherwise bring up
        // an error dialog:
        // - If we're in EMERGENCY_ONLY mode, then (obviously) you're allowed
        //   to dial emergency numbers.
        // - If we're OUT_OF_SERVICE, we still attempt to make a call,
        //   since the radio will register to any available network.

        if (isEmergencyNumber
            && ((okToCallStatus == CallStatusCode.EMERGENCY_ONLY)
                || (okToCallStatus == CallStatusCode.OUT_OF_SERVICE))) {
            if (DBG) log("placeCall: Emergency number detected with status = " + okToCallStatus);
            okToCallStatus = CallStatusCode.SUCCESS;
            if (DBG) log("==> UPDATING status to: " + okToCallStatus);
        }

        if (okToCallStatus != CallStatusCode.SUCCESS) {
            // If this is an emergency call, launch the EmergencyCallHelperService
            // to turn on the radio and retry the call.
            if (isEmergencyNumber && (okToCallStatus == CallStatusCode.POWER_OFF)) {
                Log.i(TAG, "placeCall: Trying to make emergency call while POWER_OFF!");

                // If needed, lazily instantiate an EmergencyCallHelper instance.
                synchronized (this) {
                    if (mEmergencyCallHelper == null) {
                        mEmergencyCallHelper = new EmergencyCallHelper(this);
                    }
                }

                // ...and kick off the "emergency call from airplane mode" sequence.
                mEmergencyCallHelper.startEmergencyCallFromAirplaneModeSequence(number);

                // Finally, return CallStatusCode.SUCCESS right now so
                // that the in-call UI will remain visible (in order to
                // display the progress indication.)
                // TODO: or maybe it would be more clear to return a whole
                // new CallStatusCode called "TURNING_ON_RADIO" here.
                // That way, we'd update inCallUiState.progressIndication from
                // the handleOutgoingCallError() method, rather than here.
                return CallStatusCode.SUCCESS;
            } else {
                // Otherwise, just return the (non-SUCCESS) status code
                // back to our caller.
                if (DBG) log("==> placeCallInternal(): non-success status: " + okToCallStatus);
                return okToCallStatus;
            }
        }

        // Ok, we can proceed with this outgoing call.

        // Reset some InCallUiState flags, just in case they're still set
        // from a prior call.
        inCallUiState.needToShowCallLostDialog = false;
        inCallUiState.clearProgressIndication();

        // We have a valid number, so try to actually place a call:
        // make sure we pass along the intent's URI which is a
        // reference to the contact. We may have a provider gateway
        // phone number to use for the outgoing call.
        Uri contactUri = intent.getData();

        // Watch out: PhoneUtils.placeCall() returns one of the
        // CALL_STATUS_* constants, not a CallStatusCode enum value.
        int callStatus = PhoneUtils.placeCall(mApp,
                                              phone,
                                              number,
                                              contactUri,
                                              (isEmergencyNumber || isEmergencyIntent),
                                              inCallUiState.providerGatewayUri);

        switch (callStatus) {
            case PhoneUtils.CALL_STATUS_DIALED:
                if (VDBG) log("placeCall: PhoneUtils.placeCall() succeeded for regular call '"
                             + number + "'.");


                // TODO(OTASP): still need more cleanup to simplify the mApp.cdma*State objects:
                // - Rather than checking inCallUiState.inCallScreenMode, the
                //   code here could also check for
                //   app.getCdmaOtaInCallScreenUiState() returning NORMAL.
                // - But overall, app.inCallUiState.inCallScreenMode and
                //   app.cdmaOtaInCallScreenUiState.state are redundant.
                //   Combine them.

                if (VDBG) log ("- inCallUiState.inCallScreenMode = "
                               + inCallUiState.inCallScreenMode);
                if (inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL) {
                    if (VDBG) log ("==>  OTA_NORMAL note: switching to OTA_STATUS_LISTENING.");
                    mApp.cdmaOtaScreenState.otaScreenState =
                            CdmaOtaScreenState.OtaScreenState.OTA_STATUS_LISTENING;
                }

                // Any time we initiate a call, force the DTMF dialpad to
                // close.  (We want to make sure the user can see the regular
                // in-call UI while the new call is dialing, and when it
                // first gets connected.)
                inCallUiState.showDialpad = false;

                // Also, in case a previous call was already active (i.e. if
                // we just did "Add call"), clear out the "history" of DTMF
                // digits you typed, to make sure it doesn't persist from the
                // previous call to the new call.
                // TODO: it would be more precise to do this when the actual
                // phone state change happens (i.e. when a new foreground
                // call appears and the previous call moves to the
                // background), but the InCallScreen doesn't keep enough
                // state right now to notice that specific transition in
                // onPhoneStateChanged().
                inCallUiState.dialpadDigits = null;

                // Check for an obscure ECM-related scenario: If the phone
                // is currently in ECM (Emergency callback mode) and we
                // dial a non-emergency number, that automatically
                // *cancels* ECM.  So warn the user about it.
                // (See InCallScreen.showExitingECMDialog() for more info.)
                if (PhoneUtils.isPhoneInEcm(phone) && !isEmergencyNumber) {
                    Log.i(TAG, "About to exit ECM because of an outgoing non-emergency call");
                    // Tell the InCallScreen to show the "Exiting ECM" warning.
                    inCallUiState.setPendingCallStatusCode(CallStatusCode.EXITED_ECM);
                }

                if (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                    // Start the timer for 3 Way CallerInfo
                    if (mApp.cdmaPhoneCallState.getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                        //Unmute for the second MO call
                        PhoneUtils.setMute(false);

                        // This is a "CDMA 3-way call", which means that you're dialing a
                        // 2nd outgoing call while a previous call is already in progress.
                        //
                        // Due to the limitations of CDMA this call doesn't actually go
                        // through the DIALING/ALERTING states, so we can't tell for sure
                        // when (or if) it's actually answered.  But we want to show
                        // *some* indication of what's going on in the UI, so we "fake it"
                        // by displaying the "Dialing" state for 3 seconds.

                        // Set the mThreeWayCallOrigStateDialing state to true
                        mApp.cdmaPhoneCallState.setThreeWayCallOrigState(true);

                        // Schedule the "Dialing" indication to be taken down in 3 seconds:
                        sendEmptyMessageDelayed(THREEWAY_CALLERINFO_DISPLAY_DONE,
                                                THREEWAY_CALLERINFO_DISPLAY_TIME);
                    }
                }

                return CallStatusCode.SUCCESS;

            case PhoneUtils.CALL_STATUS_DIALED_MMI:
                if (DBG) log("placeCall: specified number was an MMI code: '" + number + "'.");
                // The passed-in number was an MMI code, not a regular phone number!
                // This isn't really a failure; the Dialer may have deliberately
                // fired an ACTION_CALL intent to dial an MMI code, like for a
                // USSD call.
                //
                // Presumably an MMI_INITIATE message will come in shortly
                // (and we'll bring up the "MMI Started" dialog), or else
                // an MMI_COMPLETE will come in (which will take us to a
                // different Activity; see PhoneUtils.displayMMIComplete()).
                return CallStatusCode.DIALED_MMI;

            case PhoneUtils.CALL_STATUS_FAILED:
                Log.w(TAG, "placeCall: PhoneUtils.placeCall() FAILED for number '"
                      + number + "'.");
                // We couldn't successfully place the call; there was some
                // failure in the telephony layer.
                return CallStatusCode.CALL_FAILED;

            default:
                Log.wtf(TAG, "placeCall: unknown callStatus " + callStatus
                        + " from PhoneUtils.placeCall() for number '" + number + "'.");
                return CallStatusCode.SUCCESS;  // Try to continue anyway...
        }
    }

    /**
     * Checks the current ServiceState to make sure it's OK
     * to try making an outgoing call to the specified number.
     *
     * @return CallStatusCode.SUCCESS if it's OK to try calling the specified
     *    number.  If not, like if the radio is powered off or we have no
     *    signal, return one of the other CallStatusCode codes indicating what
     *    the problem is.
     */
    private CallStatusCode checkIfOkToInitiateOutgoingCall(int state) {
        if (VDBG) log("checkIfOkToInitiateOutgoingCall: ServiceState = " + state);

        switch (state) {
            case ServiceState.STATE_IN_SERVICE:
                // Normal operation.  It's OK to make outgoing calls.
                return CallStatusCode.SUCCESS;

            case ServiceState.STATE_POWER_OFF:
                // Radio is explictly powered off.
                return CallStatusCode.POWER_OFF;

            case ServiceState.STATE_EMERGENCY_ONLY:
                // The phone is registered, but locked. Only emergency
                // numbers are allowed.
                // Note that as of Android 2.0 at least, the telephony layer
                // does not actually use ServiceState.STATE_EMERGENCY_ONLY,
                // mainly since there's no guarantee that the radio/RIL can
                // make this distinction.  So in practice the
                // CallStatusCode.EMERGENCY_ONLY state and the string
                // "incall_error_emergency_only" are totally unused.
                return CallStatusCode.EMERGENCY_ONLY;

            case ServiceState.STATE_OUT_OF_SERVICE:
                // No network connection.
                return CallStatusCode.OUT_OF_SERVICE;

            default:
                throw new IllegalStateException("Unexpected ServiceState: " + state);
        }
    }

    /**
     * Given an Intent (which is presumably the ACTION_CALL intent that
     * initiated this outgoing call), figure out the actual phone number we
     * should dial.
     *
     * Note that the returned "number" may actually be a SIP address,
     * if the specified intent contains a sip: URI.
     *
     * This method is basically a wrapper around PhoneUtils.getNumberFromIntent(),
     * except it's also aware of the EXTRA_ACTUAL_NUMBER_TO_DIAL extra.
     * (That extra, if present, tells us the exact string to pass down to the
     * telephony layer.  It's guaranteed to be safe to dial: it's either a PSTN
     * phone number with separators and keypad letters stripped out, or a raw
     * unencoded SIP address.)
     *
     * @return the phone number corresponding to the specified Intent, or null
     *   if the Intent has no action or if the intent's data is malformed or
     *   missing.
     *
     * @throws VoiceMailNumberMissingException if the intent
     *   contains a "voicemail" URI, but there's no voicemail
     *   number configured on the device.
     */
    // TODO: Consider moving this out to PhoneUtils and/or combining it
    // with PhoneUtils.getNumberFromIntent().
    public static String getInitialNumber(Intent intent)
            throws PhoneUtils.VoiceMailNumberMissingException {
        if (VDBG) log("getInitialNumber(): " + intent);

        String action = intent.getAction();
        if (TextUtils.isEmpty(action)) {
            return null;
        }

        // If the EXTRA_ACTUAL_NUMBER_TO_DIAL extra is present, get the phone
        // number from there.  (That extra takes precedence over the actual data
        // included in the intent.)
        if (intent.hasExtra(OutgoingCallBroadcaster.EXTRA_ACTUAL_NUMBER_TO_DIAL)) {
            String actualNumberToDial =
                    intent.getStringExtra(OutgoingCallBroadcaster.EXTRA_ACTUAL_NUMBER_TO_DIAL);
            if (VDBG) log("==> got EXTRA_ACTUAL_NUMBER_TO_DIAL; returning '"
                          + actualNumberToDial + "'");
            return actualNumberToDial;
        }

        return PhoneUtils.getNumberFromIntent(PhoneApp.getInstance(), intent);
    }

    /**
     * Handles the various error conditions that can occur when initiating
     * an outgoing call.
     *
     * Most error conditions are "handled" by simply displaying an error
     * message to the user.  This is accomplished by setting the
     * inCallUiState pending call status code flag, which tells the
     * InCallScreen to display an appropriate message to the user when the
     * in-call UI comes to the foreground.
     *
     * @param status one of the CallStatusCode error codes.
     */
    private void handleOutgoingCallError(CallStatusCode status) {
        if (DBG) log("handleOutgoingCallError(): status = " + status);
        final InCallUiState inCallUiState = mApp.inCallUiState;

        // In most cases we simply want to have the InCallScreen display
        // an appropriate error dialog, so we simply copy the specified
        // status code into the InCallUiState "pending call status code"
        // field.  (See InCallScreen.showStatusIndication() for the next
        // step of the sequence.)

        switch (status) {
            case SUCCESS:
                // This case shouldn't happen; you're only supposed to call
                // handleOutgoingCallError() if there was actually an error!
                Log.wtf(TAG, "handleOutgoingCallError: SUCCESS isn't an error");
                break;

            case VOICEMAIL_NUMBER_MISSING:
                // Bring up the "Missing Voicemail Number" dialog, which
                // will ultimately take us to some other Activity (or else
                // just bail out of this activity.)

                // Send a request to the InCallScreen to display the
                // "voicemail missing" dialog when it (the InCallScreen)
                // comes to the foreground.
                inCallUiState.setPendingCallStatusCode(CallStatusCode.VOICEMAIL_NUMBER_MISSING);
                break;

            case POWER_OFF:
                // Radio is explictly powered off, presumably because the
                // device is in airplane mode.
                //
                // TODO: For now this UI is ultra-simple: we simply display
                // a message telling the user to turn off airplane mode.
                // But it might be nicer for the dialog to offer the option
                // to turn the radio on right there (and automatically retry
                // the call once network registration is complete.)
                inCallUiState.setPendingCallStatusCode(CallStatusCode.POWER_OFF);
                break;

            case EMERGENCY_ONLY:
                // Only emergency numbers are allowed, but we tried to dial
                // a non-emergency number.
                // (This state is currently unused; see comments above.)
                inCallUiState.setPendingCallStatusCode(CallStatusCode.EMERGENCY_ONLY);
                break;

            case OUT_OF_SERVICE:
                // No network connection.
                inCallUiState.setPendingCallStatusCode(CallStatusCode.OUT_OF_SERVICE);
                break;

            case NO_PHONE_NUMBER_SUPPLIED:
                // The supplied Intent didn't contain a valid phone number.
                // (This is rare and should only ever happen with broken
                // 3rd-party apps.)  For now just show a generic error.
                inCallUiState.setPendingCallStatusCode(CallStatusCode.NO_PHONE_NUMBER_SUPPLIED);
                break;

            case DIALED_MMI:
                // Our initial phone number was actually an MMI sequence.
                // There's no real "error" here, but we do bring up the
                // a Toast (as requested of the New UI paradigm).
                //
                // In-call MMIs do not trigger the normal MMI Initiate
                // Notifications, so we should notify the user here.
                // Otherwise, the code in PhoneUtils.java should handle
                // user notifications in the form of Toasts or Dialogs.
                //
                // TODO: Rather than launching a toast from here, it would
                // be cleaner to just set a pending call status code here,
                // and then let the InCallScreen display the toast...
                if (mCM.getState() == Phone.State.OFFHOOK) {
                    Toast.makeText(mApp, R.string.incall_status_dialed_mmi, Toast.LENGTH_SHORT)
                            .show();
                }
                break;

            case CALL_FAILED:
                // We couldn't successfully place the call; there was some
                // failure in the telephony layer.
                // TODO: Need UI spec for this failure case; for now just
                // show a generic error.
                inCallUiState.setPendingCallStatusCode(CallStatusCode.CALL_FAILED);
                break;

            default:
                Log.wtf(TAG, "handleOutgoingCallError: unexpected status code " + status);
                // Show a generic "call failed" error.
                inCallUiState.setPendingCallStatusCode(CallStatusCode.CALL_FAILED);
                break;
        }
    }

    /**
     * Checks the current outgoing call to see if it's an OTASP call (the
     * "activation" call used to provision CDMA devices).  If so, do any
     * necessary OTASP-specific setup before actually placing the call.
     */
    private void checkForOtaspCall(Intent intent) {
        if (OtaUtils.isOtaspCallIntent(intent)) {
            Log.i(TAG, "checkForOtaspCall: handling OTASP intent! " + intent);

            // ("OTASP-specific setup" basically means creating and initializing
            // the OtaUtils instance.  Note that this setup needs to be here in
            // the CallController.placeCall() sequence, *not* in
            // OtaUtils.startInteractiveOtasp(), since it's also possible to
            // start an OTASP call by manually dialing "*228" (in which case
            // OtaUtils.startInteractiveOtasp() never gets run at all.)
            OtaUtils.setupOtaspCall(intent);
        } else {
            if (DBG) log("checkForOtaspCall: not an OTASP call.");
        }
    }


    //
    // Debugging
    //

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}

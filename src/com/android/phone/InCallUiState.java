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

import com.android.phone.Constants.CallStatusCode;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;


/**
 * Helper class to keep track of "persistent state" of the in-call UI.
 *
 * The onscreen appearance of the in-call UI mostly depends on the current
 * Call/Connection state, which is owned by the telephony framework.  But
 * there's some application-level "UI state" too, which lives here in the
 * phone app.
 *
 * This application-level state information is *not* maintained by the
 * InCallScreen, since it needs to persist throughout an entire phone call,
 * not just a single resume/pause cycle of the InCallScreen.  So instead, that
 * state is stored here, in a singleton instance of this class.
 *
 * The state kept here is a high-level abstraction of in-call UI state: we
 * don't know about implementation details like specific widgets or strings or
 * resources, but we do understand higher level concepts (for example "is the
 * dialpad visible") and high-level modes (like InCallScreenMode) and error
 * conditions (like CallStatusCode).
 *
 * @see InCallControlState for a separate collection of "UI state" that
 * controls all the onscreen buttons of the in-call UI, based on the state of
 * the telephony layer.
 *
 * The singleton instance of this class is owned by the PhoneApp instance.
 */
public class InCallUiState {
    private static final String TAG = "InCallUiState";

    /** The singleton InCallUiState instance. */
    private static InCallUiState sInstance;

    private Context mContext;

    /**
     * Initialize the singleton InCallUiState instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the InCallUiState instance is available via the
     * PhoneApp's public "inCallUiState" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static InCallUiState init(Context context) {
        synchronized (InCallUiState.class) {
            if (sInstance == null) {
                sInstance = new InCallUiState(context);
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
    private InCallUiState(Context context) {
        mContext = context;
    }


    //
    // (1) High-level state of the whole in-call UI
    //

    /** High-level "modes" of the in-call UI. */
    public enum InCallScreenMode {
        /**
         * Normal in-call UI elements visible.
         */
        NORMAL,
        /**
         * "Manage conference" UI is visible, totally replacing the
         * normal in-call UI.
         */
        MANAGE_CONFERENCE,
        /**
         * Non-interactive UI state.  Call card is visible,
         * displaying information about the call that just ended.
         */
        CALL_ENDED,
        /**
         * Normal OTA in-call UI elements visible.
         */
        OTA_NORMAL,
        /**
         * OTA call ended UI visible, replacing normal OTA in-call UI.
         */
        OTA_ENDED,
        /**
         * Default state when not on call
         */
        UNDEFINED
    }

    /** Current high-level "mode" of the in-call UI. */
    InCallScreenMode inCallScreenMode = InCallScreenMode.UNDEFINED;


    //
    // (2) State of specific UI elements
    //

    /**
     * Is the onscreen twelve-key dialpad visible?
     */
    boolean showDialpad;

    /**
     * The contents of the twelve-key dialpad's "digits" display, which is
     * visible only when the dialpad itself is visible.
     *
     * (This is basically the "history" of DTMF digits you've typed so far
     * in the current call.  It's cleared out any time a new call starts,
     * to make sure the digits don't persist between two separate calls.)
     */
    String dialpadDigits;


    //
    // (3) Error / diagnostic indications
    //

    // This section provides an abstract concept of an "error status
    // indication" for some kind of exceptional condition that needs to be
    // communicated to the user, in the context of the in-call UI.
    //
    // If mPendingCallStatusCode is any value other than SUCCESS, that
    // indicates that the in-call UI needs to display a dialog to the user
    // with the specified title and message text.
    //
    // When an error occurs outside of the InCallScreen itself (like
    // during CallController.placeCall() for example), we inform the user
    // by doing the following steps:
    //
    // (1) set the "pending call status code" to a value other than SUCCESS
    //     (based on the specific error that happened)
    // (2) force the InCallScreen to be launched (or relaunched)
    // (3) InCallScreen.onResume() will notice that pending call status code
    //     is set, and will actually bring up the desired dialog.
    //
    // Watch out: any time you set (or change!) the pending call status code
    // field you must be sure to always (re)launch the InCallScreen.
    //
    // Finally, the InCallScreen itself is responsible for resetting the
    // pending call status code, when the user dismisses the dialog (like by
    // hitting the OK button or pressing Back).  The pending call status code
    // field is NOT cleared simply by the InCallScreen being paused or
    // finished, since the resulting dialog needs to persist across
    // orientation changes or if the screen turns off.

    // TODO: other features we might eventually need here:
    //
    //   - Some error status messages stay in force till reset,
    //     others may automatically clear themselves after
    //     a fixed delay
    //
    //   - Some error statuses may be visible as a dialog with an OK
    //     button (like "call failed"), others may be an indefinite
    //     progress dialog (like "turning on radio for emergency call").
    //
    //   - Eventually some error statuses may have extra actions (like a
    //     "retry call" button that we might provide at the bottom of the
    //     "call failed because you have no signal" dialog.)

    /**
     * The current pending "error status indication" that we need to
     * display to the user.
     *
     * If this field is set to a value other than SUCCESS, this indicates to
     * the InCallScreen that we need to show some kind of message to the user
     * (usually an error dialog) based on the specified status code.
     */
    private CallStatusCode mPendingCallStatusCode = CallStatusCode.SUCCESS;

    /**
     * @return true if there's a pending "error status indication"
     * that we need to display to the user.
     */
    public boolean hasPendingCallStatusCode() {
        return (mPendingCallStatusCode != CallStatusCode.SUCCESS);
    }

    /**
     * @return the pending "error status indication" code
     * that we need to display to the user.
     */
    public CallStatusCode getPendingCallStatusCode() {
        return mPendingCallStatusCode;
    }

    /**
     * Sets the pending "error status indication" code.
     */
    public void setPendingCallStatusCode(CallStatusCode status) {
        if (mPendingCallStatusCode != CallStatusCode.SUCCESS) {
            // Uh oh: mPendingCallStatusCode is already set to some value
            // other than SUCCESS (which indicates that there was some kind of
            // failure), and now we're trying to indicate another (potentially
            // different) failure.  But we can only indicate one failure at a
            // time to the user, so the previous pending code is now going to
            // be lost.
            Log.w(TAG, "setPendingCallStatusCode: setting new code " + status
                  + ", but a previous code " + mPendingCallStatusCode
                  + " was already pending!");
        }
        mPendingCallStatusCode = status;
    }

    /**
     * Clears out the pending "error status indication" code.
     *
     * This indicates that there's no longer any error or "exceptional
     * condition" that needs to be displayed to the user.  (Typically, this
     * method is called when the user dismisses the error dialog that came up
     * because of a previous call status code.)
     */
    public void clearPendingCallStatusCode() {
        mPendingCallStatusCode = CallStatusCode.SUCCESS;
    }

    /**
     * Flag used to control the CDMA-specific "call lost" dialog.
     *
     * If true, that means that if the *next* outgoing call fails with an
     * abnormal disconnection cause, we need to display the "call lost"
     * dialog.  (Normally, in CDMA we handle some types of call failures
     * by automatically retrying the call.  This flag is set to true when
     * we're about to auto-retry, which means that if the *retry* also
     * fails we'll give up and display an error.)
     * See the logic in InCallScreen.onDisconnect() for the full story.
     *
     * TODO: the state machine that maintains the needToShowCallLostDialog
     * flag in InCallScreen.onDisconnect() should really be moved into the
     * CallController.  Then we can get rid of this extra flag, and
     * instead simply use the CallStatusCode value CDMA_CALL_LOST to
     * trigger the "call lost" dialog.
     */
    boolean needToShowCallLostDialog;


    //
    // Progress indications
    //

    /**
     * Possible messages we might need to display along with
     * an indefinite progress spinner.
     */
    public enum ProgressIndicationType {
        /**
         * No progress indication needs to be shown.
         */
        NONE,

        /**
         * Shown when making an emergency call from airplane mode;
         * see CallController$EmergencyCallHelper.
         */
        TURNING_ON_RADIO,

        /**
         * Generic "retrying" state.  (Specifically, this is shown while
         * retrying after an initial failure from the "emergency call from
         * airplane mode" sequence.)
         */
         RETRYING
    }

    /**
     * The current progress indication that should be shown
     * to the user.  Any value other than NONE will cause the InCallScreen
     * to bring up an indefinite progress spinner along with a message
     * corresponding to the specified ProgressIndicationType.
     */
    private ProgressIndicationType progressIndication = ProgressIndicationType.NONE;

    /** Sets the current progressIndication. */
    public void setProgressIndication(ProgressIndicationType value) {
        progressIndication = value;
    }

    /** Clears the current progressIndication. */
    public void clearProgressIndication() {
        progressIndication = ProgressIndicationType.NONE;
    }

    /**
     * @return the current progress indication type, or ProgressIndicationType.NONE
     * if no progress indication is currently active.
     */
    public ProgressIndicationType getProgressIndication() {
        return progressIndication;
    }

    /** @return true if a progress indication is currently active. */
    public boolean isProgressIndicationActive() {
        return (progressIndication != ProgressIndicationType.NONE);
    }


    //
    // (4) Optional overlay when a 3rd party "provider" is used.
    //     @see InCallScreen.updateProviderOverlay()
    //

    // TODO: maybe isolate all the provider-overlay-related stuff out to a
    //       separate inner class?
    boolean providerOverlayVisible;
    CharSequence providerLabel;
    Drawable providerIcon;
    Uri providerGatewayUri;
    // The formatted address extracted from mProviderGatewayUri. User visible.
    String providerAddress;

    /**
     * Set the fields related to the provider support
     * based on the specified intent.
     */
    public void setProviderOverlayInfo(Intent intent) {
        providerLabel = PhoneUtils.getProviderLabel(mContext, intent);
        providerIcon = PhoneUtils.getProviderIcon(mContext, intent);
        providerGatewayUri = PhoneUtils.getProviderGatewayUri(intent);
        providerAddress = PhoneUtils.formatProviderUri(providerGatewayUri);
        providerOverlayVisible = true;

        // ...but if any of the "required" fields are missing, completely
        // disable the overlay.
        if (TextUtils.isEmpty(providerLabel) || providerIcon == null ||
            providerGatewayUri == null || TextUtils.isEmpty(providerAddress)) {
            clearProviderOverlayInfo();
        }
    }

    /**
     * Clear all the fields related to the provider support.
     */
    public void clearProviderOverlayInfo() {
        providerOverlayVisible = false;
        providerLabel = null;
        providerIcon = null;
        providerGatewayUri = null;
        providerAddress = null;
    }

    /**
     * "Call origin" of the most recent phone call.
     *
     * Watch out: right now this is only used to determine where the user should go after the phone
     * call. See also {@link InCallScreen} for more detail. There is *no* specific specification
     * about how this variable will be used.
     *
     * @see PhoneApp#setLatestActiveCallOrigin(String)
     * @see PhoneApp#createPhoneEndIntentUsingCallOrigin()
     *
     * TODO: we should determine some public behavior for this variable.
     */
    String latestActiveCallOrigin;

    //
    // Debugging
    //

    public void dumpState() {
        Log.d(TAG, "dumpState():");
        Log.d(TAG, "  - showDialpad: " + showDialpad);
        if (hasPendingCallStatusCode()) {
            Log.d(TAG, "  - status indication is pending!");
            Log.d(TAG, "    - pending call status code = " + mPendingCallStatusCode);
        } else {
            Log.d(TAG, "  - pending call status code: none");
        }
        Log.d(TAG, "  - progressIndication: " + progressIndication);
        if (providerOverlayVisible) {
            Log.d(TAG, "  - provider overlay VISIBLE: "
                  + providerLabel + " / "
                  + providerIcon  + " / "
                  + providerGatewayUri + " / "
                  + providerAddress);
        } else {
            Log.d(TAG, "  - provider overlay: none");
        }
        Log.d(TAG, "  - latestActiveCallOrigin: " + latestActiveCallOrigin);
    }
}

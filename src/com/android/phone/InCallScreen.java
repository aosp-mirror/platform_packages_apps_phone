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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.text.method.DialerKeyListener;
import android.util.EventLog;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.phone.Constants.CallStatusCode;
import com.android.phone.InCallUiState.InCallScreenMode;
import com.android.phone.OtaUtils.CdmaOtaInCallScreenUiState;
import com.android.phone.OtaUtils.CdmaOtaScreenState;

import java.util.List;


/**
 * Phone app "in call" screen.
 */
public class InCallScreen extends Activity
        implements View.OnClickListener {
    private static final String LOG_TAG = "InCallScreen";

    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneApp.DBG_LEVEL >= 2);

    /**
     * Intent extra used to specify whether the DTMF dialpad should be
     * initially visible when bringing up the InCallScreen.  (If this
     * extra is present, the dialpad will be initially shown if the extra
     * has the boolean value true, and initially hidden otherwise.)
     */
    // TODO: Should be EXTRA_SHOW_DIALPAD for consistency.
    static final String SHOW_DIALPAD_EXTRA = "com.android.phone.ShowDialpad";

    /**
     * Intent extra to specify the package name of the gateway
     * provider.  Used to get the name displayed in the in-call screen
     * during the call setup. The value is a string.
     */
    // TODO: This extra is currently set by the gateway application as
    // a temporary measure. Ultimately, the framework will securely
    // set it.
    /* package */ static final String EXTRA_GATEWAY_PROVIDER_PACKAGE =
            "com.android.phone.extra.GATEWAY_PROVIDER_PACKAGE";

    /**
     * Intent extra to specify the URI of the provider to place the
     * call. The value is a string. It holds the gateway address
     * (phone gateway URL should start with the 'tel:' scheme) that
     * will actually be contacted to call the number passed in the
     * intent URL or in the EXTRA_PHONE_NUMBER extra.
     */
    // TODO: Should the value be a Uri (Parcelable)? Need to make sure
    // MMI code '#' don't get confused as URI fragments.
    /* package */ static final String EXTRA_GATEWAY_URI =
            "com.android.phone.extra.GATEWAY_URI";

    // Amount of time (in msec) that we display the "Call ended" state.
    // The "short" value is for calls ended by the local user, and the
    // "long" value is for calls ended by the remote caller.
    private static final int CALL_ENDED_SHORT_DELAY =  200;  // msec
    private static final int CALL_ENDED_LONG_DELAY = 2000;  // msec

    // Amount of time that we display the PAUSE alert Dialog showing the
    // post dial string yet to be send out to the n/w
    private static final int PAUSE_PROMPT_DIALOG_TIMEOUT = 2000;  //msec

    // Amount of time that we display the provider's overlay if applicable.
    private static final int PROVIDER_OVERLAY_TIMEOUT = 5000;  // msec

    // These are values for the settings of the auto retry mode:
    // 0 = disabled
    // 1 = enabled
    // TODO (Moto):These constants don't really belong here,
    // they should be moved to Settings where the value is being looked up in the first place
    static final int AUTO_RETRY_OFF = 0;
    static final int AUTO_RETRY_ON = 1;

    // Message codes; see mHandler below.
    // Note message codes < 100 are reserved for the PhoneApp.
    private static final int PHONE_STATE_CHANGED = 101;
    private static final int PHONE_DISCONNECT = 102;
    private static final int EVENT_HEADSET_PLUG_STATE_CHANGED = 103;
    private static final int POST_ON_DIAL_CHARS = 104;
    private static final int WILD_PROMPT_CHAR_ENTERED = 105;
    private static final int ADD_VOICEMAIL_NUMBER = 106;
    private static final int DONT_ADD_VOICEMAIL_NUMBER = 107;
    private static final int DELAYED_CLEANUP_AFTER_DISCONNECT = 108;
    private static final int SUPP_SERVICE_FAILED = 110;
    private static final int ALLOW_SCREEN_ON = 112;
    private static final int REQUEST_UPDATE_BLUETOOTH_INDICATION = 114;
    private static final int PHONE_CDMA_CALL_WAITING = 115;
    private static final int REQUEST_CLOSE_SPC_ERROR_NOTICE = 118;
    private static final int REQUEST_CLOSE_OTA_FAILURE_NOTICE = 119;
    private static final int EVENT_PAUSE_DIALOG_COMPLETE = 120;
    private static final int EVENT_HIDE_PROVIDER_OVERLAY = 121;  // Time to remove the overlay.
    private static final int REQUEST_UPDATE_SCREEN = 122;
    private static final int PHONE_INCOMING_RING = 123;
    private static final int PHONE_NEW_RINGING_CONNECTION = 124;

    // When InCallScreenMode is UNDEFINED set the default action
    // to ACTION_UNDEFINED so if we are resumed the activity will
    // know its undefined. In particular checkIsOtaCall will return
    // false.
    public static final String ACTION_UNDEFINED = "com.android.phone.InCallScreen.UNDEFINED";

    /** Status codes returned from syncWithPhoneState(). */
    private enum SyncWithPhoneStateStatus {
        /**
         * Successfully updated our internal state based on the telephony state.
         */
        SUCCESS,

        /**
         * There was no phone state to sync with (i.e. the phone was
         * completely idle).  In most cases this means that the
         * in-call UI shouldn't be visible in the first place, unless
         * we need to remain in the foreground while displaying an
         * error message.
         */
        PHONE_NOT_IN_USE
    }

    private boolean mRegisteredForPhoneStates;

    private PhoneApp mApp;
    private CallManager mCM;

    // TODO: need to clean up all remaining uses of mPhone.
    // (There may be more than one Phone instance on the device, so it's wrong
    // to just keep a single mPhone field.  Instead, any time we need a Phone
    // reference we should get it dynamically from the CallManager, probably
    // based on the current foreground Call.)
    private Phone mPhone;

    private BluetoothHandsfree mBluetoothHandsfree;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothAdapter mAdapter;
    private boolean mBluetoothConnectionPending;
    private long mBluetoothConnectionRequestTime;

    // Main in-call UI ViewGroups
    private ViewGroup mInCallPanel;

    // Main in-call UI elements:
    private CallCard mCallCard;

    // UI controls:
    private InCallControlState mInCallControlState;
    private InCallTouchUi mInCallTouchUi;
    private RespondViaSmsManager mRespondViaSmsManager;  // see internalRespondViaSms()
    private ManageConferenceUtils mManageConferenceUtils;

    // DTMF Dialer controller and its view:
    private DTMFTwelveKeyDialer mDialer;
    private DTMFTwelveKeyDialerView mDialerView;

    private EditText mWildPromptText;

    // Various dialogs we bring up (see dismissAllDialogs()).
    // TODO: convert these all to use the "managed dialogs" framework.
    //
    // The MMI started dialog can actually be one of 2 items:
    //   1. An alert dialog if the MMI code is a normal MMI
    //   2. A progress dialog if the user requested a USSD
    private Dialog mMmiStartedDialog;
    private AlertDialog mMissingVoicemailDialog;
    private AlertDialog mGenericErrorDialog;
    private AlertDialog mSuppServiceFailureDialog;
    private AlertDialog mWaitPromptDialog;
    private AlertDialog mWildPromptDialog;
    private AlertDialog mCallLostDialog;
    private AlertDialog mPausePromptDialog;
    private AlertDialog mExitingECMDialog;
    // NOTE: if you add a new dialog here, be sure to add it to dismissAllDialogs() also.

    // ProgressDialog created by showProgressIndication()
    private ProgressDialog mProgressDialog;

    // TODO: If the Activity class ever provides an easy way to get the
    // current "activity lifecycle" state, we can remove these flags.
    private boolean mIsDestroyed = false;
    private boolean mIsForegroundActivity = false;

    // For use with Pause/Wait dialogs
    private String mPostDialStrAfterPause;
    private boolean mPauseInProgress = false;

    // Info about the most-recently-disconnected Connection, which is used
    // to determine what should happen when exiting the InCallScreen after a
    // call.  (This info is set by onDisconnect(), and used by
    // delayedCleanupAfterDisconnect().)
    private Connection.DisconnectCause mLastDisconnectCause;

    /** In-call audio routing options; see switchInCallAudio(). */
    public enum InCallAudioMode {
        SPEAKER,    // Speakerphone
        BLUETOOTH,  // Bluetooth headset (if available)
        EARPIECE,   // Handset earpiece (or wired headset, if connected)
    }


    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mIsDestroyed) {
                if (DBG) log("Handler: ignoring message " + msg + "; we're destroyed!");
                return;
            }
            if (!mIsForegroundActivity) {
                if (DBG) log("Handler: handling message " + msg + " while not in foreground");
                // Continue anyway; some of the messages below *want* to
                // be handled even if we're not the foreground activity
                // (like DELAYED_CLEANUP_AFTER_DISCONNECT), and they all
                // should at least be safe to handle if we're not in the
                // foreground...
            }

            switch (msg.what) {
                case SUPP_SERVICE_FAILED:
                    onSuppServiceFailed((AsyncResult) msg.obj);
                    break;

                case PHONE_STATE_CHANGED:
                    onPhoneStateChanged((AsyncResult) msg.obj);
                    break;

                case PHONE_DISCONNECT:
                    onDisconnect((AsyncResult) msg.obj);
                    break;

                case EVENT_HEADSET_PLUG_STATE_CHANGED:
                    // Update the in-call UI, since some UI elements (such
                    // as the "Speaker" button) may change state depending on
                    // whether a headset is plugged in.
                    // TODO: A full updateScreen() is overkill here, since
                    // the value of PhoneApp.isHeadsetPlugged() only affects a
                    // single onscreen UI element.  (But even a full updateScreen()
                    // is still pretty cheap, so let's keep this simple
                    // for now.)
                    updateScreen();

                    // Also, force the "audio mode" popup to refresh itself if
                    // it's visible, since one of its items is either "Wired
                    // headset" or "Handset earpiece" depending on whether the
                    // headset is plugged in or not.
                    mInCallTouchUi.refreshAudioModePopup();  // safe even if the popup's not active

                    break;

                case PhoneApp.MMI_INITIATE:
                    onMMIInitiate((AsyncResult) msg.obj);
                    break;

                case PhoneApp.MMI_CANCEL:
                    onMMICancel();
                    break;

                // handle the mmi complete message.
                // since the message display class has been replaced with
                // a system dialog in PhoneUtils.displayMMIComplete(), we
                // should finish the activity here to close the window.
                case PhoneApp.MMI_COMPLETE:
                    // Check the code to see if the request is ready to
                    // finish, this includes any MMI state that is not
                    // PENDING.
                    MmiCode mmiCode = (MmiCode) ((AsyncResult) msg.obj).result;
                    // if phone is a CDMA phone display feature code completed message
                    int phoneType = mPhone.getPhoneType();
                    if (phoneType == Phone.PHONE_TYPE_CDMA) {
                        PhoneUtils.displayMMIComplete(mPhone, mApp, mmiCode, null, null);
                    } else if (phoneType == Phone.PHONE_TYPE_GSM) {
                        if (mmiCode.getState() != MmiCode.State.PENDING) {
                            if (DBG) log("Got MMI_COMPLETE, finishing InCallScreen...");
                            endInCallScreenSession();
                        }
                    }
                    break;

                case POST_ON_DIAL_CHARS:
                    handlePostOnDialChars((AsyncResult) msg.obj, (char) msg.arg1);
                    break;

                case ADD_VOICEMAIL_NUMBER:
                    addVoiceMailNumberPanel();
                    break;

                case DONT_ADD_VOICEMAIL_NUMBER:
                    dontAddVoiceMailNumber();
                    break;

                case DELAYED_CLEANUP_AFTER_DISCONNECT:
                    delayedCleanupAfterDisconnect();
                    break;

                case ALLOW_SCREEN_ON:
                    if (VDBG) log("ALLOW_SCREEN_ON message...");
                    // Undo our previous call to preventScreenOn(true).
                    // (Note this will cause the screen to turn on
                    // immediately, if it's currently off because of a
                    // prior preventScreenOn(true) call.)
                    mApp.preventScreenOn(false);
                    break;

                case REQUEST_UPDATE_BLUETOOTH_INDICATION:
                    if (VDBG) log("REQUEST_UPDATE_BLUETOOTH_INDICATION...");
                    // The bluetooth headset state changed, so some UI
                    // elements may need to update.  (There's no need to
                    // look up the current state here, since any UI
                    // elements that care about the bluetooth state get it
                    // directly from PhoneApp.showBluetoothIndication().)
                    updateScreen();
                    break;

                case PHONE_CDMA_CALL_WAITING:
                    if (DBG) log("Received PHONE_CDMA_CALL_WAITING event ...");
                    Connection cn = mCM.getFirstActiveRingingCall().getLatestConnection();

                    // Only proceed if we get a valid connection object
                    if (cn != null) {
                        // Finally update screen with Call waiting info and request
                        // screen to wake up
                        updateScreen();
                        mApp.updateWakeState();
                    }
                    break;

                case REQUEST_CLOSE_SPC_ERROR_NOTICE:
                    if (mApp.otaUtils != null) {
                        mApp.otaUtils.onOtaCloseSpcNotice();
                    }
                    break;

                case REQUEST_CLOSE_OTA_FAILURE_NOTICE:
                    if (mApp.otaUtils != null) {
                        mApp.otaUtils.onOtaCloseFailureNotice();
                    }
                    break;

                case EVENT_PAUSE_DIALOG_COMPLETE:
                    if (mPausePromptDialog != null) {
                        if (DBG) log("- DISMISSING mPausePromptDialog.");
                        mPausePromptDialog.dismiss();  // safe even if already dismissed
                        mPausePromptDialog = null;
                    }
                    break;

                case EVENT_HIDE_PROVIDER_OVERLAY:
                    mApp.inCallUiState.providerOverlayVisible = false;
                    updateProviderOverlay();  // Clear the overlay.
                    break;

                case REQUEST_UPDATE_SCREEN:
                    updateScreen();
                    break;

                case PHONE_INCOMING_RING:
                    onIncomingRing();
                    break;

                case PHONE_NEW_RINGING_CONNECTION:
                    onNewRingingConnection();
                    break;

                default:
                    Log.wtf(LOG_TAG, "mHandler: unexpected message: " + msg);
                    break;
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                    // Listen for ACTION_HEADSET_PLUG broadcasts so that we
                    // can update the onscreen UI when the headset state changes.
                    // if (DBG) log("mReceiver: ACTION_HEADSET_PLUG");
                    // if (DBG) log("==> intent: " + intent);
                    // if (DBG) log("    state: " + intent.getIntExtra("state", 0));
                    // if (DBG) log("    name: " + intent.getStringExtra("name"));
                    // send the event and add the state as an argument.
                    Message message = Message.obtain(mHandler, EVENT_HEADSET_PLUG_STATE_CHANGED,
                            intent.getIntExtra("state", 0), 0);
                    mHandler.sendMessage(message);
                }
            }
        };


    @Override
    protected void onCreate(Bundle icicle) {
        Log.i(LOG_TAG, "onCreate()...  this = " + this);
        Profiler.callScreenOnCreate();
        super.onCreate(icicle);

        // Make sure this is a voice-capable device.
        if (!PhoneApp.sVoiceCapable) {
            // There should be no way to ever reach the InCallScreen on a
            // non-voice-capable device, since this activity is not exported by
            // our manifest, and we explicitly disable any other external APIs
            // like the CALL intent and ITelephony.showCallScreen().
            // So the fact that we got here indicates a phone app bug.
            Log.wtf(LOG_TAG, "onCreate() reached on non-voice-capable device");
            finish();
            return;
        }

        mApp = PhoneApp.getInstance();
        mApp.setInCallScreenInstance(this);

        // set this flag so this activity will stay in front of the keyguard
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        if (mApp.getPhoneState() == Phone.State.OFFHOOK) {
            // While we are in call, the in-call screen should dismiss the keyguard.
            // This allows the user to press Home to go directly home without going through
            // an insecure lock screen.
            // But we do not want to do this if there is no active call so we do not
            // bypass the keyguard if the call is not answered or declined.
            flags |= WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
        }
        getWindow().addFlags(flags);

        // Also put the system bar (if present on this device) into
        // "lights out" mode any time we're the foreground activity.
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE;
        getWindow().setAttributes(params);

        setPhone(mApp.phone);  // Sets mPhone

        mCM =  mApp.mCM;
        log("- onCreate: phone state = " + mCM.getState());

        mBluetoothHandsfree = mApp.getBluetoothHandsfree();
        if (VDBG) log("- mBluetoothHandsfree: " + mBluetoothHandsfree);

        if (mBluetoothHandsfree != null) {
            // The PhoneApp only creates a BluetoothHandsfree instance in the
            // first place if BluetoothAdapter.getDefaultAdapter()
            // succeeds.  So at this point we know the device is BT-capable.
            mAdapter = BluetoothAdapter.getDefaultAdapter();
            mAdapter.getProfileProxy(getApplicationContext(), mBluetoothProfileServiceListener,
                                    BluetoothProfile.HEADSET);

        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Inflate everything in incall_screen.xml and add it to the screen.
        setContentView(R.layout.incall_screen);

        initInCallScreen();

        registerForPhoneStates();

        // No need to change wake state here; that happens in onResume() when we
        // are actually displayed.

        // Handle the Intent we were launched with, but only if this is the
        // the very first time we're being launched (ie. NOT if we're being
        // re-initialized after previously being shut down.)
        // Once we're up and running, any future Intents we need
        // to handle will come in via the onNewIntent() method.
        if (icicle == null) {
            if (DBG) log("onCreate(): this is our very first launch, checking intent...");
            internalResolveIntent(getIntent());
        }

        Profiler.callScreenCreated();
        if (DBG) log("onCreate(): exit");
    }

     private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mBluetoothHeadset = (BluetoothHeadset) proxy;
            if (VDBG) log("- Got BluetoothHeadset: " + mBluetoothHeadset);
        }

        public void onServiceDisconnected(int profile) {
            mBluetoothHeadset = null;
        }
    };

    /**
     * Sets the Phone object used internally by the InCallScreen.
     *
     * In normal operation this is called from onCreate(), and the
     * passed-in Phone object comes from the PhoneApp.
     * For testing, test classes can use this method to
     * inject a test Phone instance.
     */
    /* package */ void setPhone(Phone phone) {
        mPhone = phone;
    }

    @Override
    protected void onResume() {
        if (DBG) log("onResume()...");
        super.onResume();

        mIsForegroundActivity = true;

        final InCallUiState inCallUiState = mApp.inCallUiState;
        if (VDBG) inCallUiState.dumpState();

        // Touch events are never considered "user activity" while the
        // InCallScreen is active, so that unintentional touches won't
        // prevent the device from going to sleep.
        mApp.setIgnoreTouchUserActivity(true);

        // Disable the status bar "window shade" the entire time we're on
        // the in-call screen.
        mApp.notificationMgr.statusBarHelper.enableExpandedView(false);
        // ...and update the in-call notification too, since the status bar
        // icon needs to be hidden while we're the foreground activity:
        mApp.notificationMgr.updateInCallNotification();

        // Listen for broadcast intents that might affect the onscreen UI.
        registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));

        // Keep a "dialer session" active when we're in the foreground.
        // (This is needed to play DTMF tones.)
        mDialer.startDialerSession();

        // Restore various other state from the InCallUiState object:

        // Update the onscreen dialpad state to match the InCallUiState.
        if (inCallUiState.showDialpad) {
            showDialpadInternal(false);  // no "opening" animation
        } else {
            hideDialpadInternal(false);  // no "closing" animation
        }
        //
        // TODO: also need to load inCallUiState.dialpadDigits into the dialpad

        // If there's a "Respond via SMS" popup still around since the
        // last time we were the foreground activity, make sure it's not
        // still active!
        // (The popup should *never* be visible initially when we first
        // come to the foreground; it only ever comes up in response to
        // the user selecting the "SMS" option from the incoming call
        // widget.)
        if (mRespondViaSmsManager != null) {
            mRespondViaSmsManager.dismissPopup();  // safe even if already dismissed
        }

        // Display an error / diagnostic indication if necessary.
        //
        // When the InCallScreen comes to the foreground, we normally we
        // display the in-call UI in whatever state is appropriate based on
        // the state of the telephony framework (e.g. an outgoing call in
        // DIALING state, an incoming call, etc.)
        //
        // But if the InCallUiState has a "pending call status code" set,
        // that means we need to display some kind of status or error
        // indication to the user instead of the regular in-call UI.  (The
        // most common example of this is when there's some kind of
        // failure while initiating an outgoing call; see
        // CallController.placeCall().)
        //
        boolean handledStartupError = false;
        if (inCallUiState.hasPendingCallStatusCode()) {
            if (DBG) log("- onResume: need to show status indication!");
            showStatusIndication(inCallUiState.getPendingCallStatusCode());

            // Set handledStartupError to ensure that we won't bail out below.
            // (We need to stay here in the InCallScreen so that the user
            // is able to see the error dialog!)
            handledStartupError = true;
        }

        // Set the volume control handler while we are in the foreground.
        final boolean bluetoothConnected = isBluetoothAudioConnected();

        if (bluetoothConnected) {
            setVolumeControlStream(AudioManager.STREAM_BLUETOOTH_SCO);
        } else {
            setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        }

        takeKeyEvents(true);

        // If an OTASP call is in progress, use the special OTASP-specific UI.
        boolean inOtaCall = false;
        if (TelephonyCapabilities.supportsOtasp(mPhone)) {
            inOtaCall = checkOtaspStateOnResume();
        }
        if (!inOtaCall) {
            // Always start off in NORMAL mode
            setInCallScreenMode(InCallScreenMode.NORMAL);
        }

        // Before checking the state of the CallManager, clean up any
        // connections in the DISCONNECTED state.
        // (The DISCONNECTED state is used only to drive the "call ended"
        // UI; it's totally useless when *entering* the InCallScreen.)
        mCM.clearDisconnected();

        // Update the onscreen UI to reflect the current telephony state.
        SyncWithPhoneStateStatus status = syncWithPhoneState();

        // Note there's no need to call updateScreen() here;
        // syncWithPhoneState() already did that if necessary.

        if (status != SyncWithPhoneStateStatus.SUCCESS) {
            if (DBG) log("- onResume: syncWithPhoneState failed! status = " + status);
            // Couldn't update the UI, presumably because the phone is totally
            // idle.

            // Even though the phone is idle, though, we do still need to
            // stay here on the InCallScreen if we're displaying an
            // error dialog (see "showStatusIndication()" above).

            if (handledStartupError) {
                // Stay here for now.  We'll eventually leave the
                // InCallScreen when the user presses the dialog's OK
                // button (see bailOutAfterErrorDialog()), or when the
                // progress indicator goes away.
                Log.i(LOG_TAG, "  ==> syncWithPhoneState failed, but staying here anyway.");
            } else {
                // The phone is idle, and we did NOT handle a
                // startup error during this pass thru onResume.
                //
                // This basically means that we're being resumed because of
                // some action *other* than a new intent.  (For example,
                // the user pressing POWER to wake up the device, causing
                // the InCallScreen to come back to the foreground.)
                //
                // In this scenario we do NOT want to stay here on the
                // InCallScreen: we're not showing any useful info to the
                // user (like a dialog), and the in-call UI itself is
                // useless if there's no active call.  So bail out.

                Log.i(LOG_TAG, "  ==> syncWithPhoneState failed; bailing out!");
                dismissAllDialogs();

                // Force the InCallScreen to truly finish(), rather than just
                // moving it to the back of the activity stack (which is what
                // our finish() method usually does.)
                // This is necessary to avoid an obscure scenario where the
                // InCallScreen can get stuck in an inconsistent state, somehow
                // causing a *subsequent* outgoing call to fail (bug 4172599).
                endInCallScreenSession(true /* force a real finish() call */);
                return;
            }
        } else if (TelephonyCapabilities.supportsOtasp(mPhone)) {
            if (inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL ||
                    inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED) {
                if (mInCallPanel != null) mInCallPanel.setVisibility(View.GONE);
                updateScreen();
                return;
            }
        }

        // InCallScreen is now active.
        EventLog.writeEvent(EventLogTags.PHONE_UI_ENTER);

        // Update the poke lock and wake lock when we move to
        // the foreground.
        //
        // But we need to do something special if we're coming
        // to the foreground while an incoming call is ringing:
        if (mCM.getState() == Phone.State.RINGING) {
            // If the phone is ringing, we *should* already be holding a
            // full wake lock (which we would have acquired before
            // firing off the intent that brought us here; see
            // CallNotifier.showIncomingCall().)
            //
            // We also called preventScreenOn(true) at that point, to
            // avoid cosmetic glitches while we were being launched.
            // So now we need to post an ALLOW_SCREEN_ON message to
            // (eventually) undo the prior preventScreenOn(true) call.
            //
            // (In principle we shouldn't do this until after our first
            // layout/draw pass.  But in practice, the delay caused by
            // simply waiting for the end of the message queue is long
            // enough to avoid any flickering of the lock screen before
            // the InCallScreen comes up.)
            if (VDBG) log("- posting ALLOW_SCREEN_ON message...");
            mHandler.removeMessages(ALLOW_SCREEN_ON);
            mHandler.sendEmptyMessage(ALLOW_SCREEN_ON);

            // TODO: There ought to be a more elegant way of doing this,
            // probably by having the PowerManager and ActivityManager
            // work together to let apps request that the screen on/off
            // state be synchronized with the Activity lifecycle.
            // (See bug 1648751.)
        } else {
            // The phone isn't ringing; this is either an outgoing call, or
            // we're returning to a call in progress.  There *shouldn't* be
            // any prior preventScreenOn(true) call that we need to undo,
            // but let's do this just to be safe:
            mApp.preventScreenOn(false);
        }
        mApp.updateWakeState();

        // Restore the mute state if the last mute state change was NOT
        // done by the user.
        if (mApp.getRestoreMuteOnInCallResume()) {
            // Mute state is based on the foreground call
            PhoneUtils.restoreMuteState();
            mApp.setRestoreMuteOnInCallResume(false);
        }

        Profiler.profileViewCreate(getWindow(), InCallScreen.class.getName());
        if (VDBG) log("onResume() done.");
    }

    // onPause is guaranteed to be called when the InCallScreen goes
    // in the background.
    @Override
    protected void onPause() {
        if (DBG) log("onPause()...");
        super.onPause();

        mIsForegroundActivity = false;

        // Force a clear of the provider overlay' frame. Since the
        // overlay is removed using a timed message, it is
        // possible we missed it if the prev call was interrupted.
        mApp.inCallUiState.providerOverlayVisible = false;
        updateProviderOverlay();

        // A safety measure to disable proximity sensor in case call failed
        // and the telephony state did not change.
        mApp.setBeginningCall(false);

        // Make sure the "Manage conference" chronometer is stopped when
        // we move away from the foreground.
        mManageConferenceUtils.stopConferenceTime();

        // as a catch-all, make sure that any dtmf tones are stopped
        // when the UI is no longer in the foreground.
        mDialer.onDialerKeyUp(null);

        // Release any "dialer session" resources, now that we're no
        // longer in the foreground.
        mDialer.stopDialerSession();

        // If the device is put to sleep as the phone call is ending,
        // we may see cases where the DELAYED_CLEANUP_AFTER_DISCONNECT
        // event gets handled AFTER the device goes to sleep and wakes
        // up again.

        // This is because it is possible for a sleep command
        // (executed with the End Call key) to come during the 2
        // seconds that the "Call Ended" screen is up.  Sleep then
        // pauses the device (including the cleanup event) and
        // resumes the event when it wakes up.

        // To fix this, we introduce a bit of code that pushes the UI
        // to the background if we pause and see a request to
        // DELAYED_CLEANUP_AFTER_DISCONNECT.

        // Note: We can try to finish directly, by:
        //  1. Removing the DELAYED_CLEANUP_AFTER_DISCONNECT messages
        //  2. Calling delayedCleanupAfterDisconnect directly

        // However, doing so can cause problems between the phone
        // app and the keyguard - the keyguard is trying to sleep at
        // the same time that the phone state is changing.  This can
        // end up causing the sleep request to be ignored.
        if (mHandler.hasMessages(DELAYED_CLEANUP_AFTER_DISCONNECT)
                && mCM.getState() != Phone.State.RINGING) {
            log("DELAYED_CLEANUP_AFTER_DISCONNECT detected, moving UI to background.");
            endInCallScreenSession();
        }

        EventLog.writeEvent(EventLogTags.PHONE_UI_EXIT);

        // Dismiss any dialogs we may have brought up, just to be 100%
        // sure they won't still be around when we get back here.
        dismissAllDialogs();

        // Re-enable the status bar (which we disabled in onResume().)
        mApp.notificationMgr.statusBarHelper.enableExpandedView(true);
        // ...and the in-call notification too:
        mApp.notificationMgr.updateInCallNotification();
        // ...and *always* reset the system bar back to its normal state
        // when leaving the in-call UI.
        // (While we're the foreground activity, we disable navigation in
        // some call states; see InCallTouchUi.updateState().)
        mApp.notificationMgr.statusBarHelper.enableSystemBarNavigation(true);

        // Unregister for broadcast intents.  (These affect the visible UI
        // of the InCallScreen, so we only care about them while we're in the
        // foreground.)
        unregisterReceiver(mReceiver);

        // Re-enable "user activity" for touch events.
        // We actually do this slightly *after* onPause(), to work around a
        // race condition where a touch can come in after we've paused
        // but before the device actually goes to sleep.
        // TODO: The PowerManager itself should prevent this from happening.
        mHandler.postDelayed(new Runnable() {
                public void run() {
                    mApp.setIgnoreTouchUserActivity(false);
                }
            }, 500);

        // Make sure we revert the poke lock and wake lock when we move to
        // the background.
        mApp.updateWakeState();

        // clear the dismiss keyguard flag so we are back to the default state
        // when we next resume
        updateKeyguardPolicy(false);
    }

    @Override
    protected void onStop() {
        if (DBG) log("onStop()...");
        super.onStop();

        stopTimer();

        Phone.State state = mCM.getState();
        if (DBG) log("onStop: state = " + state);

        if (state == Phone.State.IDLE) {
            // when OTA Activation, OTA Success/Failure dialog or OTA SPC
            // failure dialog is running, do not destroy inCallScreen. Because call
            // is already ended and dialog will not get redrawn on slider event.
            if ((mApp.cdmaOtaProvisionData != null) && (mApp.cdmaOtaScreenState != null)
                    && ((mApp.cdmaOtaScreenState.otaScreenState !=
                            CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION)
                        && (mApp.cdmaOtaScreenState.otaScreenState !=
                            CdmaOtaScreenState.OtaScreenState.OTA_STATUS_SUCCESS_FAILURE_DLG)
                        && (!mApp.cdmaOtaProvisionData.inOtaSpcState))) {
                // we don't want the call screen to remain in the activity history
                // if there are not active or ringing calls.
                if (DBG) log("- onStop: calling finish() to clear activity history...");
                moveTaskToBack(true);
                if (mApp.otaUtils != null) {
                    mApp.otaUtils.cleanOtaScreen(true);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        Log.i(LOG_TAG, "onDestroy()...  this = " + this);
        super.onDestroy();

        // Set the magic flag that tells us NOT to handle any handler
        // messages that come in asynchronously after we get destroyed.
        mIsDestroyed = true;

        mApp.setInCallScreenInstance(null);

        // Clear out the InCallScreen references in various helper objects
        // (to let them know we've been destroyed).
        if (mCallCard != null) {
            mCallCard.setInCallScreenInstance(null);
        }
        if (mInCallTouchUi != null) {
            mInCallTouchUi.setInCallScreenInstance(null);
        }
        if (mRespondViaSmsManager != null) {
            mRespondViaSmsManager.setInCallScreenInstance(null);
        }

        mDialer.clearInCallScreenReference();
        mDialer = null;

        unregisterForPhoneStates();
        // No need to change wake state here; that happens in onPause() when we
        // are moving out of the foreground.

        if (mBluetoothHeadset != null) {
            mAdapter.closeProfileProxy(BluetoothProfile.HEADSET, mBluetoothHeadset);
            mBluetoothHeadset = null;
        }

        // Dismiss all dialogs, to be absolutely sure we won't leak any of
        // them while changing orientation.
        dismissAllDialogs();

        // If there's an OtaUtils instance around, clear out its
        // references to our internal widgets.
        if (mApp.otaUtils != null) {
            mApp.otaUtils.clearUiWidgets();
        }
    }

    /**
     * Dismisses the in-call screen.
     *
     * We never *really* finish() the InCallScreen, since we don't want to
     * get destroyed and then have to be re-created from scratch for the
     * next call.  Instead, we just move ourselves to the back of the
     * activity stack.
     *
     * This also means that we'll no longer be reachable via the BACK
     * button (since moveTaskToBack() puts us behind the Home app, but the
     * home app doesn't allow the BACK key to move you any farther down in
     * the history stack.)
     *
     * (Since the Phone app itself is never killed, this basically means
     * that we'll keep a single InCallScreen instance around for the
     * entire uptime of the device.  This noticeably improves the UI
     * responsiveness for incoming calls.)
     */
    @Override
    public void finish() {
        if (DBG) log("finish()...");
        moveTaskToBack(true);
    }

    /**
     * End the current in call screen session.
     *
     * This must be called when an InCallScreen session has
     * complete so that the next invocation via an onResume will
     * not be in an old state.
     */
    public void endInCallScreenSession() {
        if (DBG) log("endInCallScreenSession()... phone state = " + mCM.getState());
        endInCallScreenSession(false);
    }

    /**
     * Internal version of endInCallScreenSession().
     *
     * @param forceFinish If true, force the InCallScreen to
     *        truly finish() rather than just calling moveTaskToBack().
     *        @see finish()
     */
    private void endInCallScreenSession(boolean forceFinish) {
        log("endInCallScreenSession(" + forceFinish + ")...  phone state = " + mCM.getState());
        if (forceFinish) {
            Log.i(LOG_TAG, "endInCallScreenSession(): FORCING a call to super.finish()!");
            super.finish();  // Call super.finish() rather than our own finish() method,
                             // which actually just calls moveTaskToBack().
        } else {
            moveTaskToBack(true);
        }
        setInCallScreenMode(InCallScreenMode.UNDEFINED);
    }

    /* package */ boolean isForegroundActivity() {
        return mIsForegroundActivity;
    }

    /* package */ void updateKeyguardPolicy(boolean dismissKeyguard) {
        if (dismissKeyguard) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    private void registerForPhoneStates() {
        if (!mRegisteredForPhoneStates) {
            mCM.registerForPreciseCallStateChanged(mHandler, PHONE_STATE_CHANGED, null);
            mCM.registerForDisconnect(mHandler, PHONE_DISCONNECT, null);
            mCM.registerForMmiInitiate(mHandler, PhoneApp.MMI_INITIATE, null);
            // register for the MMI complete message.  Upon completion,
            // PhoneUtils will bring up a system dialog instead of the
            // message display class in PhoneUtils.displayMMIComplete().
            // We'll listen for that message too, so that we can finish
            // the activity at the same time.
            mCM.registerForMmiComplete(mHandler, PhoneApp.MMI_COMPLETE, null);
            mCM.registerForCallWaiting(mHandler, PHONE_CDMA_CALL_WAITING, null);
            mCM.registerForPostDialCharacter(mHandler, POST_ON_DIAL_CHARS, null);
            mCM.registerForSuppServiceFailed(mHandler, SUPP_SERVICE_FAILED, null);
            mCM.registerForIncomingRing(mHandler, PHONE_INCOMING_RING, null);
            mCM.registerForNewRingingConnection(mHandler, PHONE_NEW_RINGING_CONNECTION, null);
            mRegisteredForPhoneStates = true;
        }
    }

    private void unregisterForPhoneStates() {
        mCM.unregisterForPreciseCallStateChanged(mHandler);
        mCM.unregisterForDisconnect(mHandler);
        mCM.unregisterForMmiInitiate(mHandler);
        mCM.unregisterForMmiComplete(mHandler);
        mCM.unregisterForCallWaiting(mHandler);
        mCM.unregisterForPostDialCharacter(mHandler);
        mCM.unregisterForSuppServiceFailed(mHandler);
        mCM.unregisterForIncomingRing(mHandler);
        mCM.unregisterForNewRingingConnection(mHandler);
        mRegisteredForPhoneStates = false;
    }

    /* package */ void updateAfterRadioTechnologyChange() {
        if (DBG) Log.d(LOG_TAG, "updateAfterRadioTechnologyChange()...");

        // Reset the call screen since the calls cannot be transferred
        // across radio technologies.
        resetInCallScreenMode();

        // Unregister for all events from the old obsolete phone
        unregisterForPhoneStates();

        // (Re)register for all events relevant to the new active phone
        registerForPhoneStates();

        // And finally, refresh the onscreen UI.  (Note that it's safe
        // to call requestUpdateScreen() even if the radio change ended up
        // causing us to exit the InCallScreen.)
        requestUpdateScreen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        log("onNewIntent: intent = " + intent + ", phone state = " + mCM.getState());

        // We're being re-launched with a new Intent.  Since it's possible for a
        // single InCallScreen instance to persist indefinitely (even if we
        // finish() ourselves), this sequence can potentially happen any time
        // the InCallScreen needs to be displayed.

        // Stash away the new intent so that we can get it in the future
        // by calling getIntent().  (Otherwise getIntent() will return the
        // original Intent from when we first got created!)
        setIntent(intent);

        // Activities are always paused before receiving a new intent, so
        // we can count on our onResume() method being called next.

        // Just like in onCreate(), handle the intent.
        internalResolveIntent(intent);
    }

    private void internalResolveIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (DBG) log("internalResolveIntent: action=" + action);

        // In gingerbread and earlier releases, the InCallScreen used to
        // directly handle certain intent actions that could initiate phone
        // calls, namely ACTION_CALL and ACTION_CALL_EMERGENCY, and also
        // OtaUtils.ACTION_PERFORM_CDMA_PROVISIONING.
        //
        // But it doesn't make sense to tie those actions to the InCallScreen
        // (or especially to the *activity lifecycle* of the InCallScreen).
        // Instead, the InCallScreen should only be concerned with running the
        // onscreen UI while in a call.  So we've now offloaded the call-control
        // functionality to a new module called CallController, and OTASP calls
        // are now launched from the OtaUtils startInteractiveOtasp() or
        // startNonInteractiveOtasp() methods.
        //
        // So now, the InCallScreen is only ever launched using the ACTION_MAIN
        // action, and (upon launch) performs no functionality other than
        // displaying the UI in a state that matches the current telephony
        // state.

        if (action.equals(intent.ACTION_MAIN)) {
            // This action is the normal way to bring up the in-call UI.
            //
            // Most of the interesting work of updating the onscreen UI (to
            // match the current telephony state) happens in the
            // syncWithPhoneState() => updateScreen() sequence that happens in
            // onResume().
            //
            // But we do check here for one extra that can come along with the
            // ACTION_MAIN intent:

            if (intent.hasExtra(SHOW_DIALPAD_EXTRA)) {
                // SHOW_DIALPAD_EXTRA can be used here to specify whether the DTMF
                // dialpad should be initially visible.  If the extra isn't
                // present at all, we just leave the dialpad in its previous state.

                boolean showDialpad = intent.getBooleanExtra(SHOW_DIALPAD_EXTRA, false);
                if (VDBG) log("- internalResolveIntent: SHOW_DIALPAD_EXTRA: " + showDialpad);

                // If SHOW_DIALPAD_EXTRA is specified, that overrides whatever
                // the previous state of inCallUiState.showDialpad was.
                mApp.inCallUiState.showDialpad = showDialpad;
            }
            // ...and in onResume() we'll update the onscreen dialpad state to
            // match the InCallUiState.

            return;
        }

        if (action.equals(OtaUtils.ACTION_DISPLAY_ACTIVATION_SCREEN)) {
            // Bring up the in-call UI in the OTASP-specific "activate" state;
            // see OtaUtils.startInteractiveOtasp().  Note that at this point
            // the OTASP call has not been started yet; we won't actually make
            // the call until the user presses the "Activate" button.

            if (!TelephonyCapabilities.supportsOtasp(mPhone)) {
                throw new IllegalStateException(
                    "Received ACTION_DISPLAY_ACTIVATION_SCREEN intent on non-OTASP-capable device: "
                    + intent);
            }

            setInCallScreenMode(InCallScreenMode.OTA_NORMAL);
            if ((mApp.cdmaOtaProvisionData != null)
                && (!mApp.cdmaOtaProvisionData.isOtaCallIntentProcessed)) {
                mApp.cdmaOtaProvisionData.isOtaCallIntentProcessed = true;
                mApp.cdmaOtaScreenState.otaScreenState =
                        CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION;
            }
            return;
        }

        // Various intent actions that should no longer come here directly:
        if (action.equals(OtaUtils.ACTION_PERFORM_CDMA_PROVISIONING)) {
            // This intent is now handled by the InCallScreenShowActivation
            // activity, which translates it into a call to
            // OtaUtils.startInteractiveOtasp().
            throw new IllegalStateException(
                "Unexpected ACTION_PERFORM_CDMA_PROVISIONING received by InCallScreen: "
                + intent);
        } else if (action.equals(Intent.ACTION_CALL)
                   || action.equals(Intent.ACTION_CALL_EMERGENCY)) {
            // ACTION_CALL* intents go to the OutgoingCallBroadcaster, which now
            // translates them into CallController.placeCall() calls rather than
            // launching the InCallScreen directly.
            throw new IllegalStateException("Unexpected CALL action received by InCallScreen: "
                                            + intent);
        } else if (action.equals(ACTION_UNDEFINED)) {
            // This action is only used for internal bookkeeping; we should
            // never actually get launched with it.
            Log.wtf(LOG_TAG, "internalResolveIntent: got launched with ACTION_UNDEFINED");
            return;
        } else {
            Log.wtf(LOG_TAG, "internalResolveIntent: unexpected intent action: " + action);
            // But continue the best we can (basically treating this case
            // like ACTION_MAIN...)
            return;
        }
    }

    private void stopTimer() {
        if (mCallCard != null) mCallCard.stopTimer();
    }

    private void initInCallScreen() {
        if (VDBG) log("initInCallScreen()...");

        // Have the WindowManager filter out touch events that are "too fat".
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        mInCallPanel = (ViewGroup) findViewById(R.id.inCallPanel);

        // Initialize the CallCard.
        mCallCard = (CallCard) findViewById(R.id.callCard);
        if (VDBG) log("  - mCallCard = " + mCallCard);
        mCallCard.setInCallScreenInstance(this);

        // Initialize the onscreen UI elements.
        initInCallTouchUi();

        // Helper class to keep track of enabledness/state of UI controls
        mInCallControlState = new InCallControlState(this, mCM);

        // Helper class to run the "Manage conference" UI
        mManageConferenceUtils = new ManageConferenceUtils(this, mCM);

        // The DTMF Dialpad.
        // TODO: Don't inflate this until the first time it's needed.
        ViewStub stub = (ViewStub)findViewById(R.id.dtmf_twelve_key_dialer_stub);
        stub.inflate();
        mDialerView = (DTMFTwelveKeyDialerView) findViewById(R.id.dtmf_twelve_key_dialer_view);
        if (DBG) log("- Found dialerView: " + mDialerView);

        // Sanity-check that (regardless of the device) at least the
        // dialer view is present:
        if (mDialerView == null) {
            Log.e(LOG_TAG, "onCreate: couldn't find dialerView", new IllegalStateException());
        }
        // Finally, create the DTMFTwelveKeyDialer instance.
        mDialer = new DTMFTwelveKeyDialer(this, mDialerView);
    }

    /**
     * Returns true if the phone is "in use", meaning that at least one line
     * is active (ie. off hook or ringing or dialing).  Conversely, a return
     * value of false means there's currently no phone activity at all.
     */
    private boolean phoneIsInUse() {
        return mCM.getState() != Phone.State.IDLE;
    }

    private boolean handleDialerKeyDown(int keyCode, KeyEvent event) {
        if (VDBG) log("handleDialerKeyDown: keyCode " + keyCode + ", event " + event + "...");

        // As soon as the user starts typing valid dialable keys on the
        // keyboard (presumably to type DTMF tones) we start passing the
        // key events to the DTMFDialer's onDialerKeyDown.  We do so
        // only if the okToDialDTMFTones() conditions pass.
        if (okToDialDTMFTones()) {
            return mDialer.onDialerKeyDown(event);

            // TODO: If the dialpad isn't currently visible, maybe
            // consider automatically bringing it up right now?
            // (Just to make sure the user sees the digits widget...)
            // But this probably isn't too critical since it's awkward to
            // use the hard keyboard while in-call in the first place,
            // especially now that the in-call UI is portrait-only...
        }

        return false;
    }

    @Override
    public void onBackPressed() {
        if (DBG) log("onBackPressed()...");

        // To consume this BACK press, the code here should just do
        // something and return.  Otherwise, call super.onBackPressed() to
        // get the default implementation (which simply finishes the
        // current activity.)

        if (mCM.hasActiveRingingCall()) {
            // The Back key, just like the Home key, is always disabled
            // while an incoming call is ringing.  (The user *must* either
            // answer or reject the call before leaving the incoming-call
            // screen.)
            if (DBG) log("BACK key while ringing: ignored");

            // And consume this event; *don't* call super.onBackPressed().
            return;
        }

        // BACK is also used to exit out of any "special modes" of the
        // in-call UI:

        if (mDialer.isOpened()) {
            hideDialpadInternal(true);  // do the "closing" animation
            return;
        }

        if (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.MANAGE_CONFERENCE) {
            // Hide the Manage Conference panel, return to NORMAL mode.
            setInCallScreenMode(InCallScreenMode.NORMAL);
            requestUpdateScreen();
            return;
        }

        // Nothing special to do.  Fall back to the default behavior.
        super.onBackPressed();
    }

    /**
     * Handles the green CALL key while in-call.
     * @return true if we consumed the event.
     */
    private boolean handleCallKey() {
        // The green CALL button means either "Answer", "Unhold", or
        // "Swap calls", or can be a no-op, depending on the current state
        // of the Phone.

        final boolean hasRingingCall = mCM.hasActiveRingingCall();
        final boolean hasActiveCall = mCM.hasActiveFgCall();
        final boolean hasHoldingCall = mCM.hasActiveBgCall();

        int phoneType = mPhone.getPhoneType();
        if (phoneType == Phone.PHONE_TYPE_CDMA) {
            // The green CALL button means either "Answer", "Swap calls/On Hold", or
            // "Add to 3WC", depending on the current state of the Phone.

            CdmaPhoneCallState.PhoneCallState currCallState =
                mApp.cdmaPhoneCallState.getCurrentCallState();
            if (hasRingingCall) {
                //Scenario 1: Accepting the First Incoming and Call Waiting call
                if (DBG) log("answerCall: First Incoming and Call Waiting scenario");
                internalAnswerCall();  // Automatically holds the current active call,
                                       // if there is one
            } else if ((currCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE)
                    && (hasActiveCall)) {
                //Scenario 2: Merging 3Way calls
                if (DBG) log("answerCall: Merge 3-way call scenario");
                // Merge calls
                PhoneUtils.mergeCalls(mCM);
            } else if (currCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                //Scenario 3: Switching between two Call waiting calls or drop the latest
                // connection if in a 3Way merge scenario
                if (DBG) log("answerCall: Switch btwn 2 calls scenario");
                internalSwapCalls();
            }
        } else if ((phoneType == Phone.PHONE_TYPE_GSM)
                || (phoneType == Phone.PHONE_TYPE_SIP)) {
            if (hasRingingCall) {
                // If an incoming call is ringing, the CALL button is actually
                // handled by the PhoneWindowManager.  (We do this to make
                // sure that we'll respond to the key even if the InCallScreen
                // hasn't come to the foreground yet.)
                //
                // We'd only ever get here in the extremely rare case that the
                // incoming call started ringing *after*
                // PhoneWindowManager.interceptKeyTq() but before the event
                // got here, or else if the PhoneWindowManager had some
                // problem connecting to the ITelephony service.
                Log.w(LOG_TAG, "handleCallKey: incoming call is ringing!"
                      + " (PhoneWindowManager should have handled this key.)");
                // But go ahead and handle the key as normal, since the
                // PhoneWindowManager presumably did NOT handle it:

                // There's an incoming ringing call: CALL means "Answer".
                internalAnswerCall();
            } else if (hasActiveCall && hasHoldingCall) {
                // Two lines are in use: CALL means "Swap calls".
                if (DBG) log("handleCallKey: both lines in use ==> swap calls.");
                internalSwapCalls();
            } else if (hasHoldingCall) {
                // There's only one line in use, AND it's on hold.
                // In this case CALL is a shortcut for "unhold".
                if (DBG) log("handleCallKey: call on hold ==> unhold.");
                PhoneUtils.switchHoldingAndActive(
                    mCM.getFirstActiveBgCall());  // Really means "unhold" in this state
            } else {
                // The most common case: there's only one line in use, and
                // it's an active call (i.e. it's not on hold.)
                // In this case CALL is a no-op.
                // (This used to be a shortcut for "add call", but that was a
                // bad idea because "Add call" is so infrequently-used, and
                // because the user experience is pretty confusing if you
                // inadvertently trigger it.)
                if (VDBG) log("handleCallKey: call in foregound ==> ignoring.");
                // But note we still consume this key event; see below.
            }
        } else {
            throw new IllegalStateException("Unexpected phone type: " + phoneType);
        }

        // We *always* consume the CALL key, since the system-wide default
        // action ("go to the in-call screen") is useless here.
        return true;
    }

    boolean isKeyEventAcceptableDTMF (KeyEvent event) {
        return (mDialer != null && mDialer.isKeyEventAcceptable(event));
    }

    /**
     * Overriden to track relevant focus changes.
     *
     * If a key is down and some time later the focus changes, we may
     * NOT recieve the keyup event; logically the keyup event has not
     * occured in this window.  This issue is fixed by treating a focus
     * changed event as an interruption to the keydown, making sure
     * that any code that needs to be run in onKeyUp is ALSO run here.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        // the dtmf tones should no longer be played
        if (VDBG) log("onWindowFocusChanged(" + hasFocus + ")...");
        if (!hasFocus && mDialer != null) {
            if (VDBG) log("- onWindowFocusChanged: faking onDialerKeyUp()...");
            mDialer.onDialerKeyUp(null);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // if (DBG) log("onKeyUp(keycode " + keyCode + ")...");

        // push input to the dialer.
        if ((mDialer != null) && (mDialer.onDialerKeyUp(event))){
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_CALL) {
            // Always consume CALL to be sure the PhoneWindow won't do anything with it
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // if (DBG) log("onKeyDown(keycode " + keyCode + ")...");

        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL:
                boolean handled = handleCallKey();
                if (!handled) {
                    Log.w(LOG_TAG, "InCallScreen should always handle KEYCODE_CALL in onKeyDown");
                }
                // Always consume CALL to be sure the PhoneWindow won't do anything with it
                return true;

            // Note there's no KeyEvent.KEYCODE_ENDCALL case here.
            // The standard system-wide handling of the ENDCALL key
            // (see PhoneWindowManager's handling of KEYCODE_ENDCALL)
            // already implements exactly what the UI spec wants,
            // namely (1) "hang up" if there's a current active call,
            // or (2) "don't answer" if there's a current ringing call.

            case KeyEvent.KEYCODE_CAMERA:
                // Disable the CAMERA button while in-call since it's too
                // easy to press accidentally.
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (mCM.getState() == Phone.State.RINGING) {
                    // If an incoming call is ringing, the VOLUME buttons are
                    // actually handled by the PhoneWindowManager.  (We do
                    // this to make sure that we'll respond to them even if
                    // the InCallScreen hasn't come to the foreground yet.)
                    //
                    // We'd only ever get here in the extremely rare case that the
                    // incoming call started ringing *after*
                    // PhoneWindowManager.interceptKeyTq() but before the event
                    // got here, or else if the PhoneWindowManager had some
                    // problem connecting to the ITelephony service.
                    Log.w(LOG_TAG, "VOLUME key: incoming call is ringing!"
                          + " (PhoneWindowManager should have handled this key.)");
                    // But go ahead and handle the key as normal, since the
                    // PhoneWindowManager presumably did NOT handle it:
                    internalSilenceRinger();

                    // As long as an incoming call is ringing, we always
                    // consume the VOLUME keys.
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_MUTE:
                onMuteClick();
                return true;

            // Various testing/debugging features, enabled ONLY when VDBG == true.
            case KeyEvent.KEYCODE_SLASH:
                if (VDBG) {
                    log("----------- InCallScreen View dump --------------");
                    // Dump starting from the top-level view of the entire activity:
                    Window w = this.getWindow();
                    View decorView = w.getDecorView();
                    decorView.debug();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_EQUALS:
                if (VDBG) {
                    log("----------- InCallScreen call state dump --------------");
                    PhoneUtils.dumpCallState(mPhone);
                    PhoneUtils.dumpCallManager();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_GRAVE:
                if (VDBG) {
                    // Placeholder for other misc temp testing
                    log("------------ Temp testing -----------------");
                    return true;
                }
                break;
        }

        if (event.getRepeatCount() == 0 && handleDialerKeyDown(keyCode, event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    /**
     * Handle a failure notification for a supplementary service
     * (i.e. conference, switch, separate, transfer, etc.).
     */
    void onSuppServiceFailed(AsyncResult r) {
        Phone.SuppService service = (Phone.SuppService) r.result;
        if (DBG) log("onSuppServiceFailed: " + service);

        int errorMessageResId;
        switch (service) {
            case SWITCH:
                // Attempt to switch foreground and background/incoming calls failed
                // ("Failed to switch calls")
                errorMessageResId = R.string.incall_error_supp_service_switch;
                break;

            case SEPARATE:
                // Attempt to separate a call from a conference call
                // failed ("Failed to separate out call")
                errorMessageResId = R.string.incall_error_supp_service_separate;
                break;

            case TRANSFER:
                // Attempt to connect foreground and background calls to
                // each other (and hanging up user's line) failed ("Call
                // transfer failed")
                errorMessageResId = R.string.incall_error_supp_service_transfer;
                break;

            case CONFERENCE:
                // Attempt to add a call to conference call failed
                // ("Conference call failed")
                errorMessageResId = R.string.incall_error_supp_service_conference;
                break;

            case REJECT:
                // Attempt to reject an incoming call failed
                // ("Call rejection failed")
                errorMessageResId = R.string.incall_error_supp_service_reject;
                break;

            case HANGUP:
                // Attempt to release a call failed ("Failed to release call(s)")
                errorMessageResId = R.string.incall_error_supp_service_hangup;
                break;

            case UNKNOWN:
            default:
                // Attempt to use a service we don't recognize or support
                // ("Unsupported service" or "Selected service failed")
                errorMessageResId = R.string.incall_error_supp_service_unknown;
                break;
        }

        // mSuppServiceFailureDialog is a generic dialog used for any
        // supp service failure, and there's only ever have one
        // instance at a time.  So just in case a previous dialog is
        // still around, dismiss it.
        if (mSuppServiceFailureDialog != null) {
            if (DBG) log("- DISMISSING mSuppServiceFailureDialog.");
            mSuppServiceFailureDialog.dismiss();  // It's safe to dismiss() a dialog
                                                  // that's already dismissed.
            mSuppServiceFailureDialog = null;
        }

        mSuppServiceFailureDialog = new AlertDialog.Builder(this)
                .setMessage(errorMessageResId)
                .setPositiveButton(R.string.ok, null)
                .create();
        mSuppServiceFailureDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        mSuppServiceFailureDialog.show();
    }

    /**
     * Something has changed in the phone's state.  Update the UI.
     */
    private void onPhoneStateChanged(AsyncResult r) {
        Phone.State state = mCM.getState();
        if (DBG) log("onPhoneStateChanged: current state = " + state);

        // There's nothing to do here if we're not the foreground activity.
        // (When we *do* eventually come to the foreground, we'll do a
        // full update then.)
        if (!mIsForegroundActivity) {
            if (DBG) log("onPhoneStateChanged: Activity not in foreground! Bailing out...");
            return;
        }

        // Update the onscreen UI.
        // We use requestUpdateScreen() here (which posts a handler message)
        // instead of calling updateScreen() directly, which allows us to avoid
        // unnecessary work if multiple onPhoneStateChanged() events come in all
        // at the same time.

        requestUpdateScreen();

        // Make sure we update the poke lock and wake lock when certain
        // phone state changes occur.
        mApp.updateWakeState();
    }

    /**
     * Updates the UI after a phone connection is disconnected, as follows:
     *
     * - If this was a missed or rejected incoming call, and no other
     *   calls are active, dismiss the in-call UI immediately.  (The
     *   CallNotifier will still create a "missed call" notification if
     *   necessary.)
     *
     * - With any other disconnect cause, if the phone is now totally
     *   idle, display the "Call ended" state for a couple of seconds.
     *
     * - Or, if the phone is still in use, stay on the in-call screen
     *   (and update the UI to reflect the current state of the Phone.)
     *
     * @param r r.result contains the connection that just ended
     */
    private void onDisconnect(AsyncResult r) {
        Connection c = (Connection) r.result;
        Connection.DisconnectCause cause = c.getDisconnectCause();
        if (DBG) log("onDisconnect: connection '" + c + "', cause = " + cause);

        boolean currentlyIdle = !phoneIsInUse();
        int autoretrySetting = AUTO_RETRY_OFF;
        boolean phoneIsCdma = (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA);
        if (phoneIsCdma) {
            // Get the Auto-retry setting only if Phone State is IDLE,
            // else let it stay as AUTO_RETRY_OFF
            if (currentlyIdle) {
                autoretrySetting = android.provider.Settings.System.getInt(mPhone.getContext().
                        getContentResolver(), android.provider.Settings.System.CALL_AUTO_RETRY, 0);
            }
        }

        // for OTA Call, only if in OTA NORMAL mode, handle OTA END scenario
        if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL)
                && ((mApp.cdmaOtaProvisionData != null)
                && (!mApp.cdmaOtaProvisionData.inOtaSpcState))) {
            setInCallScreenMode(InCallScreenMode.OTA_ENDED);
            updateScreen();
            return;
        } else if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED)
                   || ((mApp.cdmaOtaProvisionData != null)
                       && mApp.cdmaOtaProvisionData.inOtaSpcState)) {
           if (DBG) log("onDisconnect: OTA Call end already handled");
           return;
        }

        // Any time a call disconnects, clear out the "history" of DTMF
        // digits you typed (to make sure it doesn't persist from one call
        // to the next.)
        mDialer.clearDigits();

        // Under certain call disconnected states, we want to alert the user
        // with a dialog instead of going through the normal disconnect
        // routine.
        if (cause == Connection.DisconnectCause.CALL_BARRED) {
            showGenericErrorDialog(R.string.callFailed_cb_enabled, false);
            return;
        } else if (cause == Connection.DisconnectCause.FDN_BLOCKED) {
            showGenericErrorDialog(R.string.callFailed_fdn_only, false);
            return;
        } else if (cause == Connection.DisconnectCause.CS_RESTRICTED) {
            showGenericErrorDialog(R.string.callFailed_dsac_restricted, false);
            return;
        } else if (cause == Connection.DisconnectCause.CS_RESTRICTED_EMERGENCY) {
            showGenericErrorDialog(R.string.callFailed_dsac_restricted_emergency, false);
            return;
        } else if (cause == Connection.DisconnectCause.CS_RESTRICTED_NORMAL) {
            showGenericErrorDialog(R.string.callFailed_dsac_restricted_normal, false);
            return;
        }

        if (phoneIsCdma) {
            Call.State callState = mApp.notifier.getPreviousCdmaCallState();
            if ((callState == Call.State.ACTIVE)
                    && (cause != Connection.DisconnectCause.INCOMING_MISSED)
                    && (cause != Connection.DisconnectCause.NORMAL)
                    && (cause != Connection.DisconnectCause.LOCAL)
                    && (cause != Connection.DisconnectCause.INCOMING_REJECTED)) {
                showCallLostDialog();
            } else if ((callState == Call.State.DIALING || callState == Call.State.ALERTING)
                        && (cause != Connection.DisconnectCause.INCOMING_MISSED)
                        && (cause != Connection.DisconnectCause.NORMAL)
                        && (cause != Connection.DisconnectCause.LOCAL)
                        && (cause != Connection.DisconnectCause.INCOMING_REJECTED)) {

                if (mApp.inCallUiState.needToShowCallLostDialog) {
                    // Show the dialog now since the call that just failed was a retry.
                    showCallLostDialog();
                    mApp.inCallUiState.needToShowCallLostDialog = false;
                } else {
                    if (autoretrySetting == AUTO_RETRY_OFF) {
                        // Show the dialog for failed call if Auto Retry is OFF in Settings.
                        showCallLostDialog();
                        mApp.inCallUiState.needToShowCallLostDialog = false;
                    } else {
                        // Set the needToShowCallLostDialog flag now, so we'll know to show
                        // the dialog if *this* call fails.
                        mApp.inCallUiState.needToShowCallLostDialog = true;
                    }
                }
            }
        }

        // Explicitly clean up up any DISCONNECTED connections
        // in a conference call.
        // [Background: Even after a connection gets disconnected, its
        // Connection object still stays around for a few seconds, in the
        // DISCONNECTED state.  With regular calls, this state drives the
        // "call ended" UI.  But when a single person disconnects from a
        // conference call there's no "call ended" state at all; in that
        // case we blow away any DISCONNECTED connections right now to make sure
        // the UI updates instantly to reflect the current state.]
        Call call = c.getCall();
        if (call != null) {
            // We only care about situation of a single caller
            // disconnecting from a conference call.  In that case, the
            // call will have more than one Connection (including the one
            // that just disconnected, which will be in the DISCONNECTED
            // state) *and* at least one ACTIVE connection.  (If the Call
            // has *no* ACTIVE connections, that means that the entire
            // conference call just ended, so we *do* want to show the
            // "Call ended" state.)
            List<Connection> connections = call.getConnections();
            if (connections != null && connections.size() > 1) {
                for (Connection conn : connections) {
                    if (conn.getState() == Call.State.ACTIVE) {
                        // This call still has at least one ACTIVE connection!
                        // So blow away any DISCONNECTED connections
                        // (including, presumably, the one that just
                        // disconnected from this conference call.)

                        // We also force the wake state to refresh, just in
                        // case the disconnected connections are removed
                        // before the phone state change.
                        if (VDBG) log("- Still-active conf call; clearing DISCONNECTED...");
                        mApp.updateWakeState();
                        mCM.clearDisconnected();  // This happens synchronously.
                        break;
                    }
                }
            }
        }

        // Note: see CallNotifier.onDisconnect() for some other behavior
        // that might be triggered by a disconnect event, like playing the
        // busy/congestion tone.

        // Stash away some info about the call that just disconnected.
        // (This might affect what happens after we exit the InCallScreen; see
        // delayedCleanupAfterDisconnect().)
        // TODO: rather than stashing this away now and then reading it in
        // delayedCleanupAfterDisconnect(), it would be cleaner to just pass
        // this as an argument to delayedCleanupAfterDisconnect() (if we call
        // it directly) or else pass it as a Message argument when we post the
        // DELAYED_CLEANUP_AFTER_DISCONNECT message.
        mLastDisconnectCause = cause;

        // We bail out immediately (and *don't* display the "call ended"
        // state at all) if this was an incoming call.
        boolean bailOutImmediately =
                ((cause == Connection.DisconnectCause.INCOMING_MISSED)
                 || (cause == Connection.DisconnectCause.INCOMING_REJECTED))
                && currentlyIdle;

        // Note: we also do some special handling for the case when a call
        // disconnects with cause==OUT_OF_SERVICE while making an
        // emergency call from airplane mode.  That's handled by
        // EmergencyCallHelper.onDisconnect().

        // TODO: one more case where we *shouldn't* bail out immediately:
        // If the disconnect event was from an incoming ringing call, but
        // the "Respond via SMS" popup is visible onscreen.  (In this
        // case, we let the popup stay up even after the incoming call
        // stops ringing, to give people extra time to choose a response.)
        //
        // But watch out: if we allow the popup to stay onscreen even
        // after the incoming call disconnects, then we'll *also* have to
        // forcibly dismiss it if the InCallScreen gets paused in that
        // state (like by the user pressing Power or the screen timing
        // out).

        if (bailOutImmediately) {
            if (DBG) log("- onDisconnect: bailOutImmediately...");

            // Exit the in-call UI!
            // (This is basically the same "delayed cleanup" we do below,
            // just with zero delay.  Since the Phone is currently idle,
            // this call is guaranteed to immediately finish this activity.)
            delayedCleanupAfterDisconnect();
        } else {
            if (DBG) log("- onDisconnect: delayed bailout...");
            // Stay on the in-call screen for now.  (Either the phone is
            // still in use, or the phone is idle but we want to display
            // the "call ended" state for a couple of seconds.)

            // Switch to the special "Call ended" state when the phone is idle
            // but there's still a call in the DISCONNECTED state:
            if (currentlyIdle
                && (mCM.hasDisconnectedFgCall() || mCM.hasDisconnectedBgCall())) {
                if (DBG) log("- onDisconnect: switching to 'Call ended' state...");
                setInCallScreenMode(InCallScreenMode.CALL_ENDED);
            }

            // Force a UI update in case we need to display anything
            // special based on this connection's DisconnectCause
            // (see CallCard.getCallFailedString()).
            updateScreen();

            // Some other misc cleanup that we do if the call that just
            // disconnected was the foreground call.
            final boolean hasActiveCall = mCM.hasActiveFgCall();
            if (!hasActiveCall) {
                if (DBG) log("- onDisconnect: cleaning up after FG call disconnect...");

                // Dismiss any dialogs which are only meaningful for an
                // active call *and* which become moot if the call ends.
                if (mWaitPromptDialog != null) {
                    if (VDBG) log("- DISMISSING mWaitPromptDialog.");
                    mWaitPromptDialog.dismiss();  // safe even if already dismissed
                    mWaitPromptDialog = null;
                }
                if (mWildPromptDialog != null) {
                    if (VDBG) log("- DISMISSING mWildPromptDialog.");
                    mWildPromptDialog.dismiss();  // safe even if already dismissed
                    mWildPromptDialog = null;
                }
                if (mPausePromptDialog != null) {
                    if (DBG) log("- DISMISSING mPausePromptDialog.");
                    mPausePromptDialog.dismiss();  // safe even if already dismissed
                    mPausePromptDialog = null;
                }
            }

            // Updating the screen wake state is done in onPhoneStateChanged().


            // CDMA: We only clean up if the Phone state is IDLE as we might receive an
            // onDisconnect for a Call Collision case (rare but possible).
            // For Call collision cases i.e. when the user makes an out going call
            // and at the same time receives an Incoming Call, the Incoming Call is given
            // higher preference. At this time framework sends a disconnect for the Out going
            // call connection hence we should *not* bring down the InCallScreen as the Phone
            // State would be RINGING
            if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                if (!currentlyIdle) {
                    // Clean up any connections in the DISCONNECTED state.
                    // This is necessary cause in CallCollision the foreground call might have
                    // connections in DISCONNECTED state which needs to be cleared.
                    mCM.clearDisconnected();

                    // The phone is still in use.  Stay here in this activity.
                    // But we don't need to keep the screen on.
                    if (DBG) log("onDisconnect: Call Collision case - staying on InCallScreen.");
                    if (DBG) PhoneUtils.dumpCallState(mPhone);
                    return;
                }
            }

            // Finally, arrange for delayedCleanupAfterDisconnect() to get
            // called after a short interval (during which we display the
            // "call ended" state.)  At that point, if the
            // Phone is idle, we'll finish out of this activity.
            int callEndedDisplayDelay =
                    (cause == Connection.DisconnectCause.LOCAL)
                    ? CALL_ENDED_SHORT_DELAY : CALL_ENDED_LONG_DELAY;
            mHandler.removeMessages(DELAYED_CLEANUP_AFTER_DISCONNECT);
            mHandler.sendEmptyMessageDelayed(DELAYED_CLEANUP_AFTER_DISCONNECT,
                                             callEndedDisplayDelay);
        }

        // Remove 3way timer (only meaningful for CDMA)
        // TODO: this call needs to happen in the CallController, not here.
        // (It should probably be triggered by the CallNotifier's onDisconnect method.)
        // mHandler.removeMessages(THREEWAY_CALLERINFO_DISPLAY_DONE);
    }

    /**
     * Brings up the "MMI Started" dialog.
     */
    private void onMMIInitiate(AsyncResult r) {
        if (VDBG) log("onMMIInitiate()...  AsyncResult r = " + r);

        // Watch out: don't do this if we're not the foreground activity,
        // mainly since in the Dialog.show() might fail if we don't have a
        // valid window token any more...
        // (Note that this exact sequence can happen if you try to start
        // an MMI code while the radio is off or out of service.)
        if (!mIsForegroundActivity) {
            if (VDBG) log("Activity not in foreground! Bailing out...");
            return;
        }

        // Also, if any other dialog is up right now (presumably the
        // generic error dialog displaying the "Starting MMI..."  message)
        // take it down before bringing up the real "MMI Started" dialog
        // in its place.
        dismissAllDialogs();

        MmiCode mmiCode = (MmiCode) r.result;
        if (VDBG) log("  - MmiCode: " + mmiCode);

        Message message = Message.obtain(mHandler, PhoneApp.MMI_CANCEL);
        mMmiStartedDialog = PhoneUtils.displayMMIInitiate(this, mmiCode,
                                                          message, mMmiStartedDialog);
    }

    /**
     * Handles an MMI_CANCEL event, which is triggered by the button
     * (labeled either "OK" or "Cancel") on the "MMI Started" dialog.
     * @see onMMIInitiate
     * @see PhoneUtils.cancelMmiCode
     */
    private void onMMICancel() {
        if (VDBG) log("onMMICancel()...");

        // First of all, cancel the outstanding MMI code (if possible.)
        PhoneUtils.cancelMmiCode(mPhone);

        // Regardless of whether the current MMI code was cancelable, the
        // PhoneApp will get an MMI_COMPLETE event very soon, which will
        // take us to the MMI Complete dialog (see
        // PhoneUtils.displayMMIComplete().)
        //
        // But until that event comes in, we *don't* want to stay here on
        // the in-call screen, since we'll be visible in a
        // partially-constructed state as soon as the "MMI Started" dialog
        // gets dismissed.  So let's forcibly bail out right now.
        if (DBG) log("onMMICancel: finishing InCallScreen...");
        endInCallScreenSession();
    }

    /**
     * Handles the POST_ON_DIAL_CHARS message from the Phone
     * (see our call to mPhone.setOnPostDialCharacter() above.)
     *
     * TODO: NEED TO TEST THIS SEQUENCE now that we no longer handle
     * "dialable" key events here in the InCallScreen: we do directly to the
     * Dialer UI instead.  Similarly, we may now need to go directly to the
     * Dialer to handle POST_ON_DIAL_CHARS too.
     */
    private void handlePostOnDialChars(AsyncResult r, char ch) {
        Connection c = (Connection) r.result;

        if (c != null) {
            Connection.PostDialState state =
                    (Connection.PostDialState) r.userObj;

            if (VDBG) log("handlePostOnDialChar: state = " +
                    state + ", ch = " + ch);

            switch (state) {
                case STARTED:
                    mDialer.stopLocalToneIfNeeded();
                    if (mPauseInProgress) {
                        /**
                         * Note that on some devices, this will never happen,
                         * because we will not ever enter the PAUSE state.
                         */
                        showPausePromptDialog(c, mPostDialStrAfterPause);
                    }
                    mPauseInProgress = false;
                    mDialer.startLocalToneIfNeeded(ch);

                    // TODO: is this needed, now that you can't actually
                    // type DTMF chars or dial directly from here?
                    // If so, we'd need to yank you out of the in-call screen
                    // here too (and take you to the 12-key dialer in "in-call" mode.)
                    // displayPostDialedChar(ch);
                    break;

                case WAIT:
                    // wait shows a prompt.
                    if (DBG) log("handlePostOnDialChars: show WAIT prompt...");
                    mDialer.stopLocalToneIfNeeded();
                    String postDialStr = c.getRemainingPostDialString();
                    showWaitPromptDialog(c, postDialStr);
                    break;

                case WILD:
                    if (DBG) log("handlePostOnDialChars: show WILD prompt");
                    mDialer.stopLocalToneIfNeeded();
                    showWildPromptDialog(c);
                    break;

                case COMPLETE:
                    mDialer.stopLocalToneIfNeeded();
                    break;

                case PAUSE:
                    // pauses for a brief period of time then continue dialing.
                    mDialer.stopLocalToneIfNeeded();
                    mPostDialStrAfterPause = c.getRemainingPostDialString();
                    mPauseInProgress = true;
                    break;

                default:
                    break;
            }
        }
    }

    /**
     * Pop up an alert dialog with OK and Cancel buttons to allow user to
     * Accept or Reject the WAIT inserted as part of the Dial string.
     */
    private void showWaitPromptDialog(final Connection c, String postDialStr) {
        if (DBG) log("showWaitPromptDialogChoice: '" + postDialStr + "'...");

        Resources r = getResources();
        StringBuilder buf = new StringBuilder();
        buf.append(r.getText(R.string.wait_prompt_str));
        buf.append(postDialStr);

        // if (DBG) log("- mWaitPromptDialog = " + mWaitPromptDialog);
        if (mWaitPromptDialog != null) {
            if (DBG) log("- DISMISSING mWaitPromptDialog.");
            mWaitPromptDialog.dismiss();  // safe even if already dismissed
            mWaitPromptDialog = null;
        }

        mWaitPromptDialog = new AlertDialog.Builder(this)
                .setMessage(buf.toString())
                .setPositiveButton(R.string.pause_prompt_yes,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            if (DBG) log("handle WAIT_PROMPT_CONFIRMED, proceed...");
                            c.proceedAfterWaitChar();
                        }
                    })
                .setNegativeButton(R.string.pause_prompt_no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            if (DBG) log("handle POST_DIAL_CANCELED!");
                            c.cancelPostDial();
                        }
                    })
                .create();
        mWaitPromptDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

        mWaitPromptDialog.show();
    }

    /**
     * Pop up an alert dialog which waits for 2 seconds for each P (Pause) Character entered
     * as part of the Dial String.
     */
    private void showPausePromptDialog(final Connection c, String postDialStrAfterPause) {
        Resources r = getResources();
        StringBuilder buf = new StringBuilder();
        buf.append(r.getText(R.string.pause_prompt_str));
        buf.append(postDialStrAfterPause);

        if (mPausePromptDialog != null) {
            if (DBG) log("- DISMISSING mPausePromptDialog.");
            mPausePromptDialog.dismiss();  // safe even if already dismissed
            mPausePromptDialog = null;
        }

        mPausePromptDialog = new AlertDialog.Builder(this)
                .setMessage(buf.toString())
                .create();
        mPausePromptDialog.show();
        // 2 second timer
        Message msg = Message.obtain(mHandler, EVENT_PAUSE_DIALOG_COMPLETE);
        mHandler.sendMessageDelayed(msg, PAUSE_PROMPT_DIALOG_TIMEOUT);
    }

    private View createWildPromptView() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(5, 5, 5, 5);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);

        TextView promptMsg = new TextView(this);
        promptMsg.setTextSize(14);
        promptMsg.setTypeface(Typeface.DEFAULT_BOLD);
        promptMsg.setText(getResources().getText(R.string.wild_prompt_str));

        result.addView(promptMsg, lp);

        mWildPromptText = new EditText(this);
        mWildPromptText.setKeyListener(DialerKeyListener.getInstance());
        mWildPromptText.setMovementMethod(null);
        mWildPromptText.setTextSize(14);
        mWildPromptText.setMaxLines(1);
        mWildPromptText.setHorizontallyScrolling(true);
        mWildPromptText.setBackgroundResource(android.R.drawable.editbox_background);

        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.setMargins(0, 3, 0, 0);

        result.addView(mWildPromptText, lp2);

        return result;
    }

    private void showWildPromptDialog(final Connection c) {
        View v = createWildPromptView();

        if (mWildPromptDialog != null) {
            if (VDBG) log("- DISMISSING mWildPromptDialog.");
            mWildPromptDialog.dismiss();  // safe even if already dismissed
            mWildPromptDialog = null;
        }

        mWildPromptDialog = new AlertDialog.Builder(this)
                .setView(v)
                .setPositiveButton(
                        R.string.send_button,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                if (VDBG) log("handle WILD_PROMPT_CHAR_ENTERED, proceed...");
                                String replacement = null;
                                if (mWildPromptText != null) {
                                    replacement = mWildPromptText.getText().toString();
                                    mWildPromptText = null;
                                }
                                c.proceedAfterWildChar(replacement);
                                mApp.pokeUserActivity();
                            }
                        })
                .setOnCancelListener(
                        new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface dialog) {
                                if (VDBG) log("handle POST_DIAL_CANCELED!");
                                c.cancelPostDial();
                                mApp.pokeUserActivity();
                            }
                        })
                .create();
        mWildPromptDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        mWildPromptDialog.show();

        mWildPromptText.requestFocus();
    }

    /**
     * Updates the state of the in-call UI based on the current state of
     * the Phone.  This call has no effect if we're not currently the
     * foreground activity.
     *
     * This method is only allowed to be called from the UI thread (since it
     * manipulates our View hierarchy).  If you need to update the screen from
     * some other thread, or if you just want to "post a request" for the screen
     * to be updated (rather than doing it synchronously), call
     * requestUpdateScreen() instead.
     */
    private void updateScreen() {
        if (DBG) log("updateScreen()...");
        final InCallScreenMode inCallScreenMode = mApp.inCallUiState.inCallScreenMode;
        if (VDBG) {
            Phone.State state = mCM.getState();
            log("  - phone state = " + state);
            log("  - inCallScreenMode = " + inCallScreenMode);
        }

        // Don't update anything if we're not in the foreground (there's
        // no point updating our UI widgets since we're not visible!)
        // Also note this check also ensures we won't update while we're
        // in the middle of pausing, which could cause a visible glitch in
        // the "activity ending" transition.
        if (!mIsForegroundActivity) {
            if (DBG) log("- updateScreen: not the foreground Activity! Bailing out...");
            return;
        }

        if (inCallScreenMode == InCallScreenMode.OTA_NORMAL) {
            if (DBG) log("- updateScreen: OTA call state NORMAL...");
            if (mApp.otaUtils != null) {
                if (DBG) log("- updateScreen: mApp.otaUtils is not null, call otaShowProperScreen");
                mApp.otaUtils.otaShowProperScreen();
            }
            return;
        } else if (inCallScreenMode == InCallScreenMode.OTA_ENDED) {
            if (DBG) log("- updateScreen: OTA call ended state ...");
            // Wake up the screen when we get notification, good or bad.
            mApp.wakeUpScreen();
            if (mApp.cdmaOtaScreenState.otaScreenState
                == CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION) {
                if (DBG) log("- updateScreen: OTA_STATUS_ACTIVATION");
                if (mApp.otaUtils != null) {
                    if (DBG) log("- updateScreen: mApp.otaUtils is not null, "
                                  + "call otaShowActivationScreen");
                    mApp.otaUtils.otaShowActivateScreen();
                }
            } else {
                if (DBG) log("- updateScreen: OTA Call end state for Dialogs");
                if (mApp.otaUtils != null) {
                    if (DBG) log("- updateScreen: Show OTA Success Failure dialog");
                    mApp.otaUtils.otaShowSuccessFailure();
                }
            }
            return;
        } else if (inCallScreenMode == InCallScreenMode.MANAGE_CONFERENCE) {
            if (DBG) log("- updateScreen: manage conference mode (NOT updating in-call UI)...");
            updateManageConferencePanelIfNecessary();
            return;
        } else if (inCallScreenMode == InCallScreenMode.CALL_ENDED) {
            if (DBG) log("- updateScreen: call ended state...");
            // Continue with the rest of updateScreen() as usual, since we do
            // need to update the background (to the special "call ended" color)
            // and the CallCard (to show the "Call ended" label.)
        }

        if (DBG) log("- updateScreen: updating the in-call UI...");
        // Note we update the InCallTouchUi widget before the CallCard,
        // since the CallCard adjusts its size based on how much vertical
        // space the InCallTouchUi widget needs.
        updateInCallTouchUi();
        mCallCard.updateState(mCM);
        updateDialpadVisibility();
        updateProviderOverlay();
        updateProgressIndication();

        // Forcibly take down all dialog if an incoming call is ringing.
        if (mCM.hasActiveRingingCall()) {
            dismissAllDialogs();
        } else {
            // Wait prompt dialog is not currently up.  But it *should* be
            // up if the FG call has a connection in the WAIT state and
            // the phone isn't ringing.
            String postDialStr = null;
            List<Connection> fgConnections = mCM.getFgCallConnections();
            int phoneType = mCM.getFgPhone().getPhoneType();
            if (phoneType == Phone.PHONE_TYPE_CDMA) {
                Connection fgLatestConnection = mCM.getFgCallLatestConnection();
                if (mApp.cdmaPhoneCallState.getCurrentCallState() ==
                        CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                    for (Connection cn : fgConnections) {
                        if ((cn != null) && (cn.getPostDialState() ==
                                Connection.PostDialState.WAIT)) {
                            cn.cancelPostDial();
                        }
                    }
                } else if ((fgLatestConnection != null)
                     && (fgLatestConnection.getPostDialState() == Connection.PostDialState.WAIT)) {
                    if(DBG) log("show the Wait dialog for CDMA");
                    postDialStr = fgLatestConnection.getRemainingPostDialString();
                    showWaitPromptDialog(fgLatestConnection, postDialStr);
                }
            } else if ((phoneType == Phone.PHONE_TYPE_GSM)
                    || (phoneType == Phone.PHONE_TYPE_SIP)) {
                for (Connection cn : fgConnections) {
                    if ((cn != null) && (cn.getPostDialState() == Connection.PostDialState.WAIT)) {
                        postDialStr = cn.getRemainingPostDialString();
                        showWaitPromptDialog(cn, postDialStr);
                    }
                }
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
        }
    }

    /**
     * (Re)synchronizes the onscreen UI with the current state of the
     * telephony framework.
     *
     * @return SyncWithPhoneStateStatus.SUCCESS if we successfully updated the UI, or
     *    SyncWithPhoneStateStatus.PHONE_NOT_IN_USE if there was no phone state to sync
     *    with (ie. the phone was completely idle).  In the latter case, we
     *    shouldn't even be in the in-call UI in the first place, and it's
     *    the caller's responsibility to bail out of this activity by
     *    calling endInCallScreenSession if appropriate.
     *
     * This method directly calls updateScreen() in the normal "phone is
     * in use" case, so there's no need for the caller to do so.
     */
    private SyncWithPhoneStateStatus syncWithPhoneState() {
        boolean updateSuccessful = false;
        if (DBG) log("syncWithPhoneState()...");
        if (DBG) PhoneUtils.dumpCallState(mPhone);
        if (VDBG) dumpBluetoothState();

        // Make sure the Phone is "in use".  (If not, we shouldn't be on
        // this screen in the first place.)

        // An active or just-ended OTA call counts as "in use".
        if (TelephonyCapabilities.supportsOtasp(mCM.getFgPhone())
                && ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL)
                    || (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED))) {
            // Even when OTA Call ends, need to show OTA End UI,
            // so return Success to allow UI update.
            return SyncWithPhoneStateStatus.SUCCESS;
        }

        // If an MMI code is running that also counts as "in use".
        //
        // TODO: We currently only call getPendingMmiCodes() for GSM
        //   phones.  (The code's been that way all along.)  But CDMAPhone
        //   does in fact implement getPendingMmiCodes(), so should we
        //   check that here regardless of the phone type?
        boolean hasPendingMmiCodes =
                (mPhone.getPhoneType() == Phone.PHONE_TYPE_GSM)
                && !mPhone.getPendingMmiCodes().isEmpty();

        // Finally, it's also OK to stay here on the InCallScreen if we
        // need to display a progress indicator while something's
        // happening in the background.
        boolean showProgressIndication = mApp.inCallUiState.isProgressIndicationActive();

        if (mCM.hasActiveFgCall() || mCM.hasActiveBgCall() || mCM.hasActiveRingingCall()
                || hasPendingMmiCodes || showProgressIndication) {
            if (VDBG) log("syncWithPhoneState: it's ok to be here; update the screen...");
            updateScreen();
            return SyncWithPhoneStateStatus.SUCCESS;
        }

        Log.i(LOG_TAG, "syncWithPhoneState: phone is idle (shouldn't be here)");
        return SyncWithPhoneStateStatus.PHONE_NOT_IN_USE;
    }



    private void handleMissingVoiceMailNumber() {
        if (DBG) log("handleMissingVoiceMailNumber");

        final Message msg = Message.obtain(mHandler);
        msg.what = DONT_ADD_VOICEMAIL_NUMBER;

        final Message msg2 = Message.obtain(mHandler);
        msg2.what = ADD_VOICEMAIL_NUMBER;

        mMissingVoicemailDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.no_vm_number)
                .setMessage(R.string.no_vm_number_msg)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (VDBG) log("Missing voicemail AlertDialog: POSITIVE click...");
                            msg.sendToTarget();  // see dontAddVoiceMailNumber()
                            mApp.pokeUserActivity();
                        }})
                .setNegativeButton(R.string.add_vm_number_str,
                                   new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (VDBG) log("Missing voicemail AlertDialog: NEGATIVE click...");
                            msg2.sendToTarget();  // see addVoiceMailNumber()
                            mApp.pokeUserActivity();
                        }})
                .setOnCancelListener(new OnCancelListener() {
                        public void onCancel(DialogInterface dialog) {
                            if (VDBG) log("Missing voicemail AlertDialog: CANCEL handler...");
                            msg.sendToTarget();  // see dontAddVoiceMailNumber()
                            mApp.pokeUserActivity();
                        }})
                .create();

        // When the dialog is up, completely hide the in-call UI
        // underneath (which is in a partially-constructed state).
        mMissingVoicemailDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        mMissingVoicemailDialog.show();
    }

    private void addVoiceMailNumberPanel() {
        if (mMissingVoicemailDialog != null) {
            mMissingVoicemailDialog.dismiss();
            mMissingVoicemailDialog = null;
        }
        if (DBG) log("addVoiceMailNumberPanel: finishing InCallScreen...");
        endInCallScreenSession();

        if (DBG) log("show vm setting");

        // navigate to the Voicemail setting in the Call Settings activity.
        Intent intent = new Intent(CallFeaturesSetting.ACTION_ADD_VOICEMAIL);
        intent.setClass(this, CallFeaturesSetting.class);
        startActivity(intent);
    }

    private void dontAddVoiceMailNumber() {
        if (mMissingVoicemailDialog != null) {
            mMissingVoicemailDialog.dismiss();
            mMissingVoicemailDialog = null;
        }
        if (DBG) log("dontAddVoiceMailNumber: finishing InCallScreen...");
        endInCallScreenSession();
    }

    /**
     * Do some delayed cleanup after a Phone call gets disconnected.
     *
     * This method gets called a couple of seconds after any DISCONNECT
     * event from the Phone; it's triggered by the
     * DELAYED_CLEANUP_AFTER_DISCONNECT message we send in onDisconnect().
     *
     * If the Phone is totally idle right now, that means we've already
     * shown the "call ended" state for a couple of seconds, and it's now
     * time to endInCallScreenSession this activity.
     *
     * If the Phone is *not* idle right now, that probably means that one
     * call ended but the other line is still in use.  In that case, do
     * nothing, and instead stay here on the InCallScreen.
     */
    private void delayedCleanupAfterDisconnect() {
        log("delayedCleanupAfterDisconnect()...  Phone state = " + mCM.getState());

        // Clean up any connections in the DISCONNECTED state.
        //
        // [Background: Even after a connection gets disconnected, its
        // Connection object still stays around, in the special
        // DISCONNECTED state.  This is necessary because we we need the
        // caller-id information from that Connection to properly draw the
        // "Call ended" state of the CallCard.
        //   But at this point we truly don't need that connection any
        // more, so tell the Phone that it's now OK to to clean up any
        // connections still in that state.]
        mCM.clearDisconnected();

        // There are two cases where we should *not* exit the InCallScreen:
        //   (1) Phone is still in use
        // or
        //   (2) There's an active progress indication (i.e. the "Retrying..."
        //       progress dialog) that we need to continue to display.

        boolean stayHere = phoneIsInUse() || mApp.inCallUiState.isProgressIndicationActive();

        if (stayHere) {
            log("- delayedCleanupAfterDisconnect: staying on the InCallScreen...");
        } else {
            // Phone is idle!  We should exit the in-call UI now.
            if (DBG) log("- delayedCleanupAfterDisconnect: phone is idle...");

            // And (finally!) exit from the in-call screen
            // (but not if we're already in the process of pausing...)
            if (mIsForegroundActivity) {
                if (DBG) log("- delayedCleanupAfterDisconnect: finishing InCallScreen...");

                // In some cases we finish the call by taking the user to the
                // Call Log.  Otherwise, we simply call endInCallScreenSession,
                // which will take us back to wherever we came from.
                //
                // UI note: In eclair and earlier, we went to the Call Log
                // after outgoing calls initiated on the device, but never for
                // incoming calls.  Now we do it for incoming calls too, as
                // long as the call was answered by the user.  (We always go
                // back where you came from after a rejected or missed incoming
                // call.)
                //
                // And in any case, *never* go to the call log if we're in
                // emergency mode (i.e. if the screen is locked and a lock
                // pattern or PIN/password is set), or if we somehow got here
                // on a non-voice-capable device.

                if (VDBG) log("- Post-call behavior:");
                if (VDBG) log("  - mLastDisconnectCause = " + mLastDisconnectCause);
                if (VDBG) log("  - isPhoneStateRestricted() = " + isPhoneStateRestricted());

                // DisconnectCause values in the most common scenarios:
                // - INCOMING_MISSED: incoming ringing call times out, or the
                //                    other end hangs up while still ringing
                // - INCOMING_REJECTED: user rejects the call while ringing
                // - LOCAL: user hung up while a call was active (after
                //          answering an incoming call, or after making an
                //          outgoing call)
                // - NORMAL: the other end hung up (after answering an incoming
                //           call, or after making an outgoing call)

                if ((mLastDisconnectCause != Connection.DisconnectCause.INCOMING_MISSED)
                        && (mLastDisconnectCause != Connection.DisconnectCause.INCOMING_REJECTED)
                        && !isPhoneStateRestricted()
                        && PhoneApp.sVoiceCapable) {
                    final Intent intent = mApp.createPhoneEndIntentUsingCallOrigin();
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    if (VDBG) {
                        log("- Show Call Log (or Dialtacts) after disconnect. Current intent: "
                                + intent);
                    }
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        // Don't crash if there's somehow no "Call log" at
                        // all on this device.
                        // (This should never happen, though, since we already
                        // checked PhoneApp.sVoiceCapable above, and any
                        // voice-capable device surely *should* have a call
                        // log activity....)
                        Log.w(LOG_TAG, "delayedCleanupAfterDisconnect: "
                              + "transition to call log failed; intent = " + intent);
                        // ...so just return back where we came from....
                    }
                    // Even if we did go to the call log, note that we still
                    // call endInCallScreenSession (below) to make sure we don't
                    // stay in the activity history.
                }

                endInCallScreenSession();
            }

            // Reset the call origin when the session ends and this in-call UI is being finished.
            mApp.setLatestActiveCallOrigin(null);
        }
    }


    /**
     * View.OnClickListener implementation.
     *
     * This method handles clicks from UI elements that use the
     * InCallScreen itself as their OnClickListener.
     *
     * Note: Currently this method is used only for a few special buttons: the
     * mButtonManageConferenceDone "Back to call" button, and the OTASP-specific
     * buttons managed by OtaUtils.java.  *Most* in-call controls are handled by
     * the handleOnscreenButtonClick() method, via the InCallTouchUi widget.
     */
    public void onClick(View view) {
        int id = view.getId();
        if (VDBG) log("onClick(View " + view + ", id " + id + ")...");

        switch (id) {
            case R.id.manage_done:  // mButtonManageConferenceDone
                if (VDBG) log("onClick: mButtonManageConferenceDone...");
                // Hide the Manage Conference panel, return to NORMAL mode.
                setInCallScreenMode(InCallScreenMode.NORMAL);
                requestUpdateScreen();
                break;

            default:
                // Presumably one of the OTASP-specific buttons managed by
                // OtaUtils.java.
                // (TODO: It would be cleaner for the OtaUtils instance itself to
                // be the OnClickListener for its own buttons.)

                if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL
                     || mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED)
                    && mApp.otaUtils != null) {
                    mApp.otaUtils.onClickHandler(id);
                } else {
                    // Uh oh: we *should* only receive clicks here from the
                    // buttons managed by OtaUtils.java, but if we're not in one
                    // of the special OTASP modes, those buttons shouldn't have
                    // been visible in the first place.
                    Log.w(LOG_TAG,
                          "onClick: unexpected click from ID " + id + " (View = " + view + ")");
                }
                break;
        }

        EventLog.writeEvent(EventLogTags.PHONE_UI_BUTTON_CLICK,
                (view instanceof TextView) ? ((TextView) view).getText() : "");

        // Clicking any onscreen UI element counts as explicit "user activity".
        mApp.pokeUserActivity();
    }

    private void onHoldClick() {
        final boolean hasActiveCall = mCM.hasActiveFgCall();
        final boolean hasHoldingCall = mCM.hasActiveBgCall();
        log("onHoldClick: hasActiveCall = " + hasActiveCall
            + ", hasHoldingCall = " + hasHoldingCall);
        boolean newHoldState;
        boolean holdButtonEnabled;
        if (hasActiveCall && !hasHoldingCall) {
            // There's only one line in use, and that line is active.
            PhoneUtils.switchHoldingAndActive(
                mCM.getFirstActiveBgCall());  // Really means "hold" in this state
            newHoldState = true;
            holdButtonEnabled = true;
        } else if (!hasActiveCall && hasHoldingCall) {
            // There's only one line in use, and that line is on hold.
            PhoneUtils.switchHoldingAndActive(
                mCM.getFirstActiveBgCall());  // Really means "unhold" in this state
            newHoldState = false;
            holdButtonEnabled = true;
        } else {
            // Either zero or 2 lines are in use; "hold/unhold" is meaningless.
            newHoldState = false;
            holdButtonEnabled = false;
        }
        // No need to forcibly update the onscreen UI; just wait for the
        // onPhoneStateChanged() callback.  (This seems to be responsive
        // enough.)

        // Also, any time we hold or unhold, force the DTMF dialpad to close.
        hideDialpadInternal(true);  // do the "closing" animation
    }

    /**
     * Toggles in-call audio between speaker and the built-in earpiece (or
     * wired headset.)
     */
    public void toggleSpeaker() {
        // TODO: Turning on the speaker seems to enable the mic
        //   whether or not the "mute" feature is active!
        // Not sure if this is an feature of the telephony API
        //   that I need to handle specially, or just a bug.
        boolean newSpeakerState = !PhoneUtils.isSpeakerOn(this);
        log("toggleSpeaker(): newSpeakerState = " + newSpeakerState);

        if (newSpeakerState && isBluetoothAvailable() && isBluetoothAudioConnected()) {
            disconnectBluetoothAudio();
        }
        PhoneUtils.turnOnSpeaker(this, newSpeakerState, true);

        // And update the InCallTouchUi widget (since the "audio mode"
        // button might need to change its appearance based on the new
        // audio state.)
        updateInCallTouchUi();
    }

    /*
     * onMuteClick is called only when there is a foreground call
     */
    private void onMuteClick() {
        boolean newMuteState = !PhoneUtils.getMute();
        log("onMuteClick(): newMuteState = " + newMuteState);
        PhoneUtils.setMute(newMuteState);
    }

    /**
     * Toggles whether or not to route in-call audio to the bluetooth
     * headset, or do nothing (but log a warning) if no bluetooth device
     * is actually connected.
     *
     * TODO: this method is currently unused, but the "audio mode" UI
     * design is still in flux so let's keep it around for now.
     * (But if we ultimately end up *not* providing any way for the UI to
     * simply "toggle bluetooth", we can get rid of this method.)
     */
    public void toggleBluetooth() {
        if (VDBG) log("toggleBluetooth()...");

        if (isBluetoothAvailable()) {
            // Toggle the bluetooth audio connection state:
            if (isBluetoothAudioConnected()) {
                disconnectBluetoothAudio();
            } else {
                // Manually turn the speaker phone off, instead of allowing the
                // Bluetooth audio routing to handle it, since there's other
                // important state-updating that needs to happen in the
                // PhoneUtils.turnOnSpeaker() method.
                // (Similarly, whenever the user turns *on* the speaker, we
                // manually disconnect the active bluetooth headset;
                // see toggleSpeaker() and/or switchInCallAudio().)
                if (PhoneUtils.isSpeakerOn(this)) {
                    PhoneUtils.turnOnSpeaker(this, false, true);
                }

                connectBluetoothAudio();
            }
        } else {
            // Bluetooth isn't available; the onscreen UI shouldn't have
            // allowed this request in the first place!
            Log.w(LOG_TAG, "toggleBluetooth(): bluetooth is unavailable");
        }

        // And update the InCallTouchUi widget (since the "audio mode"
        // button might need to change its appearance based on the new
        // audio state.)
        updateInCallTouchUi();
    }

    /**
     * Switches the current routing of in-call audio between speaker,
     * bluetooth, and the built-in earpiece (or wired headset.)
     *
     * This method is used on devices that provide a single 3-way switch
     * for audio routing.  For devices that provide separate toggles for
     * Speaker and Bluetooth, see toggleBluetooth() and toggleSpeaker().
     *
     * TODO: UI design is still in flux.  If we end up totally
     * eliminating the concept of Speaker and Bluetooth toggle buttons,
     * we can get rid of toggleBluetooth() and toggleSpeaker().
     */
    public void switchInCallAudio(InCallAudioMode newMode) {
        log("switchInCallAudio: new mode = " + newMode);
        switch (newMode) {
            case SPEAKER:
                if (!PhoneUtils.isSpeakerOn(this)) {
                    // Switch away from Bluetooth, if it was active.
                    if (isBluetoothAvailable() && isBluetoothAudioConnected()) {
                        disconnectBluetoothAudio();
                    }
                    PhoneUtils.turnOnSpeaker(this, true, true);
                }
                break;

            case BLUETOOTH:
                // If already connected to BT, there's nothing to do here.
                if (isBluetoothAvailable() && !isBluetoothAudioConnected()) {
                    // Manually turn the speaker phone off, instead of allowing the
                    // Bluetooth audio routing to handle it, since there's other
                    // important state-updating that needs to happen in the
                    // PhoneUtils.turnOnSpeaker() method.
                    // (Similarly, whenever the user turns *on* the speaker, we
                    // manually disconnect the active bluetooth headset;
                    // see toggleSpeaker() and/or switchInCallAudio().)
                    if (PhoneUtils.isSpeakerOn(this)) {
                        PhoneUtils.turnOnSpeaker(this, false, true);
                    }
                    connectBluetoothAudio();
                }
                break;

            case EARPIECE:
                // Switch to either the handset earpiece, or the wired headset (if connected.)
                // (Do this by simply making sure both speaker and bluetooth are off.)
                if (isBluetoothAvailable() && isBluetoothAudioConnected()) {
                    disconnectBluetoothAudio();
                }
                if (PhoneUtils.isSpeakerOn(this)) {
                    PhoneUtils.turnOnSpeaker(this, false, true);
                }
                break;

            default:
                Log.wtf(LOG_TAG, "switchInCallAudio: unexpected mode " + newMode);
                break;
        }

        // And finally, update the InCallTouchUi widget (since the "audio
        // mode" button might need to change its appearance based on the
        // new audio state.)
        updateInCallTouchUi();
    }

    /**
     * Handle a click on the "Show/Hide dialpad" button.
     */
    private void onShowHideDialpad() {
        if (VDBG) log("onShowHideDialpad()...");
        if (mDialer.isOpened()) {
            hideDialpadInternal(true);  // do the "closing" animation
        } else {
            showDialpadInternal(true);  // do the "opening" animation
        }
    }

    // Internal wrapper around DTMFTwelveKeyDialer.openDialer()
    private void showDialpadInternal(boolean animate) {
        mDialer.openDialer(animate);
        // And update the InCallUiState (so that we'll restore the dialpad
        // to the correct state if we get paused/resumed).
        mApp.inCallUiState.showDialpad = true;
    }

    // Internal wrapper around DTMFTwelveKeyDialer.closeDialer()
    private void hideDialpadInternal(boolean animate) {
        mDialer.closeDialer(animate);
        // And update the InCallUiState (so that we'll restore the dialpad
        // to the correct state if we get paused/resumed).
        mApp.inCallUiState.showDialpad = false;
    }

    /**
     * Handles button clicks from the InCallTouchUi widget.
     */
    /* package */ void handleOnscreenButtonClick(int id) {
        if (DBG) log("handleOnscreenButtonClick(id " + id + ")...");

        switch (id) {
            // Actions while an incoming call is ringing:
            case R.id.incomingCallAnswer:
                internalAnswerCall();
                break;
            case R.id.incomingCallReject:
                hangupRingingCall();
                break;
            case R.id.incomingCallRespondViaSms:
                internalRespondViaSms();
                break;

            // The other regular (single-tap) buttons used while in-call:
            case R.id.holdButton:
                onHoldClick();
                break;
            case R.id.swapButton:
                internalSwapCalls();
                break;
            case R.id.endButton:
                internalHangup();
                break;
            case R.id.dialpadButton:
                onShowHideDialpad();
                break;
            case R.id.muteButton:
                onMuteClick();
                break;
            case R.id.addButton:
                PhoneUtils.startNewCall(mCM);  // Fires off an ACTION_DIAL intent
                break;
            case R.id.mergeButton:
            case R.id.cdmaMergeButton:
                PhoneUtils.mergeCalls(mCM);
                break;
            case R.id.manageConferenceButton:
                // Show the Manage Conference panel.
                setInCallScreenMode(InCallScreenMode.MANAGE_CONFERENCE);
                requestUpdateScreen();
                break;

            default:
                Log.w(LOG_TAG, "handleOnscreenButtonClick: unexpected ID " + id);
                break;
        }

        // Clicking any onscreen UI element counts as explicit "user activity".
        mApp.pokeUserActivity();

        // Just in case the user clicked a "stateful" UI element (like one
        // of the toggle buttons), we force the in-call buttons to update,
        // to make sure the user sees the *new* current state.
        //
        // Note that some in-call buttons will *not* immediately change the
        // state of the UI, namely those that send a request to the telephony
        // layer (like "Hold" or "End call".)  For those buttons, the
        // updateInCallTouchUi() call here won't have any visible effect.
        // Instead, the UI will be updated eventually when the next
        // onPhoneStateChanged() event comes in and triggers an updateScreen()
        // call.
        //
        // TODO: updateInCallTouchUi() is overkill here; it would be
        // more efficient to update *only* the affected button(s).
        // (But this isn't a big deal since updateInCallTouchUi() is pretty
        // cheap anyway...)
        updateInCallTouchUi();
    }

    /**
     * Update the network provider's overlay based on the value of
     * InCallUiState.providerOverlayVisible.
     * If false the overlay is hidden otherwise it is shown.  A
     * delayed message is posted to take the overalay down after
     * PROVIDER_OVERLAY_TIMEOUT. This ensures the user will see the
     * overlay even if the call setup phase is very short.
     */
    private void updateProviderOverlay() {
        final InCallUiState inCallUiState = mApp.inCallUiState;

        if (VDBG) log("updateProviderOverlay: " + inCallUiState.providerOverlayVisible);

        ViewGroup overlay = (ViewGroup) findViewById(R.id.inCallProviderOverlay);

        if (inCallUiState.providerOverlayVisible) {
            CharSequence template = getText(R.string.calling_via_template);
            CharSequence text = TextUtils.expandTemplate(template,
                                                         inCallUiState.providerLabel,
                                                         inCallUiState.providerAddress);

            TextView message = (TextView) findViewById(R.id.callingVia);
            message.setText(text);

            ImageView image = (ImageView) findViewById(R.id.callingViaIcon);
            image.setImageDrawable(inCallUiState.providerIcon);

            overlay.setVisibility(View.VISIBLE);

            // Remove any zombie messages and then send a message to
            // self to remove the overlay after some time.
            mHandler.removeMessages(EVENT_HIDE_PROVIDER_OVERLAY);
            Message msg = Message.obtain(mHandler, EVENT_HIDE_PROVIDER_OVERLAY);
            mHandler.sendMessageDelayed(msg, PROVIDER_OVERLAY_TIMEOUT);
        } else {
            overlay.setVisibility(View.GONE);
        }
    }

    /**
     * Display a status or error indication to the user according to the
     * specified InCallUiState.CallStatusCode value.
     */
    private void showStatusIndication(CallStatusCode status) {
        switch (status) {
            case SUCCESS:
                // The InCallScreen does not need to display any kind of error indication,
                // so we shouldn't have gotten here in the first place.
                Log.wtf(LOG_TAG, "showStatusIndication: nothing to display");
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
                showGenericErrorDialog(R.string.incall_error_power_off,
                                       true /* isStartupError */);
                break;

            case EMERGENCY_ONLY:
                // Only emergency numbers are allowed, but we tried to dial
                // a non-emergency number.
                // (This state is currently unused; see comments above.)
                showGenericErrorDialog(R.string.incall_error_emergency_only,
                                       true /* isStartupError */);
                break;

            case OUT_OF_SERVICE:
                // No network connection.
                showGenericErrorDialog(R.string.incall_error_out_of_service,
                                       true /* isStartupError */);
                break;

            case NO_PHONE_NUMBER_SUPPLIED:
                // The supplied Intent didn't contain a valid phone number.
                // (This is rare and should only ever happen with broken
                // 3rd-party apps.)  For now just show a generic error.
                showGenericErrorDialog(R.string.incall_error_no_phone_number_supplied,
                                       true /* isStartupError */);
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
                showGenericErrorDialog(R.string.incall_error_call_failed,
                                       true /* isStartupError */);
                break;

            case VOICEMAIL_NUMBER_MISSING:
                // We tried to call a voicemail: URI but the device has no
                // voicemail number configured.
                handleMissingVoiceMailNumber();
                break;

            case CDMA_CALL_LOST:
                // This status indicates that InCallScreen should display the
                // CDMA-specific "call lost" dialog.  (If an outgoing call fails,
                // and the CDMA "auto-retry" feature is enabled, *and* the retried
                // call fails too, we display this specific dialog.)
                //
                // TODO: currently unused; see InCallUiState.needToShowCallLostDialog
                break;

            case EXITED_ECM:
                // This status indicates that InCallScreen needs to display a
                // warning that we're exiting ECM (emergency callback mode).
                showExitingECMDialog();
                break;

            default:
                throw new IllegalStateException(
                    "showStatusIndication: unexpected status code: " + status);
        }

        // TODO: still need to make sure that pressing OK or BACK from
        // *any* of the dialogs we launch here ends up calling
        // inCallUiState.clearPendingCallStatusCode()
        //  *and*
        // make sure the Dialog handles both OK *and* cancel by calling
        // endInCallScreenSession.  (See showGenericErrorDialog() for an
        // example.)
        //
        // (showGenericErrorDialog() currently does this correctly,
        // but handleMissingVoiceMailNumber() probably needs to be fixed too.)
        //
        // Also need to make sure that bailing out of any of these dialogs by
        // pressing Home clears out the pending status code too.  (If you do
        // that, neither the dialog's clickListener *or* cancelListener seems
        // to run...)
    }

    /**
     * Utility function to bring up a generic "error" dialog, and then bail
     * out of the in-call UI when the user hits OK (or the BACK button.)
     */
    private void showGenericErrorDialog(int resid, boolean isStartupError) {
        CharSequence msg = getResources().getText(resid);
        if (DBG) log("showGenericErrorDialog('" + msg + "')...");

        // create the clicklistener and cancel listener as needed.
        DialogInterface.OnClickListener clickListener;
        OnCancelListener cancelListener;
        if (isStartupError) {
            clickListener = new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    bailOutAfterErrorDialog();
                }};
            cancelListener = new OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    bailOutAfterErrorDialog();
                }};
        } else {
            clickListener = new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    delayedCleanupAfterDisconnect();
                }};
            cancelListener = new OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    delayedCleanupAfterDisconnect();
                }};
        }

        // TODO: Consider adding a setTitle() call here (with some generic
        // "failure" title?)
        mGenericErrorDialog = new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, clickListener)
                .setOnCancelListener(cancelListener)
                .create();

        // When the dialog is up, completely hide the in-call UI
        // underneath (which is in a partially-constructed state).
        mGenericErrorDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        mGenericErrorDialog.show();
    }

    private void showCallLostDialog() {
        if (DBG) log("showCallLostDialog()...");

        // Don't need to show the dialog if InCallScreen isn't in the forgeround
        if (!mIsForegroundActivity) {
            if (DBG) log("showCallLostDialog: not the foreground Activity! Bailing out...");
            return;
        }

        // Don't need to show the dialog again, if there is one already.
        if (mCallLostDialog != null) {
            if (DBG) log("showCallLostDialog: There is a mCallLostDialog already.");
            return;
        }

        mCallLostDialog = new AlertDialog.Builder(this)
                .setMessage(R.string.call_lost)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .create();
        mCallLostDialog.show();
    }

    /**
     * Displays the "Exiting ECM" warning dialog.
     *
     * Background: If the phone is currently in ECM (Emergency callback
     * mode) and we dial a non-emergency number, that automatically
     * *cancels* ECM.  (That behavior comes from CdmaCallTracker.dial().)
     * When that happens, we need to warn the user that they're no longer
     * in ECM (bug 4207607.)
     *
     * So bring up a dialog explaining what's happening.  There's nothing
     * for the user to do, by the way; we're simply providing an
     * indication that they're exiting ECM.  We *could* use a Toast for
     * this, but toasts are pretty easy to miss, so instead use a dialog
     * with a single "OK" button.
     *
     * TODO: it's ugly that the code here has to make assumptions about
     *   the behavior of the telephony layer (namely that dialing a
     *   non-emergency number while in ECM causes us to exit ECM.)
     *
     *   Instead, this warning dialog should really be triggered by our
     *   handler for the
     *   TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED intent in
     *   PhoneApp.java.  But that won't work until that intent also
     *   includes a *reason* why we're exiting ECM, since we need to
     *   display this dialog when exiting ECM because of an outgoing call,
     *   but NOT if we're exiting ECM because the user manually turned it
     *   off via the EmergencyCallbackModeExitDialog.
     *
     *   Or, it might be simpler to just have outgoing non-emergency calls
     *   *not* cancel ECM.  That way the UI wouldn't have to do anything
     *   special here.
     */
    private void showExitingECMDialog() {
        Log.i(LOG_TAG, "showExitingECMDialog()...");

        if (mExitingECMDialog != null) {
            if (DBG) log("- DISMISSING mExitingECMDialog.");
            mExitingECMDialog.dismiss();  // safe even if already dismissed
            mExitingECMDialog = null;
        }

        // When the user dismisses the "Exiting ECM" dialog, we clear out
        // the pending call status code field (since we're done with this
        // dialog), but do *not* bail out of the InCallScreen.

        final InCallUiState inCallUiState = mApp.inCallUiState;
        DialogInterface.OnClickListener clickListener = new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    inCallUiState.clearPendingCallStatusCode();
                }};
        OnCancelListener cancelListener = new OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    inCallUiState.clearPendingCallStatusCode();
                }};

        // Ultra-simple AlertDialog with only an OK button:
        mExitingECMDialog = new AlertDialog.Builder(this)
                .setMessage(R.string.progress_dialog_exiting_ecm)
                .setPositiveButton(R.string.ok, clickListener)
                .setOnCancelListener(cancelListener)
                .create();
        mExitingECMDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        mExitingECMDialog.show();
    }

    private void bailOutAfterErrorDialog() {
        if (mGenericErrorDialog != null) {
            if (DBG) log("bailOutAfterErrorDialog: DISMISSING mGenericErrorDialog.");
            mGenericErrorDialog.dismiss();
            mGenericErrorDialog = null;
        }
        if (DBG) log("bailOutAfterErrorDialog(): end InCallScreen session...");

        // Now that the user has dismissed the error dialog (presumably by
        // either hitting the OK button or pressing Back, we can now reset
        // the pending call status code field.
        //
        // (Note that the pending call status is NOT cleared simply
        // by the InCallScreen being paused or finished, since the resulting
        // dialog is supposed to persist across orientation changes or if the
        // screen turns off.)
        //
        // See the "Error / diagnostic indications" section of
        // InCallUiState.java for more detailed info about the
        // pending call status code field.
        final InCallUiState inCallUiState = mApp.inCallUiState;
        inCallUiState.clearPendingCallStatusCode();

        // Force the InCallScreen to truly finish(), rather than just
        // moving it to the back of the activity stack (which is what
        // our finish() method usually does.)
        // This is necessary to avoid an obscure scenario where the
        // InCallScreen can get stuck in an inconsistent state, somehow
        // causing a *subsequent* outgoing call to fail (bug 4172599).
        endInCallScreenSession(true /* force a real finish() call */);
    }

    /**
     * Dismisses (and nulls out) all persistent Dialogs managed
     * by the InCallScreen.  Useful if (a) we're about to bring up
     * a dialog and want to pre-empt any currently visible dialogs,
     * or (b) as a cleanup step when the Activity is going away.
     */
    private void dismissAllDialogs() {
        if (DBG) log("dismissAllDialogs()...");

        // Note it's safe to dismiss() a dialog that's already dismissed.
        // (Even if the AlertDialog object(s) below are still around, it's
        // possible that the actual dialog(s) may have already been
        // dismissed by the user.)

        if (mMissingVoicemailDialog != null) {
            if (VDBG) log("- DISMISSING mMissingVoicemailDialog.");
            mMissingVoicemailDialog.dismiss();
            mMissingVoicemailDialog = null;
        }
        if (mMmiStartedDialog != null) {
            if (VDBG) log("- DISMISSING mMmiStartedDialog.");
            mMmiStartedDialog.dismiss();
            mMmiStartedDialog = null;
        }
        if (mGenericErrorDialog != null) {
            if (VDBG) log("- DISMISSING mGenericErrorDialog.");
            mGenericErrorDialog.dismiss();
            mGenericErrorDialog = null;
        }
        if (mSuppServiceFailureDialog != null) {
            if (VDBG) log("- DISMISSING mSuppServiceFailureDialog.");
            mSuppServiceFailureDialog.dismiss();
            mSuppServiceFailureDialog = null;
        }
        if (mWaitPromptDialog != null) {
            if (VDBG) log("- DISMISSING mWaitPromptDialog.");
            mWaitPromptDialog.dismiss();
            mWaitPromptDialog = null;
        }
        if (mWildPromptDialog != null) {
            if (VDBG) log("- DISMISSING mWildPromptDialog.");
            mWildPromptDialog.dismiss();
            mWildPromptDialog = null;
        }
        if (mCallLostDialog != null) {
            if (VDBG) log("- DISMISSING mCallLostDialog.");
            mCallLostDialog.dismiss();
            mCallLostDialog = null;
        }
        if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL
                || mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED)
                && mApp.otaUtils != null) {
            mApp.otaUtils.dismissAllOtaDialogs();
        }
        if (mPausePromptDialog != null) {
            if (DBG) log("- DISMISSING mPausePromptDialog.");
            mPausePromptDialog.dismiss();
            mPausePromptDialog = null;
        }
        if (mExitingECMDialog != null) {
            if (DBG) log("- DISMISSING mExitingECMDialog.");
            mExitingECMDialog.dismiss();
            mExitingECMDialog = null;
        }
    }

    /**
     * Updates the state of the onscreen "progress indication" used in
     * some (relatively rare) scenarios where we need to wait for
     * something to happen before enabling the in-call UI.
     *
     * If necessary, this method will cause a ProgressDialog (i.e. a
     * spinning wait cursor) to be drawn *on top of* whatever the current
     * state of the in-call UI is.
     *
     * @see InCallUiState.ProgressIndicationType
     */
    private void updateProgressIndication() {
        // If an incoming call is ringing, that takes priority over any
        // possible value of inCallUiState.progressIndication.
        if (mCM.hasActiveRingingCall()) {
            dismissProgressIndication();
            return;
        }

        // Otherwise, put up a progress indication if indicated by the
        // inCallUiState.progressIndication field.
        final InCallUiState inCallUiState = mApp.inCallUiState;
        switch (inCallUiState.getProgressIndication()) {
            case NONE:
                // No progress indication necessary, so make sure it's dismissed.
                dismissProgressIndication();
                break;

            case TURNING_ON_RADIO:
                showProgressIndication(
                    R.string.emergency_enable_radio_dialog_title,
                    R.string.emergency_enable_radio_dialog_message);
                break;

            case RETRYING:
                showProgressIndication(
                    R.string.emergency_enable_radio_dialog_title,
                    R.string.emergency_enable_radio_dialog_retry);
                break;

            default:
                Log.wtf(LOG_TAG, "updateProgressIndication: unexpected value: "
                        + inCallUiState.getProgressIndication());
                dismissProgressIndication();
                break;
        }
    }

    /**
     * Show an onscreen "progress indication" with the specified title and message.
     */
    private void showProgressIndication(int titleResId, int messageResId) {
        if (DBG) log("showProgressIndication(message " + messageResId + ")...");

        // TODO: make this be a no-op if the progress indication is
        // already visible with the exact same title and message.

        dismissProgressIndication();  // Clean up any prior progress indication
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setTitle(getText(titleResId));
        mProgressDialog.setMessage(getText(messageResId));
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setCancelable(false);
        mProgressDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        mProgressDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        mProgressDialog.show();
    }

    /**
     * Dismiss the onscreen "progress indication" (if present).
     */
    private void dismissProgressIndication() {
        if (DBG) log("dismissProgressIndication()...");
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();  // safe even if already dismissed
            mProgressDialog = null;
        }
    }


    //
    // Helper functions for answering incoming calls.
    //

    /**
     * Answer a ringing call.  This method does nothing if there's no
     * ringing or waiting call.
     */
    private void internalAnswerCall() {
        log("internalAnswerCall()...");
        // if (DBG) PhoneUtils.dumpCallState(mPhone);

        final boolean hasRingingCall = mCM.hasActiveRingingCall();

        if (hasRingingCall) {
            Phone phone = mCM.getRingingPhone();
            Call ringing = mCM.getFirstActiveRingingCall();
            int phoneType = phone.getPhoneType();
            if (phoneType == Phone.PHONE_TYPE_CDMA) {
                if (DBG) log("internalAnswerCall: answering (CDMA)...");
                if (mCM.hasActiveFgCall()
                        && mCM.getFgPhone().getPhoneType() == Phone.PHONE_TYPE_SIP) {
                    // The incoming call is CDMA call and the ongoing
                    // call is a SIP call. The CDMA network does not
                    // support holding an active call, so there's no
                    // way to swap between a CDMA call and a SIP call.
                    // So for now, we just don't allow a CDMA call and
                    // a SIP call to be active at the same time.We'll
                    // "answer incoming, end ongoing" in this case.
                    if (DBG) log("internalAnswerCall: answer "
                            + "CDMA incoming and end SIP ongoing");
                    PhoneUtils.answerAndEndActive(mCM, ringing);
                } else {
                    PhoneUtils.answerCall(ringing);
                }
            } else if (phoneType == Phone.PHONE_TYPE_SIP) {
                if (DBG) log("internalAnswerCall: answering (SIP)...");
                if (mCM.hasActiveFgCall()
                        && mCM.getFgPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                    // Similar to the PHONE_TYPE_CDMA handling.
                    // The incoming call is SIP call and the ongoing
                    // call is a CDMA call. The CDMA network does not
                    // support holding an active call, so there's no
                    // way to swap between a CDMA call and a SIP call.
                    // So for now, we just don't allow a CDMA call and
                    // a SIP call to be active at the same time.We'll
                    // "answer incoming, end ongoing" in this case.
                    if (DBG) log("internalAnswerCall: answer "
                            + "SIP incoming and end CDMA ongoing");
                    PhoneUtils.answerAndEndActive(mCM, ringing);
                } else {
                    PhoneUtils.answerCall(ringing);
                }
            }else if (phoneType == Phone.PHONE_TYPE_GSM){
                // GSM: this is usually just a wrapper around
                // PhoneUtils.answerCall(), *but* we also need to do
                // something special for the "both lines in use" case.

                final boolean hasActiveCall = mCM.hasActiveFgCall();
                final boolean hasHoldingCall = mCM.hasActiveBgCall();

                if (hasActiveCall && hasHoldingCall) {
                    if (DBG) log("internalAnswerCall: answering (both lines in use!)...");
                    // The relatively rare case where both lines are
                    // already in use.  We "answer incoming, end ongoing"
                    // in this case, according to the current UI spec.
                    PhoneUtils.answerAndEndActive(mCM, ringing);

                    // Alternatively, we could use
                    // PhoneUtils.answerAndEndHolding(mPhone);
                    // here to end the on-hold call instead.
                } else {
                    if (DBG) log("internalAnswerCall: answering...");
                    PhoneUtils.answerCall(ringing);  // Automatically holds the current active call,
                                                    // if there is one
                }
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }

            // Call origin is valid only with outgoing calls. Disable it on incoming calls.
            mApp.setLatestActiveCallOrigin(null);
        }
    }

    /**
     * Answer the ringing call *and* hang up the ongoing call.
     */
    private void internalAnswerAndEnd() {
        if (DBG) log("internalAnswerAndEnd()...");
        if (VDBG) PhoneUtils.dumpCallManager();
        // In the rare case when multiple calls are ringing, the UI policy
        // it to always act on the first ringing call.
        PhoneUtils.answerAndEndActive(mCM, mCM.getFirstActiveRingingCall());
    }

    /**
     * Hang up the ringing call (aka "Don't answer").
     */
    /* package */ void hangupRingingCall() {
        if (DBG) log("hangupRingingCall()...");
        if (VDBG) PhoneUtils.dumpCallManager();
        // In the rare case when multiple calls are ringing, the UI policy
        // it to always act on the first ringing call.
        PhoneUtils.hangupRingingCall(mCM.getFirstActiveRingingCall());
    }

    /**
     * Silence the ringer (if an incoming call is ringing.)
     */
    private void internalSilenceRinger() {
        if (DBG) log("internalSilenceRinger()...");
        final CallNotifier notifier = mApp.notifier;
        if (notifier.isRinging()) {
            // ringer is actually playing, so silence it.
            notifier.silenceRinger();
        }
    }

    /**
     * Respond via SMS to the ringing call.
     * @see RespondViaSmsManager
     */
    private void internalRespondViaSms() {
        log("internalRespondViaSms()...");
        if (VDBG) PhoneUtils.dumpCallManager();

        if (mRespondViaSmsManager == null) {
            throw new IllegalStateException(
                "got internalRespondViaSms(), but mRespondViaSmsManager was never initialized");
        }

        // In the rare case when multiple calls are ringing, the UI policy
        // it to always act on the first ringing call.
        Call ringingCall = mCM.getFirstActiveRingingCall();

        mRespondViaSmsManager.showRespondViaSmsPopup(ringingCall);

        // Silence the ringer, since it would be distracting while you're trying
        // to pick a response.  (Note that we'll restart the ringer if you bail
        // out of the popup, though; see RespondViaSmsCancelListener.)
        internalSilenceRinger();
    }

    /**
     * Hang up the current active call.
     */
    private void internalHangup() {
        Phone.State state = mCM.getState();
        log("internalHangup()...  phone state = " + state);

        // Regardless of the phone state, issue a hangup request.
        // (If the phone is already idle, this call will presumably have no
        // effect (but also see the note below.))
        PhoneUtils.hangup(mCM);

        // If the user just hung up the only active call, we'll eventually exit
        // the in-call UI after the following sequence:
        // - When the hangup() succeeds, we'll get a DISCONNECT event from
        //   the telephony layer (see onDisconnect()).
        // - We immediately switch to the "Call ended" state (see the "delayed
        //   bailout" code path in onDisconnect()) and also post a delayed
        //   DELAYED_CLEANUP_AFTER_DISCONNECT message.
        // - When the DELAYED_CLEANUP_AFTER_DISCONNECT message comes in (see
        //   delayedCleanupAfterDisconnect()) we do some final cleanup, and exit
        //   this activity unless the phone is still in use (i.e. if there's
        //   another call, or something else going on like an active MMI
        //   sequence.)

        if (state == Phone.State.IDLE) {
            // The user asked us to hang up, but the phone was (already) idle!
            Log.w(LOG_TAG, "internalHangup(): phone is already IDLE!");

            // This is rare, but can happen in a few cases:
            // (a) If the user quickly double-taps the "End" button.  In this case
            //   we'll see that 2nd press event during the brief "Call ended"
            //   state (where the phone is IDLE), or possibly even before the
            //   radio has been able to respond to the initial hangup request.
            // (b) More rarely, this can happen if the user presses "End" at the
            //   exact moment that the call ends on its own (like because of the
            //   other person hanging up.)
            // (c) Finally, this could also happen if we somehow get stuck here on
            //   the InCallScreen with the phone truly idle, perhaps due to a
            //   bug where we somehow *didn't* exit when the phone became idle
            //   in the first place.

            // TODO: as a "safety valve" for case (c), consider immediately
            // bailing out of the in-call UI right here.  (The user can always
            // bail out by pressing Home, of course, but they'll probably try
            // pressing End first.)
            //
            //    Log.i(LOG_TAG, "internalHangup(): phone is already IDLE!  Bailing out...");
            //    endInCallScreenSession();
        }
    }

    /**
     * InCallScreen-specific wrapper around PhoneUtils.switchHoldingAndActive().
     */
    private void internalSwapCalls() {
        if (DBG) log("internalSwapCalls()...");

        // Any time we swap calls, force the DTMF dialpad to close.
        // (We want the regular in-call UI to be visible right now, so the
        // user can clearly see which call is now in the foreground.)
        hideDialpadInternal(true);  // do the "closing" animation

        // Also, clear out the "history" of DTMF digits you typed, to make
        // sure you don't see digits from call #1 while call #2 is active.
        // (Yes, this does mean that swapping calls twice will cause you
        // to lose any previous digits from the current call; see the TODO
        // comment on DTMFTwelvKeyDialer.clearDigits() for more info.)
        mDialer.clearDigits();

        // Swap the fg and bg calls.
        // In the future we may provides some way for user to choose among
        // multiple background calls, for now, always act on the first background calll.
        PhoneUtils.switchHoldingAndActive(mCM.getFirstActiveBgCall());

        // If we have a valid BluetoothHandsfree then since CDMA network or
        // Telephony FW does not send us information on which caller got swapped
        // we need to update the second call active state in BluetoothHandsfree internally
        if (mCM.getBgPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            BluetoothHandsfree bthf = mApp.getBluetoothHandsfree();
            if (bthf != null) {
                bthf.cdmaSwapSecondCallState();
            }
        }

    }

    /**
     * Sets the current high-level "mode" of the in-call UI.
     *
     * NOTE: if newMode is CALL_ENDED, the caller is responsible for
     * posting a delayed DELAYED_CLEANUP_AFTER_DISCONNECT message, to make
     * sure the "call ended" state goes away after a couple of seconds.
     *
     * Note this method does NOT refresh of the onscreen UI; the caller is
     * responsible for calling updateScreen() or requestUpdateScreen() if
     * necessary.
     */
    private void setInCallScreenMode(InCallScreenMode newMode) {
        if (DBG) log("setInCallScreenMode: " + newMode);
        mApp.inCallUiState.inCallScreenMode = newMode;

        switch (newMode) {
            case MANAGE_CONFERENCE:
                if (!PhoneUtils.isConferenceCall(mCM.getActiveFgCall())) {
                    Log.w(LOG_TAG, "MANAGE_CONFERENCE: no active conference call!");
                    // Hide the Manage Conference panel, return to NORMAL mode.
                    setInCallScreenMode(InCallScreenMode.NORMAL);
                    return;
                }
                List<Connection> connections = mCM.getFgCallConnections();
                // There almost certainly will be > 1 connection,
                // since isConferenceCall() just returned true.
                if ((connections == null) || (connections.size() <= 1)) {
                    Log.w(LOG_TAG,
                          "MANAGE_CONFERENCE: Bogus TRUE from isConferenceCall(); connections = "
                          + connections);
                    // Hide the Manage Conference panel, return to NORMAL mode.
                    setInCallScreenMode(InCallScreenMode.NORMAL);
                    return;
                }

                // TODO: Don't do this here. The call to
                // initManageConferencePanel() should instead happen
                // automagically in ManageConferenceUtils the very first
                // time you call updateManageConferencePanel() or
                // setPanelVisible(true).
                mManageConferenceUtils.initManageConferencePanel();  // if necessary

                mManageConferenceUtils.updateManageConferencePanel(connections);

                // The "Manage conference" UI takes up the full main frame,
                // replacing the inCallPanel and CallCard PopupWindow.
                mManageConferenceUtils.setPanelVisible(true);

                // Start the chronometer.
                // TODO: Similarly, we shouldn't expose startConferenceTime()
                // and stopConferenceTime(); the ManageConferenceUtils
                // class ought to manage the conferenceTime widget itself
                // based on setPanelVisible() calls.

                // Note: there is active Fg call since we are in conference call
                long callDuration =
                        mCM.getActiveFgCall().getEarliestConnection().getDurationMillis();
                mManageConferenceUtils.startConferenceTime(
                        SystemClock.elapsedRealtime() - callDuration);

                mInCallPanel.setVisibility(View.GONE);

                // No need to close the dialer here, since the Manage
                // Conference UI will just cover it up anyway.

                break;

            case CALL_ENDED:
                // Display the CallCard (in the "Call ended" state)
                // and hide all other UI.

                mManageConferenceUtils.setPanelVisible(false);
                mManageConferenceUtils.stopConferenceTime();

                // Make sure the CallCard (which is a child of mInCallPanel) is visible.
                mInCallPanel.setVisibility(View.VISIBLE);

                break;

            case NORMAL:
                mInCallPanel.setVisibility(View.VISIBLE);
                mManageConferenceUtils.setPanelVisible(false);
                mManageConferenceUtils.stopConferenceTime();
                break;

            case OTA_NORMAL:
                mApp.otaUtils.setCdmaOtaInCallScreenUiState(
                        OtaUtils.CdmaOtaInCallScreenUiState.State.NORMAL);
                mInCallPanel.setVisibility(View.GONE);
                break;

            case OTA_ENDED:
                mApp.otaUtils.setCdmaOtaInCallScreenUiState(
                        OtaUtils.CdmaOtaInCallScreenUiState.State.ENDED);
                mInCallPanel.setVisibility(View.GONE);
                break;

            case UNDEFINED:
                // Set our Activities intent to ACTION_UNDEFINED so
                // that if we get resumed after we've completed a call
                // the next call will not cause checkIsOtaCall to
                // return true.
                //
                // TODO(OTASP): update these comments
                //
                // With the framework as of October 2009 the sequence below
                // causes the framework to call onResume, onPause, onNewIntent,
                // onResume. If we don't call setIntent below then when the
                // first onResume calls checkIsOtaCall via checkOtaspStateOnResume it will
                // return true and the Activity will be confused.
                //
                //  1) Power up Phone A
                //  2) Place *22899 call and activate Phone A
                //  3) Press the power key on Phone A to turn off the display
                //  4) Call Phone A from Phone B answering Phone A
                //  5) The screen will be blank (Should be normal InCallScreen)
                //  6) Hang up the Phone B
                //  7) Phone A displays the activation screen.
                //
                // Step 3 is the critical step to cause the onResume, onPause
                // onNewIntent, onResume sequence. If step 3 is skipped the
                // sequence will be onNewIntent, onResume and all will be well.
                setIntent(new Intent(ACTION_UNDEFINED));

                // Cleanup Ota Screen if necessary and set the panel
                // to VISIBLE.
                if (mCM.getState() != Phone.State.OFFHOOK) {
                    if (mApp.otaUtils != null) {
                        mApp.otaUtils.cleanOtaScreen(true);
                    }
                } else {
                    log("WARNING: Setting mode to UNDEFINED but phone is OFFHOOK,"
                            + " skip cleanOtaScreen.");
                }
                mInCallPanel.setVisibility(View.VISIBLE);
                break;
        }
    }

    /**
     * @return true if the "Manage conference" UI is currently visible.
     */
    /* package */ boolean isManageConferenceMode() {
        return (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.MANAGE_CONFERENCE);
    }

    /**
     * Checks if the "Manage conference" UI needs to be updated.
     * If the state of the current conference call has changed
     * since our previous call to updateManageConferencePanel()),
     * do a fresh update.  Also, if the current call is no longer a
     * conference call at all, bail out of the "Manage conference" UI and
     * return to InCallScreenMode.NORMAL mode.
     */
    private void updateManageConferencePanelIfNecessary() {
        if (VDBG) log("updateManageConferencePanelIfNecessary: " + mCM.getActiveFgCall() + "...");

        List<Connection> connections = mCM.getFgCallConnections();
        if (connections == null) {
            if (VDBG) log("==> no connections on foreground call!");
            // Hide the Manage Conference panel, return to NORMAL mode.
            setInCallScreenMode(InCallScreenMode.NORMAL);
            SyncWithPhoneStateStatus status = syncWithPhoneState();
            if (status != SyncWithPhoneStateStatus.SUCCESS) {
                Log.w(LOG_TAG, "- syncWithPhoneState failed! status = " + status);
                // We shouldn't even be in the in-call UI in the first
                // place, so bail out:
                if (DBG) log("updateManageConferencePanelIfNecessary: endInCallScreenSession... 1");
                endInCallScreenSession();
                return;
            }
            return;
        }

        int numConnections = connections.size();
        if (numConnections <= 1) {
            if (VDBG) log("==> foreground call no longer a conference!");
            // Hide the Manage Conference panel, return to NORMAL mode.
            setInCallScreenMode(InCallScreenMode.NORMAL);
            SyncWithPhoneStateStatus status = syncWithPhoneState();
            if (status != SyncWithPhoneStateStatus.SUCCESS) {
                Log.w(LOG_TAG, "- syncWithPhoneState failed! status = " + status);
                // We shouldn't even be in the in-call UI in the first
                // place, so bail out:
                if (DBG) log("updateManageConferencePanelIfNecessary: endInCallScreenSession... 2");
                endInCallScreenSession();
                return;
            }
            return;
        }

        // TODO: the test to see if numConnections has changed can go in
        // updateManageConferencePanel(), rather than here.
        if (numConnections != mManageConferenceUtils.getNumCallersInConference()) {
            if (VDBG) log("==> Conference size has changed; need to rebuild UI!");
            mManageConferenceUtils.updateManageConferencePanel(connections);
        }
    }

    /**
     * Updates the visibility of the DTMF dialpad (and its onscreen
     * "handle", if applicable), based on the current state of the phone
     * and/or the current InCallScreenMode.
     */
    private void updateDialpadVisibility() {
        //
        // (1) The dialpad itself:
        //
        // If an incoming call is ringing, make sure the dialpad is
        // closed.  (We do this to make sure we're not covering up the
        // "incoming call" UI, and especially to make sure that the "touch
        // lock" overlay won't appear.)
        if (mCM.getState() == Phone.State.RINGING) {
            hideDialpadInternal(false);  // don't do the "closing" animation

            // Also, clear out the "history" of DTMF digits you may have typed
            // into the previous call (so you don't see the previous call's
            // digits if you answer this call and then bring up the dialpad.)
            //
            // TODO: it would be more precise to do this when you *answer* the
            // incoming call, rather than as soon as it starts ringing, but
            // the InCallScreen doesn't keep enough state right now to notice
            // that specific transition in onPhoneStateChanged().
            mDialer.clearDigits();
        }

        //
        // (2) The main in-call panel (containing the CallCard):
        //
        // We need to hide the CallCard (which is a
        // child of mInCallPanel) while the dialpad is visible.
        //

        if (isDialerOpened()) {
            if (VDBG) log("- updateDialpadVisibility: dialpad open, hide mInCallPanel...");
            CallCard.Fade.hide(mInCallPanel, View.GONE);
        } else {
            // Dialpad is dismissed; bring back the CallCard if
            // it's supposed to be visible.
            if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.NORMAL)
                || (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.CALL_ENDED)) {
                if (VDBG) log("- updateDialpadVisibility: dialpad dismissed, show mInCallPanel...");
                CallCard.Fade.show(mInCallPanel);
            }
        }
    }

    /**
     * @return true if the DTMF dialpad is currently visible.
     */
    /* package */ boolean isDialerOpened() {
        return (mDialer != null && mDialer.isOpened());
    }

    /**
     * Called any time the DTMF dialpad is opened.
     * @see DTMFTwelveKeyDialer.onDialerOpen()
     */
    /* package */ void onDialerOpen() {
        if (DBG) log("onDialerOpen()...");

        // Update the in-call touch UI.
        updateInCallTouchUi();

        // Update any other onscreen UI elements that depend on the dialpad.
        updateDialpadVisibility();

        // This counts as explicit "user activity".
        mApp.pokeUserActivity();

        //If on OTA Call, hide OTA Screen
        // TODO: This may not be necessary, now that the dialpad is
        // always visible in OTA mode.
        if  ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL
                || mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED)
                && mApp.otaUtils != null) {
            mApp.otaUtils.hideOtaScreen();
        }
    }

    /**
     * Called any time the DTMF dialpad is closed.
     * @see DTMFTwelveKeyDialer.onDialerClose()
     */
    /* package */ void onDialerClose() {
        if (DBG) log("onDialerClose()...");

        // OTA-specific cleanup upon closing the dialpad.
        if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL)
            || (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED)
            || ((mApp.cdmaOtaScreenState != null)
                && (mApp.cdmaOtaScreenState.otaScreenState ==
                    CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION))) {
            if (mApp.otaUtils != null) {
                mApp.otaUtils.otaShowProperScreen();
            }
        }

        // Update the in-call touch UI.
        updateInCallTouchUi();

        // Update the visibility of the dialpad itself (and any other
        // onscreen UI elements that depend on it.)
        updateDialpadVisibility();

        // This counts as explicit "user activity".
        mApp.pokeUserActivity();
    }

    /**
     * Determines when we can dial DTMF tones.
     */
    private boolean okToDialDTMFTones() {
        final boolean hasRingingCall = mCM.hasActiveRingingCall();
        final Call.State fgCallState = mCM.getActiveFgCallState();

        // We're allowed to send DTMF tones when there's an ACTIVE
        // foreground call, and not when an incoming call is ringing
        // (since DTMF tones are useless in that state), or if the
        // Manage Conference UI is visible (since the tab interferes
        // with the "Back to call" button.)

        // We can also dial while in ALERTING state because there are
        // some connections that never update to an ACTIVE state (no
        // indication from the network).
        boolean canDial =
            (fgCallState == Call.State.ACTIVE || fgCallState == Call.State.ALERTING)
            && !hasRingingCall
            && (mApp.inCallUiState.inCallScreenMode != InCallScreenMode.MANAGE_CONFERENCE);

        if (VDBG) log ("[okToDialDTMFTones] foreground state: " + fgCallState +
                ", ringing state: " + hasRingingCall +
                ", call screen mode: " + mApp.inCallUiState.inCallScreenMode +
                ", result: " + canDial);

        return canDial;
    }

    /**
     * @return true if the in-call DTMF dialpad should be available to the
     *      user, given the current state of the phone and the in-call UI.
     *      (This is used to control the enabledness of the "Show
     *      dialpad" onscreen button; see InCallControlState.dialpadEnabled.)
     */
    /* package */ boolean okToShowDialpad() {
        // The dialpad is available only when it's OK to dial DTMF
        // tones given the current state of the current call.
        return okToDialDTMFTones();
    }

    /**
     * Initializes the in-call touch UI on devices that need it.
     */
    private void initInCallTouchUi() {
        if (DBG) log("initInCallTouchUi()...");
        // TODO: we currently use the InCallTouchUi widget in at least
        // some states on ALL platforms.  But if some devices ultimately
        // end up not using *any* onscreen touch UI, we should make sure
        // to not even inflate the InCallTouchUi widget on those devices.
        mInCallTouchUi = (InCallTouchUi) findViewById(R.id.inCallTouchUi);
        mInCallTouchUi.setInCallScreenInstance(this);

        // RespondViaSmsManager implements the "Respond via SMS"
        // feature that's triggered from the incoming call widget.
        mRespondViaSmsManager = new RespondViaSmsManager();
        mRespondViaSmsManager.setInCallScreenInstance(this);
    }

    /**
     * Updates the state of the in-call touch UI.
     */
    private void updateInCallTouchUi() {
        if (mInCallTouchUi != null) {
            mInCallTouchUi.updateState(mCM);
        }
    }

    /**
     * @return the InCallTouchUi widget
     */
    /* package */ InCallTouchUi getInCallTouchUi() {
        return mInCallTouchUi;
    }

    /**
     * Posts a handler message telling the InCallScreen to refresh the
     * onscreen in-call UI.
     *
     * This is just a wrapper around updateScreen(), for use by the
     * rest of the phone app or from a thread other than the UI thread.
     *
     * updateScreen() is a no-op if the InCallScreen is not the foreground
     * activity, so it's safe to call this whether or not the InCallScreen
     * is currently visible.
     */
    /* package */ void requestUpdateScreen() {
        if (DBG) log("requestUpdateScreen()...");
        mHandler.removeMessages(REQUEST_UPDATE_SCREEN);
        mHandler.sendEmptyMessage(REQUEST_UPDATE_SCREEN);
    }

    /**
     * @return true if it's OK to display the in-call touch UI, given the
     * current state of the InCallScreen.
     */
    /* package */ boolean okToShowInCallTouchUi() {
        // Note that this method is concerned only with the internal state
        // of the InCallScreen.  (The InCallTouchUi widget has separate
        // logic to make sure it's OK to display the touch UI given the
        // current telephony state, and that it's allowed on the current
        // device in the first place.)

        // The touch UI is available in the following InCallScreenModes:
        // - NORMAL (obviously)
        // - CALL_ENDED (which is intended to look mostly the same as
        //               a normal in-call state, even though the in-call
        //               buttons are mostly disabled)
        // and is hidden in any of the other modes, like MANAGE_CONFERENCE
        // or one of the OTA modes (which use totally different UIs.)

        return ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.NORMAL)
                || (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.CALL_ENDED));
    }

    /**
     * @return true if we're in restricted / emergency dialing only mode.
     */
    public boolean isPhoneStateRestricted() {
        // TODO:  This needs to work IN TANDEM with the KeyGuardViewMediator Code.
        // Right now, it looks like the mInputRestricted flag is INTERNAL to the
        // KeyGuardViewMediator and SPECIFICALLY set to be FALSE while the emergency
        // phone call is being made, to allow for input into the InCallScreen.
        // Having the InCallScreen judge the state of the device from this flag
        // becomes meaningless since it is always false for us.  The mediator should
        // have an additional API to let this app know that it should be restricted.
        int serviceState = mCM.getServiceState();
        return ((serviceState == ServiceState.STATE_EMERGENCY_ONLY) ||
                (serviceState == ServiceState.STATE_OUT_OF_SERVICE) ||
                (mApp.getKeyguardManager().inKeyguardRestrictedInputMode()));
    }


    //
    // Bluetooth helper methods.
    //
    // - BluetoothAdapter is the Bluetooth system service.  If
    //   getDefaultAdapter() returns null
    //   then the device is not BT capable.  Use BluetoothDevice.isEnabled()
    //   to see if BT is enabled on the device.
    //
    // - BluetoothHeadset is the API for the control connection to a
    //   Bluetooth Headset.  This lets you completely connect/disconnect a
    //   headset (which we don't do from the Phone UI!) but also lets you
    //   get the address of the currently active headset and see whether
    //   it's currently connected.
    //
    // - BluetoothHandsfree is the API to control the audio connection to
    //   a bluetooth headset. We use this API to switch the headset on and
    //   off when the user presses the "Bluetooth" button.
    //   Our BluetoothHandsfree instance (mBluetoothHandsfree) is created
    //   by the PhoneApp and will be null if the device is not BT capable.
    //

    /**
     * @return true if the Bluetooth on/off switch in the UI should be
     *         available to the user (i.e. if the device is BT-capable
     *         and a headset is connected.)
     */
    /* package */ boolean isBluetoothAvailable() {
        if (VDBG) log("isBluetoothAvailable()...");
        if (mBluetoothHandsfree == null) {
            // Device is not BT capable.
            if (VDBG) log("  ==> FALSE (not BT capable)");
            return false;
        }

        // There's no need to ask the Bluetooth system service if BT is enabled:
        //
        //    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        //    if ((adapter == null) || !adapter.isEnabled()) {
        //        if (DBG) log("  ==> FALSE (BT not enabled)");
        //        return false;
        //    }
        //    if (DBG) log("  - BT enabled!  device name " + adapter.getName()
        //                 + ", address " + adapter.getAddress());
        //
        // ...since we already have a BluetoothHeadset instance.  We can just
        // call isConnected() on that, and assume it'll be false if BT isn't
        // enabled at all.

        // Check if there's a connected headset, using the BluetoothHeadset API.
        boolean isConnected = false;
        if (mBluetoothHeadset != null) {
            List<BluetoothDevice> deviceList = mBluetoothHeadset.getConnectedDevices();

            if (deviceList.size() > 0) {
                BluetoothDevice device = deviceList.get(0);
                isConnected = true;

                if (VDBG) log("  - headset state = " +
                              mBluetoothHeadset.getConnectionState(device));
                if (VDBG) log("  - headset address: " + device);
                if (VDBG) log("  - isConnected: " + isConnected);
            }
        }

        if (VDBG) log("  ==> " + isConnected);
        return isConnected;
    }

    /**
     * @return true if a BT device is available, and its audio is currently connected.
     */
    /* package */ boolean isBluetoothAudioConnected() {
        if (mBluetoothHandsfree == null) {
            if (VDBG) log("isBluetoothAudioConnected: ==> FALSE (null mBluetoothHandsfree)");
            return false;
        }
        boolean isAudioOn = mBluetoothHandsfree.isAudioOn();
        if (VDBG) log("isBluetoothAudioConnected: ==> isAudioOn = " + isAudioOn);
        return isAudioOn;
    }

    /**
     * Helper method used to control the onscreen "Bluetooth" indication;
     * see InCallControlState.bluetoothIndicatorOn.
     *
     * @return true if a BT device is available and its audio is currently connected,
     *              <b>or</b> if we issued a BluetoothHandsfree.userWantsAudioOn()
     *              call within the last 5 seconds (which presumably means
     *              that the BT audio connection is currently being set
     *              up, and will be connected soon.)
     */
    /* package */ boolean isBluetoothAudioConnectedOrPending() {
        if (isBluetoothAudioConnected()) {
            if (VDBG) log("isBluetoothAudioConnectedOrPending: ==> TRUE (really connected)");
            return true;
        }

        // If we issued a userWantsAudioOn() call "recently enough", even
        // if BT isn't actually connected yet, let's still pretend BT is
        // on.  This makes the onscreen indication more responsive.
        if (mBluetoothConnectionPending) {
            long timeSinceRequest =
                    SystemClock.elapsedRealtime() - mBluetoothConnectionRequestTime;
            if (timeSinceRequest < 5000 /* 5 seconds */) {
                if (VDBG) log("isBluetoothAudioConnectedOrPending: ==> TRUE (requested "
                             + timeSinceRequest + " msec ago)");
                return true;
            } else {
                if (VDBG) log("isBluetoothAudioConnectedOrPending: ==> FALSE (request too old: "
                             + timeSinceRequest + " msec ago)");
                mBluetoothConnectionPending = false;
                return false;
            }
        }

        if (VDBG) log("isBluetoothAudioConnectedOrPending: ==> FALSE");
        return false;
    }

    /**
     * Posts a message to our handler saying to update the onscreen UI
     * based on a bluetooth headset state change.
     */
    /* package */ void requestUpdateBluetoothIndication() {
        if (VDBG) log("requestUpdateBluetoothIndication()...");
        // No need to look at the current state here; any UI elements that
        // care about the bluetooth state (i.e. the CallCard) get
        // the necessary state directly from PhoneApp.showBluetoothIndication().
        mHandler.removeMessages(REQUEST_UPDATE_BLUETOOTH_INDICATION);
        mHandler.sendEmptyMessage(REQUEST_UPDATE_BLUETOOTH_INDICATION);
    }

    private void dumpBluetoothState() {
        log("============== dumpBluetoothState() =============");
        log("= isBluetoothAvailable: " + isBluetoothAvailable());
        log("= isBluetoothAudioConnected: " + isBluetoothAudioConnected());
        log("= isBluetoothAudioConnectedOrPending: " + isBluetoothAudioConnectedOrPending());
        log("= PhoneApp.showBluetoothIndication: "
            + mApp.showBluetoothIndication());
        log("=");
        if (mBluetoothHandsfree != null) {
            log("= BluetoothHandsfree.isAudioOn: " + mBluetoothHandsfree.isAudioOn());
            if (mBluetoothHeadset != null) {
                List<BluetoothDevice> deviceList = mBluetoothHeadset.getConnectedDevices();

                if (deviceList.size() > 0) {
                    BluetoothDevice device = deviceList.get(0);
                    log("= BluetoothHeadset.getCurrentDevice: " + device);
                    log("= BluetoothHeadset.State: "
                        + mBluetoothHeadset.getConnectionState(device));
                }
            } else {
                log("= mBluetoothHeadset is null");
            }
        } else {
            log("= mBluetoothHandsfree is null; device is not BT capable");
        }
    }

    /* package */ void connectBluetoothAudio() {
        if (VDBG) log("connectBluetoothAudio()...");
        if (mBluetoothHandsfree != null) {
            mBluetoothHandsfree.userWantsAudioOn();
        }

        // Watch out: The bluetooth connection doesn't happen instantly;
        // the userWantsAudioOn() call returns instantly but does its real
        // work in another thread.  The mBluetoothConnectionPending flag
        // is just a little trickery to ensure that the onscreen UI updates
        // instantly. (See isBluetoothAudioConnectedOrPending() above.)
        mBluetoothConnectionPending = true;
        mBluetoothConnectionRequestTime = SystemClock.elapsedRealtime();
    }

    /* package */ void disconnectBluetoothAudio() {
        if (VDBG) log("disconnectBluetoothAudio()...");
        if (mBluetoothHandsfree != null) {
            mBluetoothHandsfree.userWantsAudioOff();
        }
        mBluetoothConnectionPending = false;
    }

    /**
     * Posts a handler message telling the InCallScreen to close
     * the OTA failure notice after the specified delay.
     * @see OtaUtils.otaShowProgramFailureNotice
     */
    /* package */ void requestCloseOtaFailureNotice(long timeout) {
        if (DBG) log("requestCloseOtaFailureNotice() with timeout: " + timeout);
        mHandler.sendEmptyMessageDelayed(REQUEST_CLOSE_OTA_FAILURE_NOTICE, timeout);

        // TODO: we probably ought to call removeMessages() for this
        // message code in either onPause or onResume, just to be 100%
        // sure that the message we just posted has no way to affect a
        // *different* call if the user quickly backs out and restarts.
        // (This is also true for requestCloseSpcErrorNotice() below, and
        // probably anywhere else we use mHandler.sendEmptyMessageDelayed().)
    }

    /**
     * Posts a handler message telling the InCallScreen to close
     * the SPC error notice after the specified delay.
     * @see OtaUtils.otaShowSpcErrorNotice
     */
    /* package */ void requestCloseSpcErrorNotice(long timeout) {
        if (DBG) log("requestCloseSpcErrorNotice() with timeout: " + timeout);
        mHandler.sendEmptyMessageDelayed(REQUEST_CLOSE_SPC_ERROR_NOTICE, timeout);
    }

    public boolean isOtaCallInActiveState() {
        if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL)
                || ((mApp.cdmaOtaScreenState != null)
                    && (mApp.cdmaOtaScreenState.otaScreenState ==
                        CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION))) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Handle OTA Call End scenario when display becomes dark during OTA Call
     * and InCallScreen is in pause mode.  CallNotifier will listen for call
     * end indication and call this api to handle OTA Call end scenario
     */
    public void handleOtaCallEnd() {
        if (DBG) log("handleOtaCallEnd entering");
        if (((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL)
                || ((mApp.cdmaOtaScreenState != null)
                && (mApp.cdmaOtaScreenState.otaScreenState !=
                    CdmaOtaScreenState.OtaScreenState.OTA_STATUS_UNDEFINED)))
                && ((mApp.cdmaOtaProvisionData != null)
                && (!mApp.cdmaOtaProvisionData.inOtaSpcState))) {
            if (DBG) log("handleOtaCallEnd - Set OTA Call End stater");
            setInCallScreenMode(InCallScreenMode.OTA_ENDED);
            updateScreen();
        }
    }

    public boolean isOtaCallInEndState() {
        return (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED);
    }


    /**
     * Upon resuming the in-call UI, check to see if an OTASP call is in
     * progress, and if so enable the special OTASP-specific UI.
     *
     * TODO: have a simple single flag in InCallUiState for this rather than
     * needing to know about all those mApp.cdma*State objects.
     *
     * @return true if any OTASP-related UI is active
     */
    private boolean checkOtaspStateOnResume() {
        // If there's no OtaUtils instance, that means we haven't even tried
        // to start an OTASP call (yet), so there's definitely nothing to do here.
        if (mApp.otaUtils == null) {
            if (DBG) log("checkOtaspStateOnResume: no OtaUtils instance; nothing to do.");
            return false;
        }

        if ((mApp.cdmaOtaScreenState == null) || (mApp.cdmaOtaProvisionData == null)) {
            // Uh oh -- something wrong with our internal OTASP state.
            // (Since this is an OTASP-capable device, these objects
            // *should* have already been created by PhoneApp.onCreate().)
            throw new IllegalStateException("checkOtaspStateOnResume: "
                                            + "app.cdmaOta* objects(s) not initialized");
        }

        // The PhoneApp.cdmaOtaInCallScreenUiState instance is the
        // authoritative source saying whether or not the in-call UI should
        // show its OTASP-related UI.

        OtaUtils.CdmaOtaInCallScreenUiState.State cdmaOtaInCallScreenState =
                mApp.otaUtils.getCdmaOtaInCallScreenUiState();
        // These states are:
        // - UNDEFINED: no OTASP-related UI is visible
        // - NORMAL: OTASP call in progress, so show in-progress OTASP UI
        // - ENDED: OTASP call just ended, so show success/failure indication

        boolean otaspUiActive =
                (cdmaOtaInCallScreenState == OtaUtils.CdmaOtaInCallScreenUiState.State.NORMAL)
                || (cdmaOtaInCallScreenState == OtaUtils.CdmaOtaInCallScreenUiState.State.ENDED);

        if (otaspUiActive) {
            // Make sure the OtaUtils instance knows about the InCallScreen's
            // OTASP-related UI widgets.
            //
            // (This call has no effect if the UI widgets have already been set up.
            // It only really matters  the very first time that the InCallScreen instance
            // is onResume()d after starting an OTASP call.)
            mApp.otaUtils.updateUiWidgets(this, mInCallPanel, mInCallTouchUi, mCallCard);

            // Also update the InCallScreenMode based on the cdmaOtaInCallScreenState.

            if (cdmaOtaInCallScreenState == OtaUtils.CdmaOtaInCallScreenUiState.State.NORMAL) {
                if (DBG) log("checkOtaspStateOnResume - in OTA Normal mode");
                setInCallScreenMode(InCallScreenMode.OTA_NORMAL);
            } else if (cdmaOtaInCallScreenState ==
                       OtaUtils.CdmaOtaInCallScreenUiState.State.ENDED) {
                if (DBG) log("checkOtaspStateOnResume - in OTA END mode");
                setInCallScreenMode(InCallScreenMode.OTA_ENDED);
            }

            // TODO(OTASP): we might also need to go into OTA_ENDED mode
            // in one extra case:
            //
            // else if (mApp.cdmaOtaScreenState.otaScreenState ==
            //            CdmaOtaScreenState.OtaScreenState.OTA_STATUS_SUCCESS_FAILURE_DLG) {
            //     if (DBG) log("checkOtaspStateOnResume - set OTA END Mode");
            //     setInCallScreenMode(InCallScreenMode.OTA_ENDED);
            // }

        } else {
            // OTASP is not active; reset to regular in-call UI.

            if (DBG) log("checkOtaspStateOnResume - Set OTA NORMAL Mode");
            setInCallScreenMode(InCallScreenMode.OTA_NORMAL);

            if (mApp.otaUtils != null) {
                mApp.otaUtils.cleanOtaScreen(false);
            }
        }

        // TODO(OTASP):
        // The original check from checkIsOtaCall() when handling ACTION_MAIN was this:
        //
        //        [ . . . ]
        //        else if (action.equals(intent.ACTION_MAIN)) {
        //            if (DBG) log("checkIsOtaCall action ACTION_MAIN");
        //            boolean isRingingCall = mCM.hasActiveRingingCall();
        //            if (isRingingCall) {
        //                if (DBG) log("checkIsOtaCall isRingingCall: " + isRingingCall);
        //                return false;
        //            } else if ((mApp.cdmaOtaInCallScreenUiState.state
        //                            == CdmaOtaInCallScreenUiState.State.NORMAL)
        //                    || (mApp.cdmaOtaInCallScreenUiState.state
        //                            == CdmaOtaInCallScreenUiState.State.ENDED)) {
        //                if (DBG) log("action ACTION_MAIN, OTA call already in progress");
        //                isOtaCall = true;
        //            } else {
        //                if (mApp.cdmaOtaScreenState.otaScreenState !=
        //                        CdmaOtaScreenState.OtaScreenState.OTA_STATUS_UNDEFINED) {
        //                    if (DBG) log("checkIsOtaCall action ACTION_MAIN, "
        //                                 + "OTA call in progress with UNDEFINED");
        //                    isOtaCall = true;
        //                }
        //            }
        //        }
        //
        // Also, in internalResolveIntent() we used to do this:
        //
        //        if ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_NORMAL)
        //                || (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.OTA_ENDED)) {
        //            // If in OTA Call, update the OTA UI
        //            updateScreen();
        //            return;
        //        }
        //
        // We still need more cleanup to simplify the mApp.cdma*State objects.

        return otaspUiActive;
    }

    /**
     * Updates and returns the InCallControlState instance.
     */
    public InCallControlState getUpdatedInCallControlState() {
        if (VDBG) log("getUpdatedInCallControlState()...");
        mInCallControlState.update();
        return mInCallControlState;
    }

    public void resetInCallScreenMode() {
        if (DBG) log("resetInCallScreenMode: setting mode to UNDEFINED...");
        setInCallScreenMode(InCallScreenMode.UNDEFINED);
    }

    /**
     * Updates the onscreen hint displayed while the user is dragging one
     * of the handles of the RotarySelector widget used for incoming
     * calls.
     *
     * @param hintTextResId resource ID of the hint text to display,
     *        or 0 if no hint should be visible.
     * @param hintColorResId resource ID for the color of the hint text
     */
    /* package */ void updateIncomingCallWidgetHint(int hintTextResId, int hintColorResId) {
        if (VDBG) log("updateIncomingCallWidgetHint(" + hintTextResId + ")...");
        if (mCallCard != null) {
            mCallCard.setIncomingCallWidgetHint(hintTextResId, hintColorResId);
            mCallCard.updateState(mCM);
            // TODO: if hintTextResId == 0, consider NOT clearing the onscreen
            // hint right away, but instead post a delayed handler message to
            // keep it onscreen for an extra second or two.  (This might make
            // the hint more helpful if the user quickly taps one of the
            // handles without dragging at all...)
            // (Or, maybe this should happen completely within the RotarySelector
            // widget, since the widget itself probably wants to keep the colored
            // arrow visible for some extra time also...)
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        super.dispatchPopulateAccessibilityEvent(event);
        mCallCard.dispatchPopulateAccessibilityEvent(event);
        return true;
    }

    /**
     * Manually handle configuration changes.
     *
     * We specify android:configChanges="orientation|keyboardHidden|uiMode" in
     * our manifest to make sure the system doesn't destroy and re-create us
     * due to the above config changes.  Instead, this method will be called,
     * and should manually rebuild the onscreen UI to keep it in sync with the
     * current configuration.
     *
     */
    public void onConfigurationChanged(Configuration newConfig) {
        if (DBG) log("onConfigurationChanged: newConfig = " + newConfig);

        // Note: At the time this function is called, our Resources object
        // will have already been updated to return resource values matching
        // the new configuration.

        // Watch out: we *can* still get destroyed and recreated if a
        // configuration change occurs that is *not* listed in the
        // android:configChanges attribute.  TODO: Any others we need to list?

        super.onConfigurationChanged(newConfig);

        // Nothing else to do here, since (currently) the InCallScreen looks
        // exactly the same regardless of configuration.
        // (Specifically, we'll never be in landscape mode because we set
        // android:screenOrientation="portrait" in our manifest, and we don't
        // change our UI at all based on newConfig.keyboardHidden or
        // newConfig.uiMode.)

        // TODO: we do eventually want to handle at least some config changes, such as:
        boolean isKeyboardOpen = (newConfig.keyboardHidden == Configuration.KEYBOARDHIDDEN_NO);
        if (DBG) log("  - isKeyboardOpen = " + isKeyboardOpen);
        boolean isLandscape = (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
        if (DBG) log("  - isLandscape = " + isLandscape);
        if (DBG) log("  - uiMode = " + newConfig.uiMode);
        // See bug 2089513.
    }

    /**
     * Handles an incoming RING event from the telephony layer.
     */
    private void onIncomingRing() {
        if (DBG) log("onIncomingRing()...");
        // IFF we're visible, forward this event to the InCallTouchUi
        // instance (which uses this event to drive the animation of the
        // incoming-call UI.)
        if (mIsForegroundActivity && (mInCallTouchUi != null)) {
            mInCallTouchUi.onIncomingRing();
        }
    }

    /**
     * Handles a "new ringing connection" event from the telephony layer.
     */
    private void onNewRingingConnection() {
        if (DBG) log("onNewRingingConnection()...");

        // This event comes in right at the start of the incoming-call
        // sequence, exactly once per incoming call.  We use this event to
        // reset any incoming-call-related UI elements that might have
        // been left in an inconsistent state after a prior incoming call.
        // (Note we do this whether or not we're the foreground activity,
        // since this event comes in *before* we actually get launched to
        // display the incoming-call UI.)

        // If there's a "Respond via SMS" popup still around since the
        // last time we were the foreground activity, make sure it's not
        // still active(!) since that would interfere with *this* incoming
        // call.
        // (Note that we also do this same check in onResume().  But we
        // need it here too, to make sure the popup gets reset in the case
        // where a call-waiting call comes in while the InCallScreen is
        // already in the foreground.)
        if (mRespondViaSmsManager != null) {
            mRespondViaSmsManager.dismissPopup();  // safe even if already dismissed
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

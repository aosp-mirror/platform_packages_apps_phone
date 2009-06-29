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
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
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
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.Checkin;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.text.method.DialerKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SlidingDrawer;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Phone app "in call" screen.
 */
public class InCallScreen extends Activity
        implements View.OnClickListener, View.OnTouchListener,
                CallerInfoAsyncQuery.OnQueryCompleteListener {
    private static final String LOG_TAG = "InCallScreen";

    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneApp.DBG_LEVEL >= 2);

    // Enable detailed logs of user actions while on the in-call screen.
    // TODO: For now this is totally disabled.  But for future user
    // research, we should change the PHONE_UI_EVENT_* events to use
    // android.util.EventLog rather than Checkin.logEvent, so that we can
    // select which of those events to upload on a tag-by-tag basis
    // (which means that we could actually ship devices with this logging
    // enabled.)
    /* package */ static final boolean ENABLE_PHONE_UI_EVENT_LOGGING = false;

    /**
     * Intent extra used to specify whether the DTMF dialpad should be
     * initially visible when bringing up the InCallScreen.  (If this
     * extra is present, the dialpad will be initially shown if the extra
     * has the boolean value true, and initially hidden otherwise.)
     */
    static final String SHOW_DIALPAD_EXTRA = "com.android.phone.ShowDialpad";

    // Event values used with Checkin.Events.Tag.PHONE_UI events:
    /** The in-call UI became active */
    static final String PHONE_UI_EVENT_ENTER = "enter";
    /** User exited the in-call UI */
    static final String PHONE_UI_EVENT_EXIT = "exit";
    /** User clicked one of the touchable in-call buttons */
    static final String PHONE_UI_EVENT_BUTTON_CLICK = "button_click";

    // Amount of time (in msec) that we display the "Call ended" state.
    // The "short" value is for calls ended by the local user, and the
    // "long" value is for calls ended by the remote caller.
    private static final int CALL_ENDED_SHORT_DELAY =  200;  // msec
    private static final int CALL_ENDED_LONG_DELAY = 2000;  // msec

    // Amount of time (in msec) that we keep the in-call menu onscreen
    // *after* the user changes the state of one of the toggle buttons.
    private static final int MENU_DISMISS_DELAY =  1000;  // msec

    // The "touch lock" overlay timeout comes from Gservices; this is the default.
    private static final int TOUCH_LOCK_DELAY_DEFAULT =  6000;  // msec

    // Amount of time for Displaying "Dialing" for 3way Calling origination
    private static final int THREEWAY_CALLERINFO_DISPLAY_TIME = 2000; // msec

    // See CallTracker.MAX_CONNECTIONS_PER_CALL
    private static final int MAX_CALLERS_IN_CONFERENCE = 5;

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
    private static final int DISMISS_MENU = 111;
    private static final int ALLOW_SCREEN_ON = 112;
    private static final int TOUCH_LOCK_TIMER = 113;
    private static final int BLUETOOTH_STATE_CHANGED = 114;
    private static final int PHONE_CDMA_CALL_WAITING = 115;
    private static final int THREEWAY_CALLERINFO_DISPLAY_DONE = 116;


    // High-level "modes" of the in-call UI.
    private enum InCallScreenMode {
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
        CALL_ENDED
    }
    private InCallScreenMode mInCallScreenMode;

    // Possible error conditions that can happen on startup.
    // These are returned as status codes from the various helper
    // functions we call from onCreate() and/or onResume().
    // See syncWithPhoneState() and checkIfOkToInitiateOutgoingCall() for details.
    private enum InCallInitStatus {
        SUCCESS,
        VOICEMAIL_NUMBER_MISSING,
        POWER_OFF,
        EMERGENCY_ONLY,
        PHONE_NOT_IN_USE,
        NO_PHONE_NUMBER_SUPPLIED,
        DIALED_MMI,
        CALL_FAILED
    }
    private InCallInitStatus mInCallInitialStatus;  // see onResume()

    private boolean mRegisteredForPhoneStates;

    private Phone mPhone;
    private Call mForegroundCall;
    private Call mBackgroundCall;
    private Call mRingingCall;

    private BluetoothHandsfree mBluetoothHandsfree;
    private BluetoothHeadset mBluetoothHeadset;
    private boolean mBluetoothConnectionPending;
    private long mBluetoothConnectionRequestTime;

    // Main in-call UI ViewGroups
    private ViewGroup mMainFrame;
    private ViewGroup mInCallPanel;

    // Menu button hint below the "main frame"
    private TextView mMenuButtonHint;

    // Main in-call UI elements:
    private CallCard mCallCard;
    private InCallMenu mInCallMenu;  // created lazily on first MENU keypress

    /**
     * DTMF Dialer objects, including the model and the sliding drawer / dialer
     * UI container and the dialer display field for landscape presentation.
     */
    private DTMFTwelveKeyDialer mDialer;
    private SlidingDrawer mDialerDrawer;
    private EditText mDTMFDisplay;

    // "Manage conference" UI elements
    private ViewGroup mManageConferencePanel;
    private Button mButtonManageConferenceDone;
    private ViewGroup[] mConferenceCallList;
    private int mNumCallersInConference;
    private Chronometer mConferenceTime;

    private EditText mWildPromptText;

    // "Touch lock" overlay graphic
    private View mTouchLockOverlay;  // The overlay over the whole screen
    private View mTouchLockIcon;  // The "lock" icon in the middle of the screen
    private Animation mTouchLockFadeIn;
    private long mTouchLockLastTouchTime;  // in SystemClock.uptimeMillis() time base

    // Onscreen "answer" UI, for devices with no hardware CALL button.
    private View mOnscreenAnswerUiContainer;  // The container for the whole UI, or null if unused
    private View mOnscreenAnswerButton;  // The "answer" button itself
    private long mOnscreenAnswerButtonLastTouchTime;  // in SystemClock.uptimeMillis() time base

    // Various dialogs we bring up (see dismissAllDialogs())
    // The MMI started dialog can actually be one of 2 items:
    //   1. An alert dialog if the MMI code is a normal MMI
    //   2. A progress dialog if the user requested a USSD
    private Dialog mMmiStartedDialog;
    private AlertDialog mMissingVoicemailDialog;
    private AlertDialog mGenericErrorDialog;
    private AlertDialog mSuppServiceFailureDialog;
    private AlertDialog mWaitPromptDialog;
    private AlertDialog mWildPromptDialog;

    // TODO: If the Activity class ever provides an easy way to get the
    // current "activity lifecycle" state, we can remove these flags.
    private boolean mIsDestroyed = false;
    private boolean mIsForegroundActivity = false;

    // Flag indicating whether or not we should bring up the Call Log when
    // exiting the in-call UI due to the Phone becoming idle.  (This is
    // true if the most recently disconnected Call was initiated by the
    // user, or false if it was an incoming call.)
    // This flag is used by delayedCleanupAfterDisconnect(), and is set by
    // onDisconnect() (which is the only place that either posts a
    // DELAYED_CLEANUP_AFTER_DISCONNECT event *or* calls
    // delayedCleanupAfterDisconnect() directly.)
    private boolean mShowCallLogAfterDisconnect;

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

            PhoneApp app = PhoneApp.getInstance();
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
                    // Update the in-call UI, since some UI elements (in
                    // particular the "Speaker" menu button) change state
                    // depending on whether a headset is plugged in.
                    // TODO: A full updateScreen() is overkill here, since
                    // the value of PhoneApp.isHeadsetPlugged() only affects a
                    // single menu item.  (But even a full updateScreen()
                    // is still pretty cheap, so let's keep this simple
                    // for now.)
                    if (msg.arg1 != 1 && !isBluetoothAudioConnected()){
                        // If the state is "not connected", restore the speaker state.
                        // We ONLY want to do this on the wired headset connect /
                        // disconnect events for now though, so we're only triggering
                        // on EVENT_HEADSET_PLUG_STATE_CHANGED.
                        PhoneUtils.restoreSpeakerMode(getApplicationContext());
                    }
                    updateScreen();
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
                    if (mPhone.getPhoneName().equals("CDMA")) {
                        PhoneUtils.displayMMIComplete(mPhone, app, mmiCode, null, null);
                    } else {
                        if (mmiCode.getState() != MmiCode.State.PENDING) {
                            if (DBG) log("Got MMI_COMPLETE, finishing...");
                            finish();
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

                case DISMISS_MENU:
                    // dismissMenu() has no effect if the menu is already closed.
                    dismissMenu(true);  // dismissImmediate = true
                    break;

                case ALLOW_SCREEN_ON:
                    if (VDBG) log("ALLOW_SCREEN_ON message...");
                    // Undo our previous call to preventScreenOn(true).
                    // (Note this will cause the screen to turn on
                    // immediately, if it's currently off because of a
                    // prior preventScreenOn(true) call.)
                    app.preventScreenOn(false);
                    break;

                case TOUCH_LOCK_TIMER:
                    if (VDBG) log("TOUCH_LOCK_TIMER...");
                    touchLockTimerExpired();
                    break;

                case BLUETOOTH_STATE_CHANGED:
                    if (VDBG) log("BLUETOOTH_STATE_CHANGED...");
                    // The bluetooth headset state changed, so some UI
                    // elements may need to update.  (There's no need to
                    // look up the current state here, since any UI
                    // elements that care about the bluetooth state get it
                    // directly from PhoneApp.showBluetoothIndication().)
                    updateScreen();
                    break;

                case PHONE_CDMA_CALL_WAITING:
                    if (DBG) log("Received PHONE_CDMA_CALL_WAITING event ...");
                    Connection cn = mRingingCall.getLatestConnection();

                    // Only proceed if we get a valid connection object
                    if (cn != null) {
                        // Finally update screen with Call waiting info and request
                        // screen to wake up
                        updateScreen();
                        app.updateWakeState();
                    }
                    break;

                case THREEWAY_CALLERINFO_DISPLAY_DONE:
                    if (DBG) log("Received THREEWAY_CALLERINFO_DISPLAY_DONE event ...");
                    if (app.cdmaPhoneCallState.getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                        // Set the mThreeWayCallOrigStateDialing state to true
                        app.cdmaPhoneCallState.setThreeWayCallOrigState(false);

                        //Finally update screen with with the current on going call
                        updateScreen();
                    }
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
        if (DBG) log("onCreate()...  this = " + this);

        Profiler.callScreenOnCreate();

        super.onCreate(icicle);

        final PhoneApp app = PhoneApp.getInstance();
        app.setInCallScreenInstance(this);

        setPhone(app.phone);  // Sets mPhone and mForegroundCall/mBackgroundCall/mRingingCall

        mBluetoothHandsfree = app.getBluetoothHandsfree();
        if (VDBG) log("- mBluetoothHandsfree: " + mBluetoothHandsfree);

        if (mBluetoothHandsfree != null) {
            // The PhoneApp only creates a BluetoothHandsfree instance in the
            // first place if getSystemService(Context.BLUETOOTH_SERVICE)
            // succeeds.  So at this point we know the device is BT-capable.
            mBluetoothHeadset = new BluetoothHeadset(this, null);
            if (VDBG) log("- Got BluetoothHeadset: " + mBluetoothHeadset);
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Inflate everything in incall_screen.xml and add it to the screen.
        setContentView(R.layout.incall_screen);
        mDialerDrawer = (SlidingDrawer) findViewById(R.id.dialer_container);

        initInCallScreen();

        // Create the dtmf dialer.  Note that mDialer is instantiated
        // regardless of screen orientation, although the onscreen touchable
        // dialpad is used only in portrait mode.
        mDialer = new DTMFTwelveKeyDialer(this);

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

            // Stash the result code from internalResolveIntent() in the
            // mInCallInitialStatus field.  If it's an error code, we'll
            // handle it in onResume().
            mInCallInitialStatus = internalResolveIntent(getIntent());
            if (mInCallInitialStatus != InCallInitStatus.SUCCESS) {
                Log.w(LOG_TAG, "onCreate: status " + mInCallInitialStatus
                      + " from internalResolveIntent()");
                // See onResume() for the actual error handling.
            }
        } else {
            mInCallInitialStatus = InCallInitStatus.SUCCESS;
        }

        // When in landscape mode, the user can enter dtmf tones
        // at any time.  We need to make sure the DTMFDialer is
        // setup correctly.
        if (ConfigurationHelper.isLandscape()) {
            mDialer.startDialerSession();
            if (VDBG) log("Dialer initialized (in landscape mode).");
        }

        Profiler.callScreenCreated();
    }

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
        // Hang onto the three Call objects too; they're singletons that
        // are constant (and never null) for the life of the Phone.
        mForegroundCall = mPhone.getForegroundCall();
        mBackgroundCall = mPhone.getBackgroundCall();
        mRingingCall = mPhone.getRingingCall();
    }

    @Override
    protected void onResume() {
        if (DBG) log("onResume()...");
        super.onResume();

        mIsForegroundActivity = true;

        final PhoneApp app = PhoneApp.getInstance();

        // Disable the keyguard the entire time the InCallScreen is
        // active.  (This is necessary only for the case of receiving an
        // incoming call while the device is locked; we need to disable
        // the keyguard so you can answer the call and use the in-call UI,
        // but we always re-enable the keyguard as soon as you leave this
        // screen (see onPause().))
        app.disableKeyguard();

        // Touch events are never considered "user activity" while the
        // InCallScreen is active, so that unintentional touches won't
        // prevent the device from going to sleep.
        app.setIgnoreTouchUserActivity(true);

        // Disable the status bar "window shade" the entire time we're on
        // the in-call screen.
        NotificationMgr.getDefault().getStatusBarMgr().enableExpandedView(false);

        // Listen for broadcast intents that might affect the onscreen UI.
        registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));

        // Check for any failures that happened during onCreate() or onNewIntent().
        if (DBG) log("- onResume: initial status = " + mInCallInitialStatus);
        if (mInCallInitialStatus != InCallInitStatus.SUCCESS) {
            if (DBG) log("- onResume: failure during startup: " + mInCallInitialStatus);

            // Don't bring up the regular Phone UI!  Instead bring up
            // something more specific to let the user deal with the
            // problem.
            handleStartupError(mInCallInitialStatus);

            // But it *is* OK to continue with the rest of onResume(),
            // since any further setup steps (like updateScreen() and the
            // CallCard setup) will fall back to a "blank" state if the
            // phone isn't in use.
            mInCallInitialStatus = InCallInitStatus.SUCCESS;
        }

        // Set the volume control handler while we are in the foreground.
        if (isBluetoothAudioConnected()) {
            setVolumeControlStream(AudioManager.STREAM_BLUETOOTH_SCO);
        } else {
            setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        }

        takeKeyEvents(true);

        // Always start off in NORMAL mode.
        setInCallScreenMode(InCallScreenMode.NORMAL);

        // Before checking the state of the phone, clean up any
        // connections in the DISCONNECTED state.
        // (The DISCONNECTED state is used only to drive the "call ended"
        // UI; it's totally useless when *entering* the InCallScreen.)
        mPhone.clearDisconnected();

        InCallInitStatus status = syncWithPhoneState();
        if (status != InCallInitStatus.SUCCESS) {
            if (DBG) log("- syncWithPhoneState failed! status = " + status);
            // Couldn't update the UI, presumably because the phone is totally
            // idle.  But don't finish() immediately, since we might still
            // have an error dialog up that the user needs to see.
            // (And in that case, the error dialog is responsible for calling
            // finish() when the user dismisses it.)
        }

        if (ENABLE_PHONE_UI_EVENT_LOGGING) {
            // InCallScreen is now active.
            Checkin.logEvent(getContentResolver(),
                             Checkin.Events.Tag.PHONE_UI,
                             PHONE_UI_EVENT_ENTER);
        }

        // Update the poke lock and wake lock when we move to
        // the foreground.
        //
        // But we need to do something special if we're coming
        // to the foreground while an incoming call is ringing:
        if (mPhone.getState() == Phone.State.RINGING) {
            // If the phone is ringing, we *should* already be holding a
            // full wake lock (which we would have acquired before
            // firing off the intent that brought us here; see
            // PhoneUtils.showIncomingCallUi().)
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
            app.preventScreenOn(false);
        }
        app.updateWakeState();

        // The "touch lock" overlay is NEVER visible when we resume.
        // (In particular, this check ensures that we won't still be
        // locked after the user wakes up the screen by pressing MENU.)
        enableTouchLock(false);
        // ...but if the dialpad is open we DO need to start the timer
        // that will eventually bring up the "touch lock" overlay.
        if (mDialer.isOpened()) resetTouchLockTimer();

        // Restore the mute state if the last mute state change was NOT
        // done by the user.
        if (app.getRestoreMuteOnInCallResume()) {
            PhoneUtils.restoreMuteState(mPhone);
            app.setRestoreMuteOnInCallResume(false);
        }

        Profiler.profileViewCreate(getWindow(), InCallScreen.class.getName());
        if (VDBG) log("onResume() done.");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (VDBG) log("onSaveInstanceState()...");
        super.onSaveInstanceState(outState);

        // TODO: Save any state of the UI that needs to persist across
        // configuration changes (ie. switching between portrait and
        // landscape.)
    }

    @Override
    protected void onPause() {
        if (DBG) log("onPause()...");
        super.onPause();

        mIsForegroundActivity = false;

        final PhoneApp app = PhoneApp.getInstance();

        // make sure the chronometer is stopped when we move away from
        // the foreground.
        if (mConferenceTime != null) {
            mConferenceTime.stop();
        }

        // as a catch-all, make sure that any dtmf tones are stopped
        // when the UI is no longer in the foreground.
        mDialer.onDialerKeyUp(null);

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
        if (mHandler.hasMessages(DELAYED_CLEANUP_AFTER_DISCONNECT)) {
            if (DBG) log("DELAYED_CLEANUP_AFTER_DISCONNECT detected, moving UI to background.");
            finish();
        }

        if (ENABLE_PHONE_UI_EVENT_LOGGING) {
            // InCallScreen is no longer active.
            Checkin.logEvent(getContentResolver(),
                             Checkin.Events.Tag.PHONE_UI,
                             PHONE_UI_EVENT_EXIT);
        }

        // Clean up the menu, in case we get paused while the menu is up
        // for some reason.
        dismissMenu(true);  // dismiss immediately

        // Dismiss any dialogs we may have brought up, just to be 100%
        // sure they won't still be around when we get back here.
        dismissAllDialogs();

        // Re-enable the status bar (which we disabled in onResume().)
        NotificationMgr.getDefault().getStatusBarMgr().enableExpandedView(true);

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
                    app.setIgnoreTouchUserActivity(false);
                }
            }, 500);

        // The keyguard was disabled the entire time the InCallScreen was
        // active (see onResume()).  Re-enable it now.
        app.reenableKeyguard();

        // Make sure we revert the poke lock and wake lock when we move to
        // the background.
        app.updateWakeState();
    }

    @Override
    protected void onStop() {
        if (VDBG) log("onStop()...");
        super.onStop();

        stopTimer();

        Phone.State state = mPhone.getState();
        if (VDBG) log("onStop: state = " + state);

        if (state == Phone.State.IDLE) {
            // we don't want the call screen to remain in the activity history
            // if there are not active or ringing calls.
            if (DBG) log("- onStop: calling finish() to clear activity history...");
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (DBG) log("onDestroy()...");
        super.onDestroy();

        // Set the magic flag that tells us NOT to handle any handler
        // messages that come in asynchronously after we get destroyed.
        mIsDestroyed = true;

        final PhoneApp app = PhoneApp.getInstance();
        app.setInCallScreenInstance(null);

        // Clear out the InCallScreen references in various helper objects
        // (to let them know we've been destroyed).
        if (mInCallMenu != null) {
            mInCallMenu.clearInCallScreenReference();
        }
        if (mCallCard != null) {
            mCallCard.setInCallScreenInstance(null);
        }

        // Make sure that the dialer session is over and done with.
        // 1. In Landscape mode, we stop the tone generator directly
        // 2. In portrait mode, the tone generator is stopped
        // whenever the dialer is closed by the framework, (either
        // from a user request or calling close on the drawer
        // directly), so all we have to do is to make sure the
        // dialer is closed {see DTMFTwelvKeyDialer.onDialerClose}
        // (it is ok to call this even if the dialer is not open).
        if (ConfigurationHelper.isLandscape()) {
            mDialer.stopDialerSession();
        } else {
            // make sure the dialer drawer is closed.
            mDialer.closeDialer(false);
        }
        mDialer.clearInCallScreenReference();
        mDialer = null;

        unregisterForPhoneStates();
        // No need to change wake state here; that happens in onPause() when we
        // are moving out of the foreground.

        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.close();
            mBluetoothHeadset = null;
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

    /* package */ boolean isForegroundActivity() {
        return mIsForegroundActivity;
    }

    private void registerForPhoneStates() {
        if (!mRegisteredForPhoneStates) {
            mPhone.registerForPhoneStateChanged(mHandler, PHONE_STATE_CHANGED, null);
            mPhone.registerForDisconnect(mHandler, PHONE_DISCONNECT, null);
            if (mPhone.getPhoneName().equals("GSM")) {
                mPhone.registerForMmiInitiate(mHandler, PhoneApp.MMI_INITIATE, null);

                // register for the MMI complete message.  Upon completion,
                // PhoneUtils will bring up a system dialog instead of the
                // message display class in PhoneUtils.displayMMIComplete().
                // We'll listen for that message too, so that we can finish
                // the activity at the same time.
                mPhone.registerForMmiComplete(mHandler, PhoneApp.MMI_COMPLETE, null);
            } else { // CDMA
                if (DBG) log("Registering for Call Waiting.");
                mPhone.registerForCallWaiting(mHandler, PHONE_CDMA_CALL_WAITING, null);
            }

            mPhone.setOnPostDialCharacter(mHandler, POST_ON_DIAL_CHARS, null);
            mPhone.registerForSuppServiceFailed(mHandler, SUPP_SERVICE_FAILED, null);
            mRegisteredForPhoneStates = true;
        }
    }

    private void unregisterForPhoneStates() {
        mPhone.unregisterForPhoneStateChanged(mHandler);
        mPhone.unregisterForDisconnect(mHandler);
        mPhone.unregisterForMmiInitiate(mHandler);
        mPhone.unregisterForCallWaiting(mHandler);
        mPhone.setOnPostDialCharacter(null, POST_ON_DIAL_CHARS, null);
        mRegisteredForPhoneStates = false;
    }

    /* package */ void updateAfterRadioTechnologyChange() {
        if (DBG) Log.d(LOG_TAG, "updateAfterRadioTechnologyChange()...");
        // Unregister for all events from the old obsolete phone
        unregisterForPhoneStates();

        // (Re)register for all events relevant to the new active phone
        registerForPhoneStates();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (DBG) log("onNewIntent: intent=" + intent);

        // We're being re-launched with a new Intent.  Since we keep
        // around a single InCallScreen instance for the life of the phone
        // process (see finish()), this sequence will happen EVERY time
        // there's a new incoming or outgoing call except for the very
        // first time the InCallScreen gets created.  This sequence will
        // also happen if the InCallScreen is already in the foreground
        // (e.g. getting a new ACTION_CALL intent while we were already
        // using the other line.)

        // Stash away the new intent so that we can get it in the future
        // by calling getIntent().  (Otherwise getIntent() will return the
        // original Intent from when we first got created!)
        setIntent(intent);

        // Activities are always paused before receiving a new intent, so
        // we can count on our onResume() method being called next.

        // Just like in onCreate(), handle this intent, and stash the
        // result code from internalResolveIntent() in the
        // mInCallInitialStatus field.  If it's an error code, we'll
        // handle it in onResume().
        mInCallInitialStatus = internalResolveIntent(intent);
        if (mInCallInitialStatus != InCallInitStatus.SUCCESS) {
            Log.w(LOG_TAG, "onNewIntent: status " + mInCallInitialStatus
                  + " from internalResolveIntent()");
            // See onResume() for the actual error handling.
        }
    }

    private InCallInitStatus internalResolveIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return InCallInitStatus.SUCCESS;
        }

        String action = intent.getAction();
        if (DBG) log("internalResolveIntent: action=" + action);

        // The calls to setRestoreMuteOnInCallResume() inform the phone
        // that we're dealing with new connections (either a placing an
        // outgoing call or answering an incoming one, and NOT handling
        // an aborted "Add Call" request), so we should let the mute state
        // be handled by the PhoneUtils phone state change handler.
        final PhoneApp app = PhoneApp.getInstance();
        if (action.equals(Intent.ACTION_ANSWER)) {
            internalAnswerCall();
            app.setRestoreMuteOnInCallResume(false);
            return InCallInitStatus.SUCCESS;
        } else if (action.equals(Intent.ACTION_CALL)
                || action.equals(Intent.ACTION_CALL_EMERGENCY)) {
            app.setRestoreMuteOnInCallResume(false);
            return placeCall(intent);
        } else if (action.equals(intent.ACTION_MAIN)) {
            // The MAIN action is used to bring up the in-call screen without
            // doing any other explicit action, like when you return to the
            // current call after previously bailing out of the in-call UI.
            // SHOW_DIALPAD_EXTRA can be used here to specify whether the DTMF
            // dialpad should be initially visible.  If the extra isn't
            // present at all, we just leave the dialpad in its previous state.
            if (intent.hasExtra(SHOW_DIALPAD_EXTRA)) {
                boolean showDialpad = intent.getBooleanExtra(SHOW_DIALPAD_EXTRA, false);
                if (VDBG) log("- internalResolveIntent: SHOW_DIALPAD_EXTRA value = " + showDialpad);
                if (showDialpad) {
                    mDialer.openDialer(false);  // no "opening" animation
                } else {
                    mDialer.closeDialer(false);  // no "closing" animation
                }
            }
            return InCallInitStatus.SUCCESS;
        } else {
            Log.w(LOG_TAG, "internalResolveIntent: unexpected intent action: " + action);
            // But continue the best we can (basically treating this case
            // like ACTION_MAIN...)
            return InCallInitStatus.SUCCESS;
        }
    }

    private void stopTimer() {
        if (mCallCard != null) mCallCard.stopTimer();
    }

    private void initInCallScreen() {
        if (VDBG) log("initInCallScreen()...");

        // Have the WindowManager filter out touch events that are "too fat".
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        mMainFrame = (ViewGroup) findViewById(R.id.mainFrame);
        mInCallPanel = (ViewGroup) findViewById(R.id.inCallPanel);

        ConfigurationHelper.initConfiguration(getResources().getConfiguration());

        // Create a CallCard and add it to our View hierarchy.
        // TODO: there's no good reason for call_card_popup to be a
        // separate layout that we need to manually inflate here.
        // (That design is left over from when the call card was drawn in
        // its own PopupWindow.)
        // Instead, the CallCard should just be <include>d directly from
        // incall_screen.xml.
        View callCardLayout = getLayoutInflater().inflate(
                R.layout.call_card_popup,
                mInCallPanel);
        mCallCard = (CallCard) callCardLayout.findViewById(R.id.callCard);
        if (VDBG) log("  - mCallCard = " + mCallCard);
        mCallCard.setInCallScreenInstance(this);

        // Menu Button hint
        mMenuButtonHint = (TextView) findViewById(R.id.menuButtonHint);

        // Other platform-specific UI initialization.
        initOnscreenAnswerUi();

        // Make any final updates to our View hierarchy that depend on the
        // current configuration.
        ConfigurationHelper.applyConfigurationToLayout(this);
    }

    /**
     * Returns true if the phone is "in use", meaning that at least one line
     * is active (ie. off hook or ringing or dialing).  Conversely, a return
     * value of false means there's currently no phone activity at all.
     */
    private boolean phoneIsInUse() {
        return mPhone.getState() != Phone.State.IDLE;
    }

    private boolean handleDialerKeyDown(int keyCode, KeyEvent event) {
        if (VDBG) log("handleDialerKeyDown: keyCode " + keyCode + ", event " + event + "...");

        // As soon as the user starts typing valid dialable keys on the
        // keyboard (presumably to type DTMF tones) we start passing the
        // key events to the DTMFDialer's onDialerKeyDown.  We do so
        // only if the okToDialDTMFTones() conditions pass.
        if (okToDialDTMFTones()) {
            return mDialer.onDialerKeyDown(event);
        }

        return false;
    }

    /**
     * Handles a DOWN keypress on the BACK key.
     */
    private boolean handleBackKey() {
        if (VDBG) log("handleBackKey()...");

        // While an incoming call is ringing, BACK behaves just like
        // ENDCALL: it stops the ringing and rejects the current call.
        final CallNotifier notifier = PhoneApp.getInstance().notifier;
        if (notifier.isRinging()) {
            if (DBG) log("BACK key while ringing: reject the call");
            internalHangupRingingCall();

            // Don't consume the key; instead let the BACK event *also*
            // get handled normally by the framework (which presumably
            // will cause us to exit out of this activity.)
            return false;
        }

        // BACK is also used to exit out of any "special modes" of the
        // in-call UI:

        if (mDialer.isOpened()) {
            // Take down the "touch lock" overlay *immediately* to let the
            // user clearly see the DTMF dialpad's closing animation.
            enableTouchLock(false);

            mDialer.closeDialer(true);  // do the "closing" animation
            return true;
        }

        if (mInCallScreenMode == InCallScreenMode.MANAGE_CONFERENCE) {
            // Hide the Manage Conference panel, return to NORMAL mode.
            setInCallScreenMode(InCallScreenMode.NORMAL);
            return true;
        }

        return false;
    }

    /**
     * Handles the green CALL key while in-call.
     * @return true if we consumed the event.
     */
    private boolean handleCallKey() {
        // The green CALL button means either "Answer", "Unhold", or
        // "Swap calls", or can be a no-op, depending on the current state
        // of the Phone.

        final boolean hasRingingCall = !mRingingCall.isIdle();
        final boolean hasActiveCall = !mForegroundCall.isIdle();
        final boolean hasHoldingCall = !mBackgroundCall.isIdle();

        if (mPhone.getPhoneName().equals("CDMA")) {
            // The green CALL button means either "Answer", "Swap calls/On Hold", or
            // "Add to 3WC", depending on the current state of the Phone.

            PhoneApp app = PhoneApp.getInstance();
            CdmaPhoneCallState.PhoneCallState currCallState =
                app.cdmaPhoneCallState.getCurrentCallState();
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
                PhoneUtils.mergeCalls(mPhone);
            } else if (currCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                //Scenario 3: Switching between two Call waiting calls or drop the latest
                // connection if in a 3Way merge scenario
                if (DBG) log("answerCall: Switch btwn 2 calls scenario");
                // Send flash cmd
                PhoneUtils.switchHoldingAndActive(mPhone);
            }
        } else { // GSM.
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
                if (hasActiveCall && hasHoldingCall) {
                    if (DBG) log("handleCallKey: ringing (both lines in use) ==> answer!");
                    internalAnswerCallBothLinesInUse();
                } else {
                    if (DBG) log("handleCallKey: ringing ==> answer!");
                    internalAnswerCall();  // Automatically holds the current active call,
                                           // if there is one
                }
            } else if (hasActiveCall && hasHoldingCall) {
                // Two lines are in use: CALL means "Swap calls".
                if (DBG) log("handleCallKey: both lines in use ==> swap calls.");
                internalSwapCalls();
            } else if (hasHoldingCall) {
                // There's only one line in use, AND it's on hold.
                // In this case CALL is a shortcut for "unhold".
                if (DBG) log("handleCallKey: call on hold ==> unhold.");
                PhoneUtils.switchHoldingAndActive(mPhone);  // Really means "unhold" in this state
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
     *
     * Note, this focus change event happens AFTER the in-call menu is
     * displayed, so mIsMenuDisplayed should always be correct by the
     * time this method is called in the framework, please see:
     * {@link onCreatePanelView}, {@link onOptionsMenuClosed}
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        // if (DBG) log("dispatchKeyEvent(event " + event + ")...");

        // Intercept some events before they get dispatched to our views.
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Disable DPAD keys and trackball clicks if the touch lock
                // overlay is up, since "touch lock" really means "disable
                // the DTMF dialpad" (rather than only disabling touch events.)
                if (mDialer.isOpened() && isTouchLocked()) {
                    if (DBG) log("- ignoring DPAD event while touch-locked...");
                    return true;
                }
                break;

            default:
                break;
        }

        return super.dispatchKeyEvent(event);
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

            case KeyEvent.KEYCODE_BACK:
                if (handleBackKey()) {
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_CAMERA:
                // Disable the CAMERA button while in-call since it's too
                // easy to press accidentally.
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (mPhone.getState() == Phone.State.RINGING) {
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

                    final CallNotifier notifier = PhoneApp.getInstance().notifier;
                    if (notifier.isRinging()) {
                        // ringer is actually playing, so silence it.
                        PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_IDLE);
                        if (DBG) log("VOLUME key: silence ringer");
                        notifier.silenceRinger();
                    }

                    // As long as an incoming call is ringing, we always
                    // consume the VOLUME keys.
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_MENU:
                // Special case for the MENU key: if the "touch lock"
                // overlay is up (over the DTMF dialpad), allow MENU to
                // dismiss the overlay just as if you had double-tapped
                // the onscreen icon.
                // (We do this because MENU is normally used to bring the
                // UI back after the screen turns off, and the touch lock
                // overlay "feels" very similar to the screen going off.
                // This is also here to be "backward-compatibile" with the
                // 1.0 behavior, where you *needed* to hit MENU to bring
                // back the dialpad after 6 seconds of idle time.)
                if (mDialer.isOpened() && isTouchLocked()) {
                    if (VDBG) log("- allowing MENU to dismiss touch lock overlay...");
                    // Take down the touch lock overlay, but post a
                    // message in the future to bring it back later.
                    enableTouchLock(false);
                    resetTouchLockTimer();
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_MUTE:
                PhoneUtils.setMute(mPhone, !PhoneUtils.getMute(mPhone));
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
                .setCancelable(true)
                .create();
        mSuppServiceFailureDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        mSuppServiceFailureDialog.show();
    }

    /**
     * Something has changed in the phone's state.  Update the UI.
     */
    private void onPhoneStateChanged(AsyncResult r) {
        if (VDBG) log("onPhoneStateChanged()...");

        // There's nothing to do here if we're not the foreground activity.
        // (When we *do* eventually come to the foreground, we'll do a
        // full update then.)
        if (!mIsForegroundActivity) {
            if (VDBG) log("onPhoneStateChanged: Activity not in foreground! Bailing out...");
            return;
        }

        updateScreen();

        // Make sure we update the poke lock and wake lock when certain
        // phone state changes occur.
        PhoneApp.getInstance().updateWakeState();
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
        if (DBG) log("onDisconnect: " + c + ", cause=" + cause);

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
        } else if (cause == Connection.DisconnectCause.CDMA_LOCKED_UNTIL_POWER_CYCLE) {
            showGenericErrorDialog(R.string.callFailed_cdma_lockedUntilPowerCycle, false);
            return;
        } else if (cause == Connection.DisconnectCause.CDMA_DROP) {
            showGenericErrorDialog(R.string.callFailed_cdma_drop, false);
            return;
        } else if (cause == Connection.DisconnectCause.CDMA_INTERCEPT) {
            showGenericErrorDialog(R.string.callFailed_cdma_intercept, false);
            return;
        } else if (cause == Connection.DisconnectCause.CDMA_REORDER) {
            showGenericErrorDialog(R.string.callFailed_cdma_reorder, false);
            return;
        } else if (cause == Connection.DisconnectCause.CDMA_SO_REJECT) {
            showGenericErrorDialog(R.string.callFailed_cdma_SO_reject, false);
            return;
        }else if (cause == Connection.DisconnectCause.CDMA_RETRY_ORDER) {
            showGenericErrorDialog(R.string.callFailed_cdma_retryOrder, false);
            return;
        } else if (cause == Connection.DisconnectCause.CDMA_ACCESS_FAILURE) {
            showGenericErrorDialog(R.string.callFailed_cdma_accessFailure, false);
            return;
        } else if (cause == Connection.DisconnectCause.CDMA_PREEMPTED) {
            showGenericErrorDialog(R.string.callFailed_cdma_preempted, false);
            return;
        } else if (cause == Connection.DisconnectCause.CDMA_NOT_EMERGENCY) {
            showGenericErrorDialog(R.string.callFailed_cdma_notEmergency, false);
            return;
        }

        final PhoneApp app = PhoneApp.getInstance();
        boolean currentlyIdle = !phoneIsInUse();

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
                        app.updateWakeState();
                        mPhone.clearDisconnected();  // This happens synchronously.
                        break;
                    }
                }
            }
        }

        // Retrieve the emergency call retry count from this intent, in
        // case we need to retry the call again.
        int emergencyCallRetryCount = getIntent().getIntExtra(
                EmergencyCallHandler.EMERGENCY_CALL_RETRY_KEY,
                EmergencyCallHandler.INITIAL_ATTEMPT);

        // Note: see CallNotifier.onDisconnect() for some other behavior
        // that might be triggered by a disconnect event, like playing the
        // busy/congestion tone.

        // Keep track of whether this call was user-initiated or not.
        // (This affects where we take the user next; see delayedCleanupAfterDisconnect().)
        mShowCallLogAfterDisconnect = !c.isIncoming();

        // We bail out immediately (and *don't* display the "call ended"
        // state at all) in a couple of cases, including those where we
        // are waiting for the radio to finish powering up for an
        // emergency call:
        boolean bailOutImmediately =
                ((cause == Connection.DisconnectCause.INCOMING_MISSED)
                 || (cause == Connection.DisconnectCause.INCOMING_REJECTED)
                 || ((cause == Connection.DisconnectCause.OUT_OF_SERVICE)
                         && (emergencyCallRetryCount > 0)))
                && currentlyIdle;

        if (bailOutImmediately) {
            if (VDBG) log("- onDisconnect: bailOutImmediately...");
            // Exit the in-call UI!
            // (This is basically the same "delayed cleanup" we do below,
            // just with zero delay.  Since the Phone is currently idle,
            // this call is guaranteed to immediately finish() this activity.)
            delayedCleanupAfterDisconnect();

            // Retry the call, by resending the intent to the emergency
            // call handler activity.
            if ((cause == Connection.DisconnectCause.OUT_OF_SERVICE)
                    && (emergencyCallRetryCount > 0)) {
                startActivity(getIntent()
                        .setClassName(this, EmergencyCallHandler.class.getName()));
            }
        } else {
            if (VDBG) log("- onDisconnect: delayed bailout...");
            // Stay on the in-call screen for now.  (Either the phone is
            // still in use, or the phone is idle but we want to display
            // the "call ended" state for a couple of seconds.)

            // Force a UI update in case we need to display anything
            // special given this connection's DisconnectCause (see
            // CallCard.getCallFailedString()).
            updateScreen();

            // Display the special "Call ended" state when the phone is idle
            // but there's still a call in the DISCONNECTED state:
            if (currentlyIdle
                && ((mForegroundCall.getState() == Call.State.DISCONNECTED)
                    || (mBackgroundCall.getState() == Call.State.DISCONNECTED))) {
                if (VDBG) log("- onDisconnect: switching to 'Call ended' state...");
                setInCallScreenMode(InCallScreenMode.CALL_ENDED);
            }

            // Some other misc cleanup that we do if the call that just
            // disconnected was the foreground call.
            final boolean hasActiveCall = !mForegroundCall.isIdle();
            if (!hasActiveCall) {
                if (VDBG) log("- onDisconnect: cleaning up after FG call disconnect...");

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
            }

            // Updating the screen wake state is done in onPhoneStateChanged().

            // Finally, arrange for delayedCleanupAfterDisconnect() to get
            // called after a short interval (during which we display the
            // "call ended" state.)  At that point, if the
            // Phone is idle, we'll finish() out of this activity.
            int callEndedDisplayDelay =
                    (cause == Connection.DisconnectCause.LOCAL)
                    ? CALL_ENDED_SHORT_DELAY : CALL_ENDED_LONG_DELAY;
            mHandler.removeMessages(DELAYED_CLEANUP_AFTER_DISCONNECT);
            mHandler.sendEmptyMessageDelayed(DELAYED_CLEANUP_AFTER_DISCONNECT,
                                             callEndedDisplayDelay);
        }

        // Remove 3way timer (only meaningful for CDMA)
        mHandler.removeMessages(THREEWAY_CALLERINFO_DISPLAY_DONE);
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
        if (DBG) log("onMMICancel: finishing...");
        finish();
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
                    // TODO: is this needed, now that you can't actually
                    // type DTMF chars or dial directly from here?
                    // If so, we'd need to yank you out of the in-call screen
                    // here too (and take you to the 12-key dialer in "in-call" mode.)
                    // displayPostDialedChar(ch);
                    break;

                case WAIT:
                    //if (DBG) log("show wait prompt...");
                    String postDialStr = c.getRemainingPostDialString();
                    showWaitPromptDialog(c, postDialStr);
                    break;

                case WILD:
                    //if (DBG) log("prompt user to replace WILD char");
                    showWildPromptDialog(c);
                    break;

                case COMPLETE:
                    break;

                default:
                    break;
            }
        }
    }

    private void showWaitPromptDialog(final Connection c, String postDialStr) {
        Resources r = getResources();
        StringBuilder buf = new StringBuilder();
        buf.append(r.getText(R.string.wait_prompt_str));
        buf.append(postDialStr);

        if (mWaitPromptDialog != null) {
            if (VDBG) log("- DISMISSING mWaitPromptDialog.");
            mWaitPromptDialog.dismiss();  // safe even if already dismissed
            mWaitPromptDialog = null;
        }

        mWaitPromptDialog = new AlertDialog.Builder(this)
                .setMessage(buf.toString())
                .setPositiveButton(R.string.send_button, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            if (VDBG) log("handle WAIT_PROMPT_CONFIRMED, proceed...");
                            c.proceedAfterWaitChar();
                            PhoneApp.getInstance().pokeUserActivity();
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface dialog) {
                            if (VDBG) log("handle POST_DIAL_CANCELED!");
                            c.cancelPostDial();
                            PhoneApp.getInstance().pokeUserActivity();
                        }
                    })
                .create();
        mWaitPromptDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        mWaitPromptDialog.show();
    }

    private View createWildPromptView() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(5, 5, 5, 5);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.FILL_PARENT,
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
                        ViewGroup.LayoutParams.FILL_PARENT,
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
                                PhoneApp.getInstance().pokeUserActivity();
                            }
                        })
                .setOnCancelListener(
                        new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface dialog) {
                                if (VDBG) log("handle POST_DIAL_CANCELED!");
                                c.cancelPostDial();
                                PhoneApp.getInstance().pokeUserActivity();
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
     * the Phone.
     */
    private void updateScreen() {
        if (VDBG) log("updateScreen()...");

        // Don't update anything if we're not in the foreground (there's
        // no point updating our UI widgets since we're not visible!)
        // Also note this check also ensures we won't update while we're
        // in the middle of pausing, which could cause a visible glitch in
        // the "activity ending" transition.
        if (!mIsForegroundActivity) {
            if (VDBG) log("- updateScreen: not the foreground Activity! Bailing out...");
            return;
        }

        // Update the state of the in-call menu items.
        if (mInCallMenu != null) {
            // TODO: do this only if the menu is visible!
            if (VDBG) log("- updateScreen: updating menu items...");
            boolean okToShowMenu = mInCallMenu.updateItems(mPhone);
            if (!okToShowMenu) {
                // Uh oh: we were only trying to update the state of the
                // menu items, but the logic in InCallMenu.updateItems()
                // just decided the menu shouldn't be visible at all!
                // (That's probably means that the call ended
                // asynchronously while the menu was up.)
                //
                // So take the menu down ASAP.
                if (VDBG) log("- updateScreen: Tried to update menu; now need to dismiss!");
                // dismissMenu() has no effect if the menu is already closed.
                dismissMenu(true);  // dismissImmediate = true
            }
        }

        if (mInCallScreenMode == InCallScreenMode.MANAGE_CONFERENCE) {
            if (VDBG) log("- updateScreen: manage conference mode (NOT updating in-call UI)...");
            updateManageConferencePanelIfNecessary();
            return;
        } else if (mInCallScreenMode == InCallScreenMode.CALL_ENDED) {
            if (VDBG) log("- updateScreen: call ended state (NOT updating in-call UI)...");
            return;
        }

        if (VDBG) log("- updateScreen: updating the in-call UI...");
        mCallCard.updateState(mPhone);
        updateDialpadVisibility();
        updateOnscreenAnswerUi();
        updateMenuButtonHint();
    }

    /**
     * (Re)synchronizes the onscreen UI with the current state of the
     * Phone.
     *
     * @return InCallInitStatus.SUCCESS if we successfully updated the UI, or
     *    InCallInitStatus.PHONE_NOT_IN_USE if there was no phone state to sync
     *    with (ie. the phone was completely idle).  In the latter case, we
     *    shouldn't even be in the in-call UI in the first place, and it's
     *    the caller's responsibility to bail out of this activity (by
     *    calling finish()) if appropriate.
     */
    private InCallInitStatus syncWithPhoneState() {
        boolean updateSuccessful = false;
        if (DBG) log("syncWithPhoneState()...");
        if (VDBG) PhoneUtils.dumpCallState(mPhone);
        if (VDBG) dumpBluetoothState();

        // Make sure the Phone is "in use".  (If not, we shouldn't be on
        // this screen in the first place.)

        // Need to treat running MMI codes as a connection as well.
        // Do not check for getPendingMmiCodes when phone is a CDMA phone
        if (!mForegroundCall.isIdle() || !mBackgroundCall.isIdle() || !mRingingCall.isIdle()
            || mPhone.getPhoneName().equals("CDMA") || !mPhone.getPendingMmiCodes().isEmpty()) {
            if (VDBG) log("syncWithPhoneState: it's ok to be here; update the screen...");
            updateScreen();
            return InCallInitStatus.SUCCESS;
        }

        if (DBG) log("syncWithPhoneState: phone is idle; we shouldn't be here!");
        return InCallInitStatus.PHONE_NOT_IN_USE;
    }

    /**
     * Given the Intent we were initially launched with,
     * figure out the actual phone number we should dial.
     *
     * @return the phone number corresponding to the
     *   specified Intent, or null if the Intent is not
     *   a ACTION_CALL intent or if the intent's data is
     *   malformed or missing.
     *
     * @throws VoiceMailNumberMissingException if the intent
     *   contains a "voicemail" URI, but there's no voicemail
     *   number configured on the device.
     */
    private String getInitialNumber(Intent intent)
            throws PhoneUtils.VoiceMailNumberMissingException {
        String action = intent.getAction();

        if (action == null) {
            return null;
        }

        if (action != null && action.equals(Intent.ACTION_CALL) &&
                intent.hasExtra(Intent.EXTRA_PHONE_NUMBER)) {
            return intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
        }

        return PhoneUtils.getNumberFromIntent(this, mPhone, intent);
    }

    /**
     * Make a call to whomever the intent tells us to.
     *
     * @param intent the Intent we were launched with
     * @return InCallInitStatus.SUCCESS if we successfully initiated an
     *    outgoing call.  If there was some kind of failure, return one of
     *    the other InCallInitStatus codes indicating what went wrong.
     */
    private InCallInitStatus placeCall(Intent intent) {
        if (VDBG) log("placeCall()...  intent = " + intent);

        String number;

        // Check the current ServiceState to make sure it's OK
        // to even try making a call.
        InCallInitStatus okToCallStatus = checkIfOkToInitiateOutgoingCall();

        try {
            number = getInitialNumber(intent);
        } catch (PhoneUtils.VoiceMailNumberMissingException ex) {
            // If the call status is NOT in an acceptable state, it
            // may effect the way the voicemail number is being
            // retrieved.  Mask the VoiceMailNumberMissingException
            // with the underlying issue of the phone state.
            if (okToCallStatus != InCallInitStatus.SUCCESS) {
                if (DBG) log("Voicemail number not reachable in current SIM card state.");
                return okToCallStatus;
            }
            if (DBG) log("VoiceMailNumberMissingException from getInitialNumber()");
            return InCallInitStatus.VOICEMAIL_NUMBER_MISSING;
        }

        if (number == null) {
            Log.w(LOG_TAG, "placeCall: couldn't get a phone number from Intent " + intent);
            return InCallInitStatus.NO_PHONE_NUMBER_SUPPLIED;
        }

        boolean isEmergencyNumber = PhoneNumberUtils.isEmergencyNumber(number);
        boolean isEmergencyIntent = Intent.ACTION_CALL_EMERGENCY.equals(intent.getAction());

        if (isEmergencyNumber && !isEmergencyIntent) {
            Log.e(LOG_TAG, "Non-CALL_EMERGENCY Intent " + intent
                    + " attempted to call emergency number " + number
                    + ".");
            return InCallInitStatus.CALL_FAILED;
        } else if (!isEmergencyNumber && isEmergencyIntent) {
            Log.e(LOG_TAG, "Received CALL_EMERGENCY Intent " + intent
                    + " with non-emergency number " + number
                    + " -- failing call.");
            return InCallInitStatus.CALL_FAILED;
        }

        // need to make sure that the state is adjusted if we are ONLY
        // allowed to dial emergency numbers AND we encounter an
        // emergency number request.
        if (isEmergencyNumber && okToCallStatus == InCallInitStatus.EMERGENCY_ONLY) {
            okToCallStatus = InCallInitStatus.SUCCESS;
            if (DBG) log("Emergency number detected, changing state to: " + okToCallStatus);
        }

        if (okToCallStatus != InCallInitStatus.SUCCESS) {
            // If this is an emergency call, we call the emergency call
            // handler activity to turn on the radio and do whatever else
            // is needed. For now, we finish the InCallScreen (since were
            // expecting a callback when the emergency call handler dictates
            // it) and just return the success state.
            if (isEmergencyNumber && (okToCallStatus == InCallInitStatus.POWER_OFF)) {
                startActivity(intent.setClassName(this, EmergencyCallHandler.class.getName()));
                if (DBG) log("placeCall: starting EmergencyCallHandler, finishing...");
                finish();
                return InCallInitStatus.SUCCESS;
            } else {
                return okToCallStatus;
            }
        }

        // We have a valid number, so try to actually place a call:
        //make sure we pass along the URI as a reference to the contact.
        int callStatus = PhoneUtils.placeCall(mPhone, number, intent.getData());
        switch (callStatus) {
            case PhoneUtils.CALL_STATUS_DIALED:
                if (VDBG) log("placeCall: PhoneUtils.placeCall() succeeded for regular call '"
                             + number + "'.");

                // Any time we initiate a call, force the DTMF dialpad to
                // close.  (We want to make sure the user can see the regular
                // in-call UI while the new call is dialing, and when it
                // first gets connected.)
                mDialer.closeDialer(false);  // no "closing" animation

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
                mDialer.clearDigits();

                PhoneApp app = PhoneApp.getInstance();
                if (app.phone.getPhoneName().equals("CDMA")) {
                    // Start the 2 second timer for 3 Way CallerInfo
                    if (app.cdmaPhoneCallState.getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                        //Unmute for the second MO call
                        PhoneUtils.setMuteInternal(mPhone, false);

                        //Start the timer for displaying "Dialing" for second call
                        Message msg = Message.obtain(mHandler, THREEWAY_CALLERINFO_DISPLAY_DONE);
                        mHandler.sendMessageDelayed(msg, THREEWAY_CALLERINFO_DISPLAY_TIME);

                        // Set the mThreeWayCallOrigStateDialing state to true
                        app.cdmaPhoneCallState.setThreeWayCallOrigState(true);

                        //Update screen to show 3way dialing
                        updateScreen();
                    }
                }

                return InCallInitStatus.SUCCESS;
            case PhoneUtils.CALL_STATUS_DIALED_MMI:
                if (DBG) log("placeCall: specified number was an MMI code: '" + number + "'.");
                // The passed-in number was an MMI code, not a regular phone number!
                // This isn't really a failure; the Dialer may have deliberately
                // fired a ACTION_CALL intent to dial an MMI code, like for a
                // USSD call.
                //
                // Presumably an MMI_INITIATE message will come in shortly
                // (and we'll bring up the "MMI Started" dialog), or else
                // an MMI_COMPLETE will come in (which will take us to a
                // different Activity; see PhoneUtils.displayMMIComplete()).
                return InCallInitStatus.DIALED_MMI;
            case PhoneUtils.CALL_STATUS_FAILED:
                Log.w(LOG_TAG, "placeCall: PhoneUtils.placeCall() FAILED for number '"
                      + number + "'.");
                // We couldn't successfully place the call; there was some
                // failure in the telephony layer.
                return InCallInitStatus.CALL_FAILED;
            default:
                Log.w(LOG_TAG, "placeCall: unknown callStatus " + callStatus
                      + " from PhoneUtils.placeCall() for number '" + number + "'.");
                return InCallInitStatus.SUCCESS;  // Try to continue anyway...
        }
    }

    /**
     * Checks the current ServiceState to make sure it's OK
     * to try making an outgoing call to the specified number.
     *
     * @return InCallInitStatus.SUCCESS if it's OK to try calling the specified
     *    number.  If not, like if the radio is powered off or we have no
     *    signal, return one of the other InCallInitStatus codes indicating what
     *    the problem is.
     */
    private InCallInitStatus checkIfOkToInitiateOutgoingCall() {
        // Watch out: do NOT use PhoneStateIntentReceiver.getServiceState() here;
        // that's not guaranteed to be fresh.  To synchronously get the
        // CURRENT service state, ask the Phone object directly:
        int state = mPhone.getServiceState().getState();
        if (VDBG) log("checkIfOkToInitiateOutgoingCall: ServiceState = " + state);

        switch (state) {
            case ServiceState.STATE_IN_SERVICE:
                // Normal operation.  It's OK to make outgoing calls.
                return InCallInitStatus.SUCCESS;


            case ServiceState.STATE_POWER_OFF:
                // Radio is explictly powered off.
                return InCallInitStatus.POWER_OFF;

            case ServiceState.STATE_OUT_OF_SERVICE:
            case ServiceState.STATE_EMERGENCY_ONLY:
                // The phone is registered, but locked. Only emergency
                // numbers are allowed.
                return InCallInitStatus.EMERGENCY_ONLY;
            default:
                throw new IllegalStateException("Unexpected ServiceState: " + state);
        }
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
                            PhoneApp.getInstance().pokeUserActivity();
                        }})
                .setNegativeButton(R.string.add_vm_number_str,
                                   new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (VDBG) log("Missing voicemail AlertDialog: NEGATIVE click...");
                            msg2.sendToTarget();  // see addVoiceMailNumber()
                            PhoneApp.getInstance().pokeUserActivity();
                        }})
                .setOnCancelListener(new OnCancelListener() {
                        public void onCancel(DialogInterface dialog) {
                            if (VDBG) log("Missing voicemail AlertDialog: CANCEL handler...");
                            msg.sendToTarget();  // see dontAddVoiceMailNumber()
                            PhoneApp.getInstance().pokeUserActivity();
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
        finish();

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
        if (DBG) log("dontAddVoiceMailNumber: finishing...");
        finish();
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
     * time to finish() this activity.
     *
     * If the Phone is *not* idle right now, that probably means that one
     * call ended but the other line is still in use.  In that case, we
     * *don't* exit the in-call screen, but we at least turn off the
     * backlight (which we turned on in onDisconnect().)
     */
    private void delayedCleanupAfterDisconnect() {
        if (VDBG) log("delayedCleanupAfterDisconnect()...  Phone state = " + mPhone.getState());

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
        mPhone.clearDisconnected();

        if (!phoneIsInUse()) {
            // Phone is idle!  We should exit this screen now.
            if (DBG) log("- delayedCleanupAfterDisconnect: phone is idle...");

            // No need to re-enable keyguard or screen wake state here;
            // that happens in onPause() when we actually exit.

            // And (finally!) exit from the in-call screen
            // (but not if we're already in the process of pausing...)
            if (mIsForegroundActivity) {
                if (DBG) log("- delayedCleanupAfterDisconnect: finishing...");

                // If this is a call that was initiated by the user, and
                // we're *not* in emergency mode, finish the call by
                // taking the user to the Call Log.
                // Otherwise we simply call finish(), which will take us
                // back to wherever we came from.
                if (mShowCallLogAfterDisconnect && !isPhoneStateRestricted()) {
                    if (VDBG) log("- Show Call Log after disconnect...");
                    final Intent intent = PhoneApp.createCallLogIntent();
                    startActivity(intent);
                    // Even in this case we still call finish() (below),
                    // to make sure we don't stay in the activity history.
                }

                finish();
            }
        } else {
            // The phone is still in use.  Stay here in this activity, but
            // we don't need to keep the screen on.
            if (DBG) log("- delayedCleanupAfterDisconnect: staying on the InCallScreen...");
            if (DBG) PhoneUtils.dumpCallState(mPhone);
            // No need to re-enable keyguard or screen wake state here;
            // should be taken care of in onPhoneStateChanged();
        }
    }


    //
    // Callbacks for buttons / menu items.
    //

    public void onClick(View view) {
        int id = view.getId();
        if (VDBG) log("onClick(View " + view + ", id " + id + ")...");
        if (VDBG && view instanceof InCallMenuItemView) {
            InCallMenuItemView item = (InCallMenuItemView) view;
            log("  ==> menu item! " + item);
        }

        // Most menu items dismiss the menu immediately once you click
        // them.  But some items (the "toggle" buttons) are different:
        // they want the menu to stay visible for a second afterwards to
        // give you feedback about the state change.
        boolean dismissMenuImmediate = true;
        Context context = getApplicationContext();

        switch (id) {
            case R.id.menuAnswerAndHold:
                if (VDBG) log("onClick: AnswerAndHold...");
                internalAnswerCall();  // Automatically holds the current active call
                break;

            case R.id.menuAnswerAndEnd:
                if (VDBG) log("onClick: AnswerAndEnd...");
                internalAnswerAndEnd();
                break;

            case R.id.menuAnswer:
                if (DBG) log("onClick: Answer...");
                internalAnswerCall();
                break;

            case R.id.menuIgnore:
                if (DBG) log("onClick: Ignore...");
                final CallNotifier notifier = PhoneApp.getInstance().notifier;
                notifier.onCdmaCallWaitingReject();
                break;

            case R.id.menuSwapCalls:
                if (VDBG) log("onClick: SwapCalls...");
                internalSwapCalls();
                break;

            case R.id.menuMergeCalls:
                if (VDBG) log("onClick: MergeCalls...");
                PhoneUtils.mergeCalls(mPhone);
                break;

            case R.id.menuManageConference:
                if (VDBG) log("onClick: ManageConference...");
                // Show the Manage Conference panel.
                setInCallScreenMode(InCallScreenMode.MANAGE_CONFERENCE);
                break;

            case R.id.menuShowDialpad:
                if (VDBG) log("onClick: Show/hide dialpad...");
                if (mDialer.isOpened()) {
                    mDialer.closeDialer(true);  // do the "closing" animation
                } else {
                    mDialer.openDialer(true);  // do the "opening" animation
                }
                break;

            case R.id.manage_done:  // mButtonManageConferenceDone
                if (VDBG) log("onClick: mButtonManageConferenceDone...");
                // Hide the Manage Conference panel, return to NORMAL mode.
                setInCallScreenMode(InCallScreenMode.NORMAL);
                break;

            case R.id.menuSpeaker:
                if (VDBG) log("onClick: Speaker...");
                // TODO: Turning on the speaker seems to enable the mic
                //   whether or not the "mute" feature is active!
                // Not sure if this is an feature of the telephony API
                //   that I need to handle specially, or just a bug.
                boolean newSpeakerState = !PhoneUtils.isSpeakerOn(context);
                if (newSpeakerState && isBluetoothAvailable() && isBluetoothAudioConnected()) {
                    disconnectBluetoothAudio();
                }
                PhoneUtils.turnOnSpeaker(context, newSpeakerState);

                if (newSpeakerState) {
                    // The "touch lock" overlay is NEVER used when the speaker is on.
                    enableTouchLock(false);
                } else {
                    // User just turned the speaker *off*.  If the dialpad
                    // is open, we need to start the timer that will
                    // eventually bring up the "touch lock" overlay.
                    if (mDialer.isOpened() && !isTouchLocked()) {
                        resetTouchLockTimer();
                    }
                }

                // This is a "toggle" button; let the user see the new state for a moment.
                dismissMenuImmediate = false;
                break;

            case R.id.menuBluetooth:
                if (VDBG) log("onClick: Bluetooth...");
                onBluetoothClick(context);
                // This is a "toggle" button; let the user see the new state for a moment.
                dismissMenuImmediate = false;
                break;

            case R.id.menuMute:
                if (VDBG) log("onClick: Mute...");
                boolean newMuteState = !PhoneUtils.getMute(mPhone);
                PhoneUtils.setMute(mPhone, newMuteState);
                // This is a "toggle" button; let the user see the new state for a moment.
                dismissMenuImmediate = false;
                break;

            case R.id.menuHold:
                if (VDBG) log("onClick: Hold...");
                onHoldClick();
                // This is a "toggle" button; let the user see the new state for a moment.
                dismissMenuImmediate = false;
                break;

            case R.id.menuAddCall:
                if (VDBG) log("onClick: AddCall...");
                PhoneUtils.startNewCall(mPhone);  // Fires off a ACTION_DIAL intent
                break;

            case R.id.menuEndCall:
                if (VDBG) log("onClick: EndCall...");
                PhoneUtils.hangup(mPhone);
                break;

            default:
                Log.w(LOG_TAG,
                      "Got click from unexpected View ID " + id + " (View = " + view + ")");
                break;
        }

        if (ENABLE_PHONE_UI_EVENT_LOGGING) {
            // TODO: For now we care only about whether the user uses the
            // in-call buttons at all.  But in the future we may want to
            // log exactly which buttons are being clicked.  (Maybe just
            // call view.getText() here, and append that to the event value?)
            Checkin.logEvent(getContentResolver(),
                             Checkin.Events.Tag.PHONE_UI,
                             PHONE_UI_EVENT_BUTTON_CLICK);
        }

        // If the user just clicked a "stateful" menu item (i.e. one of
        // the toggle buttons), we keep the menu onscreen briefly to
        // provide visual feedback.  Since we want the user to see the
        // *new* current state, force the menu items to update right now.
        //
        // Note that some toggle buttons ("Hold" in particular) do NOT
        // immediately change the state of the Phone.  In that case, the
        // updateItems() call below won't have any visible effect.
        // Instead, the menu will get updated by the updateScreen() call
        // that happens from onPhoneStateChanged().

        if (!dismissMenuImmediate) {
            // TODO: mInCallMenu.updateItems() is a very big hammer; it
            // would be more efficient to update *only* the menu item(s)
            // we just changed.  (Doing it this way doesn't seem to cause
            // a noticeable performance problem, though.)
            if (VDBG) log("- onClick: updating menu to show 'new' current state...");
            boolean okToShowMenu = mInCallMenu.updateItems(mPhone);
            if (!okToShowMenu) {
                // Uh oh.  All we tried to do was update the state of the
                // menu items, but the logic in InCallMenu.updateItems()
                // just decided the menu shouldn't be visible at all!
                // (That probably means that the call ended asynchronously
                // while the menu was up.)
                //
                // That's OK; just make sure to take the menu down ASAP.
                if (VDBG) log("onClick: Tried to update menu, but now need to take it down!");
                dismissMenuImmediate = true;
            }
        }

        // Any menu item counts as explicit "user activity".
        PhoneApp.getInstance().pokeUserActivity();

        // Finally, *any* action handled here closes the menu (either
        // immediately, or after a short delay).
        //
        // Note that some of the clicks we handle here aren't even menu
        // items in the first place, like the mButtonManageConferenceDone
        // button.  That's OK; if the menu is already closed, the
        // dismissMenu() call does nothing.
        dismissMenu(dismissMenuImmediate);
    }

    private void onHoldClick() {
        if (VDBG) log("onHoldClick()...");

        final boolean hasActiveCall = !mForegroundCall.isIdle();
        final boolean hasHoldingCall = !mBackgroundCall.isIdle();
        if (VDBG) log("- hasActiveCall = " + hasActiveCall
                      + ", hasHoldingCall = " + hasHoldingCall);
        boolean newHoldState;
        boolean holdButtonEnabled;
        if (hasActiveCall && !hasHoldingCall) {
            // There's only one line in use, and that line is active.
            PhoneUtils.switchHoldingAndActive(mPhone);  // Really means "hold" in this state
            newHoldState = true;
            holdButtonEnabled = true;
        } else if (!hasActiveCall && hasHoldingCall) {
            // There's only one line in use, and that line is on hold.
            PhoneUtils.switchHoldingAndActive(mPhone);  // Really means "unhold" in this state
            newHoldState = false;
            holdButtonEnabled = true;
        } else {
            // Either zero or 2 lines are in use; "hold/unhold" is meaningless.
            newHoldState = false;
            holdButtonEnabled = false;
        }
        // TODO: we *could* now forcibly update the "Hold" button based on
        // "newHoldState" and "holdButtonEnabled".  But for now, do
        // nothing here, and instead let the menu get updated when the
        // onPhoneStateChanged() callback comes in.  (This seems to be
        // responsive enough.)
    }

    private void onBluetoothClick(Context context) {
        if (VDBG) log("onBluetoothClick()...");

        if (isBluetoothAvailable()) {
            // Toggle the bluetooth audio connection state:
            if (isBluetoothAudioConnected()) {
                disconnectBluetoothAudio();
            } else {
                // Manually turn the speaker phone off, instead of allowing the
                // Bluetooth audio routing handle it.  This ensures that the rest
                // of the speakerphone code is executed, and reciprocates the
                // menuSpeaker code above in onClick().  The onClick() code
                // disconnects the active bluetooth headsets when the
                // speakerphone is turned on.
                if (PhoneUtils.isSpeakerOn(context)) {
                    PhoneUtils.turnOnSpeaker(context, false);
                }

                connectBluetoothAudio();
            }
        } else {
            // Bluetooth isn't available; the "Audio" button shouldn't have
            // been enabled in the first place!
            Log.w(LOG_TAG, "Got onBluetoothClick, but bluetooth is unavailable");
        }
    }

    /**
     * Updates the "Press Menu for more options" hint based on the current
     * state of the Phone.
     */
    private void updateMenuButtonHint() {
        if (VDBG) log("updateMenuButtonHint()...");
        boolean hintVisible = true;

        final boolean hasRingingCall = !mRingingCall.isIdle();
        final boolean hasActiveCall = !mForegroundCall.isIdle();
        final boolean hasHoldingCall = !mBackgroundCall.isIdle();

        // The hint is hidden only when there's no menu at all,
        // which only happens in a few specific cases:
        if (mInCallScreenMode == InCallScreenMode.CALL_ENDED) {
            // The "Call ended" state.
            hintVisible = false;
        } else if (hasRingingCall && !(hasActiveCall && !hasHoldingCall)) {
            // An incoming call where you *don't* have the option to
            // "answer & end" or "answer & hold".
            hintVisible = false;
        } else if (!phoneIsInUse()) {
            // Or if the phone is totally idle (like if an error dialog
            // is up, or an MMI is running.)
            hintVisible = false;
        }
        int hintVisibility = (hintVisible) ? View.VISIBLE : View.GONE;

        // We actually have two separate "menu button hint" TextViews; one
        // used only in portrait mode (part of the CallCard) and one used
        // only in landscape mode (part of the InCallScreen.)
        TextView callCardMenuButtonHint = mCallCard.getMenuButtonHint();
        if (ConfigurationHelper.isLandscape()) {
            callCardMenuButtonHint.setVisibility(View.GONE);
            mMenuButtonHint.setVisibility(hintVisibility);
        } else {
            callCardMenuButtonHint.setVisibility(hintVisibility);
            mMenuButtonHint.setVisibility(View.GONE);
        }

        // TODO: Consider hiding the hint(s) whenever the menu is onscreen!
        // (Currently, the menu is rendered on top of the hint, but the
        // menu is semitransparent so you can still see the hint
        // underneath, and the hint is *just* visible enough to be
        // distracting.)
    }

    /**
     * Brings up UI to handle the various error conditions that
     * can occur when first initializing the in-call UI.
     * This is called from onResume() if we encountered
     * an error while processing our initial Intent.
     *
     * @param status one of the InCallInitStatus error codes.
     */
    private void handleStartupError(InCallInitStatus status) {
        if (DBG) log("handleStartupError(): status = " + status);

        // NOTE that the regular Phone UI is in an uninitialized state at
        // this point, so we don't ever want the user to see it.
        // That means:
        // - Any cases here that need to go to some other activity should
        //   call startActivity() AND immediately call finish() on this one.
        // - Any cases here that bring up a Dialog must ensure that the
        //   Dialog handles both OK *and* cancel by calling finish() on this
        //   Activity.  (See showGenericErrorDialog() for an example.)

        switch(status) {

            case VOICEMAIL_NUMBER_MISSING:
                // Bring up the "Missing Voicemail Number" dialog, which
                // will ultimately take us to some other Activity (or else
                // just bail out of this activity.)
                handleMissingVoiceMailNumber();
                break;

            case POWER_OFF:
                // Radio is explictly powered off.

                // TODO: This UI is ultra-simple for 1.0.  It would be nicer
                // to bring up a Dialog instead with the option "turn on radio
                // now".  If selected, we'd turn the radio on, wait for
                // network registration to complete, and then make the call.

                showGenericErrorDialog(R.string.incall_error_power_off, true);
                break;

            case EMERGENCY_ONLY:
                // Only emergency numbers are allowed, but we tried to dial
                // a non-emergency number.
                showGenericErrorDialog(R.string.incall_error_emergency_only, true);
                break;

            case PHONE_NOT_IN_USE:
                // This error is handled directly in onResume() (by bailing
                // out of the activity.)  We should never see it here.
                Log.w(LOG_TAG,
                      "handleStartupError: unexpected PHONE_NOT_IN_USE status");
                break;

            case NO_PHONE_NUMBER_SUPPLIED:
                // The supplied Intent didn't contain a valid phone number.
                // TODO: Need UI spec for this failure case; for now just
                // show a generic error.
                showGenericErrorDialog(R.string.incall_error_no_phone_number_supplied, true);
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
                if (mPhone.getState() == Phone.State.OFFHOOK) {
                    Toast.makeText(this, R.string.incall_status_dialed_mmi, Toast.LENGTH_SHORT)
                        .show();
                }
                break;

            case CALL_FAILED:
                // We couldn't successfully place the call; there was some
                // failure in the telephony layer.
                // TODO: Need UI spec for this failure case; for now just
                // show a generic error.
                showGenericErrorDialog(R.string.incall_error_call_failed, true);
                break;

            default:
                Log.w(LOG_TAG, "handleStartupError: unexpected status code " + status);
                showGenericErrorDialog(R.string.incall_error_call_failed, true);
                break;
        }
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

    private void bailOutAfterErrorDialog() {
        if (mGenericErrorDialog != null) {
            if (VDBG) log("bailOutAfterErrorDialog: DISMISSING mGenericErrorDialog.");
            mGenericErrorDialog.dismiss();
            mGenericErrorDialog = null;
        }
        if (DBG) log("bailOutAfterErrorDialog(): finishing...");
        finish();
    }

    /**
     * Dismisses (and nulls out) all persistent Dialogs managed
     * by the InCallScreen.  Useful if (a) we're about to bring up
     * a dialog and want to pre-empt any currently visible dialogs,
     * or (b) as a cleanup step when the Activity is going away.
     */
    private void dismissAllDialogs() {
        if (VDBG) log("dismissAllDialogs()...");

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
    }


    //
    // Helper functions for answering incoming calls.
    //

    /**
     * Answer the ringing call.
     */
    /* package */ void internalAnswerCall() {
        if (DBG) log("internalAnswerCall()...");
        // if (DBG) PhoneUtils.dumpCallState(mPhone);
        PhoneUtils.answerCall(mPhone);  // Automatically holds the current active call,
                                        // if there is one
        // Manually set the mute to false, especially in the case of an
        // incoming call-waiting call
        // TODO: would this need to be done for GSM also?
        if (mPhone.getPhoneName().equals("CDMA")) {
            PhoneUtils.setMute(mPhone, false);
        }
    }

    /**
     * Answer the ringing call *and* hang up the ongoing call.
     */
    /* package */ void internalAnswerAndEnd() {
        if (DBG) log("internalAnswerAndEnd()...");
        // if (DBG) PhoneUtils.dumpCallState(mPhone);
        PhoneUtils.answerAndEndActive(mPhone);
    }

    /**
     * Answer the ringing call, in the special case where both lines
     * are already in use.
     *
     * We "answer incoming, end ongoing" in this case, according to the
     * current UI spec.
     */
    /* package */ void internalAnswerCallBothLinesInUse() {
        if (DBG) log("internalAnswerCallBothLinesInUse()...");
        // if (DBG) PhoneUtils.dumpCallState(mPhone);

        PhoneUtils.answerAndEndActive(mPhone);
        // Alternatively, we could use
        //    PhoneUtils.answerAndEndHolding(mPhone);
        // here to end the on-hold call instead.
    }

    /**
     * Hang up the ringing call (aka "Don't answer").
     */
    /* package */ void internalHangupRingingCall() {
        if (DBG) log("internalHangupRingingCall()...");
        PhoneUtils.hangupRingingCall(mPhone);
    }

    /**
     * InCallScreen-specific wrapper around PhoneUtils.switchHoldingAndActive().
     */
    private void internalSwapCalls() {
        if (VDBG) log("internalSwapCalls()...");

        // Any time we swap calls, force the DTMF dialpad to close.
        // (We want the regular in-call UI to be visible right now, so the
        // user can clearly see which call is now in the foreground.)
        mDialer.closeDialer(true);  // do the "closing" animation

        // Also, clear out the "history" of DTMF digits you typed, to make
        // sure you don't see digits from call #1 while call #2 is active.
        // (Yes, this does mean that swapping calls twice will cause you
        // to lose any previous digits from the current call; see the TODO
        // comment on DTMFTwelvKeyDialer.clearDigits() for more info.)
        mDialer.clearDigits();

        // Swap the fg and bg calls.
        PhoneUtils.switchHoldingAndActive(mPhone);
    }

    //
    // "Manage conference" UI.
    //
    // TODO: There's a lot of code here, and this source file is already too large.
    // Consider moving all this code out to a separate class.
    //

    private void initManageConferencePanel() {
        if (VDBG) log("initManageConferencePanel()...");
        if (mManageConferencePanel == null) {
            mManageConferencePanel = (ViewGroup) findViewById(R.id.manageConferencePanel);

            // set up the Conference Call chronometer
            mConferenceTime = (Chronometer) findViewById(R.id.manageConferencePanelHeader);
            mConferenceTime.setFormat(getString(R.string.caller_manage_header));

            // Create list of conference call widgets
            mConferenceCallList = new ViewGroup[MAX_CALLERS_IN_CONFERENCE];
            {
                final int[] viewGroupIdList = {R.id.caller0, R.id.caller1, R.id.caller2,
                        R.id.caller3, R.id.caller4};
                for (int i = 0; i < MAX_CALLERS_IN_CONFERENCE; i++) {
                    mConferenceCallList[i] = (ViewGroup) findViewById(viewGroupIdList[i]);
                }
            }

            mButtonManageConferenceDone =
                    (Button) findViewById(R.id.manage_done);
            mButtonManageConferenceDone.setOnClickListener(this);
        }
    }

    /**
     * Sets the current high-level "mode" of the in-call UI.
     *
     * NOTE: if newMode is CALL_ENDED, the caller is responsible for
     * posting a delayed DELAYED_CLEANUP_AFTER_DISCONNECT message, to make
     * sure the "call ended" state goes away after a couple of seconds.
     */
    private void setInCallScreenMode(InCallScreenMode newMode) {
        if (VDBG) log("setInCallScreenMode: " + newMode);
        mInCallScreenMode = newMode;
        switch (mInCallScreenMode) {
            case MANAGE_CONFERENCE:
                if (!PhoneUtils.isConferenceCall(mForegroundCall)) {
                    Log.w(LOG_TAG, "MANAGE_CONFERENCE: no active conference call!");
                    // Hide the Manage Conference panel, return to NORMAL mode.
                    setInCallScreenMode(InCallScreenMode.NORMAL);
                    return;
                }
                List<Connection> connections = mForegroundCall.getConnections();
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

                initManageConferencePanel();  // if necessary
                updateManageConferencePanel(connections);

                // The "Manage conference" UI takes up the full main frame,
                // replacing the inCallPanel and CallCard PopupWindow.
                mManageConferencePanel.setVisibility(View.VISIBLE);

                // start the chronometer.
                long callDuration = mForegroundCall.getEarliestConnection().getDurationMillis();
                mConferenceTime.setBase(SystemClock.elapsedRealtime() - callDuration);
                mConferenceTime.start();

                mInCallPanel.setVisibility(View.GONE);
                mDialer.hideDTMFDisplay(true);

                break;

            case CALL_ENDED:
                // Display the CallCard (in the "Call ended" state)
                // and hide all other UI.

                if (mManageConferencePanel != null) {
                    mManageConferencePanel.setVisibility(View.GONE);
                    // stop the timer if the panel is hidden.
                    mConferenceTime.stop();
                }
                updateMenuButtonHint();  // Hide the Menu button hint

                // Make sure the CallCard (which is a child of mInCallPanel) is visible.
                mInCallPanel.setVisibility(View.VISIBLE);
                mDialer.hideDTMFDisplay(false);

                break;

            case NORMAL:
                mInCallPanel.setVisibility(View.VISIBLE);
                mDialer.hideDTMFDisplay(false);
                if (mManageConferencePanel != null) {
                    mManageConferencePanel.setVisibility(View.GONE);
                    // stop the timer if the panel is hidden.
                    mConferenceTime.stop();
                }
                break;
        }

        // Update the visibility of the DTMF dialer tab on any state
        // change.
        updateDialpadVisibility();
    }

    /**
     * @return true if the "Manage conference" UI is currently visible.
     */
    /* package */ boolean isManageConferenceMode() {
        return (mInCallScreenMode == InCallScreenMode.MANAGE_CONFERENCE);
    }

    /**
     * Updates the "Manage conference" UI based on the specified List of
     * connections.
     *
     * @param connections the List of connections belonging to
     *        the current foreground call; size must be greater than 1
     *        (or it wouldn't be a conference call in the first place.)
     */
    private void updateManageConferencePanel(List<Connection> connections) {
        mNumCallersInConference = connections.size();
        if (VDBG) log("updateManageConferencePanel()... num connections in conference = "
                     + mNumCallersInConference);

        // Can we give the user the option to separate out ("go private with") a single
        // caller from this conference?
        final boolean hasActiveCall = !mForegroundCall.isIdle();
        final boolean hasHoldingCall = !mBackgroundCall.isIdle();
        boolean canSeparate = !(hasActiveCall && hasHoldingCall);

        for (int i = 0; i < MAX_CALLERS_IN_CONFERENCE; i++) {
            if (i < mNumCallersInConference) {
                // Fill in the row in the UI for this caller.
                Connection connection = (Connection) connections.get(i);
                updateManageConferenceRow(i, connection, canSeparate);
            } else {
                // Blank out this row in the UI
                updateManageConferenceRow(i, null, false);
            }
        }
    }

    /**
     * Checks if the "Manage conference" UI needs to be updated.
     * If the state of the current conference call has changed
     * since our previous call to updateManageConferencePanel()),
     * do a fresh update.
     */
    private void updateManageConferencePanelIfNecessary() {
        if (VDBG) log("updateManageConferencePanel: mForegroundCall " + mForegroundCall + "...");

        List<Connection> connections = mForegroundCall.getConnections();
        if (connections == null) {
            if (VDBG) log("==> no connections on foreground call!");
            // Hide the Manage Conference panel, return to NORMAL mode.
            setInCallScreenMode(InCallScreenMode.NORMAL);
            InCallInitStatus status = syncWithPhoneState();
            if (status != InCallInitStatus.SUCCESS) {
                Log.w(LOG_TAG, "- syncWithPhoneState failed! status = " + status);
                // We shouldn't even be in the in-call UI in the first
                // place, so bail out:
                if (DBG) log("updateManageConferencePanelIfNecessary: finishing...");
                finish();
                return;
            }
            return;
        }

        int numConnections = connections.size();
        if (numConnections <= 1) {
            if (VDBG) log("==> foreground call no longer a conference!");
            // Hide the Manage Conference panel, return to NORMAL mode.
            setInCallScreenMode(InCallScreenMode.NORMAL);
            InCallInitStatus status = syncWithPhoneState();
            if (status != InCallInitStatus.SUCCESS) {
                Log.w(LOG_TAG, "- syncWithPhoneState failed! status = " + status);
                // We shouldn't even be in the in-call UI in the first
                // place, so bail out:
                if (DBG) log("updateManageConferencePanelIfNecessary: finishing...");
                finish();
                return;
            }
            return;
        }
        if (numConnections != mNumCallersInConference) {
            if (VDBG) log("==> Conference size has changed; need to rebuild UI!");
            updateManageConferencePanel(connections);
            return;
        }
    }

    /**
     * Updates a single row of the "Manage conference" UI.  (One row in this
     * UI represents a single caller in the conference.)
     *
     * @param i the row to update
     * @param connection the Connection corresponding to this caller.
     *        If null, that means this is an "empty slot" in the conference,
     *        so hide this row in the UI.
     * @param canSeparate if true, show a "Separate" (i.e. "Private") button
     *        on this row in the UI.
     */
    private void updateManageConferenceRow(final int i,
                                           final Connection connection,
                                           boolean canSeparate) {
        if (VDBG) log("updateManageConferenceRow(" + i + ")...  connection = " + connection);

        if (connection != null) {
            // Activate this row of the Manage conference panel:
            mConferenceCallList[i].setVisibility(View.VISIBLE);

            // get the relevant children views
            ImageButton endButton = (ImageButton) mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerDisconnect);
            ImageButton separateButton = (ImageButton) mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerSeparate);
            TextView nameTextView = (TextView) mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerName);
            TextView numberTextView = (TextView) mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerNumber);
            TextView numberTypeTextView = (TextView) mConferenceCallList[i].findViewById(
                    R.id.conferenceCallerNumberType);

            if (VDBG) log("- button: " + endButton + ", nameTextView: " + nameTextView);

            // Hook up this row's buttons.
            View.OnClickListener endThisConnection = new View.OnClickListener() {
                    public void onClick(View v) {
                        endConferenceConnection(i, connection);
                        PhoneApp.getInstance().pokeUserActivity();
                    }
                };
            endButton.setOnClickListener(endThisConnection);
            //
            if (canSeparate) {
                View.OnClickListener separateThisConnection = new View.OnClickListener() {
                        public void onClick(View v) {
                            separateConferenceConnection(i, connection);
                            PhoneApp.getInstance().pokeUserActivity();
                        }
                    };
                separateButton.setOnClickListener(separateThisConnection);
                separateButton.setVisibility(View.VISIBLE);
            } else {
                separateButton.setVisibility(View.INVISIBLE);
            }

            // Name/number for this caller.
            // TODO: need to deal with private or blocked caller id?
            PhoneUtils.CallerInfoToken info = PhoneUtils.startGetCallerInfo(this, connection,
                    this, mConferenceCallList[i]);

            // display the CallerInfo.
            displayCallerInfoForConferenceRow (info.currentInfo, nameTextView,
                    numberTypeTextView, numberTextView);
        } else {
            // Disable this row of the Manage conference panel:
            mConferenceCallList[i].setVisibility(View.GONE);
        }
    }

    /**
     * Implemented for CallerInfoAsyncQuery.OnQueryCompleteListener interface.
     * refreshes the nameTextView when called.
     */
    public void onQueryComplete(int token, Object cookie, CallerInfo ci){
        if (VDBG) log("callerinfo query complete, updating UI.");

        // get the viewgroup (conference call list item) and make it visible
        ViewGroup vg = (ViewGroup) cookie;
        vg.setVisibility(View.VISIBLE);

        // update the list item with this information.
        displayCallerInfoForConferenceRow (ci,
                (TextView) vg.findViewById(R.id.conferenceCallerName),
                (TextView) vg.findViewById(R.id.conferenceCallerNumberType),
                (TextView) vg.findViewById(R.id.conferenceCallerNumber));
    }

    /**
     * Helper function to fill out the Conference Call(er) information
     * for each item in the "Manage Conference Call" list.
     */
    private final void displayCallerInfoForConferenceRow(CallerInfo ci, TextView nameTextView,
            TextView numberTypeTextView, TextView numberTextView) {

        // gather the correct name and number information.
        String callerName = "";
        String callerNumber = "";
        String callerNumberType = "";
        if (ci != null) {
            callerName = ci.name;
            if (TextUtils.isEmpty(callerName)) {
                callerName = ci.phoneNumber;
                if (TextUtils.isEmpty(callerName)) {
                    callerName = getString(R.string.unknown);
                }
            } else {
                callerNumber = ci.phoneNumber;
                callerNumberType = ci.phoneLabel;
            }
        }

        // set the caller name
        nameTextView.setText(callerName);

        // set the caller number in subscript, or make the field disappear.
        if (TextUtils.isEmpty(callerNumber)) {
            numberTextView.setVisibility(View.GONE);
            numberTypeTextView.setVisibility(View.GONE);
        } else {
            numberTextView.setVisibility(View.VISIBLE);
            numberTextView.setText(callerNumber);
            numberTypeTextView.setVisibility(View.VISIBLE);
            numberTypeTextView.setText(callerNumberType);
        }
    }

    /**
     * Ends the specified connection on a conference call.  This method is
     * run (via a closure containing a row index and Connection) when the
     * user clicks the "End" button on a specific row in the Manage
     * conference UI.
     */
    private void endConferenceConnection(int i, Connection connection) {
        if (VDBG) log("===> ENDING conference connection " + i
                      + ": Connection " + connection);
        // The actual work of ending the connection:
        PhoneUtils.hangup(connection);
        // No need to manually update the "Manage conference" UI here;
        // that'll happen automatically very soon (when we get the
        // onDisconnect() callback triggered by this hangup() call.)
    }

    /**
     * Separates out the specified connection on a conference call.  This
     * method is run (via a closure containing a row index and Connection)
     * when the user clicks the "Separate" (i.e. "Private") button on a
     * specific row in the Manage conference UI.
     */
    private void separateConferenceConnection(int i, Connection connection) {
        if (VDBG) log("===> SEPARATING conference connection " + i
                      + ": Connection " + connection);

        PhoneUtils.separateCall(connection);

        // Note that separateCall() automagically makes the
        // newly-separated call into the foreground call (which is the
        // desired UI), so there's no need to do any further
        // call-switching here.
        // There's also no need to manually update (or hide) the "Manage
        // conference" UI; that'll happen on its own in a moment (when we
        // get the phone state change event triggered by the call to
        // separateCall().)
    }

    /**
     * Updates the visibility of the DTMF dialpad and the "sliding drawer"
     * handle, based on the current state of the phone and/or the current
     * InCallScreenMode.
     */
    private void updateDialpadVisibility() {
        //
        // (1) The dialpad itself:
        //
        // If an incoming call is ringing, make sure the dialpad is
        // closed.  (We do this to make sure we're not covering up the
        // "incoming call" UI, and especially to make sure that the "touch
        // lock" overlay won't appear.)
        if (mPhone.getState() == Phone.State.RINGING) {
            mDialer.closeDialer(false);  // don't do the "closing" animation

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
        // (2) The "sliding drawer" handle:
        //
        // This handle should be visible only if it's OK to actually open
        // the dialpad.
        //
        if (mDialerDrawer != null) {
            int visibility = okToShowDialpad() ? View.VISIBLE : View.GONE;
            mDialerDrawer.setVisibility(visibility);
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
        if (VDBG) log("onDialerOpen()...");

        // ANY time the dialpad becomes visible, start the timer that will
        // eventually bring up the "touch lock" overlay.
        resetTouchLockTimer();

        // This counts as explicit "user activity".
        PhoneApp.getInstance().pokeUserActivity();
    }

    /**
     * Called any time the DTMF dialpad is closed.
     * @see DTMFTwelveKeyDialer.onDialerClose()
     */
    /* package */ void onDialerClose() {
        if (VDBG) log("onDialerClose()...");

        // Dismiss the "touch lock" overlay if it was visible.
        // (The overlay is only ever used on top of the dialpad).
        enableTouchLock(false);

        // This counts as explicit "user activity".
        PhoneApp.getInstance().pokeUserActivity();
    }

    /**
     * Get the DTMF dialer display field.
     */
    /* package */ EditText getDialerDisplay() {
        return mDTMFDisplay;
    }

    /**
     * Determines when we can dial DTMF tones.
     */
    private boolean okToDialDTMFTones() {
        final boolean hasRingingCall = !mRingingCall.isIdle();
        final Call.State fgCallState = mForegroundCall.getState();

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
            && (mInCallScreenMode != InCallScreenMode.MANAGE_CONFERENCE);

        if (VDBG) log ("[okToDialDTMFTones] foreground state: " + fgCallState +
                ", ringing state: " + hasRingingCall +
                ", call screen mode: " + mInCallScreenMode +
                ", result: " + canDial);

        return canDial;
    }

    /**
     * @return true if the in-call DTMF dialpad should be available to the
     *      user, given the current state of the phone and the in-call UI.
     *      (This is used to control the visiblity of the dialer's
     *      SlidingDrawer handle, and the enabledness of the "Show
     *      dialpad" menu item.)
     */
    /* package */ boolean okToShowDialpad() {
        // The dialpad is never used in landscape mode.  And even in
        // portrait mode, it's available only when it's OK to dial DTMF
        // tones given the current state of the current call.
        return !ConfigurationHelper.isLandscape() && okToDialDTMFTones();
    }


    /**
     * Initializes the onscreen "answer" UI on devices that need it.
     */
    private void initOnscreenAnswerUi() {
        // This UI is only used on devices with no hard CALL or SEND button.

        // TODO: For now, explicitly enable this for sholes devices.
        // (Note PRODUCT_DEVICE is "sholes" for both sholes and voles builds.)
        boolean allowOnscreenAnswerUi =
                "sholes".equals(SystemProperties.get("ro.product.device"));
        //
        // TODO: But ultimately we should either (a) use some framework API
        // to detect whether the current device has a hard SEND button, or
        // (b) have this depend on a product-specific resource flag in
        // config.xml, like the forthcoming "is_full_touch_ui" boolean.

        if (DBG) log("initOnscreenAnswerUi: device '" + SystemProperties.get("ro.product.device")
                     + "', allowOnscreenAnswerUi = " + allowOnscreenAnswerUi);

        if (allowOnscreenAnswerUi) {
            ViewStub stub = (ViewStub) findViewById(R.id.onscreenAnswerUiStub);
            mOnscreenAnswerUiContainer = stub.inflate();

            mOnscreenAnswerButton = findViewById(R.id.onscreenAnswerButton);
            mOnscreenAnswerButton.setOnTouchListener(this);
        }
    }

    /**
     * Updates the visibility of the onscreen "answer" UI.  On devices
     * with no hardware CALL button, this UI becomes visible while an
     * incoming call is ringing.
     *
     * TODO: This method should eventually be rolled into a more general
     * method to update *all* onscreen UI elements that need to be
     * different on different devices (depending on which hard buttons are
     * present and/or if we don't have to worry about false touches while
     * in-call.)
     */
    private void updateOnscreenAnswerUi() {
        if (mOnscreenAnswerUiContainer != null) {
            if (DBG) log("onscreenAnswerUI: phone state is " + mPhone.getState().toString());
            if (mPhone.getState() == Phone.State.RINGING) {
                // A phone call is ringing *or* call waiting.
                mOnscreenAnswerUiContainer.setVisibility(View.VISIBLE);
            } else {
                mOnscreenAnswerUiContainer.setVisibility(View.GONE);
            }
            if (DBG) log("set onscreen answer UI visibility to " +
                    mOnscreenAnswerUiContainer.getVisibility());
        }
    }


    /**
     * Helper class to manage the (small number of) manual layout and UI
     * changes needed by the in-call UI when switching between landscape
     * and portrait mode.
     *
     * TODO: Ideally, all this information should come directly from
     * resources, with alternate sets of resources for for different
     * configurations (like alternate layouts under res/layout-land
     * or res/layout-finger.)
     *
     * But for now, we don't use any alternate resources.  Instead, the
     * resources under res/layout are hardwired for portrait mode, and we
     * use this class's applyConfigurationToLayout() method to reach into
     * our View hierarchy and manually patch up anything that needs to be
     * different for landscape mode.
     */
    /* package */ static class ConfigurationHelper {
        /** This class is never instantiated. */
        private ConfigurationHelper() {
        }

        // "Configuration constants" set by initConfiguration()
        static int sOrientation = Configuration.ORIENTATION_UNDEFINED;

        static boolean isLandscape() {
            return sOrientation == Configuration.ORIENTATION_LANDSCAPE;
        }

        /**
         * Initializes the "Configuration constants" based on the
         * specified configuration.
         */
        static void initConfiguration(Configuration config) {
            if (VDBG) Log.d(LOG_TAG, "[InCallScreen.ConfigurationHelper] "
                           + "initConfiguration(" + config + ")...");
            sOrientation = config.orientation;
        }

        /**
         * Updates the InCallScreen's View hierarchy, applying any
         * necessary changes given the current configuration.
         */
        static void applyConfigurationToLayout(InCallScreen inCallScreen) {
            if (sOrientation == Configuration.ORIENTATION_UNDEFINED) {
                throw new IllegalStateException("need to call initConfiguration first");
            }

            // find the landscape-only DTMF display field.
            inCallScreen.mDTMFDisplay = (EditText) inCallScreen.findViewById(R.id.dtmfDialerField);

            // Our layout resources describe the *portrait mode* layout of
            // the Phone UI (see the TODO above in the doc comment for
            // the ConfigurationHelper class.)  So if we're in landscape
            // mode now, reach into our View hierarchy and update the
            // (few) layout params that need to be different.
            if (isLandscape()) {
                // Update CallCard-related stuff
                inCallScreen.mCallCard.updateForLandscapeMode();

                // No need to adjust the visiblity for mDTMFDisplay here because
                // we're relying on the resources (layouts in layout-finger vs.
                // layout-land-finger) to manage when mDTMFDisplay is shown.
            }
        }
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
        return ((mPhone.getServiceState().getState() == ServiceState.STATE_EMERGENCY_ONLY) ||
                (mPhone.getServiceState().getState() == ServiceState.STATE_OUT_OF_SERVICE) ||
                (PhoneApp.getInstance().getKeyguardManager().inKeyguardRestrictedInputMode()));
    }

    //
    // In-call menu UI
    //

    /**
     * Override onCreatePanelView(), in order to get complete control
     * over the UI that comes up when the user presses MENU.
     *
     * This callback allows me to return a totally custom View hierarchy
     * (with custom layout and custom "item" views) to be shown instead
     * of a standard android.view.Menu hierarchy.
     *
     * This gets called (with featureId == FEATURE_OPTIONS_PANEL) every
     * time we need to bring up the menu.  (And in cases where we return
     * non-null, that means that the "standard" menu callbacks
     * onCreateOptionsMenu() and onPrepareOptionsMenu() won't get called
     * at all.)
     */
    @Override
    public View onCreatePanelView(int featureId) {
        if (VDBG) log("onCreatePanelView(featureId = " + featureId + ")...");

        // We only want this special behavior for the "options panel"
        // feature (i.e. the standard menu triggered by the MENU button.)
        if (featureId != Window.FEATURE_OPTIONS_PANEL) {
            return null;
        }

        // TODO: May need to revisit the wake state here if this needs to be
        // tweaked.

        // Make sure there are no pending messages to *dismiss* the menu.
        mHandler.removeMessages(DISMISS_MENU);

        if (mInCallMenu == null) {
            if (VDBG) log("onCreatePanelView: creating mInCallMenu (first time)...");
            mInCallMenu = new InCallMenu(this);
            mInCallMenu.initMenu();
        }

        boolean okToShowMenu = mInCallMenu.updateItems(mPhone);
        return okToShowMenu ? mInCallMenu.getView() : null;
    }

    /**
     * Dismisses the menu panel (see onCreatePanelView().)
     *
     * @param dismissImmediate If true, hide the panel immediately.
     *            If false, leave the menu visible onscreen for
     *            a brief interval before dismissing it (so the
     *            user can see the state change resulting from
     *            his original click.)
     */
    /* package */ void dismissMenu(boolean dismissImmediate) {
        if (VDBG) log("dismissMenu(immediate = " + dismissImmediate + ")...");

        if (dismissImmediate) {
            closeOptionsMenu();
        } else {
            mHandler.removeMessages(DISMISS_MENU);
            mHandler.sendEmptyMessageDelayed(DISMISS_MENU, MENU_DISMISS_DELAY);
            // This will result in a dismissMenu(true) call shortly.
        }
    }

    /**
     * Override onPanelClosed() to capture the panel closing event,
     * allowing us to set the poke lock correctly whenever the option
     * menu panel goes away.
     */
    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        if (VDBG) log("onPanelClosed(featureId = " + featureId + ")...");

        // We only want this special behavior for the "options panel"
        // feature (i.e. the standard menu triggered by the MENU button.)
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            // TODO: May need to return to the original wake state here
            // if onCreatePanelView ends up changing the wake state.
        }

        super.onPanelClosed(featureId, menu);
    }

    //
    // Bluetooth helper methods.
    //
    // - BluetoothDevice is the Bluetooth system service
    //   (Context.BLUETOOTH_SERVICE).  If getSystemService() returns null
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
        //    BluetoothDevice bluetoothDevice =
        //            (BluetoothDevice) getSystemService(Context.BLUETOOTH_SERVICE);
        //    if ((bluetoothDevice == null) || !bluetoothDevice.isEnabled()) {
        //        if (DBG) log("  ==> FALSE (BT not enabled)");
        //        return false;
        //    }
        //    if (DBG) log("  - BT enabled!  device name " + bluetoothDevice.getName()
        //                 + ", address " + bluetoothDevice.getAddress());
        //
        // ...since we already have a BluetoothHeadset instance.  We can just
        // call isConnected() on that, and assume it'll be false if BT isn't
        // enabled at all.

        // Check if there's a connected headset, using the BluetoothHeadset API.
        boolean isConnected = false;
        if (mBluetoothHeadset != null) {
            if (VDBG) log("  - headset state = " + mBluetoothHeadset.getState());
            String headsetAddress = mBluetoothHeadset.getHeadsetAddress();
            if (VDBG) log("  - headset address: " + headsetAddress);
            if (headsetAddress != null) {
                isConnected = mBluetoothHeadset.isConnected(headsetAddress);
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
     * Helper method used to control the state of the green LED in the
     * "Bluetooth" menu item.
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
        // on.  This is how we make the green LED in the menu item turn on
        // right away.
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
    /* package */ void updateBluetoothIndication() {
        if (VDBG) log("updateBluetoothIndication()...");
        // No need to look at the current state here; any UI elements that
        // care about the bluetooth state (i.e. the CallCard) get
        // the necessary state directly from PhoneApp.showBluetoothIndication().
        mHandler.removeMessages(BLUETOOTH_STATE_CHANGED);
        mHandler.sendEmptyMessage(BLUETOOTH_STATE_CHANGED);
    }

    private void dumpBluetoothState() {
        log("============== dumpBluetoothState() =============");
        log("= isBluetoothAvailable: " + isBluetoothAvailable());
        log("= isBluetoothAudioConnected: " + isBluetoothAudioConnected());
        log("= isBluetoothAudioConnectedOrPending: " + isBluetoothAudioConnectedOrPending());
        log("= PhoneApp.showBluetoothIndication: "
            + PhoneApp.getInstance().showBluetoothIndication());
        log("=");
        if (mBluetoothHandsfree != null) {
            log("= BluetoothHandsfree.isAudioOn: " + mBluetoothHandsfree.isAudioOn());
            if (mBluetoothHeadset != null) {
                String headsetAddress = mBluetoothHeadset.getHeadsetAddress();
                log("= BluetoothHeadset.getHeadsetAddress: " + headsetAddress);
                if (headsetAddress != null) {
                    log("= BluetoothHeadset.isConnected: "
                        + mBluetoothHeadset.isConnected(headsetAddress));
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
        // work in another thread.  Also, in practice the BT connection
        // takes longer than MENU_DISMISS_DELAY to complete(!) so we need
        // a little trickery here to make the menu item's green LED update
        // instantly.
        // (See isBluetoothAudioConnectedOrPending() above.)
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

    //
    // "Touch lock" UI.
    //
    // When the DTMF dialpad is up, after a certain amount of idle time we
    // display an overlay graphic on top of the dialpad and "lock" the
    // touch UI.  (UI Rationale: We need *some* sort of screen lock, with
    // a fairly short timeout, to avoid false touches from the user's face
    // while in-call.  But we *don't* want to do this by turning off the
    // screen completely, since that's confusing (the user can't tell
    // what's going on) *and* it's fairly cumbersome to have to hit MENU
    // to bring the screen back, rather than using some gesture on the
    // touch screen.)
    //
    // The user can dismiss the touch lock overlay by double-tapping on
    // the central "lock" icon.  Also, the touch lock overlay will go away
    // by itself if the DTMF dialpad is dismissed for any reason, such as
    // the current call getting disconnected (see onDialerClose()).
    //

    /**
     * Initializes the "touch lock" UI widgets.  We do this lazily
     * to avoid slowing down the initial launch of the InCallScreen.
     */
    private void initTouchLock() {
        if (VDBG) log("initTouchLock()...");
        if (mTouchLockOverlay != null) {
            Log.w(LOG_TAG, "initTouchLock: already initialized!");
            return;
        }

        mTouchLockOverlay = (View) findViewById(R.id.touchLockOverlay);
        // Note mTouchLockOverlay's visibility is initially GONE.
        mTouchLockIcon = (View) findViewById(R.id.touchLockIcon);

        // Handle touch events.  (Basically mTouchLockOverlay consumes and
        // discards any touch events it sees, and mTouchLockIcon listens
        // for the "double-tap to unlock" gesture.)
        mTouchLockOverlay.setOnTouchListener(this);
        mTouchLockIcon.setOnTouchListener(this);

        mTouchLockFadeIn = AnimationUtils.loadAnimation(this, R.anim.touch_lock_fade_in);
    }

    private boolean isTouchLocked() {
        return (mTouchLockOverlay != null) && (mTouchLockOverlay.getVisibility() == View.VISIBLE);
    }

    /**
     * Enables or disables the "touch lock" overlay on top of the DTMF dialpad.
     *
     * If enable=true, bring up the overlay immediately using an animated
     * fade-in effect.  (Or do nothing if the overlay isn't appropriate
     * right now, like if the dialpad isn't up, or the speaker is on.)
     *
     * If enable=false, immediately take down the overlay.  (Or do nothing
     * if the overlay isn't actually up right now.)
     *
     * Note that with enable=false this method will *not* automatically
     * start the touch lock timer.  (So when taking down the overlay while
     * the dialer is still up, the caller is also responsible for calling
     * resetTouchLockTimer(), to make sure the overlay will get
     * (re-)enabled later.)
     *
     */
    private void enableTouchLock(boolean enable) {
        if (VDBG) log("enableTouchLock(" + enable + ")...");
        if (enable) {
            // The "touch lock" overlay is only ever used on top of the
            // DTMF dialpad.
            if (!mDialer.isOpened()) {
                if (VDBG) log("enableTouchLock: dialpad isn't up, no need to lock screen.");
                return;
            }

            // Also, the "touch lock" overlay NEVER appears if the speaker is in use.
            if (PhoneUtils.isSpeakerOn(getApplicationContext())) {
                if (VDBG) log("enableTouchLock: speaker is on, no need to lock screen.");
                return;
            }

            // Initialize the UI elements if necessary.
            if (mTouchLockOverlay == null) {
                initTouchLock();
            }

            // First take down the menu if it's up (since it's confusing
            // to see a touchable menu *above* the touch lock overlay.)
            // Note dismissMenu() has no effect if the menu is already closed.
            dismissMenu(true);  // dismissImmediate = true

            // Bring up the touch lock overlay (with an animated fade)
            mTouchLockOverlay.setVisibility(View.VISIBLE);
            mTouchLockOverlay.startAnimation(mTouchLockFadeIn);
        } else {
            // TODO: it might be nice to immediately kill the animation if
            // we're in the middle of fading-in:
            //   if (mTouchLockFadeIn.hasStarted() && !mTouchLockFadeIn.hasEnded()) {
            //      mTouchLockOverlay.clearAnimation();
            //   }
            // but the fade-in is so quick that this probably isn't necessary.

            // Take down the touch lock overlay (immediately)
            if (mTouchLockOverlay != null) mTouchLockOverlay.setVisibility(View.GONE);
        }
    }

    /**
     * Schedule the "touch lock" overlay to begin fading in after a short
     * delay, but only if the DTMF dialpad is currently visible.
     *
     * (This is designed to be triggered on any user activity
     * while the dialpad is up but not locked, and also
     * whenever the user "unlocks" the touch lock overlay.)
     *
     * Calling this method supersedes any previous resetTouchLockTimer()
     * calls (i.e. we first clear any pending TOUCH_LOCK_TIMER messages.)
     */
    private void resetTouchLockTimer() {
        if (VDBG) log("resetTouchLockTimer()...");
        mHandler.removeMessages(TOUCH_LOCK_TIMER);
        if (mDialer.isOpened() && !isTouchLocked()) {
            // The touch lock delay value comes from Gservices; we use
            // the same value that's used for the PowerManager's
            // POKE_LOCK_SHORT_TIMEOUT flag (i.e. the fastest possible
            // screen timeout behavior.)

            // Do a fresh lookup each time, since Gservices values can
            // change on the fly.  (The Settings.Gservices helper class
            // caches these values so this call is usually cheap.)
            int touchLockDelay = Settings.Gservices.getInt(
                    getContentResolver(),
                    Settings.Gservices.SHORT_KEYLIGHT_DELAY_MS,
                    TOUCH_LOCK_DELAY_DEFAULT);
            mHandler.sendEmptyMessageDelayed(TOUCH_LOCK_TIMER, touchLockDelay);
        }
    }

    /**
     * Handles the TOUCH_LOCK_TIMER event.
     * @see resetTouchLockTimer
     */
    private void touchLockTimerExpired() {
        // Ok, it's been long enough since we had any user activity with
        // the DTMF dialpad up.  If the dialpad is still up, start fading
        // in the "touch lock" overlay.
        enableTouchLock(true);
    }

    // View.OnTouchListener implementation
    public boolean onTouch(View v, MotionEvent event) {
        if (VDBG) log ("onTouch(View " + v + ")...");

        // Handle touch events on the "touch lock" overlay.
        if ((v == mTouchLockIcon) || (v == mTouchLockOverlay)) {

            // TODO: move this big hunk of code to a helper function, or
            // even better out to a separate helper class containing all
            // the touch lock overlay code.

            // We only care about these touches while the touch lock UI is
            // visible (including the time during the fade-in animation.)
            if (!isTouchLocked()) {
                // Got an event from the touch lock UI, but we're not locked!
                // (This was probably a touch-UP right after we unlocked.
                // Ignore it.)
                return false;
            }

            // (v == mTouchLockIcon) means the user hit the lock icon in the
            // middle of the screen, and (v == mTouchLockOverlay) is a touch
            // anywhere else on the overlay.

            if (v == mTouchLockIcon) {
                // Direct hit on the "lock" icon.  Handle the double-tap gesture.
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    long now = SystemClock.uptimeMillis();
                    if (VDBG) log("- touch lock icon: handling a DOWN event, t = " + now);

                    // Look for the double-tap gesture:
                    if (now < mTouchLockLastTouchTime + ViewConfiguration.getDoubleTapTimeout()) {
                        if (VDBG) log("==> touch lock icon: DOUBLE-TAP!");
                        // This was the 2nd tap of a double-tap gesture.
                        // Take down the touch lock overlay, but post a
                        // message in the future to bring it back later.
                        enableTouchLock(false);
                        resetTouchLockTimer();
                        // This counts as explicit "user activity".
                        PhoneApp.getInstance().pokeUserActivity();
                    }
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    // Stash away the current time in case this is the first
                    // tap of a double-tap gesture.  (We measure the time from
                    // the first tap's UP to the second tap's DOWN.)
                    mTouchLockLastTouchTime = SystemClock.uptimeMillis();
                }

                // And regardless of what just happened, we *always* consume
                // touch events while the touch lock UI is (or was) visible.
                return true;

            } else {  // (v == mTouchLockOverlay)
                // User touched the "background" area of the touch lock overlay.

                // TODO: If we're in the middle of the fade-in animation,
                // consider making a touch *anywhere* immediately unlock the
                // UI.  This could be risky, though, if the user tries to
                // *double-tap* during the fade-in (in which case the 2nd tap
                // might 't become a false touch on the dialpad!)
                //
                //if (event.getAction() == MotionEvent.ACTION_DOWN) {
                //    if (DBG) log("- touch lock overlay background: handling a DOWN event.");
                //
                //    if (mTouchLockFadeIn.hasStarted() && !mTouchLockFadeIn.hasEnded()) {
                //        // If we're still fading-in, a touch *anywhere* onscreen
                //        // immediately unlocks.
                //        if (DBG) log("==> touch lock: tap during fade-in!");
                //
                //        mTouchLockOverlay.clearAnimation();
                //        enableTouchLock(false);
                //        // ...but post a message in the future to bring it
                //        // back later.
                //        resetTouchLockTimer();
                //    }
                //}

                // And regardless of what just happened, we *always* consume
                // touch events while the touch lock UI is (or was) visible.
                return true;
            }

        // Handle touch events on the onscreen "answer" button.
        } else if (v == mOnscreenAnswerButton) {

            // TODO: this "double-tap detection" code is also duplicated
            // above (for mTouchLockIcon).  Instead, extract it out to a
            // helper class that can listen for double-taps on an
            // arbitrary View, or maybe even a whole new "DoubleTapButton"
            // widget.

            // Look for the double-tap gesture.
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                long now = SystemClock.uptimeMillis();
                if (DBG) log("- onscreen answer button: handling a DOWN event, t = " + now);  // foo -- VDBG

                // Look for the double-tap gesture:
                if (now < mOnscreenAnswerButtonLastTouchTime + ViewConfiguration.getDoubleTapTimeout()) {
                    if (DBG) log("==> onscreen answer button: DOUBLE-TAP!");
                    // This was the 2nd tap of the double-tap gesture: answer the call!

                    final boolean hasRingingCall = !mRingingCall.isIdle();
                    if (hasRingingCall) {
                        final boolean hasActiveCall = !mForegroundCall.isIdle();
                        final boolean hasHoldingCall = !mBackgroundCall.isIdle();
                        if (hasActiveCall && hasHoldingCall) {
                            if (DBG) log("onscreen answer button: ringing (both lines in use) ==> answer!");
                            internalAnswerCallBothLinesInUse();
                        } else {
                            if (DBG) log("onscreen answer button: ringing ==> answer!");
                            internalAnswerCall();  // Automatically holds the current active call,
                                                   // if there is one
                        }
                    } else {
                        // The ringing call presumably stopped just when
                        // the user was double-tapping.
                        if (DBG) log("onscreen answer button: no ringing call (any more); ignoring...");
                    }
                }
                // The onscreen "answer" button will go away as soon as
                // the phone goes from ringing to offhook, since that
                // state change will trigger an updateScreen() call.
                // TODO: consider explicitly starting some fancier
                // animation here, like fading out the "answer" button, or
                // sliding it offscreen...

            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Stash away the current time in case this is the first
                // tap of a double-tap gesture.  (We measure the time from
                // the first tap's UP to the second tap's DOWN.)
                mOnscreenAnswerButtonLastTouchTime = SystemClock.uptimeMillis();
            }

            // And regardless of what just happened, we *always*
            // consume touch events to this button.
            return true;

        } else {
            Log.w(LOG_TAG, "onTouch: event from unexpected View: " + v);
            return false;
        }
    }

    // Any user activity while the dialpad is up, but not locked, should
    // reset the touch lock timer back to the full delay amount.
    @Override
    public void onUserInteraction() {
        if (mDialer.isOpened() && !isTouchLocked()) {
            resetTouchLockTimer();
        }
    }


    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

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
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHeadsetPhone;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UpdateLock;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.provider.Settings.System;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.phone.common.CallLogAsync;
import com.android.phone.OtaUtils.CdmaOtaScreenState;
import com.android.server.sip.SipService;

/**
 * Global state for the telephony subsystem when running in the primary
 * phone process.
 */
public class PhoneGlobals extends ContextWrapper
        implements AccelerometerListener.OrientationListener {
    /* package */ static final String LOG_TAG = "PhoneApp";

    /**
     * Phone app-wide debug level:
     *   0 - no debug logging
     *   1 - normal debug logging if ro.debuggable is set (which is true in
     *       "eng" and "userdebug" builds but not "user" builds)
     *   2 - ultra-verbose debug logging
     *
     * Most individual classes in the phone app have a local DBG constant,
     * typically set to
     *   (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1)
     * or else
     *   (PhoneApp.DBG_LEVEL >= 2)
     * depending on the desired verbosity.
     *
     * ***** DO NOT SUBMIT WITH DBG_LEVEL > 0 *************
     */
    /* package */ static final int DBG_LEVEL = 0;

    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // Message codes; see mHandler below.
    private static final int EVENT_SIM_NETWORK_LOCKED = 3;
    private static final int EVENT_WIRED_HEADSET_PLUG = 7;
    private static final int EVENT_SIM_STATE_CHANGED = 8;
    private static final int EVENT_UPDATE_INCALL_NOTIFICATION = 9;
    private static final int EVENT_DATA_ROAMING_DISCONNECTED = 10;
    private static final int EVENT_DATA_ROAMING_OK = 11;
    private static final int EVENT_UNSOL_CDMA_INFO_RECORD = 12;
    private static final int EVENT_DOCK_STATE_CHANGED = 13;
    private static final int EVENT_TTY_PREFERRED_MODE_CHANGED = 14;
    private static final int EVENT_TTY_MODE_GET = 15;
    private static final int EVENT_TTY_MODE_SET = 16;
    private static final int EVENT_START_SIP_SERVICE = 17;

    // The MMI codes are also used by the InCallScreen.
    public static final int MMI_INITIATE = 51;
    public static final int MMI_COMPLETE = 52;
    public static final int MMI_CANCEL = 53;
    // Don't use message codes larger than 99 here; those are reserved for
    // the individual Activities of the Phone UI.

    /**
     * Allowable values for the wake lock code.
     *   SLEEP means the device can be put to sleep.
     *   PARTIAL means wake the processor, but we display can be kept off.
     *   FULL means wake both the processor and the display.
     */
    public enum WakeState {
        SLEEP,
        PARTIAL,
        FULL
    }

    /**
     * Intent Action used for hanging up the current call from Notification bar. This will
     * choose first ringing call, first active call, or first background call (typically in
     * HOLDING state).
     */
    public static final String ACTION_HANG_UP_ONGOING_CALL =
            "com.android.phone.ACTION_HANG_UP_ONGOING_CALL";

    /**
     * Intent Action used for making a phone call from Notification bar.
     * This is for missed call notifications.
     */
    public static final String ACTION_CALL_BACK_FROM_NOTIFICATION =
            "com.android.phone.ACTION_CALL_BACK_FROM_NOTIFICATION";

    /**
     * Intent Action used for sending a SMS from notification bar.
     * This is for missed call notifications.
     */
    public static final String ACTION_SEND_SMS_FROM_NOTIFICATION =
            "com.android.phone.ACTION_SEND_SMS_FROM_NOTIFICATION";

    private static PhoneGlobals sMe;

    // A few important fields we expose to the rest of the package
    // directly (rather than thru set/get methods) for efficiency.
    Phone phone;
    CallController callController;
    InCallUiState inCallUiState;
    CallerInfoCache callerInfoCache;
    CallNotifier notifier;
    NotificationMgr notificationMgr;
    Ringer ringer;
    IBluetoothHeadsetPhone mBluetoothPhone;
    PhoneInterfaceManager phoneMgr;
    CallManager mCM;
    int mBluetoothHeadsetState = BluetoothProfile.STATE_DISCONNECTED;
    int mBluetoothHeadsetAudioState = BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
    boolean mShowBluetoothIndication = false;
    static int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    static boolean sVoiceCapable = true;

    // Internal PhoneApp Call state tracker
    CdmaPhoneCallState cdmaPhoneCallState;

    // The InCallScreen instance (or null if the InCallScreen hasn't been
    // created yet.)
    private InCallScreen mInCallScreen;

    // The currently-active PUK entry activity and progress dialog.
    // Normally, these are the Emergency Dialer and the subsequent
    // progress dialog.  null if there is are no such objects in
    // the foreground.
    private Activity mPUKEntryActivity;
    private ProgressDialog mPUKEntryProgressDialog;

    private boolean mIsSimPinEnabled;
    private String mCachedSimPin;

    // True if a wired headset is currently plugged in, based on the state
    // from the latest Intent.ACTION_HEADSET_PLUG broadcast we received in
    // mReceiver.onReceive().
    private boolean mIsHeadsetPlugged;

    // True if the keyboard is currently *not* hidden
    // Gets updated whenever there is a Configuration change
    private boolean mIsHardKeyboardOpen;

    // True if we are beginning a call, but the phone state has not changed yet
    private boolean mBeginningCall;

    // Last phone state seen by updatePhoneState()
    private PhoneConstants.State mLastPhoneState = PhoneConstants.State.IDLE;

    private WakeState mWakeState = WakeState.SLEEP;

    private PowerManager mPowerManager;
    private IPowerManager mPowerManagerService;
    private PowerManager.WakeLock mWakeLock;
    private PowerManager.WakeLock mPartialWakeLock;
    private PowerManager.WakeLock mProximityWakeLock;
    private KeyguardManager mKeyguardManager;
    private AccelerometerListener mAccelerometerListener;
    private int mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;

    private UpdateLock mUpdateLock;

    // Broadcast receiver for various intent broadcasts (see onCreate())
    private final BroadcastReceiver mReceiver = new PhoneAppBroadcastReceiver();

    // Broadcast receiver purely for ACTION_MEDIA_BUTTON broadcasts
    private final BroadcastReceiver mMediaButtonReceiver = new MediaButtonBroadcastReceiver();

    /** boolean indicating restoring mute state on InCallScreen.onResume() */
    private boolean mShouldRestoreMuteOnInCallResume;

    /**
     * The singleton OtaUtils instance used for OTASP calls.
     *
     * The OtaUtils instance is created lazily the first time we need to
     * make an OTASP call, regardless of whether it's an interactive or
     * non-interactive OTASP call.
     */
    public OtaUtils otaUtils;

    // Following are the CDMA OTA information Objects used during OTA Call.
    // cdmaOtaProvisionData object store static OTA information that needs
    // to be maintained even during Slider open/close scenarios.
    // cdmaOtaConfigData object stores configuration info to control visiblity
    // of each OTA Screens.
    // cdmaOtaScreenState object store OTA Screen State information.
    public OtaUtils.CdmaOtaProvisionData cdmaOtaProvisionData;
    public OtaUtils.CdmaOtaConfigData cdmaOtaConfigData;
    public OtaUtils.CdmaOtaScreenState cdmaOtaScreenState;
    public OtaUtils.CdmaOtaInCallScreenUiState cdmaOtaInCallScreenUiState;

    // TTY feature enabled on this platform
    private boolean mTtyEnabled;
    // Current TTY operating mode selected by user
    private int mPreferredTtyMode = Phone.TTY_MODE_OFF;

    /**
     * Set the restore mute state flag. Used when we are setting the mute state
     * OUTSIDE of user interaction {@link PhoneUtils#startNewCall(Phone)}
     */
    /*package*/void setRestoreMuteOnInCallResume (boolean mode) {
        mShouldRestoreMuteOnInCallResume = mode;
    }

    /**
     * Get the restore mute state flag.
     * This is used by the InCallScreen {@link InCallScreen#onResume()} to figure
     * out if we need to restore the mute state for the current active call.
     */
    /*package*/boolean getRestoreMuteOnInCallResume () {
        return mShouldRestoreMuteOnInCallResume;
    }

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            PhoneConstants.State phoneState;
            switch (msg.what) {
                // Starts the SIP service. It's a no-op if SIP API is not supported
                // on the deivce.
                // TODO: Having the phone process host the SIP service is only
                // temporary. Will move it to a persistent communication process
                // later.
                case EVENT_START_SIP_SERVICE:
                    SipService.start(getApplicationContext());
                    break;

                // TODO: This event should be handled by the lock screen, just
                // like the "SIM missing" and "Sim locked" cases (bug 1804111).
                case EVENT_SIM_NETWORK_LOCKED:
                    if (getResources().getBoolean(R.bool.ignore_sim_network_locked_events)) {
                        // Some products don't have the concept of a "SIM network lock"
                        Log.i(LOG_TAG, "Ignoring EVENT_SIM_NETWORK_LOCKED event; "
                              + "not showing 'SIM network unlock' PIN entry screen");
                    } else {
                        // Normal case: show the "SIM network unlock" PIN entry screen.
                        // The user won't be able to do anything else until
                        // they enter a valid SIM network PIN.
                        Log.i(LOG_TAG, "show sim depersonal panel");
                        IccNetworkDepersonalizationPanel ndpPanel =
                                new IccNetworkDepersonalizationPanel(PhoneGlobals.getInstance());
                        ndpPanel.show();
                    }
                    break;

                case EVENT_UPDATE_INCALL_NOTIFICATION:
                    // Tell the NotificationMgr to update the "ongoing
                    // call" icon in the status bar, if necessary.
                    // Currently, this is triggered by a bluetooth headset
                    // state change (since the status bar icon needs to
                    // turn blue when bluetooth is active.)
                    if (DBG) Log.d (LOG_TAG, "- updating in-call notification from handler...");
                    notificationMgr.updateInCallNotification();
                    break;

                case EVENT_DATA_ROAMING_DISCONNECTED:
                    notificationMgr.showDataDisconnectedRoaming();
                    break;

                case EVENT_DATA_ROAMING_OK:
                    notificationMgr.hideDataDisconnectedRoaming();
                    break;

                case MMI_COMPLETE:
                    onMMIComplete((AsyncResult) msg.obj);
                    break;

                case MMI_CANCEL:
                    PhoneUtils.cancelMmiCode(phone);
                    break;

                case EVENT_WIRED_HEADSET_PLUG:
                    // Since the presence of a wired headset or bluetooth affects the
                    // speakerphone, update the "speaker" state.  We ONLY want to do
                    // this on the wired headset connect / disconnect events for now
                    // though, so we're only triggering on EVENT_WIRED_HEADSET_PLUG.

                    phoneState = mCM.getState();
                    // Do not change speaker state if phone is not off hook
                    if (phoneState == PhoneConstants.State.OFFHOOK && !isBluetoothHeadsetAudioOn()) {
                        if (!isHeadsetPlugged()) {
                            // if the state is "not connected", restore the speaker state.
                            PhoneUtils.restoreSpeakerMode(getApplicationContext());
                        } else {
                            // if the state is "connected", force the speaker off without
                            // storing the state.
                            PhoneUtils.turnOnSpeaker(getApplicationContext(), false, false);
                        }
                    }
                    // Update the Proximity sensor based on headset state
                    updateProximitySensorMode(phoneState);

                    // Force TTY state update according to new headset state
                    if (mTtyEnabled) {
                        sendMessage(obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
                    }
                    break;

                case EVENT_SIM_STATE_CHANGED:
                    // Marks the event where the SIM goes into ready state.
                    // Right now, this is only used for the PUK-unlocking
                    // process.
                    if (msg.obj.equals(IccCardConstants.INTENT_VALUE_ICC_READY)) {
                        // when the right event is triggered and there
                        // are UI objects in the foreground, we close
                        // them to display the lock panel.
                        if (mPUKEntryActivity != null) {
                            mPUKEntryActivity.finish();
                            mPUKEntryActivity = null;
                        }
                        if (mPUKEntryProgressDialog != null) {
                            mPUKEntryProgressDialog.dismiss();
                            mPUKEntryProgressDialog = null;
                        }
                    }
                    break;

                case EVENT_UNSOL_CDMA_INFO_RECORD:
                    //TODO: handle message here;
                    break;

                case EVENT_DOCK_STATE_CHANGED:
                    // If the phone is docked/undocked during a call, and no wired or BT headset
                    // is connected: turn on/off the speaker accordingly.
                    boolean inDockMode = false;
                    if (mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                        inDockMode = true;
                    }
                    if (VDBG) Log.d(LOG_TAG, "received EVENT_DOCK_STATE_CHANGED. Phone inDock = "
                            + inDockMode);

                    phoneState = mCM.getState();
                    if (phoneState == PhoneConstants.State.OFFHOOK &&
                        !isHeadsetPlugged() && !isBluetoothHeadsetAudioOn()) {
                        PhoneUtils.turnOnSpeaker(getApplicationContext(), inDockMode, true);
                        updateInCallScreen();  // Has no effect if the InCallScreen isn't visible
                    }
                    break;

                case EVENT_TTY_PREFERRED_MODE_CHANGED:
                    // TTY mode is only applied if a headset is connected
                    int ttyMode;
                    if (isHeadsetPlugged()) {
                        ttyMode = mPreferredTtyMode;
                    } else {
                        ttyMode = Phone.TTY_MODE_OFF;
                    }
                    phone.setTTYMode(ttyMode, mHandler.obtainMessage(EVENT_TTY_MODE_SET));
                    break;

                case EVENT_TTY_MODE_GET:
                    handleQueryTTYModeResponse(msg);
                    break;

                case EVENT_TTY_MODE_SET:
                    handleSetTTYModeResponse(msg);
                    break;
            }
        }
    };

    public PhoneGlobals(Context context) {
        super(context);
        sMe = this;
    }

    public void onCreate() {
        if (VDBG) Log.v(LOG_TAG, "onCreate()...");

        ContentResolver resolver = getContentResolver();

        // Cache the "voice capable" flag.
        // This flag currently comes from a resource (which is
        // overrideable on a per-product basis):
        sVoiceCapable =
                getResources().getBoolean(com.android.internal.R.bool.config_voice_capable);
        // ...but this might eventually become a PackageManager "system
        // feature" instead, in which case we'd do something like:
        // sVoiceCapable =
        //   getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_VOICE_CALLS);

        if (phone == null) {
            // Initialize the telephony framework
            PhoneFactory.makeDefaultPhones(this);

            // Get the default phone
            phone = PhoneFactory.getDefaultPhone();

            // Start TelephonyDebugService After the default phone is created.
            Intent intent = new Intent(this, TelephonyDebugService.class);
            startService(intent);

            mCM = CallManager.getInstance();
            mCM.registerPhone(phone);

            // Create the NotificationMgr singleton, which is used to display
            // status bar icons and control other status bar behavior.
            notificationMgr = NotificationMgr.init(this);

            phoneMgr = PhoneInterfaceManager.init(this, phone);

            mHandler.sendEmptyMessage(EVENT_START_SIP_SERVICE);

            int phoneType = phone.getPhoneType();

            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                // Create an instance of CdmaPhoneCallState and initialize it to IDLE
                cdmaPhoneCallState = new CdmaPhoneCallState();
                cdmaPhoneCallState.CdmaPhoneCallStateInit();
            }

            if (BluetoothAdapter.getDefaultAdapter() != null) {
                // Start BluetoothPhoneService even if device is not voice capable.
                // The device can still support VOIP.
                startService(new Intent(this, BluetoothPhoneService.class));
                bindService(new Intent(this, BluetoothPhoneService.class),
                            mBluetoothPhoneConnection, 0);
            } else {
                // Device is not bluetooth capable
                mBluetoothPhone = null;
            }

            ringer = Ringer.init(this);

            // before registering for phone state changes
            mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, LOG_TAG);
            // lock used to keep the processor awake, when we don't care for the display.
            mPartialWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
            // Wake lock used to control proximity sensor behavior.
            if (mPowerManager.isWakeLockLevelSupported(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                mProximityWakeLock = mPowerManager.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOG_TAG);
            }
            if (DBG) Log.d(LOG_TAG, "onCreate: mProximityWakeLock: " + mProximityWakeLock);

            // create mAccelerometerListener only if we are using the proximity sensor
            if (proximitySensorModeEnabled()) {
                mAccelerometerListener = new AccelerometerListener(this, this);
            }

            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            // get a handle to the service so that we can use it later when we
            // want to set the poke lock.
            mPowerManagerService = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));

            // Get UpdateLock to suppress system-update related events (e.g. dialog show-up)
            // during phone calls.
            mUpdateLock = new UpdateLock("phone");

            if (DBG) Log.d(LOG_TAG, "onCreate: mUpdateLock: " + mUpdateLock);

            CallLogger callLogger = new CallLogger(this, new CallLogAsync());

            // Create the CallController singleton, which is the interface
            // to the telephony layer for user-initiated telephony functionality
            // (like making outgoing calls.)
            callController = CallController.init(this, callLogger);
            // ...and also the InCallUiState instance, used by the CallController to
            // keep track of some "persistent state" of the in-call UI.
            inCallUiState = InCallUiState.init(this);

            // Create the CallerInfoCache singleton, which remembers custom ring tone and
            // send-to-voicemail settings.
            //
            // The asynchronous caching will start just after this call.
            callerInfoCache = CallerInfoCache.init(this);

            // Create the CallNotifer singleton, which handles
            // asynchronous events from the telephony layer (like
            // launching the incoming-call UI when an incoming call comes
            // in.)
            notifier = CallNotifier.init(this, phone, ringer, callLogger);

            // register for ICC status
            IccCard sim = phone.getIccCard();
            if (sim != null) {
                if (VDBG) Log.v(LOG_TAG, "register for ICC status");
                sim.registerForNetworkLocked(mHandler, EVENT_SIM_NETWORK_LOCKED, null);
            }

            // register for MMI/USSD
            mCM.registerForMmiComplete(mHandler, MMI_COMPLETE, null);

            // register connection tracking to PhoneUtils
            PhoneUtils.initializeConnectionHandler(mCM);

            // Read platform settings for TTY feature
            mTtyEnabled = getResources().getBoolean(R.bool.tty_enabled);

            // Register for misc other intent broadcasts.
            IntentFilter intentFilter =
                    new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intentFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);
            intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
            intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
            if (mTtyEnabled) {
                intentFilter.addAction(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
            }
            intentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            registerReceiver(mReceiver, intentFilter);

            // Use a separate receiver for ACTION_MEDIA_BUTTON broadcasts,
            // since we need to manually adjust its priority (to make sure
            // we get these intents *before* the media player.)
            IntentFilter mediaButtonIntentFilter =
                    new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            // TODO verify the independent priority doesn't need to be handled thanks to the
            //  private intent handler registration
            // Make sure we're higher priority than the media player's
            // MediaButtonIntentReceiver (which currently has the default
            // priority of zero; see apps/Music/AndroidManifest.xml.)
            mediaButtonIntentFilter.setPriority(1);
            //
            registerReceiver(mMediaButtonReceiver, mediaButtonIntentFilter);
            // register the component so it gets priority for calls
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.registerMediaButtonEventReceiverForCalls(new ComponentName(this.getPackageName(),
                    MediaButtonBroadcastReceiver.class.getName()));

            //set the default values for the preferences in the phone.
            PreferenceManager.setDefaultValues(this, R.xml.network_setting, false);

            PreferenceManager.setDefaultValues(this, R.xml.call_feature_setting, false);

            // Make sure the audio mode (along with some
            // audio-mode-related state of our own) is initialized
            // correctly, given the current state of the phone.
            PhoneUtils.setAudioMode(mCM);
        }

        if (TelephonyCapabilities.supportsOtasp(phone)) {
            cdmaOtaProvisionData = new OtaUtils.CdmaOtaProvisionData();
            cdmaOtaConfigData = new OtaUtils.CdmaOtaConfigData();
            cdmaOtaScreenState = new OtaUtils.CdmaOtaScreenState();
            cdmaOtaInCallScreenUiState = new OtaUtils.CdmaOtaInCallScreenUiState();
        }

        // XXX pre-load the SimProvider so that it's ready
        resolver.getType(Uri.parse("content://icc/adn"));

        // start with the default value to set the mute state.
        mShouldRestoreMuteOnInCallResume = false;

        // TODO: Register for Cdma Information Records
        // phone.registerCdmaInformationRecord(mHandler, EVENT_UNSOL_CDMA_INFO_RECORD, null);

        // Read TTY settings and store it into BP NV.
        // AP owns (i.e. stores) the TTY setting in AP settings database and pushes the setting
        // to BP at power up (BP does not need to make the TTY setting persistent storage).
        // This way, there is a single owner (i.e AP) for the TTY setting in the phone.
        if (mTtyEnabled) {
            mPreferredTtyMode = android.provider.Settings.Secure.getInt(
                    phone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE,
                    Phone.TTY_MODE_OFF);
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
        }
        // Read HAC settings and configure audio hardware
        if (getResources().getBoolean(R.bool.hac_enabled)) {
            int hac = android.provider.Settings.System.getInt(phone.getContext().getContentResolver(),
                                                              android.provider.Settings.System.HEARING_AID,
                                                              0);
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setParameter(CallFeaturesSetting.HAC_KEY, hac != 0 ?
                                      CallFeaturesSetting.HAC_VAL_ON :
                                      CallFeaturesSetting.HAC_VAL_OFF);
        }
   }

    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            mIsHardKeyboardOpen = true;
        } else {
            mIsHardKeyboardOpen = false;
        }

        // Update the Proximity sensor based on keyboard state
        updateProximitySensorMode(mCM.getState());
    }

    /**
     * Returns the singleton instance of the PhoneApp.
     */
    static PhoneGlobals getInstance() {
        if (sMe == null) {
            throw new IllegalStateException("No PhoneGlobals here!");
        }
        return sMe;
    }

    /**
     * Returns the singleton instance of the PhoneApp if running as the
     * primary user, otherwise null.
     */
    static PhoneGlobals getInstanceIfPrimary() {
        return sMe;
    }

    /**
     * Returns the Phone associated with this instance
     */
    static Phone getPhone() {
        return getInstance().phone;
    }

    Ringer getRinger() {
        return ringer;
    }

    IBluetoothHeadsetPhone getBluetoothPhoneService() {
        return mBluetoothPhone;
    }

    boolean isBluetoothHeadsetAudioOn() {
        return (mBluetoothHeadsetAudioState != BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
    }

    /**
     * Returns an Intent that can be used to go to the "Call log"
     * UI (aka CallLogActivity) in the Contacts app.
     *
     * Watch out: there's no guarantee that the system has any activity to
     * handle this intent.  (In particular there may be no "Call log" at
     * all on on non-voice-capable devices.)
     */
    /* package */ static Intent createCallLogIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, null);
        intent.setType("vnd.android.cursor.dir/calls");
        return intent;
    }

    /**
     * Return an Intent that can be used to bring up the in-call screen.
     *
     * This intent can only be used from within the Phone app, since the
     * InCallScreen is not exported from our AndroidManifest.
     */
    /* package */ static Intent createInCallIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.setClassName("com.android.phone", getCallScreenClassName());
        return intent;
    }

    /**
     * Variation of createInCallIntent() that also specifies whether the
     * DTMF dialpad should be initially visible when the InCallScreen
     * comes up.
     */
    /* package */ static Intent createInCallIntent(boolean showDialpad) {
        Intent intent = createInCallIntent();
        intent.putExtra(InCallScreen.SHOW_DIALPAD_EXTRA, showDialpad);
        return intent;
    }

    /**
     * Returns PendingIntent for hanging up ongoing phone call. This will typically be used from
     * Notification context.
     */
    /* package */ static PendingIntent createHangUpOngoingCallPendingIntent(Context context) {
        Intent intent = new Intent(PhoneGlobals.ACTION_HANG_UP_ONGOING_CALL, null,
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    /* package */ static PendingIntent getCallBackPendingIntent(Context context, String number) {
        Intent intent = new Intent(ACTION_CALL_BACK_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_TEL, number, null),
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    /* package */ static PendingIntent getSendSmsFromNotificationPendingIntent(
            Context context, String number) {
        Intent intent = new Intent(ACTION_SEND_SMS_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_SMSTO, number, null),
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    private static String getCallScreenClassName() {
        return InCallScreen.class.getName();
    }

    /**
     * Starts the InCallScreen Activity.
     */
    /* package */ void displayCallScreen() {
        if (VDBG) Log.d(LOG_TAG, "displayCallScreen()...");

        // On non-voice-capable devices we shouldn't ever be trying to
        // bring up the InCallScreen in the first place.
        if (!sVoiceCapable) {
            Log.w(LOG_TAG, "displayCallScreen() not allowed: non-voice-capable device",
                  new Throwable("stack dump"));  // Include a stack trace since this warning
                                                 // indicates a bug in our caller
            return;
        }

        try {
            startActivity(createInCallIntent());
        } catch (ActivityNotFoundException e) {
            // It's possible that the in-call UI might not exist (like on
            // non-voice-capable devices), so don't crash if someone
            // accidentally tries to bring it up...
            Log.w(LOG_TAG, "displayCallScreen: transition to InCallScreen failed: " + e);
        }
        Profiler.callScreenRequested();
    }

    boolean isSimPinEnabled() {
        return mIsSimPinEnabled;
    }

    boolean authenticateAgainstCachedSimPin(String pin) {
        return (mCachedSimPin != null && mCachedSimPin.equals(pin));
    }

    void setCachedSimPin(String pin) {
        mCachedSimPin = pin;
    }

    void setInCallScreenInstance(InCallScreen inCallScreen) {
        mInCallScreen = inCallScreen;
    }

    /**
     * @return true if the in-call UI is running as the foreground
     * activity.  (In other words, from the perspective of the
     * InCallScreen activity, return true between onResume() and
     * onPause().)
     *
     * Note this method will return false if the screen is currently off,
     * even if the InCallScreen *was* in the foreground just before the
     * screen turned off.  (This is because the foreground activity is
     * always "paused" while the screen is off.)
     */
    boolean isShowingCallScreen() {
        if (mInCallScreen == null) return false;
        return mInCallScreen.isForegroundActivity();
    }

    /**
     * @return true if the in-call UI is running as the foreground activity, or,
     * it went to background due to screen being turned off. This might be useful
     * to determine if the in-call screen went to background because of other
     * activities, or its proximity sensor state or manual power-button press.
     *
     * Here are some examples.
     *
     * - If you want to know if the activity is in foreground or screen is turned off
     *   from the in-call UI (i.e. though it is not "foreground" anymore it will become
     *   so after screen being turned on), check
     *   {@link #isShowingCallScreenForProximity()} is true or not.
     *   {@link #updateProximitySensorMode(com.android.internal.telephony.PhoneConstants.State)} is
     *   doing this.
     *
     * - If you want to know if the activity is not in foreground just because screen
     *   is turned off (not due to other activity's interference), check
     *   {@link #isShowingCallScreen()} is false *and* {@link #isShowingCallScreenForProximity()}
     *   is true. InCallScreen#onDisconnect() is doing this check.
     *
     * @see #isShowingCallScreen()
     *
     * TODO: come up with better naming..
     */
    boolean isShowingCallScreenForProximity() {
        if (mInCallScreen == null) return false;
        return mInCallScreen.isForegroundActivityForProximity();
    }

    /**
     * Dismisses the in-call UI.
     *
     * This also ensures that you won't be able to get back to the in-call
     * UI via the BACK button (since this call removes the InCallScreen
     * from the activity history.)
     * For OTA Call, it call InCallScreen api to handle OTA Call End scenario
     * to display OTA Call End screen.
     */
    /* package */ void dismissCallScreen() {
        if (mInCallScreen != null) {
            if ((TelephonyCapabilities.supportsOtasp(phone)) &&
                    (mInCallScreen.isOtaCallInActiveState()
                    || mInCallScreen.isOtaCallInEndState()
                    || ((cdmaOtaScreenState != null)
                    && (cdmaOtaScreenState.otaScreenState
                            != CdmaOtaScreenState.OtaScreenState.OTA_STATUS_UNDEFINED)))) {
                // TODO: During OTA Call, display should not become dark to
                // allow user to see OTA UI update. Phone app needs to hold
                // a SCREEN_DIM_WAKE_LOCK wake lock during the entire OTA call.
                wakeUpScreen();
                // If InCallScreen is not in foreground we resume it to show the OTA call end screen
                // Fire off the InCallScreen intent
                displayCallScreen();

                mInCallScreen.handleOtaCallEnd();
                return;
            } else {
                mInCallScreen.finish();
            }
        }
    }

    /**
     * Handles OTASP-related events from the telephony layer.
     *
     * While an OTASP call is active, the CallNotifier forwards
     * OTASP-related telephony events to this method.
     */
    void handleOtaspEvent(Message msg) {
        if (DBG) Log.d(LOG_TAG, "handleOtaspEvent(message " + msg + ")...");

        if (otaUtils == null) {
            // We shouldn't be getting OTASP events without ever
            // having started the OTASP call in the first place!
            Log.w(LOG_TAG, "handleOtaEvents: got an event but otaUtils is null! "
                  + "message = " + msg);
            return;
        }

        otaUtils.onOtaProvisionStatusChanged((AsyncResult) msg.obj);
    }

    /**
     * Similarly, handle the disconnect event of an OTASP call
     * by forwarding it to the OtaUtils instance.
     */
    /* package */ void handleOtaspDisconnect() {
        if (DBG) Log.d(LOG_TAG, "handleOtaspDisconnect()...");

        if (otaUtils == null) {
            // We shouldn't be getting OTASP events without ever
            // having started the OTASP call in the first place!
            Log.w(LOG_TAG, "handleOtaspDisconnect: otaUtils is null!");
            return;
        }

        otaUtils.onOtaspDisconnect();
    }

    /**
     * Sets the activity responsible for un-PUK-blocking the device
     * so that we may close it when we receive a positive result.
     * mPUKEntryActivity is also used to indicate to the device that
     * we are trying to un-PUK-lock the phone. In other words, iff
     * it is NOT null, then we are trying to unlock and waiting for
     * the SIM to move to READY state.
     *
     * @param activity is the activity to close when PUK has
     * finished unlocking. Can be set to null to indicate the unlock
     * or SIM READYing process is over.
     */
    void setPukEntryActivity(Activity activity) {
        mPUKEntryActivity = activity;
    }

    Activity getPUKEntryActivity() {
        return mPUKEntryActivity;
    }

    /**
     * Sets the dialog responsible for notifying the user of un-PUK-
     * blocking - SIM READYing progress, so that we may dismiss it
     * when we receive a positive result.
     *
     * @param dialog indicates the progress dialog informing the user
     * of the state of the device.  Dismissed upon completion of
     * READYing process
     */
    void setPukEntryProgressDialog(ProgressDialog dialog) {
        mPUKEntryProgressDialog = dialog;
    }

    ProgressDialog getPUKEntryProgressDialog() {
        return mPUKEntryProgressDialog;
    }

    /**
     * Controls whether or not the screen is allowed to sleep.
     *
     * Once sleep is allowed (WakeState is SLEEP), it will rely on the
     * settings for the poke lock to determine when to timeout and let
     * the device sleep {@link PhoneGlobals#setScreenTimeout}.
     *
     * @param ws tells the device to how to wake.
     */
    /* package */ void requestWakeState(WakeState ws) {
        if (VDBG) Log.d(LOG_TAG, "requestWakeState(" + ws + ")...");
        synchronized (this) {
            if (mWakeState != ws) {
                switch (ws) {
                    case PARTIAL:
                        // acquire the processor wake lock, and release the FULL
                        // lock if it is being held.
                        mPartialWakeLock.acquire();
                        if (mWakeLock.isHeld()) {
                            mWakeLock.release();
                        }
                        break;
                    case FULL:
                        // acquire the full wake lock, and release the PARTIAL
                        // lock if it is being held.
                        mWakeLock.acquire();
                        if (mPartialWakeLock.isHeld()) {
                            mPartialWakeLock.release();
                        }
                        break;
                    case SLEEP:
                    default:
                        // release both the PARTIAL and FULL locks.
                        if (mWakeLock.isHeld()) {
                            mWakeLock.release();
                        }
                        if (mPartialWakeLock.isHeld()) {
                            mPartialWakeLock.release();
                        }
                        break;
                }
                mWakeState = ws;
            }
        }
    }

    /**
     * If we are not currently keeping the screen on, then poke the power
     * manager to wake up the screen for the user activity timeout duration.
     */
    /* package */ void wakeUpScreen() {
        synchronized (this) {
            if (mWakeState == WakeState.SLEEP) {
                if (DBG) Log.d(LOG_TAG, "pulse screen lock");
                mPowerManager.wakeUp(SystemClock.uptimeMillis());
            }
        }
    }

    /**
     * Sets the wake state and screen timeout based on the current state
     * of the phone, and the current state of the in-call UI.
     *
     * This method is a "UI Policy" wrapper around
     * {@link PhoneGlobals#requestWakeState} and {@link PhoneGlobals#setScreenTimeout}.
     *
     * It's safe to call this method regardless of the state of the Phone
     * (e.g. whether or not it's idle), and regardless of the state of the
     * Phone UI (e.g. whether or not the InCallScreen is active.)
     */
    /* package */ void updateWakeState() {
        PhoneConstants.State state = mCM.getState();

        // True if the in-call UI is the foreground activity.
        // (Note this will be false if the screen is currently off,
        // since in that case *no* activity is in the foreground.)
        boolean isShowingCallScreen = isShowingCallScreen();

        // True if the InCallScreen's DTMF dialer is currently opened.
        // (Note this does NOT imply whether or not the InCallScreen
        // itself is visible.)
        boolean isDialerOpened = (mInCallScreen != null) && mInCallScreen.isDialerOpened();

        // True if the speakerphone is in use.  (If so, we *always* use
        // the default timeout.  Since the user is obviously not holding
        // the phone up to his/her face, we don't need to worry about
        // false touches, and thus don't need to turn the screen off so
        // aggressively.)
        // Note that we need to make a fresh call to this method any
        // time the speaker state changes.  (That happens in
        // PhoneUtils.turnOnSpeaker().)
        boolean isSpeakerInUse = (state == PhoneConstants.State.OFFHOOK) && PhoneUtils.isSpeakerOn(this);

        // TODO (bug 1440854): The screen timeout *might* also need to
        // depend on the bluetooth state, but this isn't as clear-cut as
        // the speaker state (since while using BT it's common for the
        // user to put the phone straight into a pocket, in which case the
        // timeout should probably still be short.)

        if (DBG) Log.d(LOG_TAG, "updateWakeState: callscreen " + isShowingCallScreen
                       + ", dialer " + isDialerOpened
                       + ", speaker " + isSpeakerInUse + "...");

        //
        // Decide whether to force the screen on or not.
        //
        // Force the screen to be on if the phone is ringing or dialing,
        // or if we're displaying the "Call ended" UI for a connection in
        // the "disconnected" state.
        // However, if the phone is disconnected while the user is in the
        // middle of selecting a quick response message, we should not force
        // the screen to be on.
        //
        boolean isRinging = (state == PhoneConstants.State.RINGING);
        boolean isDialing = (phone.getForegroundCall().getState() == Call.State.DIALING);
        boolean showingQuickResponseDialog = (mInCallScreen != null) &&
                mInCallScreen.isQuickResponseDialogShowing();
        boolean showingDisconnectedConnection =
                PhoneUtils.hasDisconnectedConnections(phone) && isShowingCallScreen;
        boolean keepScreenOn = isRinging || isDialing ||
                (showingDisconnectedConnection && !showingQuickResponseDialog);
        if (DBG) Log.d(LOG_TAG, "updateWakeState: keepScreenOn = " + keepScreenOn
                       + " (isRinging " + isRinging
                       + ", isDialing " + isDialing
                       + ", showingQuickResponse " + showingQuickResponseDialog
                       + ", showingDisc " + showingDisconnectedConnection + ")");
        // keepScreenOn == true means we'll hold a full wake lock:
        requestWakeState(keepScreenOn ? WakeState.FULL : WakeState.SLEEP);
    }

    /**
     * Manually pokes the PowerManager's userActivity method.  Since we
     * set the {@link WindowManager.LayoutParams#INPUT_FEATURE_DISABLE_USER_ACTIVITY}
     * flag while the InCallScreen is active when there is no proximity sensor,
     * we need to do this for touch events that really do count as user activity
     * (like pressing any onscreen UI elements.)
     */
    /* package */ void pokeUserActivity() {
        if (VDBG) Log.d(LOG_TAG, "pokeUserActivity()...");
        mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
    }

    /**
     * Set when a new outgoing call is beginning, so we can update
     * the proximity sensor state.
     * Cleared when the InCallScreen is no longer in the foreground,
     * in case the call fails without changing the telephony state.
     */
    /* package */ void setBeginningCall(boolean beginning) {
        // Note that we are beginning a new call, for proximity sensor support
        mBeginningCall = beginning;
        // Update the Proximity sensor based on mBeginningCall state
        updateProximitySensorMode(mCM.getState());
    }

    /**
     * Updates the wake lock used to control proximity sensor behavior,
     * based on the current state of the phone.  This method is called
     * from the CallNotifier on any phone state change.
     *
     * On devices that have a proximity sensor, to avoid false touches
     * during a call, we hold a PROXIMITY_SCREEN_OFF_WAKE_LOCK wake lock
     * whenever the phone is off hook.  (When held, that wake lock causes
     * the screen to turn off automatically when the sensor detects an
     * object close to the screen.)
     *
     * This method is a no-op for devices that don't have a proximity
     * sensor.
     *
     * Note this method doesn't care if the InCallScreen is the foreground
     * activity or not.  That's because we want the proximity sensor to be
     * enabled any time the phone is in use, to avoid false cheek events
     * for whatever app you happen to be running.
     *
     * Proximity wake lock will *not* be held if any one of the
     * conditions is true while on a call:
     * 1) If the audio is routed via Bluetooth
     * 2) If a wired headset is connected
     * 3) if the speaker is ON
     * 4) If the slider is open(i.e. the hardkeyboard is *not* hidden)
     *
     * @param state current state of the phone (see {@link Phone#State})
     */
    /* package */ void updateProximitySensorMode(PhoneConstants.State state) {
        if (VDBG) Log.d(LOG_TAG, "updateProximitySensorMode: state = " + state);

        if (proximitySensorModeEnabled()) {
            synchronized (mProximityWakeLock) {
                // turn proximity sensor off and turn screen on immediately if
                // we are using a headset, the keyboard is open, or the device
                // is being held in a horizontal position.
                boolean screenOnImmediately = (isHeadsetPlugged()
                                               || PhoneUtils.isSpeakerOn(this)
                                               || isBluetoothHeadsetAudioOn()
                                               || mIsHardKeyboardOpen);

                // We do not keep the screen off when the user is outside in-call screen and we are
                // horizontal, but we do not force it on when we become horizontal until the
                // proximity sensor goes negative.
                boolean horizontal =
                        (mOrientation == AccelerometerListener.ORIENTATION_HORIZONTAL);
                screenOnImmediately |= !isShowingCallScreenForProximity() && horizontal;

                // We do not keep the screen off when dialpad is visible, we are horizontal, and
                // the in-call screen is being shown.
                // At that moment we're pretty sure users want to use it, instead of letting the
                // proximity sensor turn off the screen by their hands.
                boolean dialpadVisible = false;
                if (mInCallScreen != null) {
                    dialpadVisible =
                            mInCallScreen.getUpdatedInCallControlState().dialpadEnabled
                            && mInCallScreen.getUpdatedInCallControlState().dialpadVisible
                            && isShowingCallScreen();
                }
                screenOnImmediately |= dialpadVisible && horizontal;

                if (((state == PhoneConstants.State.OFFHOOK) || mBeginningCall) && !screenOnImmediately) {
                    // Phone is in use!  Arrange for the screen to turn off
                    // automatically when the sensor detects a close object.
                    if (!mProximityWakeLock.isHeld()) {
                        if (DBG) Log.d(LOG_TAG, "updateProximitySensorMode: acquiring...");
                        mProximityWakeLock.acquire();
                    } else {
                        if (VDBG) Log.d(LOG_TAG, "updateProximitySensorMode: lock already held.");
                    }
                } else {
                    // Phone is either idle, or ringing.  We don't want any
                    // special proximity sensor behavior in either case.
                    if (mProximityWakeLock.isHeld()) {
                        if (DBG) Log.d(LOG_TAG, "updateProximitySensorMode: releasing...");
                        // Wait until user has moved the phone away from his head if we are
                        // releasing due to the phone call ending.
                        // Qtherwise, turn screen on immediately
                        int flags =
                            (screenOnImmediately ? 0 : PowerManager.WAIT_FOR_PROXIMITY_NEGATIVE);
                        mProximityWakeLock.release(flags);
                    } else {
                        if (VDBG) {
                            Log.d(LOG_TAG, "updateProximitySensorMode: lock already released.");
                        }
                    }
                }
            }
        }
    }

    @Override
    public void orientationChanged(int orientation) {
        mOrientation = orientation;
        updateProximitySensorMode(mCM.getState());
    }

    /**
     * Notifies the phone app when the phone state changes.
     *
     * This method will updates various states inside Phone app (e.g. proximity sensor mode,
     * accelerometer listener state, update-lock state, etc.)
     */
    /* package */ void updatePhoneState(PhoneConstants.State state) {
        if (state != mLastPhoneState) {
            mLastPhoneState = state;
            updateProximitySensorMode(state);

            // Try to acquire or release UpdateLock.
            //
            // Watch out: we don't release the lock here when the screen is still in foreground.
            // At that time InCallScreen will release it on onPause().
            if (state != PhoneConstants.State.IDLE) {
                // UpdateLock is a recursive lock, while we may get "acquire" request twice and
                // "release" request once for a single call (RINGING + OFFHOOK and IDLE).
                // We need to manually ensure the lock is just acquired once for each (and this
                // will prevent other possible buggy situations too).
                if (!mUpdateLock.isHeld()) {
                    mUpdateLock.acquire();
                }
            } else {
                if (!isShowingCallScreen()) {
                    if (!mUpdateLock.isHeld()) {
                        mUpdateLock.release();
                    }
                } else {
                    // For this case InCallScreen will take care of the release() call.
                }
            }

            if (mAccelerometerListener != null) {
                // use accelerometer to augment proximity sensor when in call
                mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;
                mAccelerometerListener.enable(state == PhoneConstants.State.OFFHOOK);
            }
            // clear our beginning call flag
            mBeginningCall = false;
            // While we are in call, the in-call screen should dismiss the keyguard.
            // This allows the user to press Home to go directly home without going through
            // an insecure lock screen.
            // But we do not want to do this if there is no active call so we do not
            // bypass the keyguard if the call is not answered or declined.
            if (mInCallScreen != null) {
                mInCallScreen.updateKeyguardPolicy(state == PhoneConstants.State.OFFHOOK);
            }
        }
    }

    /* package */ PhoneConstants.State getPhoneState() {
        return mLastPhoneState;
    }

    /**
     * Returns UpdateLock object.
     */
    /* package */ UpdateLock getUpdateLock() {
        return mUpdateLock;
    }

    /**
     * @return true if this device supports the "proximity sensor
     * auto-lock" feature while in-call (see updateProximitySensorMode()).
     */
    /* package */ boolean proximitySensorModeEnabled() {
        return (mProximityWakeLock != null);
    }

    KeyguardManager getKeyguardManager() {
        return mKeyguardManager;
    }

    private void onMMIComplete(AsyncResult r) {
        if (VDBG) Log.d(LOG_TAG, "onMMIComplete()...");
        MmiCode mmiCode = (MmiCode) r.result;
        PhoneUtils.displayMMIComplete(phone, getInstance(), mmiCode, null, null);
    }

    private void initForNewRadioTechnology() {
        if (DBG) Log.d(LOG_TAG, "initForNewRadioTechnology...");

         if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            // Create an instance of CdmaPhoneCallState and initialize it to IDLE
            cdmaPhoneCallState = new CdmaPhoneCallState();
            cdmaPhoneCallState.CdmaPhoneCallStateInit();
        }
        if (TelephonyCapabilities.supportsOtasp(phone)) {
            //create instances of CDMA OTA data classes
            if (cdmaOtaProvisionData == null) {
                cdmaOtaProvisionData = new OtaUtils.CdmaOtaProvisionData();
            }
            if (cdmaOtaConfigData == null) {
                cdmaOtaConfigData = new OtaUtils.CdmaOtaConfigData();
            }
            if (cdmaOtaScreenState == null) {
                cdmaOtaScreenState = new OtaUtils.CdmaOtaScreenState();
            }
            if (cdmaOtaInCallScreenUiState == null) {
                cdmaOtaInCallScreenUiState = new OtaUtils.CdmaOtaInCallScreenUiState();
            }
        } else {
            //Clean up OTA data in GSM/UMTS. It is valid only for CDMA
            clearOtaState();
        }

        ringer.updateRingerContextAfterRadioTechnologyChange(this.phone);
        notifier.updateCallNotifierRegistrationsAfterRadioTechnologyChange();
        if (mBluetoothPhone != null) {
            try {
                mBluetoothPhone.updateBtHandsfreeAfterRadioTechnologyChange();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, Log.getStackTraceString(new Throwable()));
            }
        }
        if (mInCallScreen != null) {
            mInCallScreen.updateAfterRadioTechnologyChange();
        }

        // Update registration for ICC status after radio technology change
        IccCard sim = phone.getIccCard();
        if (sim != null) {
            if (DBG) Log.d(LOG_TAG, "Update registration for ICC status...");

            //Register all events new to the new active phone
            sim.registerForNetworkLocked(mHandler, EVENT_SIM_NETWORK_LOCKED, null);
        }
    }


    /**
     * @return true if a wired headset is currently plugged in.
     *
     * @see Intent.ACTION_HEADSET_PLUG (which we listen for in mReceiver.onReceive())
     */
    boolean isHeadsetPlugged() {
        return mIsHeadsetPlugged;
    }

    /**
     * @return true if the onscreen UI should currently be showing the
     * special "bluetooth is active" indication in a couple of places (in
     * which UI elements turn blue and/or show the bluetooth logo.)
     *
     * This depends on the BluetoothHeadset state *and* the current
     * telephony state; see shouldShowBluetoothIndication().
     *
     * @see CallCard
     * @see NotificationMgr.updateInCallNotification
     */
    /* package */ boolean showBluetoothIndication() {
        return mShowBluetoothIndication;
    }

    /**
     * Recomputes the mShowBluetoothIndication flag based on the current
     * bluetooth state and current telephony state.
     *
     * This needs to be called any time the bluetooth headset state or the
     * telephony state changes.
     *
     * @param forceUiUpdate if true, force the UI elements that care
     *                      about this flag to update themselves.
     */
    /* package */ void updateBluetoothIndication(boolean forceUiUpdate) {
        mShowBluetoothIndication = shouldShowBluetoothIndication(mBluetoothHeadsetState,
                                                                 mBluetoothHeadsetAudioState,
                                                                 mCM);
        if (forceUiUpdate) {
            // Post Handler messages to the various components that might
            // need to be refreshed based on the new state.
            if (isShowingCallScreen()) mInCallScreen.requestUpdateBluetoothIndication();
            if (DBG) Log.d (LOG_TAG, "- updating in-call notification for BT state change...");
            mHandler.sendEmptyMessage(EVENT_UPDATE_INCALL_NOTIFICATION);
        }

        // Update the Proximity sensor based on Bluetooth audio state
        updateProximitySensorMode(mCM.getState());
    }

    /**
     * UI policy helper function for the couple of places in the UI that
     * have some way of indicating that "bluetooth is in use."
     *
     * @return true if the onscreen UI should indicate that "bluetooth is in use",
     *         based on the specified bluetooth headset state, and the
     *         current state of the phone.
     * @see showBluetoothIndication()
     */
    private static boolean shouldShowBluetoothIndication(int bluetoothState,
                                                         int bluetoothAudioState,
                                                         CallManager cm) {
        // We want the UI to indicate that "bluetooth is in use" in two
        // slightly different cases:
        //
        // (a) The obvious case: if a bluetooth headset is currently in
        //     use for an ongoing call.
        //
        // (b) The not-so-obvious case: if an incoming call is ringing,
        //     and we expect that audio *will* be routed to a bluetooth
        //     headset once the call is answered.

        switch (cm.getState()) {
            case OFFHOOK:
                // This covers normal active calls, and also the case if
                // the foreground call is DIALING or ALERTING.  In this
                // case, bluetooth is considered "active" if a headset
                // is connected *and* audio is being routed to it.
                return ((bluetoothState == BluetoothHeadset.STATE_CONNECTED)
                        && (bluetoothAudioState == BluetoothHeadset.STATE_AUDIO_CONNECTED));

            case RINGING:
                // If an incoming call is ringing, we're *not* yet routing
                // audio to the headset (since there's no in-call audio
                // yet!)  In this case, if a bluetooth headset is
                // connected at all, we assume that it'll become active
                // once the user answers the phone.
                return (bluetoothState == BluetoothHeadset.STATE_CONNECTED);

            default:  // Presumably IDLE
                return false;
        }
    }


    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class PhoneAppBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                boolean enabled = System.getInt(getContentResolver(),
                        System.AIRPLANE_MODE_ON, 0) == 0;
                phone.setRadioPower(enabled);
            } else if (action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                mBluetoothHeadsetState = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE,
                                                          BluetoothHeadset.STATE_DISCONNECTED);
                if (VDBG) Log.d(LOG_TAG, "mReceiver: HEADSET_STATE_CHANGED_ACTION");
                if (VDBG) Log.d(LOG_TAG, "==> new state: " + mBluetoothHeadsetState);
                updateBluetoothIndication(true);  // Also update any visible UI if necessary
            } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                mBluetoothHeadsetAudioState =
                        intent.getIntExtra(BluetoothHeadset.EXTRA_STATE,
                                           BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                if (VDBG) Log.d(LOG_TAG, "mReceiver: HEADSET_AUDIO_STATE_CHANGED_ACTION");
                if (VDBG) Log.d(LOG_TAG, "==> new state: " + mBluetoothHeadsetAudioState);
                updateBluetoothIndication(true);  // Also update any visible UI if necessary
            } else if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                if (VDBG) Log.d(LOG_TAG, "mReceiver: ACTION_ANY_DATA_CONNECTION_STATE_CHANGED");
                if (VDBG) Log.d(LOG_TAG, "- state: " + intent.getStringExtra(PhoneConstants.STATE_KEY));
                if (VDBG) Log.d(LOG_TAG, "- reason: "
                                + intent.getStringExtra(PhoneConstants.STATE_CHANGE_REASON_KEY));

                // The "data disconnected due to roaming" notification is shown
                // if (a) you have the "data roaming" feature turned off, and
                // (b) you just lost data connectivity because you're roaming.
                boolean disconnectedDueToRoaming =
                        !phone.getDataRoamingEnabled()
                        && "DISCONNECTED".equals(intent.getStringExtra(PhoneConstants.STATE_KEY))
                        && Phone.REASON_ROAMING_ON.equals(
                            intent.getStringExtra(PhoneConstants.STATE_CHANGE_REASON_KEY));
                mHandler.sendEmptyMessage(disconnectedDueToRoaming
                                          ? EVENT_DATA_ROAMING_DISCONNECTED
                                          : EVENT_DATA_ROAMING_OK);
            } else if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                if (VDBG) Log.d(LOG_TAG, "mReceiver: ACTION_HEADSET_PLUG");
                if (VDBG) Log.d(LOG_TAG, "    state: " + intent.getIntExtra("state", 0));
                if (VDBG) Log.d(LOG_TAG, "    name: " + intent.getStringExtra("name"));
                mIsHeadsetPlugged = (intent.getIntExtra("state", 0) == 1);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_WIRED_HEADSET_PLUG, 0));
            } else if ((action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) &&
                    (mPUKEntryActivity != null)) {
                // if an attempt to un-PUK-lock the device was made, while we're
                // receiving this state change notification, notify the handler.
                // NOTE: This is ONLY triggered if an attempt to un-PUK-lock has
                // been attempted.
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_SIM_STATE_CHANGED,
                        intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE)));
            } else if (action.equals(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED)) {
                String newPhone = intent.getStringExtra(PhoneConstants.PHONE_NAME_KEY);
                Log.d(LOG_TAG, "Radio technology switched. Now " + newPhone + " is active.");
                initForNewRadioTechnology();
            } else if (action.equals(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED)) {
                handleServiceStateChanged(intent);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                if (TelephonyCapabilities.supportsEcm(phone)) {
                    Log.d(LOG_TAG, "Emergency Callback Mode arrived in PhoneApp.");
                    // Start Emergency Callback Mode service
                    if (intent.getBooleanExtra("phoneinECMState", false)) {
                        context.startService(new Intent(context,
                                EmergencyCallbackModeService.class));
                    }
                } else {
                    // It doesn't make sense to get ACTION_EMERGENCY_CALLBACK_MODE_CHANGED
                    // on a device that doesn't support ECM in the first place.
                    Log.e(LOG_TAG, "Got ACTION_EMERGENCY_CALLBACK_MODE_CHANGED, "
                          + "but ECM isn't supported for phone: " + phone.getPhoneName());
                }
            } else if (action.equals(Intent.ACTION_DOCK_EVENT)) {
                mDockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (VDBG) Log.d(LOG_TAG, "ACTION_DOCK_EVENT -> mDockState = " + mDockState);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_DOCK_STATE_CHANGED, 0));
            } else if (action.equals(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION)) {
                mPreferredTtyMode = intent.getIntExtra(TtyIntent.TTY_PREFFERED_MODE,
                                                       Phone.TTY_MODE_OFF);
                if (VDBG) Log.d(LOG_TAG, "mReceiver: TTY_PREFERRED_MODE_CHANGE_ACTION");
                if (VDBG) Log.d(LOG_TAG, "    mode: " + mPreferredTtyMode);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
            } else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                int ringerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE,
                        AudioManager.RINGER_MODE_NORMAL);
                if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                    notifier.silenceRinger();
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF) ||
                action.equals(Intent.ACTION_SCREEN_ON)) {
                if (VDBG) Log.d(LOG_TAG, "mReceiver: ACTION_SCREEN_OFF" +
                        " / ACTION_SCREEN_ON");
                /*
                 * Disable Acclerometer Listener while in-call and the screen is off.
                 * This is done to ensure that power consumption is kept to a minimum
                 * in such a scenario
                 */
                if(mAccelerometerListener != null) {
                    mAccelerometerListener.enable(mLastPhoneState == PhoneConstants.State.OFFHOOK
                            && action.equals(Intent.ACTION_SCREEN_ON));
                }
            }
        }
    }

    /**
     * Broadcast receiver for the ACTION_MEDIA_BUTTON broadcast intent.
     *
     * This functionality isn't lumped in with the other intents in
     * PhoneAppBroadcastReceiver because we instantiate this as a totally
     * separate BroadcastReceiver instance, since we need to manually
     * adjust its IntentFilter's priority (to make sure we get these
     * intents *before* the media player.)
     */
    private class MediaButtonBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (VDBG) Log.d(LOG_TAG,
                           "MediaButtonBroadcastReceiver.onReceive()...  event = " + event);
            if ((event != null)
                && (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK)) {
                if (VDBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: HEADSETHOOK");
                boolean consumed = PhoneUtils.handleHeadsetHook(phone, event);
                if (VDBG) Log.d(LOG_TAG, "==> handleHeadsetHook(): consumed = " + consumed);
                if (consumed) {
                    // If a headset is attached and the press is consumed, also update
                    // any UI items (such as an InCallScreen mute button) that may need to
                    // be updated if their state changed.
                    updateInCallScreen();  // Has no effect if the InCallScreen isn't visible
                    abortBroadcast();
                }
            } else {
                if (mCM.getState() != PhoneConstants.State.IDLE) {
                    // If the phone is anything other than completely idle,
                    // then we consume and ignore any media key events,
                    // Otherwise it is too easy to accidentally start
                    // playing music while a phone call is in progress.
                    if (VDBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: consumed");
                    abortBroadcast();
                }
            }
        }
    }

    /**
     * Accepts broadcast Intents which will be prepared by {@link NotificationMgr} and thus
     * sent from framework's notification mechanism (which is outside Phone context).
     * This should be visible from outside, but shouldn't be in "exported" state.
     *
     * TODO: If possible merge this into PhoneAppBroadcastReceiver.
     */
    public static class NotificationBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // TODO: use "if (VDBG)" here.
            Log.d(LOG_TAG, "Broadcast from Notification: " + action);

            if (action.equals(ACTION_HANG_UP_ONGOING_CALL)) {
                PhoneUtils.hangup(PhoneGlobals.getInstance().mCM);
            } else if (action.equals(ACTION_CALL_BACK_FROM_NOTIFICATION)) {
                // Collapse the expanded notification and the notification item itself.
                closeSystemDialogs(context);
                clearMissedCallNotification(context);

                Intent callIntent = new Intent(Intent.ACTION_CALL_PRIVILEGED, intent.getData());
                callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                context.startActivity(callIntent);
            } else if (action.equals(ACTION_SEND_SMS_FROM_NOTIFICATION)) {
                // Collapse the expanded notification and the notification item itself.
                closeSystemDialogs(context);
                clearMissedCallNotification(context);

                Intent smsIntent = new Intent(Intent.ACTION_SENDTO, intent.getData());
                smsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(smsIntent);
            } else {
                Log.w(LOG_TAG, "Received hang-up request from notification,"
                        + " but there's no call the system can hang up.");
            }
        }

        private void closeSystemDialogs(Context context) {
            Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void clearMissedCallNotification(Context context) {
            Intent clearIntent = new Intent(context, ClearMissedCallsService.class);
            clearIntent.setAction(ClearMissedCallsService.ACTION_CLEAR_MISSED_CALLS);
            context.startService(clearIntent);
        }
    }

    private void handleServiceStateChanged(Intent intent) {
        /**
         * This used to handle updating EriTextWidgetProvider this routine
         * and and listening for ACTION_SERVICE_STATE_CHANGED intents could
         * be removed. But leaving just in case it might be needed in the near
         * future.
         */

        // If service just returned, start sending out the queued messages
        ServiceState ss = ServiceState.newFromBundle(intent.getExtras());

        if (ss != null) {
            int state = ss.getState();
            notificationMgr.updateNetworkSelection(state);
        }
    }

    public boolean isOtaCallInActiveState() {
        boolean otaCallActive = false;
        if (mInCallScreen != null) {
            otaCallActive = mInCallScreen.isOtaCallInActiveState();
        }
        if (VDBG) Log.d(LOG_TAG, "- isOtaCallInActiveState " + otaCallActive);
        return otaCallActive;
    }

    public boolean isOtaCallInEndState() {
        boolean otaCallEnded = false;
        if (mInCallScreen != null) {
            otaCallEnded = mInCallScreen.isOtaCallInEndState();
        }
        if (VDBG) Log.d(LOG_TAG, "- isOtaCallInEndState " + otaCallEnded);
        return otaCallEnded;
    }

    // it is safe to call clearOtaState() even if the InCallScreen isn't active
    public void clearOtaState() {
        if (DBG) Log.d(LOG_TAG, "- clearOtaState ...");
        if ((mInCallScreen != null)
                && (otaUtils != null)) {
            otaUtils.cleanOtaScreen(true);
            if (DBG) Log.d(LOG_TAG, "  - clearOtaState clears OTA screen");
        }
    }

    // it is safe to call dismissOtaDialogs() even if the InCallScreen isn't active
    public void dismissOtaDialogs() {
        if (DBG) Log.d(LOG_TAG, "- dismissOtaDialogs ...");
        if ((mInCallScreen != null)
                && (otaUtils != null)) {
            otaUtils.dismissAllOtaDialogs();
            if (DBG) Log.d(LOG_TAG, "  - dismissOtaDialogs clears OTA dialogs");
        }
    }

    // it is safe to call clearInCallScreenMode() even if the InCallScreen isn't active
    public void clearInCallScreenMode() {
        if (DBG) Log.d(LOG_TAG, "- clearInCallScreenMode ...");
        if (mInCallScreen != null) {
            mInCallScreen.resetInCallScreenMode();
        }
    }

    /**
     * Force the in-call UI to refresh itself, if it's currently visible.
     *
     * This method can be used any time there's a state change anywhere in
     * the phone app that needs to be reflected in the onscreen UI.
     *
     * Note that it's *not* necessary to manually refresh the in-call UI
     * (via this method) for regular telephony state changes like
     * DIALING -> ALERTING -> ACTIVE, since the InCallScreen already
     * listens for those state changes itself.
     *
     * This method does *not* force the in-call UI to come up if it's not
     * already visible.  To do that, use displayCallScreen().
     */
    /* package */ void updateInCallScreen() {
        if (DBG) Log.d(LOG_TAG, "- updateInCallScreen()...");
        if (mInCallScreen != null) {
            // Post an updateScreen() request.  Note that the
            // updateScreen() call will end up being a no-op if the
            // InCallScreen isn't the foreground activity.
            mInCallScreen.requestUpdateScreen();
        }
    }

    private void handleQueryTTYModeResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        if (ar.exception != null) {
            if (DBG) Log.d(LOG_TAG, "handleQueryTTYModeResponse: Error getting TTY state.");
        } else {
            if (DBG) Log.d(LOG_TAG,
                           "handleQueryTTYModeResponse: TTY enable state successfully queried.");

            int ttymode = ((int[]) ar.result)[0];
            if (DBG) Log.d(LOG_TAG, "handleQueryTTYModeResponse:ttymode=" + ttymode);

            Intent ttyModeChanged = new Intent(TtyIntent.TTY_ENABLED_CHANGE_ACTION);
            ttyModeChanged.putExtra("ttyEnabled", ttymode != Phone.TTY_MODE_OFF);
            sendBroadcastAsUser(ttyModeChanged, UserHandle.ALL);

            String audioTtyMode;
            switch (ttymode) {
            case Phone.TTY_MODE_FULL:
                audioTtyMode = "tty_full";
                break;
            case Phone.TTY_MODE_VCO:
                audioTtyMode = "tty_vco";
                break;
            case Phone.TTY_MODE_HCO:
                audioTtyMode = "tty_hco";
                break;
            case Phone.TTY_MODE_OFF:
            default:
                audioTtyMode = "tty_off";
                break;
            }
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setParameters("tty_mode="+audioTtyMode);
        }
    }

    private void handleSetTTYModeResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception != null) {
            if (DBG) Log.d (LOG_TAG,
                    "handleSetTTYModeResponse: Error setting TTY mode, ar.exception"
                    + ar.exception);
        }
        phone.queryTTYMode(mHandler.obtainMessage(EVENT_TTY_MODE_GET));
    }

    /**
     * "Call origin" may be used by Contacts app to specify where the phone call comes from.
     * Currently, the only permitted value for this extra is {@link #ALLOWED_EXTRA_CALL_ORIGIN}.
     * Any other value will be ignored, to make sure that malicious apps can't trick the in-call
     * UI into launching some random other app after a call ends.
     *
     * TODO: make this more generic. Note that we should let the "origin" specify its package
     * while we are now assuming it is "com.android.contacts"
     */
    public static final String EXTRA_CALL_ORIGIN = "com.android.phone.CALL_ORIGIN";
    private static final String DEFAULT_CALL_ORIGIN_PACKAGE = "com.android.dialer";
    private static final String ALLOWED_EXTRA_CALL_ORIGIN =
            "com.android.dialer.DialtactsActivity";
    /**
     * Used to determine if the preserved call origin is fresh enough.
     */
    private static final long CALL_ORIGIN_EXPIRATION_MILLIS = 30 * 1000;

    public void setLatestActiveCallOrigin(String callOrigin) {
        inCallUiState.latestActiveCallOrigin = callOrigin;
        if (callOrigin != null) {
            inCallUiState.latestActiveCallOriginTimeStamp = SystemClock.elapsedRealtime();
        } else {
            inCallUiState.latestActiveCallOriginTimeStamp = 0;
        }
    }

    /**
     * Reset call origin depending on its timestamp.
     *
     * See if the current call origin preserved by the app is fresh enough or not. If it is,
     * previous call origin will be used as is. If not, call origin will be reset.
     *
     * This will be effective especially for 3rd party apps which want to bypass phone calls with
     * their own telephone lines. In that case Phone app may finish the phone call once and make
     * another for the external apps, which will drop call origin information in Intent.
     * Even in that case we are sure the second phone call should be initiated just after the first
     * phone call, so here we restore it from the previous information iff the second call is done
     * fairly soon.
     */
    public void resetLatestActiveCallOrigin() {
        final long callOriginTimestamp = inCallUiState.latestActiveCallOriginTimeStamp;
        final long currentTimestamp = SystemClock.elapsedRealtime();
        if (VDBG) {
            Log.d(LOG_TAG, "currentTimeMillis: " + currentTimestamp
                    + ", saved timestamp for call origin: " + callOriginTimestamp);
        }
        if (inCallUiState.latestActiveCallOriginTimeStamp > 0
                && (currentTimestamp - callOriginTimestamp < CALL_ORIGIN_EXPIRATION_MILLIS)) {
            if (VDBG) {
                Log.d(LOG_TAG, "Resume previous call origin (" +
                        inCallUiState.latestActiveCallOrigin + ")");
            }
            // Do nothing toward call origin itself but update the timestamp just in case.
            inCallUiState.latestActiveCallOriginTimeStamp = currentTimestamp;
        } else {
            if (VDBG) Log.d(LOG_TAG, "Drop previous call origin and set the current one to null");
            setLatestActiveCallOrigin(null);
        }
    }

    /**
     * @return Intent which will be used when in-call UI is shown and the phone call is hang up.
     * By default CallLog screen will be introduced, but the destination may change depending on
     * its latest call origin state.
     */
    public Intent createPhoneEndIntentUsingCallOrigin() {
        if (TextUtils.equals(inCallUiState.latestActiveCallOrigin, ALLOWED_EXTRA_CALL_ORIGIN)) {
            if (VDBG) Log.d(LOG_TAG, "Valid latestActiveCallOrigin("
                    + inCallUiState.latestActiveCallOrigin + ") was found. "
                    + "Go back to the previous screen.");
            // Right now we just launch the Activity which launched in-call UI. Note that we're
            // assuming the origin is from "com.android.dialer", which may be incorrect in the
            // future.
            final Intent intent = new Intent();
            intent.setClassName(DEFAULT_CALL_ORIGIN_PACKAGE, inCallUiState.latestActiveCallOrigin);
            return intent;
        } else {
            if (VDBG) Log.d(LOG_TAG, "Current latestActiveCallOrigin ("
                    + inCallUiState.latestActiveCallOrigin + ") is not valid. "
                    + "Just use CallLog as a default destination.");
            return PhoneGlobals.createCallLogIntent();
        }
    }

    /** Service connection */
    private final ServiceConnection mBluetoothPhoneConnection = new ServiceConnection() {

        /** Handle the task of binding the local object to the service */
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i(LOG_TAG, "Headset phone created, binding local service.");
            mBluetoothPhone = IBluetoothHeadsetPhone.Stub.asInterface(service);
        }

        /** Handle the task of cleaning up the local binding */
        public void onServiceDisconnected(ComponentName className) {
            Log.i(LOG_TAG, "Headset phone disconnected, cleaning local binding.");
            mBluetoothPhone = null;
        }
    };
}

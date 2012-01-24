/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
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

import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings.System;
import android.telephony.ServiceState;
import android.util.Log;
import android.view.KeyEvent;

import android.telephony.TelephonyManager;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.MSimPhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.phone.OtaUtils.CdmaOtaScreenState;

import java.util.ArrayList;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * Top-level Application class for the Phone app.
 */
public class MSimPhoneApp extends PhoneApp {
    /* package */ static final String LOG_TAG = "MSimPhoneApp";

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
     */
    /* package */ static final int DBG_LEVEL = 1;

    //TODO DSDS,restore the logging levels
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneApp.DBG_LEVEL >= 2);

    // Message codes; see mHandler below.
    private static final int EVENT_PERSO_LOCKED = 3;
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

    // Broadcast receiver for various intent broadcasts (see onCreate())
    private BroadcastReceiver mReceiver = new PhoneAppBroadcastReceiver();

    // Broadcast receiver purely for ACTION_MEDIA_BUTTON broadcasts
    private BroadcastReceiver mMediaButtonReceiver = new MediaButtonBroadcastReceiver();

    /* Array of SinglePhone Objects to store each phoneproxy and associated objects */
    private static SinglePhone[] mSinglePhones;

    private int mDefaultSubscription = 0;

    MSimPhoneInterfaceManager phoneMgrMSim;

    MSimPhoneApp(Context context) {
        Log.d(LOG_TAG,"MSPhoneApp creation");
        sMe = this;
        mContext = context;
        Log.d(LOG_TAG,"MSPhoneApp creation"+this);
    }

    Context getContext() {
        return mContext;
    }

    @Override
    public Context getApplicationContext() {
        return mContext;
    }

    public void onCreate() {
        if (VDBG) Log.v(LOG_TAG, "onCreate()...");
        Log.d(LOG_TAG, "MSimPhoneApp:"+this);

        ContentResolver resolver = mContext.getContentResolver();

        // Cache the "voice capable" flag.
        // This flag currently comes from a resource (which is
        // overrideable on a per-product basis):
        sVoiceCapable =
                mContext.getResources().getBoolean(com.android.internal.R.bool.config_voice_capable);
        // ...but this might eventually become a PackageManager "system
        // feature" instead, in which case we'd do something like:
        // sVoiceCapable =
        //   getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_VOICE_CALLS);

        if (phone == null) {
            Log.d(LOG_TAG, " DSDS PhoneApp:");
            // Initialize the telephony framework
            MSimPhoneFactory.makeMultiSimDefaultPhones(mContext);

            // Get the default phone
            phone = MSimPhoneFactory.getDefaultPhone();

            mCM = CallManager.getInstance();

            int numPhones = TelephonyManager.getDefault().getPhoneCount();
            // Create SinglePhone which hold phone proxy and its corresponding memebers.
            mSinglePhones = new SinglePhone[numPhones];
            for(int i = 0; i < numPhones; i++) {
                mSinglePhones [i] = new SinglePhone(i);
                mCM.registerPhone(mSinglePhones[i].mPhone);
            }

            // Get the default subscription from the system property
            mDefaultSubscription = getDefaultSubscription();

            // Set Default PhoneApp variables
            setDefaultPhone(mDefaultSubscription);
            mCM.registerPhone(phone);

            // Create the NotificationMgr singleton, which is used to display
            // status bar icons and control other status bar behavior.
            notificationMgr = NotificationMgr.init(this);

            phoneMgr = PhoneInterfaceManager.init(this, phone);
            phoneMgrMSim = MSimPhoneInterfaceManager.init(this, phone);

            mHandler.sendEmptyMessage(EVENT_START_SIP_SERVICE);

            int phoneType = phone.getPhoneType();

            if (phoneType == Phone.PHONE_TYPE_CDMA) {
                // Create an instance of CdmaPhoneCallState and initialize it to IDLE
                cdmaPhoneCallState = new CdmaPhoneCallState();
                cdmaPhoneCallState.CdmaPhoneCallStateInit();
            }

            if (BluetoothAdapter.getDefaultAdapter() != null) {
                // Start BluetoothHandsree even if device is not voice capable.
                // The device can still support VOIP.
                mBtHandsfree = BluetoothHandsfree.init(mContext, mCM);
                mContext.startService(new Intent(mContext, BluetoothHeadsetService.class));
            } else {
                // Device is not bluetooth capable
                mBtHandsfree = null;
            }

            ringer = Ringer.init(mContext);

            mReceiver = new MSimPhoneAppBroadcastReceiver();
            mMediaButtonReceiver = new MSimMediaButtonBroadcastReceiver();

            // before registering for phone state changes
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    LOG_TAG);
            // lock used to keep the processor awake, when we don't care for the display.
            mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
            // Wake lock used to control proximity sensor behavior.
            if ((pm.getSupportedWakeLockFlags()
                 & PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) != 0x0) {
                mProximityWakeLock =
                        pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOG_TAG);
            }
            if (DBG) Log.d(LOG_TAG, "onCreate: mProximityWakeLock: " + mProximityWakeLock);

            // create mAccelerometerListener only if we are using the proximity sensor
            if (proximitySensorModeEnabled()) {
                mAccelerometerListener = new AccelerometerListener(mContext, this);
            }

            mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);

            // get a handle to the service so that we can use it later when we
            // want to set the poke lock.
            mPowerManagerService = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));

            // Create the CallController singleton, which is the interface
            // to the telephony layer for user-initiated telephony functionality
            // (like making outgoing calls.)
            callController = CallController.init(this);
            // ...and also the InCallUiState instance, used by the CallController to
            // keep track of some "persistent state" of the in-call UI.
            inCallUiState = InCallUiState.init(this.mContext);

            // Create the CallNotifer singleton, which handles
            // asynchronous events from the telephony layer (like
            // launching the incoming-call UI when an incoming call comes
            // in.)
            notifier = CallNotifier.init(this, phone, ringer, mBtHandsfree, new CallLogAsync());

            // register for ICC status
            IccCard sim = phone.getIccCard();
            if (sim != null) {
                if (VDBG) Log.v(LOG_TAG, "register for ICC status");
                sim.registerForPersoLocked(mHandler, EVENT_PERSO_LOCKED, null);
            }

            // register for MMI/USSD
            mCM.registerForMmiComplete(mHandler, MMI_COMPLETE, null);

            // register connection tracking to PhoneUtils
            PhoneUtils.initializeConnectionHandler(mCM);

            // Read platform settings for TTY feature
            mTtyEnabled = mContext.getResources().getBoolean(R.bool.tty_enabled);

            // Register for misc other intent broadcasts.
            IntentFilter intentFilter =
                    new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intentFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);
            intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
            intentFilter.addAction(Intent.ACTION_BATTERY_LOW);
            intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
            if (mTtyEnabled) {
                intentFilter.addAction(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
            }
            intentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mReceiver, intentFilter);

            // Use a separate receiver for ACTION_MEDIA_BUTTON broadcasts,
            // since we need to manually adjust its priority (to make sure
            // we get these intents *before* the media player.)
            IntentFilter mediaButtonIntentFilter =
                    new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            //
            // Make sure we're higher priority than the media player's
            // MediaButtonIntentReceiver (which currently has the default
            // priority of zero; see apps/Music/AndroidManifest.xml.)
            mediaButtonIntentFilter.setPriority(1);
            //
            mContext.registerReceiver(mMediaButtonReceiver, mediaButtonIntentFilter);

            //set the default values for the preferences in the phone.
            PreferenceManager.setDefaultValues(mContext, R.xml.network_setting, false);

            PreferenceManager.setDefaultValues(mContext, R.xml.call_feature_setting, false);

            // Make sure the audio mode (along with some
            // audio-mode-related state of our own) is initialized
            // correctly, given the current state of the phone.
            PhoneUtils.setAudioMode(mCM);
        }

        if (TelephonyCapabilities.supportsOtasp(phone)) {
            for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
                updatePhoneAppCdmaVariables(i) ;
            }
        }

        // XXX pre-load the SimProvider so that it's ready
        resolver.getType(Uri.parse("content://iccmsim/adn"));

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
        }
        // Read HAC settings and configure audio hardware
        if (mContext.getResources().getBoolean(R.bool.hac_enabled)) {
            int hac = android.provider.Settings.System.getInt(
                    phone.getContext().getContentResolver(),
                    android.provider.Settings.System.HEARING_AID, 0);
            AudioManager audioManager = (AudioManager) mContext
                    .getSystemService(Context.AUDIO_SERVICE);
            audioManager.setParameter(CallFeaturesSetting.HAC_KEY, hac != 0 ?
                                      CallFeaturesSetting.HAC_VAL_ON :
                                      CallFeaturesSetting.HAC_VAL_OFF);
        }

    }

    /**
     * Returns an Intent that can be used to go to the "Call log"
     * UI (aka CallLogActivity) in the Contacts app.
     *
     * Watch out: there's no guarantee that the system has any activity to
     * handle this intent.  (In particular there may be no "Call log" at
     * all on on non-voice-capable devices.)
     */
    @Override
    /* package */ Intent createCallLogIntent(int subscription) {
        Intent  intent = new Intent(Intent.ACTION_VIEW, null);
        intent.putExtra(SUBSCRIPTION_KEY, subscription);
        intent.setType("vnd.android.cursor.dir/calls");
        return intent;
    }
    /**
     * Return an Intent that can be used to bring up the in-call screen.
     *
     * This intent can only be used from within the Phone app, since the
     * InCallScreen is not exported from our AndroidManifest.
     */
    @Override
    /* package */ Intent createInCallIntent(int subscription) {
        Log.d(LOG_TAG, "createInCallIntent subscription:");
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.putExtra(SUBSCRIPTION_KEY, subscription);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.setClassName("com.android.phone", getCallScreenClassName());
        return intent;
    }

    @Override
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
            mContext.startActivity(createInCallIntent(mCM.getPhoneInCall().getSubscription()));
        } catch (ActivityNotFoundException e) {
            // It's possible that the in-call UI might not exist (like on
            // non-voice-capable devices), so don't crash if someone
            // accidentally tries to bring it up...
            Log.w(LOG_TAG, "displayCallScreen: transition to InCallScreen failed: " + e);
        }
        Profiler.callScreenRequested();
    }

    boolean isSimPinEnabled(int subscription) {
        SinglePhone singlePhone = getSinglePhone(subscription);
        return singlePhone.mIsSimPinEnabled;
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
    /* package */
    @Override
    void dismissCallScreen(Phone phone) {
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

    @Override
    /* package */ Phone.State getPhoneState(int subscription) {
        return getSinglePhone(subscription).mLastPhoneState;
    }

    @Override
    public void onMMIComplete(AsyncResult r) {
        if (VDBG) Log.d(LOG_TAG, "onMMIComplete()...");
        MmiCode mmiCode = (MmiCode) r.result;
        Phone localPhone = (Phone) mmiCode.getPhone();
        PhoneUtils.displayMMIComplete(localPhone, getInstance().mContext, mmiCode, null, null);
    }

    void initForNewRadioTechnology(int subscription) {
        if (DBG) Log.d(LOG_TAG, "initForNewRadioTechnology...");
        SinglePhone singlePhone = getSinglePhone(subscription);

        Phone phone = singlePhone.mPhone;

        if (TelephonyCapabilities.supportsOtasp(phone)) {
           // Create an instance of CdmaPhoneCallState and initialize it to IDLE
           singlePhone.initializeCdmaVariables();
           updatePhoneAppCdmaVariables(subscription);
           clearOtaState();
        }

        ringer.updateRingerContextAfterRadioTechnologyChange(this.phone);
        notifier.updateCallNotifierRegistrationsAfterRadioTechnologyChange();
        if (mBtHandsfree != null) {
            mBtHandsfree.updateBtHandsfreeAfterRadioTechnologyChange();
        }
        if (mInCallScreen != null) {
            mInCallScreen.updateAfterRadioTechnologyChange();
        }
    }

    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class MSimPhoneAppBroadcastReceiver extends PhoneApp.PhoneAppBroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(LOG_TAG,"Action intent recieved:"+action);
            //gets the subscription information ( "0" or "1")
            int subscription = intent.getIntExtra(SUBSCRIPTION_KEY, getDefaultSubscription());
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                // When airplane mode is selected/deselected from settings
                // AirplaneModeEnabler sets the value of extra "state" to
                // true if airplane mode is enabled and false if it is
                // disabled and broadcasts the intent. setRadioPower uses
                // true if airplane mode is disabled and false if enabled.
                boolean enabled = intent.getBooleanExtra("state",false);
                for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
                    getPhone(i).setRadioPower(!enabled);
                }
            } else if (action.equals(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED)) {
                String newPhone = intent.getStringExtra(Phone.PHONE_NAME_KEY);
                Log.d(LOG_TAG, "Radio technology switched. Now " + newPhone + " is active.");
                initForNewRadioTechnology(subscription);
            } else if (action.equals(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED)) {
                Phone phone = getPhone(subscription);
                handleServiceStateChanged(intent, phone);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                Phone phone = getPhone(subscription);
                if (TelephonyCapabilities.supportsEcm(phone)) {
                    Log.d(LOG_TAG, "Emergency Callback Mode arrived in PhoneApp"
                            + " on Sub =" + subscription);
                    // Start Emergency Callback Mode service
                    if (intent.getBooleanExtra("phoneinECMState", false)) {
                        Intent ecbmIntent = new Intent(context, EmergencyCallbackModeService.class);
                        ecbmIntent.putExtra(SUBSCRIPTION_KEY, subscription);
                        context.startService(ecbmIntent);
                    }
                } else {
                    // It doesn't make sense to get ACTION_EMERGENCY_CALLBACK_MODE_CHANGED
                    // on a device that doesn't support ECM in the first place.
                    Log.e(LOG_TAG, "Got ACTION_EMERGENCY_CALLBACK_MODE_CHANGED, "
                          + "but ECM isn't supported for phone: " + phone.getPhoneName());
                }
            } else if (action.equals(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED)) {
                Log.d(LOG_TAG, "Default subscription changed, subscription: " + subscription);
                mDefaultSubscription = subscription;
                setDefaultPhone(subscription);
                phoneMgr.setPhone(phone);
            } else {
                super.onReceive(context, intent);
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
    private class MSimMediaButtonBroadcastReceiver extends PhoneApp.MediaButtonBroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            super.onReceive(context, intent);
        }
    }

    // updates cdma variables of PhoneApp
    private void updatePhoneAppCdmaVariables(int subscription) {
        Log.v(LOG_TAG,"updatePhoneAppCdmaVariables" + subscription);
        SinglePhone singlePhone = getSinglePhone(subscription);

        if ((singlePhone != null) &&(singlePhone.mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA)) {
            cdmaPhoneCallState = singlePhone.mCdmaPhoneCallState;
            cdmaOtaProvisionData = singlePhone.mCdmaOtaProvisionData;
            cdmaOtaConfigData = singlePhone.mCdmaOtaConfigData;
            cdmaOtaScreenState = singlePhone.mCdmaOtaScreenState;
            cdmaOtaInCallScreenUiState = singlePhone.mCdmaOtaInCallScreenUiState;
        }
    }

    private void clearCdmaVariables(int subscription) {
        SinglePhone singlePhone = getSinglePhone(subscription);
        singlePhone.clearCdmaVariables();
        cdmaPhoneCallState = null;
        cdmaOtaProvisionData = null;
        cdmaOtaConfigData = null;
        cdmaOtaScreenState = null;
        cdmaOtaInCallScreenUiState = null;
    }

    private void handleServiceStateChanged(Intent intent, Phone phone) {

        // This function used to handle updating EriTextWidgetProvider

        // If service just returned, start sending out the queued messages
        ServiceState ss = ServiceState.newFromBundle(intent.getExtras());

        if (ss != null) {
            int state = ss.getState();
            ((NotificationMgr)notificationMgr).updateNetworkSelection(state);
        }
    }

    // gets the SinglePhone corresponding to a subscription
    static private SinglePhone getSinglePhone(int subscription) {
        try {
            return mSinglePhones[subscription];
        } catch (IndexOutOfBoundsException e) {
            Log.e(LOG_TAG,"subscripton Index out of bounds "+e);
            return null;
        }
    }

    // gets the Default Phone
    @Override
    Phone getDefaultPhone() {
        return getPhone(getDefaultSubscription());
    }

    // gets the Phone correspoding to a subscription
    @Override
    Phone getPhone(int subscription) {
        SinglePhone singlePhone= getSinglePhone(subscription);
        if (singlePhone != null) {
            return singlePhone.mPhone;
        } else {
            Log.w(LOG_TAG, "singlePhone object is null returning default phone");
            return sMe.phone;
        }
    }

    /**
      * Get the subscription that has service
      */
    @Override
    public int getVoiceSubscriptionInService() {
        int voiceSub = getVoiceSubscription();
        int sub = voiceSub;
        for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
            Phone phone = getPhone(i);
            int ss = phone.getServiceState().getState();
            if ((ss == ServiceState.STATE_IN_SERVICE)
                    || (ss == ServiceState.STATE_EMERGENCY_ONLY)) {
                sub = i;
                if (sub == voiceSub) break;
            }
        }
        return sub;
    }

    CdmaPhoneCallState getCdmaPhoneCallState (int subscription) {
        SinglePhone singlePhone = getSinglePhone(subscription);
        if (singlePhone == null) {
            return null;
        }
        return singlePhone.mCdmaPhoneCallState;
    }

    //Sets the default phoneApp variables
    void setDefaultPhone(int subscription){
        //When default phone dynamically changes need to handle
        SinglePhone singlePhone = getSinglePhone(subscription);
        phone = singlePhone.mPhone;
        mLastPhoneState = singlePhone.mLastPhoneState;
        updatePhoneAppCdmaVariables(subscription);
        mDefaultSubscription = subscription;
    }
    /* Gets the default subscription */
    @Override
    public int getDefaultSubscription() {
        return MSimPhoneFactory.getDefaultSubscription();
    }

    @Override
    /* Gets User preferred Voice subscription setting*/
    public int getVoiceSubscription() {
        return MSimPhoneFactory.getVoiceSubscription();
    }

    @Override
    /* Gets User preferred Data subscription setting*/
    public int getDataSubscription() {
        return MSimPhoneFactory.getDataSubscription();
    }

    /* Gets User preferred SMS subscription setting*/
    public int getSMSSubscription() {
        return MSimPhoneFactory.getSMSSubscription();
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

}

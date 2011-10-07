/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.telephony.Phone;

/**
 * TODO: This is intended as a temporary repository for behavior policy
 * functions that depend upon the type of phone or the carrier.  Ultimately
 * these sorts of questions should be answered by the telephony layer.
 */
public class TelephonyCapabilities {
    private static final String LOG_TAG = "TelephonyCapabilities";

    /** This class is never instantiated. */
    private TelephonyCapabilities() {
    }

    /**
     * On GSM devices, we never use short tones.
     * On CDMA devices, it depends upon the settings.
     * TODO: I don't think this has anything to do with GSM versus CDMA,
     * should we be looking only at the setting?
     */
    /* package */ static boolean useShortDtmfTones(Phone phone, Context context) {
        int phoneType = phone.getPhoneType();
        if (phoneType == Phone.PHONE_TYPE_GSM) {
            return false;
        } else if (phoneType == Phone.PHONE_TYPE_CDMA) {
            int toneType = android.provider.Settings.System.getInt(
                    context.getContentResolver(),
                Settings.System.DTMF_TONE_TYPE_WHEN_DIALING,
                CallFeaturesSetting.DTMF_TONE_TYPE_NORMAL);
            if (toneType == CallFeaturesSetting.DTMF_TONE_TYPE_NORMAL) {
                return true;
            } else {
                return false;
            }
        } else if (phoneType == Phone.PHONE_TYPE_SIP) {
            return false;
        } else {
            throw new IllegalStateException("Unexpected phone type: " + phoneType);
        }
    }

    /**
     * Return true if the current phone supports ECM ("Emergency Callback
     * Mode"), which is a feature where the device goes into a special
     * state for a short period of time after making an outgoing emergency
     * call.
     *
     * (On current devices, that state lasts 5 minutes.  It prevents data
     * usage by other apps, to avoid conflicts with any possible incoming
     * calls.  It also puts up a notification in the status bar, showing a
     * countdown while ECM is active, and allowing the user to exit ECM.)
     *
     * Currently this is assumed to be true for CDMA phones, and false
     * otherwise.
     *
     * TODO: This capability should really be exposed by the telephony
     * layer, since it depends on the underlying telephony technology.
     * (Or, is this actually carrier-specific?  Is it VZW-only?)
     */
    /* package */ static boolean supportsEcm(Phone phone) {
        return (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA);
    }

    /**
     * Return true if the current phone supports Over The Air Service
     * Provisioning (OTASP)
     *
     * Currently this is assumed to be true for CDMA phones, and false
     * otherwise.
     *
     * TODO: This capability should really be exposed by the telephony
     * layer, since it depends on the underlying telephony technology.
     *
     * TODO: Watch out: this is also highly carrier-specific, since the
     * OTA procedure is different from one carrier to the next, *and* the
     * different carriers may want very different onscreen UI as well.
     * The procedure may even be different for different devices with the
     * same carrier.
     *
     * So we eventually will need a much more flexible, pluggable design.
     * This method here is just a placeholder to reduce hardcoded
     * "if (CDMA)" checks sprinkled throughout the rest of the phone app.
     *
     * TODO: consider using the term "OTASP" rather "OTA" everywhere in the
     * phone app, since OTA can also mean over-the-air software updates.
     */
    /* package */ static boolean supportsOtasp(Phone phone) {
        return (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA);
    }

    /**
     * Return true if the current phone can retrieve the voice message count.
     *
     * Currently this is assumed to be true on CDMA phones and false otherwise.
     *
     * TODO: This capability should really be exposed by the telephony
     * layer, since it depends on the underlying telephony technology.
     */
    /* package */ static boolean supportsVoiceMessageCount(Phone phone) {
        return (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA);
    }

    /**
     * Return true if this phone allows the user to select which
     * network to use.
     *
     * Currently this is assumed to be true only on GSM phones.
     *
     * TODO: Should CDMA phones allow this as well?
     */
    /* package */ static boolean supportsNetworkSelection(Phone phone) {
        return (phone.getPhoneType() == Phone.PHONE_TYPE_GSM);
    }

    /**
     * Returns a resource ID for a label to use when displaying the
     * "device id" of the current device.  (This is currently used as the
     * title of the "device id" dialog.)
     *
     * This is specific to the device's telephony technology: the device
     * id is called "IMEI" on GSM phones and "MEID" on CDMA phones.
     * TODO: ultimately this name should come directly from the
     * telephony layer.
     */
    /* package */ static int getDeviceIdLabel(Phone phone) {
        if (phone.getPhoneType() == Phone.PHONE_TYPE_GSM) {
            return R.string.imei;
        } else if (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            return R.string.meid;
        } else {
            Log.w(LOG_TAG, "getDeviceIdLabel: no known label for phone "
                  + phone.getPhoneName());
            return 0;
        }
    }

    /**
     * Return true if the current phone supports the ability to explicitly
     * manage the state of a conference call (i.e. view the participants,
     * and hangup or separate individual callers.)
     *
     * The in-call screen's "Manage conference" UI is available only on
     * devices that support this feature.
     *
     * Currently this is assumed to be true on GSM phones and false otherwise.
     * TODO: This capability should really be exposed by the telephony
     * layer, since it depends on the underlying telephony technology.
     */
    /* package */ static boolean supportsConferenceCallManagement(Phone phone) {
        return ((phone.getPhoneType() == Phone.PHONE_TYPE_GSM)
                || (phone.getPhoneType() == Phone.PHONE_TYPE_SIP));
    }

    /**
     * Return true if the current phone supports explicit "Hold" and
     * "Unhold" actions for an active call.  (If so, the in-call UI will
     * provide onscreen "Hold" / "Unhold" buttons.)
     *
     * Currently this is assumed to be true on GSM phones and false
     * otherwise.  (In particular, CDMA has no concept of "putting a call
     * on hold.")
     * TODO: This capability should really be exposed by the telephony
     * layer, since it depends on the underlying telephony technology.
     */
    /* package */ static boolean supportsHoldAndUnhold(Phone phone) {
        return ((phone.getPhoneType() == Phone.PHONE_TYPE_GSM)
                || (phone.getPhoneType() == Phone.PHONE_TYPE_SIP));
    }

    /**
     * Return true if the current phone supports distinct "Answer & Hold"
     * and "Answer & End" behaviors in the call-waiting scenario.  If so,
     * the in-call UI may provide separate buttons or menu items for these
     * two actions.
     *
     * Currently this is assumed to be true on GSM phones and false
     * otherwise.  (In particular, CDMA has no concept of explicitly
     * managing the background call, or "putting a call on hold.")
     *
     * TODO: This capability should really be exposed by the telephony
     * layer, since it depends on the underlying telephony technology.
     *
     * TODO: It might be better to expose this capability in a more
     * generic form, like maybe "supportsExplicitMultipleLineManagement()"
     * rather than focusing specifically on call-waiting behavior.
     */
    /* package */ static boolean supportsAnswerAndHold(Phone phone) {
        return ((phone.getPhoneType() == Phone.PHONE_TYPE_GSM)
                || (phone.getPhoneType() == Phone.PHONE_TYPE_SIP));
    }
}

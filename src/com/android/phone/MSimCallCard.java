/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-2012 Code Aurora Forum. All rights reserved.
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
import android.pim.ContactsAsyncHelper;
import android.telephony.PhoneNumberUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.CallManager;

import java.util.List;


/**
 * "Call card" UI element: the in-call screen contains a tiled layout of call
 * cards, each representing the state of a current "call" (ie. an active call,
 * a call on hold, or an incoming call.)
 */
public class MSimCallCard extends CallCard {
    private static final String LOG_TAG = "MSimCallCard";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    //Display subscription info for incoming call.
    private TextView mSubInfo;
    public MSimCallCard(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (DBG) log("MSimCallCard constructor...");
        if (DBG) log("- this = " + this);
        if (DBG) log("- context " + context + ", attrs " + attrs);
    }

    @Override
    protected void inflate(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(
                R.layout.call_card_multi_sim,  // resource
                this,                // root
                true);
    }

   @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (DBG) log("CallCard onFinishInflate(this = " + this + ")...");
        mSubInfo = (TextView) findViewById(R.id.subInfo);
    }

    /**
     * Updates the main block of caller info on the CallCard
     * (ie. the stuff in the primaryCallInfo block) based on the specified Call.
     */
    @Override
    protected void displayMainCallStatus(CallManager cm, Call call) {
        if (DBG) log("displayMainCallStatus(call " + call + ")...");

        if (call == null) {
            // There's no call to display, presumably because the phone is idle.
            mPrimaryCallInfo.setVisibility(View.GONE);
            return;
        }
        mPrimaryCallInfo.setVisibility(View.VISIBLE);

        Call.State state = call.getState();
        if (DBG) log("  - call.state: " + call.getState());

        switch (state) {
            case ACTIVE:
            case DISCONNECTING:
                // update timer field
                if (DBG) log("displayMainCallStatus: start periodicUpdateTimer");
                mCallTime.setActiveCallMode(call);
                mCallTime.reset();
                mCallTime.periodicUpdateTimer();

                break;

            case HOLDING:
                // update timer field
                mCallTime.cancelTimer();

                break;

            case DISCONNECTED:
                // Stop getting timer ticks from this call
                mCallTime.cancelTimer();

                break;

            case DIALING:
            case ALERTING:
                // Stop getting timer ticks from a previous call
                mCallTime.cancelTimer();
                //Display subscription info only for incoming calls.
                if (mSubInfo != null) {
                    mSubInfo.setVisibility(View.GONE);
                }
                break;

            case INCOMING:
            case WAITING:
                // Stop getting timer ticks from a previous call
                mCallTime.cancelTimer();
                if (mSubInfo != null) {
                    //Get the subscription from current call object.
                    int subscription = call.getPhone().getSubscription();
                    subscription++;
                    String subInfo = "SUB" + subscription;
                    if (DBG) Log.v(LOG_TAG, "Setting subinfo: " + subInfo);
                    mSubInfo.setText(subInfo);
                    mSubInfo.setVisibility(View.VISIBLE);
                }
                break;

            case IDLE:
                // The "main CallCard" should never be trying to display
                // an idle call!  In updateState(), if the phone is idle,
                // we call updateNoCall(), which means that we shouldn't
                // have passed a call into this method at all.
                Log.w(LOG_TAG, "displayMainCallStatus: IDLE call in the main call card!");

                // (It is possible, though, that we had a valid call which
                // became idle *after* the check in updateState() but
                // before we get here...  So continue the best we can,
                // with whatever (stale) info we can get from the
                // passed-in Call object.)

                break;

            default:
                Log.w(LOG_TAG, "displayMainCallStatus: unexpected call state: " + state);
                break;
        }

        updateCallStateWidgets(call);

        if (PhoneUtils.isConferenceCall(call)) {
            // Update onscreen info for a conference call.
            updateDisplayForConference(call);
        } else {
            // Update onscreen info for a regular call (which presumably
            // has only one connection.)
            Connection conn = null;
            int phoneType = call.getPhone().getPhoneType();
            if (phoneType == Phone.PHONE_TYPE_CDMA) {
                conn = call.getLatestConnection();
            } else if ((phoneType == Phone.PHONE_TYPE_GSM)
                  || (phoneType == Phone.PHONE_TYPE_SIP)) {
                conn = call.getEarliestConnection();
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }

            if (conn == null) {
                if (DBG) log("displayMainCallStatus: connection is null, using default values.");
                // if the connection is null, we run through the behaviour
                // we had in the past, which breaks down into trivial steps
                // with the current implementation of getCallerInfo and
                // updateDisplayForPerson.
                CallerInfo info = PhoneUtils.getCallerInfo(getContext(), null /* conn */);
                updateDisplayForPerson(info, Connection.PRESENTATION_ALLOWED, false, call, conn);
            } else {
                if (DBG) log("  - CONN: " + conn + ", state = " + conn.getState());
                int presentation = conn.getNumberPresentation();

                // make sure that we only make a new query when the current
                // callerinfo differs from what we've been requested to display.
                boolean runQuery = true;
                Object o = conn.getUserData();
                if (o instanceof PhoneUtils.CallerInfoToken) {
                    runQuery = mPhotoTracker.isDifferentImageRequest(
                            ((PhoneUtils.CallerInfoToken) o).currentInfo);
                } else {
                    runQuery = mPhotoTracker.isDifferentImageRequest(conn);
                }

                // Adding a check to see if the update was caused due to a Phone number update
                // or CNAP update. If so then we need to start a new query
                if (phoneType == Phone.PHONE_TYPE_CDMA) {
                    Object obj = conn.getUserData();
                    String updatedNumber = conn.getAddress();
                    String updatedCnapName = conn.getCnapName();
                    CallerInfo info = null;
                    if (obj instanceof PhoneUtils.CallerInfoToken) {
                        info = ((PhoneUtils.CallerInfoToken) o).currentInfo;
                    } else if (o instanceof CallerInfo) {
                        info = (CallerInfo) o;
                    }

                    if (info != null) {
                        if (updatedNumber != null && !updatedNumber.equals(info.phoneNumber)) {
                            if (DBG) log("- displayMainCallStatus: updatedNumber = "
                                    + updatedNumber);
                            runQuery = true;
                        }
                        if (updatedCnapName != null && !updatedCnapName.equals(info.cnapName)) {
                            if (DBG) log("- displayMainCallStatus: updatedCnapName = "
                                    + updatedCnapName);
                            runQuery = true;
                        }
                    }
                }

                if (runQuery) {
                    if (DBG) log("- displayMainCallStatus: starting CallerInfo query...");
                    PhoneUtils.CallerInfoToken info =
                            PhoneUtils.startGetCallerInfo(getContext(), conn, this, call);
                    updateDisplayForPerson(info.currentInfo, presentation, !info.isFinal,
                                           call, conn);
                } else {
                    // No need to fire off a new query.  We do still need
                    // to update the display, though (since we might have
                    // previously been in the "conference call" state.)
                    if (DBG) log("- displayMainCallStatus: using data we already have...");
                    if (o instanceof CallerInfo) {
                        CallerInfo ci = (CallerInfo) o;
                        // In case of emergency and voice mail numbers, ci.phoneNumber is
                        // updated with "Emergency Number" text and voice mail tag respectively.
                        // So, ci.phoneNumber will not match connection address.
                        String connAddress = conn.getAddress();
                        String number = PhoneNumberUtils.stripSeparators(ci.phoneNumber);
                        if (!(ci.isEmergencyNumber() || ci.isVoiceMailNumber()) &&
                            (connAddress != null && !connAddress.equals(number))) {
                            log("- displayMainCallStatus: Phone number modified!!");
                            CallerInfo newCi = CallerInfo.getCallerInfo(getContext(), connAddress);
                            if (newCi != null) {
                                ci = newCi;
                                conn.setUserData(ci);
                            }
                        }
                        // Update CNAP information and phone number if Phone state change occurred
                        ci.cnapName = conn.getCnapName();
                        ci.numberPresentation = conn.getNumberPresentation();
                        ci.namePresentation = conn.getCnapNamePresentation();
                        if (DBG) log("- displayMainCallStatus: CNAP data from Connection: "
                                + "CNAP name=" + ci.cnapName
                                + ", Number/Name Presentation=" + ci.numberPresentation
                                + ", Number=" + ci.phoneNumber);
                        if (DBG) log("   ==> Got CallerInfo; updating display: ci = " + ci);
                        updateDisplayForPerson(ci, presentation, false, call, conn);
                    } else if (o instanceof PhoneUtils.CallerInfoToken){
                        CallerInfo ci = ((PhoneUtils.CallerInfoToken) o).currentInfo;
                        if (DBG) log("- displayMainCallStatus: CNAP data from Connection: "
                                + "CNAP name=" + ci.cnapName
                                + ", Number/Name Presentation=" + ci.numberPresentation);
                        if (DBG) log("   ==> Got CallerInfoToken; updating display: ci = " + ci);
                        updateDisplayForPerson(ci, presentation, true, call, conn);
                    } else {
                        Log.w(LOG_TAG, "displayMainCallStatus: runQuery was false, "
                              + "but we didn't have a cached CallerInfo object!  o = " + o);
                        // TODO: any easy way to recover here (given that
                        // the CallCard is probably displaying stale info
                        // right now?)  Maybe force the CallCard into the
                        // "Unknown" state?
                    }
                }
            }
        }

        // In some states we override the "photo" ImageView to be an
        // indication of the current state, rather than displaying the
        // regular photo as set above.
        updatePhotoForCallState(call);

        // One special feature of the "number" text field: For incoming
        // calls, while the user is dragging the RotarySelector widget, we
        // use mPhoneNumber to display a hint like "Rotate to answer".
        if (mIncomingCallWidgetHintTextResId != 0) {
            // Display the hint!
            mPhoneNumber.setText(mIncomingCallWidgetHintTextResId);
            mPhoneNumber.setTextColor(mContext.getResources().
                    getColor(mIncomingCallWidgetHintColorResId));
            mPhoneNumber.setVisibility(View.VISIBLE);
            mLabel.setVisibility(View.GONE);
        }
        // If we don't have a hint to display, just don't touch
        // mPhoneNumber and mLabel. (Their text / color / visibility have
        // already been set correctly, by either updateDisplayForPerson()
        // or updateDisplayForConference().)
    }

    // Accessibility event support.
    // Since none of the CallCard elements are focusable, we need to manually
    // fill in the AccessibilityEvent here (so that the name / number / etc will
    // get pronounced by a screen reader, for example.)
    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        super.dispatchPopulateAccessibilityEvent(event);
        if (mSubInfo != null) {
            dispatchPopulateAccessibilityEvent(event, mSubInfo);
        }
        return true;
    }

    private void dispatchPopulateAccessibilityEvent(AccessibilityEvent event, View view) {
        List<CharSequence> eventText = event.getText();
        int size = eventText.size();
        view.dispatchPopulateAccessibilityEvent(event);
        // if no text added write null to keep relative position
        if (size == eventText.size()) {
            eventText.add(null);
        }
    }

   // Debugging / testing code
    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

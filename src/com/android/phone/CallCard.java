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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ContentUris;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.pim.ContactsAsyncHelper;
import android.provider.ContactsContract.Contacts;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.CallManager;

import java.util.List;


/**
 * "Call card" UI element: the in-call screen contains a tiled layout of call
 * cards, each representing the state of a current "call" (ie. an active call,
 * a call on hold, or an incoming call.)
 */
public class CallCard extends FrameLayout
        implements CallTime.OnTickListener, CallerInfoAsyncQuery.OnQueryCompleteListener,
                   ContactsAsyncHelper.OnImageLoadCompleteListener {
    private static final String LOG_TAG = "CallCard";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    /**
     * Reference to the InCallScreen activity that owns us.  This may be
     * null if we haven't been initialized yet *or* after the InCallScreen
     * activity has been destroyed.
     */
    private InCallScreen mInCallScreen;

    // Phone app instance
    private PhoneApp mApplication;

    // Top-level subviews of the CallCard
    private ViewGroup mCallInfoContainer;  // Container for info about the current call(s)
    private ViewGroup mPrimaryCallInfo;  // "Call info" block #1 (the foreground or ringing call)
    private ViewGroup mPrimaryCallBanner;  // "Call banner" for the primary call
    private ViewGroup mSecondaryCallInfo;  // "Call info" block #2 (the background "on hold" call)

    // "Call state" widgets
    private TextView mCallStateLabel;
    private TextView mElapsedTime;

    // Text colors, used for various labels / titles
    private int mTextColorCallTypeSip;

    // The main block of info about the "primary" or "active" call,
    // including photo / name / phone number / etc.
    private InCallContactPhoto mPhoto;
    private TextView mName;
    private TextView mPhoneNumber;
    private TextView mLabel;
    private TextView mCallTypeLabel;
    private TextView mSocialStatus;

    // Info about the "secondary" call, which is the "call on hold" when
    // two lines are in use.
    private TextView mSecondaryCallName;
    private TextView mSecondaryCallStatus;
    private InCallContactPhoto mSecondaryCallPhoto;

    // Onscreen hint for the incoming call RotarySelector widget.
    private int mIncomingCallWidgetHintTextResId;
    private int mIncomingCallWidgetHintColorResId;

    private CallTime mCallTime;

    // Track the state for the photo.
    private ContactsAsyncHelper.ImageTracker mPhotoTracker;

    // Cached DisplayMetrics density.
    private float mDensity;

    public CallCard(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (DBG) log("CallCard constructor...");
        if (DBG) log("- this = " + this);
        if (DBG) log("- context " + context + ", attrs " + attrs);

        // Inflate the contents of this CallCard, and add it (to ourself) as a child.
        LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(
                R.layout.call_card,  // resource
                this,                // root
                true);

        mApplication = PhoneApp.getInstance();

        mCallTime = new CallTime(this);

        // create a new object to track the state for the photo.
        mPhotoTracker = new ContactsAsyncHelper.ImageTracker();

        mDensity = getResources().getDisplayMetrics().density;
        if (DBG) log("- Density: " + mDensity);
    }

    void setInCallScreenInstance(InCallScreen inCallScreen) {
        mInCallScreen = inCallScreen;
    }

    public void onTickForCallTimeElapsed(long timeElapsed) {
        // While a call is in progress, update the elapsed time shown
        // onscreen.
        updateElapsedTimeWidget(timeElapsed);
    }

    /* package */
    void stopTimer() {
        mCallTime.cancelTimer();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        if (DBG) log("CallCard onFinishInflate(this = " + this + ")...");

        mCallInfoContainer = (ViewGroup) findViewById(R.id.call_info_container);
        mPrimaryCallInfo = (ViewGroup) findViewById(R.id.call_info_1);
        mPrimaryCallBanner = (ViewGroup) findViewById(R.id.call_banner_1);
        mSecondaryCallInfo = (ViewGroup) findViewById(R.id.call_info_2);
        mCallStateLabel = (TextView) findViewById(R.id.callStateLabel);
        mElapsedTime = (TextView) findViewById(R.id.elapsedTime);

        // Text colors
        mTextColorCallTypeSip = getResources().getColor(R.color.incall_callTypeSip);

        // "Caller info" area, including photo / name / phone numbers / etc
        mPhoto = (InCallContactPhoto) findViewById(R.id.photo);
        ImageView inset = (ImageView) findViewById(R.id.insetPhoto);
        mPhoto.setInsetImageView(inset);

        mName = (TextView) findViewById(R.id.name);
        mPhoneNumber = (TextView) findViewById(R.id.phoneNumber);
        mLabel = (TextView) findViewById(R.id.label);
        mCallTypeLabel = (TextView) findViewById(R.id.callTypeLabel);
        mSocialStatus = (TextView) findViewById(R.id.socialStatus);

        // Secondary info area, for the background ("on hold") call
        mSecondaryCallName = (TextView) findViewById(R.id.secondaryCallName);
        mSecondaryCallStatus = (TextView) findViewById(R.id.secondaryCallStatus);
        mSecondaryCallPhoto = (InCallContactPhoto) findViewById(R.id.secondaryCallPhoto);
    }

    /**
     * Updates the state of all UI elements on the CallCard, based on the
     * current state of the phone.
     */
    void updateState(CallManager cm) {
        if (DBG) log("updateState(" + cm + ")...");

        // Update the onscreen UI based on the current state of the phone.

        Phone.State state = cm.getState();  // IDLE, RINGING, or OFFHOOK
        Call ringingCall = cm.getFirstActiveRingingCall();
        Call fgCall = cm.getActiveFgCall();
        Call bgCall = cm.getFirstActiveBgCall();

        // Update the overall layout of the onscreen elements.
        updateCallInfoLayout(state);

        // If the FG call is dialing/alerting, we should display for that call
        // and ignore the ringing call. This case happens when the telephony
        // layer rejects the ringing call while the FG call is dialing/alerting,
        // but the incoming call *does* briefly exist in the DISCONNECTING or
        // DISCONNECTED state.
        if ((ringingCall.getState() != Call.State.IDLE)
                && !fgCall.getState().isDialing()) {
            // A phone call is ringing, call waiting *or* being rejected
            // (ie. another call may also be active as well.)
            updateRingingCall(cm);
        } else if ((fgCall.getState() != Call.State.IDLE)
                || (bgCall.getState() != Call.State.IDLE)) {
            // We are here because either:
            // (1) the phone is off hook. At least one call exists that is
            // dialing, active, or holding, and no calls are ringing or waiting,
            // or:
            // (2) the phone is IDLE but a call just ended and it's still in
            // the DISCONNECTING or DISCONNECTED state. In this case, we want
            // the main CallCard to display "Hanging up" or "Call ended".
            // The normal "foreground call" code path handles both cases.
            updateForegroundCall(cm);
        } else {
            // We don't have any DISCONNECTED calls, which means
            // that the phone is *truly* idle.
            //
            // It's very rare to be on the InCallScreen at all in this
            // state, but it can happen in some cases:
            // - A stray onPhoneStateChanged() event came in to the
            //   InCallScreen *after* it was dismissed.
            // - We're allowed to be on the InCallScreen because
            //   an MMI or USSD is running, but there's no actual "call"
            //   to display.
            // - We're displaying an error dialog to the user
            //   (explaining why the call failed), so we need to stay on
            //   the InCallScreen so that the dialog will be visible.
            //
            // In these cases, put the callcard into a sane but "blank" state:
            updateNoCall(cm);
        }
    }

    /**
     * Updates the overall size and positioning of mCallInfoContainer and
     * the "Call info" blocks, based on the phone state.
     */
    private void updateCallInfoLayout(Phone.State state) {
        boolean ringing = (state == Phone.State.RINGING);
        if (DBG) log("updateCallInfoLayout()...  ringing = " + ringing);

        // Based on the current state, update the overall
        // CallCard layout:

        // - Update the bottom margin of mCallInfoContainer to make sure
        //   the call info area won't overlap with the touchable
        //   controls on the bottom part of the screen.

        int reservedVerticalSpace = mInCallScreen.getInCallTouchUi().getTouchUiHeight();
        ViewGroup.MarginLayoutParams callInfoLp =
                (ViewGroup.MarginLayoutParams) mCallInfoContainer.getLayoutParams();
        callInfoLp.bottomMargin = reservedVerticalSpace;  // Equivalent to setting
                                                          // android:layout_marginBottom in XML
        if (DBG) log("  ==> callInfoLp.bottomMargin: " + reservedVerticalSpace);
        mCallInfoContainer.setLayoutParams(callInfoLp);
    }

    /**
     * Updates the UI for the state where the phone is in use, but not ringing.
     */
    private void updateForegroundCall(CallManager cm) {
        if (DBG) log("updateForegroundCall()...");
        // if (DBG) PhoneUtils.dumpCallManager();

        Call fgCall = cm.getActiveFgCall();
        Call bgCall = cm.getFirstActiveBgCall();

        if (fgCall.getState() == Call.State.IDLE) {
            if (DBG) log("updateForegroundCall: no active call, show holding call");
            // TODO: make sure this case agrees with the latest UI spec.

            // Display the background call in the main info area of the
            // CallCard, since there is no foreground call.  Note that
            // displayMainCallStatus() will notice if the call we passed in is on
            // hold, and display the "on hold" indication.
            fgCall = bgCall;

            // And be sure to not display anything in the "on hold" box.
            bgCall = null;
        }

        displayMainCallStatus(cm, fgCall);

        Phone phone = fgCall.getPhone();

        int phoneType = phone.getPhoneType();
        if (phoneType == Phone.PHONE_TYPE_CDMA) {
            if ((mApplication.cdmaPhoneCallState.getCurrentCallState()
                    == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE)
                    && mApplication.cdmaPhoneCallState.IsThreeWayCallOrigStateDialing()) {
                displayOnHoldCallStatus(cm, fgCall);
            } else {
                //This is required so that even if a background call is not present
                // we need to clean up the background call area.
                displayOnHoldCallStatus(cm, bgCall);
            }
        } else if ((phoneType == Phone.PHONE_TYPE_GSM)
                || (phoneType == Phone.PHONE_TYPE_SIP)) {
            displayOnHoldCallStatus(cm, bgCall);
        }
    }

    /**
     * Updates the UI for the state where an incoming call is ringing (or
     * call waiting), regardless of whether the phone's already offhook.
     */
    private void updateRingingCall(CallManager cm) {
        if (DBG) log("updateRingingCall()...");

        Call ringingCall = cm.getFirstActiveRingingCall();

        // Display caller-id info and photo from the incoming call:
        displayMainCallStatus(cm, ringingCall);

        // And even in the Call Waiting case, *don't* show any info about
        // the current ongoing call and/or the current call on hold.
        // (Since the caller-id info for the incoming call totally trumps
        // any info about the current call(s) in progress.)
        displayOnHoldCallStatus(cm, null);
    }

    /**
     * Updates the UI for the state where the phone is not in use.
     * This is analogous to updateForegroundCall() and updateRingingCall(),
     * but for the (uncommon) case where the phone is
     * totally idle.  (See comments in updateState() above.)
     *
     * This puts the callcard into a sane but "blank" state.
     */
    private void updateNoCall(CallManager cm) {
        if (DBG) log("updateNoCall()...");

        displayMainCallStatus(cm, null);
        displayOnHoldCallStatus(cm, null);
    }

    /**
     * Updates the main block of caller info on the CallCard
     * (ie. the stuff in the primaryCallInfo block) based on the specified Call.
     */
    private void displayMainCallStatus(CallManager cm, Call call) {
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

                break;

            case INCOMING:
            case WAITING:
                // Stop getting timer ticks from a previous call
                mCallTime.cancelTimer();

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
                        // Update CNAP information if Phone state change occurred
                        ci.cnapName = conn.getCnapName();
                        ci.numberPresentation = conn.getNumberPresentation();
                        ci.namePresentation = conn.getCnapNamePresentation();
                        if (DBG) log("- displayMainCallStatus: CNAP data from Connection: "
                                + "CNAP name=" + ci.cnapName
                                + ", Number/Name Presentation=" + ci.numberPresentation);
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
            mPhoneNumber.setTextColor(getResources().getColor(mIncomingCallWidgetHintColorResId));
            mPhoneNumber.setVisibility(View.VISIBLE);
            mLabel.setVisibility(View.GONE);
        }
        // If we don't have a hint to display, just don't touch
        // mPhoneNumber and mLabel. (Their text / color / visibility have
        // already been set correctly, by either updateDisplayForPerson()
        // or updateDisplayForConference().)
    }

    /**
     * Implemented for CallerInfoAsyncQuery.OnQueryCompleteListener interface.
     * refreshes the CallCard data when it called.
     */
    public void onQueryComplete(int token, Object cookie, CallerInfo ci) {
        if (DBG) log("onQueryComplete: token " + token + ", cookie " + cookie + ", ci " + ci);

        if (cookie instanceof Call) {
            // grab the call object and update the display for an individual call,
            // as well as the successive call to update image via call state.
            // If the object is a textview instead, we update it as we need to.
            if (DBG) log("callerinfo query complete, updating ui from displayMainCallStatus()");
            Call call = (Call) cookie;
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
            PhoneUtils.CallerInfoToken cit =
                   PhoneUtils.startGetCallerInfo(getContext(), conn, this, null);

            int presentation = Connection.PRESENTATION_ALLOWED;
            if (conn != null) presentation = conn.getNumberPresentation();
            if (DBG) log("- onQueryComplete: presentation=" + presentation
                    + ", contactExists=" + ci.contactExists);

            // Depending on whether there was a contact match or not, we want to pass in different
            // CallerInfo (for CNAP). Therefore if ci.contactExists then use the ci passed in.
            // Otherwise, regenerate the CIT from the Connection and use the CallerInfo from there.
            if (ci.contactExists) {
                updateDisplayForPerson(ci, Connection.PRESENTATION_ALLOWED, false, call, conn);
            } else {
                updateDisplayForPerson(cit.currentInfo, presentation, false, call, conn);
            }
            updatePhotoForCallState(call);

        } else if (cookie instanceof TextView){
            if (DBG) log("callerinfo query complete, updating ui from ongoing or onhold");
            ((TextView) cookie).setText(PhoneUtils.getCompactNameFromCallerInfo(ci, mContext));
        }
    }

    /**
     * Implemented for ContactsAsyncHelper.OnImageLoadCompleteListener interface.
     * make sure that the call state is reflected after the image is loaded.
     */
    public void onImageLoadComplete(int token, Object cookie, ImageView iView,
            boolean imagePresent){
        if (cookie != null) {
            updatePhotoForCallState((Call) cookie);
        }
    }

    /**
     * Updates the "call state label" and the elapsed time widget based on the
     * current state of the call.
     */
    private void updateCallStateWidgets(Call call) {
        if (DBG) log("updateCallStateWidgets(call " + call + ")...");
        final Call.State state = call.getState();
        final Context context = getContext();
        final Phone phone = call.getPhone();
        final int phoneType = phone.getPhoneType();

        String callStateLabel = null;  // Label to display as part of the call banner
        int bluetoothIconId = 0;  // Icon to display alongside the call state label

        switch (state) {
            case IDLE:
                // "Call state" is meaningless in this state.
                break;

            case ACTIVE:
                // We normally don't show a "call state label" at all in
                // this state (but see below for some special cases).
                break;

            case HOLDING:
                callStateLabel = context.getString(R.string.card_title_on_hold);
                break;

            case DIALING:
            case ALERTING:
                callStateLabel = context.getString(R.string.card_title_dialing);
                break;

            case INCOMING:
            case WAITING:
                callStateLabel = context.getString(R.string.card_title_incoming_call);

                // Also, display a special icon (alongside the "Incoming call"
                // label) if there's an incoming call and audio will be routed
                // to bluetooth when you answer it.
                if (mApplication.showBluetoothIndication()) {
                    bluetoothIconId = R.drawable.ic_incoming_call_bluetooth;
                }
                break;

            case DISCONNECTING:
                // While in the DISCONNECTING state we display a "Hanging up"
                // message in order to make the UI feel more responsive.  (In
                // GSM it's normal to see a delay of a couple of seconds while
                // negotiating the disconnect with the network, so the "Hanging
                // up" state at least lets the user know that we're doing
                // something.  This state is currently not used with CDMA.)
                callStateLabel = context.getString(R.string.card_title_hanging_up);
                break;

            case DISCONNECTED:
                callStateLabel = getCallFailedString(call);
                break;

            default:
                Log.wtf(LOG_TAG, "updateCallStateWidgets: unexpected call state: " + state);
                break;
        }

        // Check a couple of other special cases (these are all CDMA-specific).

        if (phoneType == Phone.PHONE_TYPE_CDMA) {
            if ((state == Call.State.ACTIVE)
                && mApplication.cdmaPhoneCallState.IsThreeWayCallOrigStateDialing()) {
                // Display "Dialing" while dialing a 3Way call, even
                // though the foreground call state is actually ACTIVE.
                callStateLabel = context.getString(R.string.card_title_dialing);
            } else if (PhoneApp.getInstance().notifier.getIsCdmaRedialCall()) {
                callStateLabel = context.getString(R.string.card_title_redialing);
            }
        }
        if (PhoneUtils.isPhoneInEcm(phone)) {
            // In emergency callback mode (ECM), use a special label
            // that shows your own phone number.
            callStateLabel = getECMCardTitle(context, phone);
        }

        if (DBG) log("==> callStateLabel: '" + callStateLabel
                     + "', bluetoothIconId = " + bluetoothIconId);

        // Update (or hide) the onscreen widget:
        if (TextUtils.isEmpty(callStateLabel)) {
            // When hiding, do a smooth fade-out animation.
            Fade.hide(mCallStateLabel, View.GONE);
        } else {
            // ... but when becoming visible, never animate (mainly to be
            // sure you don't see a fade-in at the very beginning of a
            // call.)
            mCallStateLabel.setVisibility(View.VISIBLE);

            mCallStateLabel.setText(callStateLabel);

            // ...and display the icon too if necessary.
            if (bluetoothIconId != 0) {
                mCallStateLabel.setCompoundDrawablesWithIntrinsicBounds(bluetoothIconId, 0, 0, 0);
                mCallStateLabel.setCompoundDrawablePadding((int) (mDensity * 5));
            } else {
                // Clear out any icons
                mCallStateLabel.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            }
        }

        // ...and update the elapsed time widget too.
        switch (state) {
            case ACTIVE:
            case DISCONNECTING:
                mElapsedTime.setVisibility(View.VISIBLE);
                long duration = CallTime.getCallDuration(call);  // msec
                updateElapsedTimeWidget(duration / 1000);
                // Also see onTickForCallTimeElapsed(), which updates this
                // widget once per second while the call is active.
                break;

            case DISCONNECTED:
                // In the "Call ended" state, leave the mElapsedTime widget
                // visible, but don't touch it (so we continue to see the
                // elapsed time of the call that just ended.)
                mElapsedTime.setVisibility(View.VISIBLE);
                break;

            default:
                // In all other states (DIALING, INCOMING, HOLDING, etc.),
                // the "elapsed time" is meaningless, so don't show it.
                mElapsedTime.setVisibility(View.INVISIBLE);
                break;
        }
    }

    /**
     * Updates mElapsedTime based on the specified number of seconds.
     * A timeElapsed value of zero means to not show an elapsed time at all.
     */
    private void updateElapsedTimeWidget(long timeElapsed) {
        // if (DBG) log("updateElapsedTimeWidget: " + timeElapsed);
        if (timeElapsed == 0) {
            mElapsedTime.setText("");
        } else {
            mElapsedTime.setText(DateUtils.formatElapsedTime(timeElapsed));
        }
    }

    /**
     * Updates the "on hold" box in the "other call" info area
     * (ie. the stuff in the secondaryCallInfo block)
     * based on the specified Call.
     * Or, clear out the "on hold" box if the specified call
     * is null or idle.
     */
    private void displayOnHoldCallStatus(CallManager cm, Call call) {
        if (DBG) log("displayOnHoldCallStatus(call =" + call + ")...");

        if ((call == null) || (PhoneApp.getInstance().isOtaCallInActiveState())) {
            mSecondaryCallInfo.setVisibility(View.GONE);
            return;
        }

        boolean showSecondaryCallInfo = false;
        Call.State state = call.getState();
        switch (state) {
            case HOLDING:
                // Ok, there actually is a background call on hold.
                // Display the "on hold" box.

                // Note this case occurs only on GSM devices.  (On CDMA,
                // the "call on hold" is actually the 2nd connection of
                // that ACTIVE call; see the ACTIVE case below.)

                if (PhoneUtils.isConferenceCall(call)) {
                    if (DBG) log("==> conference call.");
                    mSecondaryCallName.setText(getContext().getString(R.string.confCall));
                    showImage(mSecondaryCallPhoto, R.drawable.picture_conference);
                } else {
                    // perform query and update the name temporarily
                    // make sure we hand the textview we want updated to the
                    // callback function.
                    if (DBG) log("==> NOT a conf call; call startGetCallerInfo...");
                    PhoneUtils.CallerInfoToken infoToken = PhoneUtils.startGetCallerInfo(
                            getContext(), call, this, mSecondaryCallName);
                    mSecondaryCallName.setText(
                            PhoneUtils.getCompactNameFromCallerInfo(infoToken.currentInfo,
                                                                    getContext()));

                    // Also pull the photo out of the current CallerInfo.
                    // (Note we assume we already have a valid photo at
                    // this point, since *presumably* the caller-id query
                    // was already run at some point *before* this call
                    // got put on hold.  If there's no cached photo, just
                    // fall back to the default "unknown" image.)
                    if (infoToken.isFinal) {
                        showCachedImage(mSecondaryCallPhoto, infoToken.currentInfo);
                    } else {
                        showImage(mSecondaryCallPhoto, R.drawable.picture_unknown);
                    }
                }

                showSecondaryCallInfo = true;

                break;

            case ACTIVE:
                // CDMA: This is because in CDMA when the user originates the second call,
                // although the Foreground call state is still ACTIVE in reality the network
                // put the first call on hold.
                if (mApplication.phone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                    List<Connection> connections = call.getConnections();
                    if (connections.size() > 2) {
                        // This means that current Mobile Originated call is the not the first 3-Way
                        // call the user is making, which in turn tells the PhoneApp that we no
                        // longer know which previous caller/party had dropped out before the user
                        // made this call.
                        mSecondaryCallName.setText(
                                getContext().getString(R.string.card_title_in_call));
                        showImage(mSecondaryCallPhoto, R.drawable.picture_unknown);
                    } else {
                        // This means that the current Mobile Originated call IS the first 3-Way
                        // and hence we display the first callers/party's info here.
                        Connection conn = call.getEarliestConnection();
                        PhoneUtils.CallerInfoToken infoToken = PhoneUtils.startGetCallerInfo(
                                getContext(), conn, this, mSecondaryCallName);

                        // Get the compactName to be displayed, but then check that against
                        // the number presentation value for the call. If it's not an allowed
                        // presentation, then display the appropriate presentation string instead.
                        CallerInfo info = infoToken.currentInfo;

                        String name = PhoneUtils.getCompactNameFromCallerInfo(info, getContext());
                        boolean forceGenericPhoto = false;
                        if (info != null && info.numberPresentation !=
                                Connection.PRESENTATION_ALLOWED) {
                            name = getPresentationString(info.numberPresentation);
                            forceGenericPhoto = true;
                        }
                        mSecondaryCallName.setText(name);

                        // Also pull the photo out of the current CallerInfo.
                        // (Note we assume we already have a valid photo at
                        // this point, since *presumably* the caller-id query
                        // was already run at some point *before* this call
                        // got put on hold.  If there's no cached photo, just
                        // fall back to the default "unknown" image.)
                        if (!forceGenericPhoto && infoToken.isFinal) {
                            showCachedImage(mSecondaryCallPhoto, info);
                        } else {
                            showImage(mSecondaryCallPhoto, R.drawable.picture_unknown);
                        }
                    }
                    showSecondaryCallInfo = true;

                } else {
                    // We shouldn't ever get here at all for non-CDMA devices.
                    Log.w(LOG_TAG, "displayOnHoldCallStatus: ACTIVE state on non-CDMA device");
                    showSecondaryCallInfo = false;
                }
                break;

            default:
                // There's actually no call on hold.  (Presumably this call's
                // state is IDLE, since any other state is meaningless for the
                // background call.)
                showSecondaryCallInfo = false;
                break;
        }

        // Show or hide the entire "secondary call" info area.
        mSecondaryCallInfo.setVisibility(showSecondaryCallInfo ? View.VISIBLE : View.GONE);
    }

    private String getCallFailedString(Call call) {
        Connection c = call.getEarliestConnection();
        int resID;

        if (c == null) {
            if (DBG) log("getCallFailedString: connection is null, using default values.");
            // if this connection is null, just assume that the
            // default case occurs.
            resID = R.string.card_title_call_ended;
        } else {

            Connection.DisconnectCause cause = c.getDisconnectCause();

            // TODO: The card *title* should probably be "Call ended" in all
            // cases, but if the DisconnectCause was an error condition we should
            // probably also display the specific failure reason somewhere...

            switch (cause) {
                case BUSY:
                    resID = R.string.callFailed_userBusy;
                    break;

                case CONGESTION:
                    resID = R.string.callFailed_congestion;
                    break;

                case TIMED_OUT:
                    resID = R.string.callFailed_timedOut;
                    break;

                case SERVER_UNREACHABLE:
                    resID = R.string.callFailed_server_unreachable;
                    break;

                case NUMBER_UNREACHABLE:
                    resID = R.string.callFailed_number_unreachable;
                    break;

                case INVALID_CREDENTIALS:
                    resID = R.string.callFailed_invalid_credentials;
                    break;

                case SERVER_ERROR:
                    resID = R.string.callFailed_server_error;
                    break;

                case OUT_OF_NETWORK:
                    resID = R.string.callFailed_out_of_network;
                    break;

                case LOST_SIGNAL:
                case CDMA_DROP:
                    resID = R.string.callFailed_noSignal;
                    break;

                case LIMIT_EXCEEDED:
                    resID = R.string.callFailed_limitExceeded;
                    break;

                case POWER_OFF:
                    resID = R.string.callFailed_powerOff;
                    break;

                case ICC_ERROR:
                    resID = R.string.callFailed_simError;
                    break;

                case OUT_OF_SERVICE:
                    resID = R.string.callFailed_outOfService;
                    break;

                case INVALID_NUMBER:
                case UNOBTAINABLE_NUMBER:
                    resID = R.string.callFailed_unobtainable_number;
                    break;

                default:
                    resID = R.string.card_title_call_ended;
                    break;
            }
        }
        return getContext().getString(resID);
    }

    /**
     * Updates the name / photo / number / label fields on the CallCard
     * based on the specified CallerInfo.
     *
     * If the current call is a conference call, use
     * updateDisplayForConference() instead.
     */
    private void updateDisplayForPerson(CallerInfo info,
                                        int presentation,
                                        boolean isTemporary,
                                        Call call,
                                        Connection conn) {
        if (DBG) log("updateDisplayForPerson(" + info + ")\npresentation:" +
                     presentation + " isTemporary:" + isTemporary);

        // inform the state machine that we are displaying a photo.
        mPhotoTracker.setPhotoRequest(info);
        mPhotoTracker.setPhotoState(ContactsAsyncHelper.ImageTracker.DISPLAY_IMAGE);

        // The actual strings we're going to display onscreen:
        String displayName;
        String displayNumber = null;
        String label = null;
        Uri personUri = null;
        String socialStatusText = null;
        Drawable socialStatusBadge = null;

        if (info != null) {
            // It appears that there is a small change in behaviour with the
            // PhoneUtils' startGetCallerInfo whereby if we query with an
            // empty number, we will get a valid CallerInfo object, but with
            // fields that are all null, and the isTemporary boolean input
            // parameter as true.

            // In the past, we would see a NULL callerinfo object, but this
            // ends up causing null pointer exceptions elsewhere down the
            // line in other cases, so we need to make this fix instead. It
            // appears that this was the ONLY call to PhoneUtils
            // .getCallerInfo() that relied on a NULL CallerInfo to indicate
            // an unknown contact.

            // Currently, info.phoneNumber may actually be a SIP address, and
            // if so, it might sometimes include the "sip:" prefix.  That
            // prefix isn't really useful to the user, though, so strip it off
            // if present.  (For any other URI scheme, though, leave the
            // prefix alone.)
            // TODO: It would be cleaner for CallerInfo to explicitly support
            // SIP addresses instead of overloading the "phoneNumber" field.
            // Then we could remove this hack, and instead ask the CallerInfo
            // for a "user visible" form of the SIP address.
            String number = info.phoneNumber;
            if ((number != null) && number.startsWith("sip:")) {
                number = number.substring(4);
            }

            if (TextUtils.isEmpty(info.name)) {
                // No valid "name" in the CallerInfo, so fall back to
                // something else.
                // (Typically, we promote the phone number up to the "name" slot
                // onscreen, and possibly display a descriptive string in the
                // "number" slot.)
                if (TextUtils.isEmpty(number)) {
                    // No name *or* number!  Display a generic "unknown" string
                    // (or potentially some other default based on the presentation.)
                    displayName =  getPresentationString(presentation);
                    if (DBG) log("  ==> no name *or* number! displayName = " + displayName);
                } else if (presentation != Connection.PRESENTATION_ALLOWED) {
                    // This case should never happen since the network should never send a phone #
                    // AND a restricted presentation. However we leave it here in case of weird
                    // network behavior
                    displayName = getPresentationString(presentation);
                    if (DBG) log("  ==> presentation not allowed! displayName = " + displayName);
                } else if (!TextUtils.isEmpty(info.cnapName)) {
                    // No name, but we do have a valid CNAP name, so use that.
                    displayName = info.cnapName;
                    info.name = info.cnapName;
                    displayNumber = number;
                    if (DBG) log("  ==> cnapName available: displayName '"
                                 + displayName + "', displayNumber '" + displayNumber + "'");
                } else {
                    // No name; all we have is a number.  This is the typical
                    // case when an incoming call doesn't match any contact,
                    // or if you manually dial an outgoing number using the
                    // dialpad.

                    // Promote the phone number up to the "name" slot:
                    displayName = number;

                    // ...and use the "number" slot for a geographical description
                    // string if available (but only for incoming calls.)
                    if ((conn != null) && (conn.isIncoming())) {
                        // TODO (CallerInfoAsyncQuery cleanup): Fix the CallerInfo
                        // query to only do the geoDescription lookup in the first
                        // place for incoming calls.
                        displayNumber = info.geoDescription;  // may be null
                    }

                    if (DBG) log("  ==>  no name; falling back to number: displayName '"
                                 + displayName + "', displayNumber '" + displayNumber + "'");
                }
            } else {
                // We do have a valid "name" in the CallerInfo.  Display that
                // in the "name" slot, and the phone number in the "number" slot.
                if (presentation != Connection.PRESENTATION_ALLOWED) {
                    // This case should never happen since the network should never send a name
                    // AND a restricted presentation. However we leave it here in case of weird
                    // network behavior
                    displayName = getPresentationString(presentation);
                    if (DBG) log("  ==> valid name, but presentation not allowed!"
                                 + " displayName = " + displayName);
                } else {
                    displayName = info.name;
                    displayNumber = number;
                    label = info.phoneLabel;
                    if (DBG) log("  ==>  name is present in CallerInfo: displayName '"
                                 + displayName + "', displayNumber '" + displayNumber + "'");
                }
            }
            personUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, info.person_id);
            if (DBG) log("- got personUri: '" + personUri
                         + "', based on info.person_id: " + info.person_id);
        } else {
            displayName =  getPresentationString(presentation);
        }

        if (call.isGeneric()) {
            mName.setText(R.string.card_title_in_call);
        } else {
            mName.setText(displayName);
        }
        mName.setVisibility(View.VISIBLE);

        // Update mPhoto
        // if the temporary flag is set, we know we'll be getting another call after
        // the CallerInfo has been correctly updated.  So, we can skip the image
        // loading until then.

        // If the photoResource is filled in for the CallerInfo, (like with the
        // Emergency Number case), then we can just set the photo image without
        // requesting for an image load. Please refer to CallerInfoAsyncQuery.java
        // for cases where CallerInfo.photoResource may be set.  We can also avoid
        // the image load step if the image data is cached.
        if (isTemporary && (info == null || !info.isCachedPhotoCurrent)) {
            mPhoto.setVisibility(View.INVISIBLE);
        } else if (info != null && info.photoResource != 0){
            showImage(mPhoto, info.photoResource);
        } else if (!showCachedImage(mPhoto, info)) {
            // Load the image with a callback to update the image state.
            // Use the default unknown picture while the query is running.
            ContactsAsyncHelper.updateImageViewWithContactPhotoAsync(
                info, 0, this, call, getContext(), mPhoto, personUri, R.drawable.picture_unknown);
        }

        if (displayNumber != null && !call.isGeneric()) {
            mPhoneNumber.setText(displayNumber);
            mPhoneNumber.setVisibility(View.VISIBLE);
        } else {
            mPhoneNumber.setVisibility(View.GONE);
        }

        if (label != null && !call.isGeneric()) {
            mLabel.setText(label);
            mLabel.setVisibility(View.VISIBLE);
        } else {
            mLabel.setVisibility(View.GONE);
        }

        // Other text fields:
        updateCallTypeLabel(call);
        updateSocialStatus(socialStatusText, socialStatusBadge, call);  // Currently unused
    }

    private String getPresentationString(int presentation) {
        String name = getContext().getString(R.string.unknown);
        if (presentation == Connection.PRESENTATION_RESTRICTED) {
            name = getContext().getString(R.string.private_num);
        } else if (presentation == Connection.PRESENTATION_PAYPHONE) {
            name = getContext().getString(R.string.payphone);
        }
        return name;
    }

    /**
     * Updates the name / photo / number / label fields
     * for the special "conference call" state.
     *
     * If the current call has only a single connection, use
     * updateDisplayForPerson() instead.
     */
    private void updateDisplayForConference(Call call) {
        if (DBG) log("updateDisplayForConference()...");

        int phoneType = call.getPhone().getPhoneType();
        if (phoneType == Phone.PHONE_TYPE_CDMA) {
            // This state corresponds to both 3-Way merged call and
            // Call Waiting accepted call.
            // In this case we display the UI in a "generic" state, with
            // the generic "dialing" icon and no caller information,
            // because in this state in CDMA the user does not really know
            // which caller party he is talking to.
            showImage(mPhoto, R.drawable.picture_dialing);
            mName.setText(R.string.card_title_in_call);
        } else if ((phoneType == Phone.PHONE_TYPE_GSM)
                || (phoneType == Phone.PHONE_TYPE_SIP)) {
            // Normal GSM (or possibly SIP?) conference call.
            // Display the "conference call" image as the contact photo.
            // TODO: Better visual treatment for contact photos in a
            // conference call (see bug 1313252).
            showImage(mPhoto, R.drawable.picture_conference);
            mName.setText(R.string.card_title_conf_call);
        } else {
            throw new IllegalStateException("Unexpected phone type: " + phoneType);
        }

        mName.setVisibility(View.VISIBLE);

        // TODO: For a conference call, the "phone number" slot is specced
        // to contain a summary of who's on the call, like "Bill Foldes
        // and Hazel Nutt" or "Bill Foldes and 2 others".
        // But for now, just hide it:
        mPhoneNumber.setVisibility(View.GONE);
        mLabel.setVisibility(View.GONE);

        // Other text fields:
        updateCallTypeLabel(call);
        updateSocialStatus(null, null, null);  // socialStatus is never visible in this state

        // TODO: for a GSM conference call, since we do actually know who
        // you're talking to, consider also showing names / numbers /
        // photos of some of the people on the conference here, so you can
        // see that info without having to click "Manage conference".  We
        // probably have enough space to show info for 2 people, at least.
        //
        // To do this, our caller would pass us the activeConnections
        // list, and we'd call PhoneUtils.getCallerInfo() separately for
        // each connection.
    }

    /**
     * Updates the CallCard "photo" IFF the specified Call is in a state
     * that needs a special photo (like "busy" or "dialing".)
     *
     * If the current call does not require a special image in the "photo"
     * slot onscreen, don't do anything, since presumably the photo image
     * has already been set (to the photo of the person we're talking, or
     * the generic "picture_unknown" image, or the "conference call"
     * image.)
     */
    private void updatePhotoForCallState(Call call) {
        if (DBG) log("updatePhotoForCallState(" + call + ")...");
        int photoImageResource = 0;

        // Check for the (relatively few) telephony states that need a
        // special image in the "photo" slot.
        Call.State state = call.getState();
        switch (state) {
            case DISCONNECTED:
                // Display the special "busy" photo for BUSY or CONGESTION.
                // Otherwise (presumably the normal "call ended" state)
                // leave the photo alone.
                Connection c = call.getEarliestConnection();
                // if the connection is null, we assume the default case,
                // otherwise update the image resource normally.
                if (c != null) {
                    Connection.DisconnectCause cause = c.getDisconnectCause();
                    if ((cause == Connection.DisconnectCause.BUSY)
                        || (cause == Connection.DisconnectCause.CONGESTION)) {
                        photoImageResource = R.drawable.picture_busy;
                    }
                } else if (DBG) {
                    log("updatePhotoForCallState: connection is null, ignoring.");
                }

                // TODO: add special images for any other DisconnectCauses?
                break;

            case ALERTING:
            case DIALING:
            default:
                // Leave the photo alone in all other states.
                // If this call is an individual call, and the image is currently
                // displaying a state, (rather than a photo), we'll need to update
                // the image.
                // This is for the case where we've been displaying the state and
                // now we need to restore the photo.  This can happen because we
                // only query the CallerInfo once, and limit the number of times
                // the image is loaded. (So a state image may overwrite the photo
                // and we would otherwise have no way of displaying the photo when
                // the state goes away.)

                // if the photoResource field is filled-in in the Connection's
                // caller info, then we can just use that instead of requesting
                // for a photo load.

                // look for the photoResource if it is available.
                CallerInfo ci = null;
                {
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

                    if (conn != null) {
                        Object o = conn.getUserData();
                        if (o instanceof CallerInfo) {
                            ci = (CallerInfo) o;
                        } else if (o instanceof PhoneUtils.CallerInfoToken) {
                            ci = ((PhoneUtils.CallerInfoToken) o).currentInfo;
                        }
                    }
                }

                if (ci != null) {
                    photoImageResource = ci.photoResource;
                }

                // If no photoResource found, check to see if this is a conference call. If
                // it is not a conference call:
                //   1. Try to show the cached image
                //   2. If the image is not cached, check to see if a load request has been
                //      made already.
                //   3. If the load request has not been made [DISPLAY_DEFAULT], start the
                //      request and note that it has started by updating photo state with
                //      [DISPLAY_IMAGE].
                // Load requests started in (3) use a placeholder image of -1 to hide the
                // image by default.  Please refer to CallerInfoAsyncQuery.java for cases
                // where CallerInfo.photoResource may be set.
                if (photoImageResource == 0) {
                    if (!PhoneUtils.isConferenceCall(call)) {
                        if (!showCachedImage(mPhoto, ci) && (mPhotoTracker.getPhotoState() ==
                                ContactsAsyncHelper.ImageTracker.DISPLAY_DEFAULT)) {
                            ContactsAsyncHelper.updateImageViewWithContactPhotoAsync(ci,
                                    getContext(), mPhoto, mPhotoTracker.getPhotoUri(), -1);
                            mPhotoTracker.setPhotoState(
                                    ContactsAsyncHelper.ImageTracker.DISPLAY_IMAGE);
                        }
                    }
                } else {
                    showImage(mPhoto, photoImageResource);
                    mPhotoTracker.setPhotoState(ContactsAsyncHelper.ImageTracker.DISPLAY_IMAGE);
                    return;
                }
                break;
        }

        if (photoImageResource != 0) {
            if (DBG) log("- overrriding photo image: " + photoImageResource);
            showImage(mPhoto, photoImageResource);
            // Track the image state.
            mPhotoTracker.setPhotoState(ContactsAsyncHelper.ImageTracker.DISPLAY_DEFAULT);
        }
    }

    /**
     * Try to display the cached image from the callerinfo object.
     *
     *  @return true if we were able to find the image in the cache, false otherwise.
     */
    private static final boolean showCachedImage(ImageView view, CallerInfo ci) {
        if ((ci != null) && ci.isCachedPhotoCurrent) {
            if (ci.cachedPhoto != null) {
                showImage(view, ci.cachedPhoto);
            } else {
                showImage(view, R.drawable.picture_unknown);
            }
            return true;
        }
        return false;
    }

    /** Helper function to display the resource in the imageview AND ensure its visibility.*/
    private static final void showImage(ImageView view, int resource) {
        view.setImageResource(resource);
        view.setVisibility(View.VISIBLE);
    }

    /** Helper function to display the drawable in the imageview AND ensure its visibility.*/
    private static final void showImage(ImageView view, Drawable drawable) {
        view.setImageDrawable(drawable);
        view.setVisibility(View.VISIBLE);
    }

    /**
     * Sets the left and right margins of the specified ViewGroup (whose
     * LayoutParams object which must inherit from
     * ViewGroup.MarginLayoutParams.)
     *
     * TODO: Is there already a convenience method like this somewhere?
     */
    private void setSideMargins(ViewGroup vg, int margin) {
        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) vg.getLayoutParams();
        // Equivalent to setting android:layout_marginLeft/Right in XML
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        vg.setLayoutParams(lp);
    }

    /**
     * Returns the special card title used in emergency callback mode (ECM),
     * which shows your own phone number.
     */
    private String getECMCardTitle(Context context, Phone phone) {
        String rawNumber = phone.getLine1Number();  // may be null or empty
        String formattedNumber;
        if (!TextUtils.isEmpty(rawNumber)) {
            formattedNumber = PhoneNumberUtils.formatNumber(rawNumber);
        } else {
            formattedNumber = context.getString(R.string.unknown);
        }
        String titleFormat = context.getString(R.string.card_title_my_phone_number);
        return String.format(titleFormat, formattedNumber);
    }

    /**
     * Updates the "Call type" label, based on the current foreground call.
     * This is a special label and/or branding we display for certain
     * kinds of calls.
     *
     * (So far, this is used only for SIP calls, which get an
     * "Internet call" label.  TODO: But eventually, the telephony
     * layer might allow each pluggable "provider" to specify a string
     * and/or icon to be displayed here.)
     */
    private void updateCallTypeLabel(Call call) {
        int phoneType = (call != null) ? call.getPhone().getPhoneType() : Phone.PHONE_TYPE_NONE;
        if (phoneType == Phone.PHONE_TYPE_SIP) {
            mCallTypeLabel.setVisibility(View.VISIBLE);
            mCallTypeLabel.setText(R.string.incall_call_type_label_sip);
            mCallTypeLabel.setTextColor(mTextColorCallTypeSip);
            // If desired, we could also display a "badge" next to the label, as follows:
            //   mCallTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
            //           callTypeSpecificBadge, null, null, null);
            //   mCallTypeLabel.setCompoundDrawablePadding((int) (mDensity * 6));
        } else {
            mCallTypeLabel.setVisibility(View.GONE);
        }
    }

    /**
     * Updates the "social status" label with the specified text and
     * (optional) badge.
     */
    private void updateSocialStatus(String socialStatusText,
                                    Drawable socialStatusBadge,
                                    Call call) {
        // The socialStatus field is *only* visible while an incoming call
        // is ringing, never in any other call state.
        if ((socialStatusText != null)
                && (call != null)
                && call.isRinging()
                && !call.isGeneric()) {
            mSocialStatus.setVisibility(View.VISIBLE);
            mSocialStatus.setText(socialStatusText);
            mSocialStatus.setCompoundDrawablesWithIntrinsicBounds(
                    socialStatusBadge, null, null, null);
            mSocialStatus.setCompoundDrawablePadding((int) (mDensity * 6));
        } else {
            mSocialStatus.setVisibility(View.GONE);
        }
    }

    /**
     * Hides the top-level UI elements of the call card:  The "main
     * call card" element representing the current active or ringing call,
     * and also the info areas for "ongoing" or "on hold" calls in some
     * states.
     *
     * This is intended to be used in special states where the normal
     * in-call UI is totally replaced by some other UI, like OTA mode on a
     * CDMA device.
     *
     * To bring back the regular CallCard UI, just re-run the normal
     * updateState() call sequence.
     */
    public void hideCallCardElements() {
        mPrimaryCallInfo.setVisibility(View.GONE);
        mSecondaryCallInfo.setVisibility(View.GONE);
    }

    /*
     * Updates the hint (like "Rotate to answer") that we display while
     * the user is dragging the incoming call RotarySelector widget.
     */
    /* package */ void setIncomingCallWidgetHint(int hintTextResId, int hintColorResId) {
        mIncomingCallWidgetHintTextResId = hintTextResId;
        mIncomingCallWidgetHintColorResId = hintColorResId;
    }

    // Accessibility event support.
    // Since none of the CallCard elements are focusable, we need to manually
    // fill in the AccessibilityEvent here (so that the name / number / etc will
    // get pronounced by a screen reader, for example.)
    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        dispatchPopulateAccessibilityEvent(event, mCallStateLabel);
        dispatchPopulateAccessibilityEvent(event, mPhoto);
        dispatchPopulateAccessibilityEvent(event, mName);
        dispatchPopulateAccessibilityEvent(event, mPhoneNumber);
        dispatchPopulateAccessibilityEvent(event, mLabel);
        dispatchPopulateAccessibilityEvent(event, mSocialStatus);
        dispatchPopulateAccessibilityEvent(event, mSecondaryCallName);
        dispatchPopulateAccessibilityEvent(event, mSecondaryCallStatus);
        dispatchPopulateAccessibilityEvent(event, mSecondaryCallPhoto);
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

    /**
     * Simple Utility class that runs fading animations on specified views.
     */
    public static class Fade {
        private static final boolean FADE_DBG = false;
        private static final long DURATION = 250;  // msec

        // View tag that's set during the fade-out animation; see hide() and
        // isFadingOut().
        private static final int FADE_STATE_KEY = R.id.fadeState;
        private static final String FADING_OUT = "fading_out";

        /**
         * Sets the visibility of the specified view to View.VISIBLE and then
         * fades it in. If the view is already visible (and not in the middle
         * of a fade-out animation), this method will return without doing
         * anything.
         *
         * @param view The view to be faded in
         */
        public static void show(final View view) {
            if (FADE_DBG) log("Fade: SHOW view " + view + "...");
            if (FADE_DBG) log("Fade: - visibility = " + view.getVisibility());
            if ((view.getVisibility() != View.VISIBLE) || isFadingOut(view)) {
                view.animate().cancel();
                // ...and clear the FADE_STATE_KEY tag in case we just
                // canceled an in-progress fade-out animation.
                view.setTag(FADE_STATE_KEY, null);

                view.setAlpha(0);
                view.setVisibility(View.VISIBLE);
                view.animate().setDuration(DURATION);
                view.animate().alpha(1);
                if (FADE_DBG) log("Fade: ==> SHOW " + view
                                  + " DONE.  Set visibility = " + View.VISIBLE);
            } else {
                if (FADE_DBG) log("Fade: ==> Ignoring, already visible AND not fading out.");
            }
        }

        /**
         * Fades out the specified view and then sets its visibility to the
         * specified value (either View.INVISIBLE or View.GONE). If the view
         * is not currently visibile, the method will return without doing
         * anything.
         *
         * Note that *during* the fade-out the view itself will still have
         * visibility View.VISIBLE, although the isFadingOut() method will
         * return true (in case the UI code needs to detect this state.)
         *
         * @param view The view to be hidden
         * @param visibility The value to which the view's visibility will be
         *                   set after it fades out.
         * Must be either View.VISIBLE or View.INVISIBLE.
         */
        public static void hide(final View view, final int visibility) {
            if (FADE_DBG) log("Fade: HIDE view " + view + "...");
            if (view.getVisibility() == View.VISIBLE &&
                (visibility == View.INVISIBLE || visibility == View.GONE)) {

                // Use a view tag to mark this view as being in the middle
                // of a fade-out animation.
                view.setTag(FADE_STATE_KEY, FADING_OUT);

                view.animate().cancel();
                view.animate().setDuration(DURATION);
                view.animate().alpha(0f).setListener(new AnimatorListenerAdapter() {
                        public void onAnimationEnd(Animator animation) {
                            view.setAlpha(1);
                            view.setVisibility(visibility);
                            view.animate().setListener(null);
                            // ...and we're done with the fade-out, so clear the view tag.
                            view.setTag(FADE_STATE_KEY, null);
                            if (FADE_DBG) log("Fade: HIDE " + view
                                              + " DONE.  Set visibility = " + visibility);
                        }
                    });
            }
        }

        /**
         * @return true if the specified view is currently in the middle
         * of a fade-out animation.  (During the fade-out, the view's
         * visibility is still VISIBLE, although in many cases the UI
         * should behave as if it's already invisible or gone.  This
         * method allows the UI code to detect that state.)
         *
         * @see hide()
         */
        public static boolean isFadingOut(final View view) {
            if (FADE_DBG) {
                log("Fade: isFadingOut view " + view + "...");
                log("Fade:   - getTag() returns: " + view.getTag(FADE_STATE_KEY));
                log("Fade:   - returning: " + (view.getTag(FADE_STATE_KEY) == FADING_OUT));
            }
            return (view.getTag(FADE_STATE_KEY) == FADING_OUT);
        }

    }


    // Debugging / testing code

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

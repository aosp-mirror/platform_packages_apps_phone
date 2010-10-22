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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.AsyncQueryHandler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.CallManager;


/**
 * NotificationManager-related utility code for the Phone app.
 */
public class NotificationMgr implements CallerInfoAsyncQuery.OnQueryCompleteListener{
    private static final String LOG_TAG = "NotificationMgr";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    private static final String[] CALL_LOG_PROJECTION = new String[] {
        Calls._ID,
        Calls.NUMBER,
        Calls.DATE,
        Calls.DURATION,
        Calls.TYPE,
    };

    // notification types
    static final int MISSED_CALL_NOTIFICATION = 1;
    static final int IN_CALL_NOTIFICATION = 2;
    static final int MMI_NOTIFICATION = 3;
    static final int NETWORK_SELECTION_NOTIFICATION = 4;
    static final int VOICEMAIL_NOTIFICATION = 5;
    static final int CALL_FORWARD_NOTIFICATION = 6;
    static final int DATA_DISCONNECTED_ROAMING_NOTIFICATION = 7;
    static final int SELECTED_OPERATOR_FAIL_NOTIFICATION = 8;

    private static NotificationMgr sMe = null;
    private Phone mPhone;
    private CallManager mCM;

    private Context mContext;
    private NotificationManager mNotificationMgr;
    private StatusBarManager mStatusBar;
    private StatusBarMgr mStatusBarMgr;
    private Toast mToast;
    private boolean mShowingSpeakerphoneIcon;
    private boolean mShowingMuteIcon;

    // used to track the missed call counter, default to 0.
    private int mNumberMissedCalls = 0;

    // Currently-displayed resource IDs for some status bar icons (or zero
    // if no notification is active):
    private int mInCallResId;

    // used to track the notification of selected network unavailable
    private boolean mSelectedUnavailableNotify = false;

    // Retry params for the getVoiceMailNumber() call; see updateMwi().
    private static final int MAX_VM_NUMBER_RETRIES = 5;
    private static final int VM_NUMBER_RETRY_DELAY_MILLIS = 10000;
    private int mVmNumberRetriesRemaining = MAX_VM_NUMBER_RETRIES;

    // Query used to look up caller-id info for the "call log" notification.
    private QueryHandler mQueryHandler = null;
    private static final int CALL_LOG_TOKEN = -1;
    private static final int CONTACT_TOKEN = -2;

    NotificationMgr(Context context) {
        mContext = context;
        mNotificationMgr = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        mStatusBar = (StatusBarManager) context.getSystemService(Context.STATUS_BAR_SERVICE);

        PhoneApp app = PhoneApp.getInstance();
        mPhone = app.phone;
        mCM = app.mCM;
    }

    static void init(Context context) {
        sMe = new NotificationMgr(context);

        // update the notifications that need to be touched at startup.
        sMe.updateNotificationsAtStartup();
    }

    static NotificationMgr getDefault() {
        return sMe;
    }

    /**
     * Class that controls the status bar.  This class maintains a set
     * of state and acts as an interface between the Phone process and
     * the Status bar.  All interaction with the status bar should be
     * though the methods contained herein.
     */

    /**
     * Factory method
     */
    StatusBarMgr getStatusBarMgr() {
        if (mStatusBarMgr == null) {
            mStatusBarMgr = new StatusBarMgr();
        }
        return mStatusBarMgr;
    }

    /**
     * StatusBarMgr implementation
     */
    class StatusBarMgr {
        // current settings
        private boolean mIsNotificationEnabled = true;
        private boolean mIsExpandedViewEnabled = true;

        private StatusBarMgr () {
        }

        /**
         * Sets the notification state (enable / disable
         * vibrating notifications) for the status bar,
         * updates the status bar service if there is a change.
         * Independent of the remaining Status Bar
         * functionality, including icons and expanded view.
         */
        void enableNotificationAlerts(boolean enable) {
            if (mIsNotificationEnabled != enable) {
                mIsNotificationEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Sets the ability to expand the notifications for the
         * status bar, updates the status bar service if there
         * is a change. Independent of the remaining Status Bar
         * functionality, including icons and notification
         * alerts.
         */
        void enableExpandedView(boolean enable) {
            if (mIsExpandedViewEnabled != enable) {
                mIsExpandedViewEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Method to synchronize status bar state with our current
         * state.
         */
        void updateStatusBar() {
            int state = StatusBarManager.DISABLE_NONE;

            if (!mIsExpandedViewEnabled) {
                state |= StatusBarManager.DISABLE_EXPAND;
            }

            if (!mIsNotificationEnabled) {
                state |= StatusBarManager.DISABLE_NOTIFICATION_ALERTS;
            }

            // send the message to the status bar manager.
            if (DBG) log("updating status bar state: " + state);
            mStatusBar.disable(state);
        }
    }

    /**
     * Makes sure phone-related notifications are up to date on a
     * freshly-booted device.
     */
    private void updateNotificationsAtStartup() {
        if (DBG) log("updateNotificationsAtStartup()...");

        // instantiate query handler
        mQueryHandler = new QueryHandler(mContext.getContentResolver());

        // setup query spec, look for all Missed calls that are new.
        StringBuilder where = new StringBuilder("type=");
        where.append(Calls.MISSED_TYPE);
        where.append(" AND new=1");

        // start the query
        if (DBG) log("- start call log query...");
        mQueryHandler.startQuery(CALL_LOG_TOKEN, null, Calls.CONTENT_URI,  CALL_LOG_PROJECTION,
                where.toString(), null, Calls.DEFAULT_SORT_ORDER);

        // Update (or cancel) the in-call notification
        if (DBG) log("- updating in-call notification at startup...");
        updateInCallNotification();

        // Depend on android.app.StatusBarManager to be set to
        // disable(DISABLE_NONE) upon startup.  This will be the
        // case even if the phone app crashes.
    }

    /** The projection to use when querying the phones table */
    static final String[] PHONES_PROJECTION = new String[] {
        PhoneLookup.NUMBER,
        PhoneLookup.DISPLAY_NAME
    };

    /**
     * Class used to run asynchronous queries to re-populate
     * the notifications we care about.
     */
    private class QueryHandler extends AsyncQueryHandler {

        /**
         * Used to store relevant fields for the Missed Call
         * notifications.
         */
        private class NotificationInfo {
            public String name;
            public String number;
            public String label;
            public long date;
        }

        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        /**
         * Handles the query results.  There are really 2 steps to this,
         * similar to what happens in RecentCallsListActivity.
         *  1. Find the list of missed calls
         *  2. For each call, run a query to retrieve the caller's name.
         */
        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            // TODO: it would be faster to use a join here, but for the purposes
            // of this small record set, it should be ok.

            // Note that CursorJoiner is not useable here because the number
            // comparisons are not strictly equals; the comparisons happen in
            // the SQL function PHONE_NUMBERS_EQUAL, which is not available for
            // the CursorJoiner.

            // Executing our own query is also feasible (with a join), but that
            // will require some work (possibly destabilizing) in Contacts
            // Provider.

            // At this point, we will execute subqueries on each row just as
            // RecentCallsListActivity.java does.
            switch (token) {
                case CALL_LOG_TOKEN:
                    if (DBG) log("call log query complete.");

                    // initial call to retrieve the call list.
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            // for each call in the call log list, create
                            // the notification object and query contacts
                            NotificationInfo n = getNotificationInfo (cursor);

                            if (DBG) log("query contacts for number: " + n.number);

                            mQueryHandler.startQuery(CONTACT_TOKEN, n,
                                    Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, n.number),
                                    PHONES_PROJECTION, null, null, PhoneLookup.NUMBER);
                        }

                        if (DBG) log("closing call log cursor.");
                        cursor.close();
                    }
                    break;
                case CONTACT_TOKEN:
                    if (DBG) log("contact query complete.");

                    // subqueries to get the caller name.
                    if ((cursor != null) && (cookie != null)){
                        NotificationInfo n = (NotificationInfo) cookie;

                        if (cursor.moveToFirst()) {
                            // we have contacts data, get the name.
                            if (DBG) log("contact :" + n.name + " found for phone: " + n.number);
                            n.name = cursor.getString(
                                    cursor.getColumnIndexOrThrow(PhoneLookup.DISPLAY_NAME));
                        }

                        // send the notification
                        if (DBG) log("sending notification.");
                        notifyMissedCall(n.name, n.number, n.label, n.date);

                        if (DBG) log("closing contact cursor.");
                        cursor.close();
                    }
                    break;
                default:
            }
        }

        /**
         * Factory method to generate a NotificationInfo object given a
         * cursor from the call log table.
         */
        private final NotificationInfo getNotificationInfo(Cursor cursor) {
            NotificationInfo n = new NotificationInfo();
            n.name = null;
            n.number = cursor.getString(cursor.getColumnIndexOrThrow(Calls.NUMBER));
            n.label = cursor.getString(cursor.getColumnIndexOrThrow(Calls.TYPE));
            n.date = cursor.getLong(cursor.getColumnIndexOrThrow(Calls.DATE));

            // make sure we update the number depending upon saved values in
            // CallLog.addCall().  If either special values for unknown or
            // private number are detected, we need to hand off the message
            // to the missed call notification.
            if ( (n.number.equals(CallerInfo.UNKNOWN_NUMBER)) ||
                 (n.number.equals(CallerInfo.PRIVATE_NUMBER)) ||
                 (n.number.equals(CallerInfo.PAYPHONE_NUMBER)) ) {
                n.number = null;
            }

            if (DBG) log("NotificationInfo constructed for number: " + n.number);

            return n;
        }
    }

    /**
     * Configures a Notification to emit the blinky green message-waiting/
     * missed-call signal.
     */
    private static void configureLedNotification(Notification note) {
        note.flags |= Notification.FLAG_SHOW_LIGHTS;
        note.defaults |= Notification.DEFAULT_LIGHTS;
    }

    /**
     * Displays a notification about a missed call.
     *
     * @param nameOrNumber either the contact name, or the phone number if no contact
     * @param label the label of the number if nameOrNumber is a name, null if it is a number
     */
    void notifyMissedCall(String name, String number, String label, long date) {
        // title resource id
        int titleResId;
        // the text in the notification's line 1 and 2.
        String expandedText, callName;

        // increment number of missed calls.
        mNumberMissedCalls++;

        // get the name for the ticker text
        // i.e. "Missed call from <caller name or number>"
        if (name != null && TextUtils.isGraphic(name)) {
            callName = name;
        } else if (!TextUtils.isEmpty(number)){
            callName = number;
        } else {
            // use "unknown" if the caller is unidentifiable.
            callName = mContext.getString(R.string.unknown);
        }

        // display the first line of the notification:
        // 1 missed call: call name
        // more than 1 missed call: <number of calls> + "missed calls"
        if (mNumberMissedCalls == 1) {
            titleResId = R.string.notification_missedCallTitle;
            expandedText = callName;
        } else {
            titleResId = R.string.notification_missedCallsTitle;
            expandedText = mContext.getString(R.string.notification_missedCallsMsg,
                    mNumberMissedCalls);
        }

        // create the target call log intent
        final Intent intent = PhoneApp.createCallLogIntent();

        // make the notification
        Notification note = new Notification(mContext, // context
                android.R.drawable.stat_notify_missed_call, // icon
                mContext.getString(R.string.notification_missedCallTicker, callName), // tickerText
                date, // when
                mContext.getText(titleResId), // expandedTitle
                expandedText, // expandedText
                intent // contentIntent
                );
        configureLedNotification(note);
        mNotificationMgr.notify(MISSED_CALL_NOTIFICATION, note);
    }

    void cancelMissedCallNotification() {
        // reset the number of missed calls to 0.
        mNumberMissedCalls = 0;
        mNotificationMgr.cancel(MISSED_CALL_NOTIFICATION);
    }

    void notifySpeakerphone() {
        if (!mShowingSpeakerphoneIcon) {
            mStatusBar.setIcon("speakerphone", android.R.drawable.stat_sys_speakerphone, 0);
            mShowingSpeakerphoneIcon = true;
        }
    }

    void cancelSpeakerphone() {
        if (mShowingSpeakerphoneIcon) {
            mStatusBar.removeIcon("speakerphone");
            mShowingSpeakerphoneIcon = false;
        }
    }

    /**
     * Calls either notifySpeakerphone() or cancelSpeakerphone() based on
     * the actual current state of the speaker.
     */
    void updateSpeakerNotification() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        if ((mPhone.getState() == Phone.State.OFFHOOK) && audioManager.isSpeakerphoneOn()) {
            if (DBG) log("updateSpeakerNotification: speaker ON");
            notifySpeakerphone();
        } else {
            if (DBG) log("updateSpeakerNotification: speaker OFF (or not offhook)");
            cancelSpeakerphone();
        }
    }

    private void notifyMute() {
        if (!mShowingMuteIcon) {
            mStatusBar.setIcon("mute", android.R.drawable.stat_notify_call_mute, 0);
            mShowingMuteIcon = true;
        }
    }

    private void cancelMute() {
        if (mShowingMuteIcon) {
            mStatusBar.removeIcon("mute");
            mShowingMuteIcon = false;
        }
    }

    /**
     * Calls either notifyMute() or cancelMute() based on
     * the actual current mute state of the Phone.
     */
    void updateMuteNotification() {
        if ((mCM.getState() == Phone.State.OFFHOOK) && PhoneUtils.getMute()) {
            if (DBG) log("updateMuteNotification: MUTED");
            notifyMute();
        } else {
            if (DBG) log("updateMuteNotification: not muted (or not offhook)");
            cancelMute();
        }
    }

    /**
     * Updates the phone app's status bar notification based on the
     * current telephony state, or cancels the notification if the phone
     * is totally idle.
     */
    void updateInCallNotification() {
        int resId;
        if (DBG) log("updateInCallNotification()...");

        if (mCM.getState() == Phone.State.IDLE) {
            cancelInCall();
            return;
        }

        final PhoneApp app = PhoneApp.getInstance();
        final boolean hasRingingCall = mCM.hasActiveRingingCall();
        final boolean hasActiveCall = mCM.hasActiveFgCall();
        final boolean hasHoldingCall = mCM.hasActiveBgCall();
        if (DBG) {
            log("  - hasRingingCall = " + hasRingingCall);
            log("  - hasActiveCall = " + hasActiveCall);
            log("  - hasHoldingCall = " + hasHoldingCall);
        }

        // Display the appropriate icon in the status bar,
        // based on the current phone and/or bluetooth state.

        boolean enhancedVoicePrivacy = app.notifier.getCdmaVoicePrivacyState();
        if (DBG) log("updateInCallNotification: enhancedVoicePrivacy = " + enhancedVoicePrivacy);

        if (hasRingingCall) {
            // There's an incoming ringing call.
            resId = R.drawable.stat_sys_phone_call_ringing;
        } else if (!hasActiveCall && hasHoldingCall) {
            // There's only one call, and it's on hold.
            if (enhancedVoicePrivacy) {
                resId = R.drawable.stat_sys_vp_phone_call_on_hold;
            } else {
                resId = R.drawable.stat_sys_phone_call_on_hold;
            }
        } else if (app.showBluetoothIndication()) {
            // Bluetooth is active.
            if (enhancedVoicePrivacy) {
                resId = R.drawable.stat_sys_vp_phone_call_bluetooth;
            } else {
                resId = R.drawable.stat_sys_phone_call_bluetooth;
            }
        } else {
            if (enhancedVoicePrivacy) {
                resId = R.drawable.stat_sys_vp_phone_call;
            } else {
                resId = R.drawable.stat_sys_phone_call;
            }
        }

        // Note we can't just bail out now if (resId == mInCallResId),
        // since even if the status icon hasn't changed, some *other*
        // notification-related info may be different from the last time
        // we were here (like the caller-id info of the foreground call,
        // if the user swapped calls...)

        if (DBG) log("- Updating status bar icon: resId = " + resId);
        mInCallResId = resId;

        // The icon in the expanded view is the same as in the status bar.
        int expandedViewIcon = mInCallResId;

        // Even if both lines are in use, we only show a single item in
        // the expanded Notifications UI.  It's labeled "Ongoing call"
        // (or "On hold" if there's only one call, and it's on hold.)
        // Also, we don't have room to display caller-id info from two
        // different calls.  So if both lines are in use, display info
        // from the foreground call.  And if there's a ringing call,
        // display that regardless of the state of the other calls.

        Call currentCall;
        if (hasRingingCall) {
            currentCall = mCM.getFirstActiveRingingCall();
        } else if (hasActiveCall) {
            currentCall = mCM.getActiveFgCall();
        } else {
            currentCall = mCM.getFirstActiveBgCall();
        }
        Connection currentConn = currentCall.getEarliestConnection();

        Notification notification = new Notification();
        notification.icon = mInCallResId;
        notification.flags |= Notification.FLAG_ONGOING_EVENT;

        // PendingIntent that can be used to launch the InCallScreen.  The
        // system fires off this intent if the user pulls down the windowshade
        // and clicks the notification's expanded view.  It's also used to
        // launch the InCallScreen immediately when when there's an incoming
        // call (see the "fullScreenIntent" field below).
        PendingIntent inCallPendingIntent =
                PendingIntent.getActivity(mContext, 0,
                                          PhoneApp.createInCallIntent(), 0);
        notification.contentIntent = inCallPendingIntent;

        // When expanded, the "Ongoing call" notification is (visually)
        // different from most other Notifications, so we need to use a
        // custom view hierarchy.
        // Our custom view, which includes an icon (either "ongoing call" or
        // "on hold") and 2 lines of text: (1) the label (either "ongoing
        // call" with time counter, or "on hold), and (2) the compact name of
        // the current Connection.
        RemoteViews contentView = new RemoteViews(mContext.getPackageName(),
                                                   R.layout.ongoing_call_notification);
        contentView.setImageViewResource(R.id.icon, expandedViewIcon);

        // if the connection is valid, then build what we need for the
        // first line of notification information, and start the chronometer.
        // Otherwise, don't bother and just stick with line 2.
        if (currentConn != null) {
            // Determine the "start time" of the current connection, in terms
            // of the SystemClock.elapsedRealtime() timebase (which is what
            // the Chronometer widget needs.)
            //   We can't use currentConn.getConnectTime(), because (1) that's
            // in the currentTimeMillis() time base, and (2) it's zero when
            // the phone first goes off hook, since the getConnectTime counter
            // doesn't start until the DIALING -> ACTIVE transition.
            //   Instead we start with the current connection's duration,
            // and translate that into the elapsedRealtime() timebase.
            long callDurationMsec = currentConn.getDurationMillis();
            long chronometerBaseTime = SystemClock.elapsedRealtime() - callDurationMsec;

            // Line 1 of the expanded view (in bold text):
            String expandedViewLine1;
            if (hasRingingCall) {
                // Incoming call is ringing.
                // Note this isn't a format string!  (We want "Incoming call"
                // here, not "Incoming call (1:23)".)  But that's OK; if you
                // call String.format() with more arguments than format
                // specifiers, the extra arguments are ignored.
                expandedViewLine1 = mContext.getString(R.string.notification_incoming_call);
            } else if (hasHoldingCall && !hasActiveCall) {
                // Only one call, and it's on hold.
                // Note this isn't a format string either (see comment above.)
                expandedViewLine1 = mContext.getString(R.string.notification_on_hold);
            } else {
                // Normal ongoing call.
                // Format string with a "%s" where the current call time should go.
                expandedViewLine1 = mContext.getString(R.string.notification_ongoing_call_format);
            }

            if (DBG) log("- Updating expanded view: line 1 '" + /*expandedViewLine1*/ "xxxxxxx" + "'");

            // Text line #1 is actually a Chronometer, not a plain TextView.
            // We format the elapsed time of the current call into a line like
            // "Ongoing call (01:23)".
            contentView.setChronometer(R.id.text1,
                                       chronometerBaseTime,
                                       expandedViewLine1,
                                       true);
        } else if (DBG) {
            Log.w(LOG_TAG, "updateInCallNotification: null connection, can't set exp view line 1.");
        }

        // display conference call string if this call is a conference
        // call, otherwise display the connection information.

        // Line 2 of the expanded view (smaller text).  This is usually a
        // contact name or phone number.
        String expandedViewLine2 = "";
        // TODO: it may not make sense for every point to make separate
        // checks for isConferenceCall, so we need to think about
        // possibly including this in startGetCallerInfo or some other
        // common point.
        if (PhoneUtils.isConferenceCall(currentCall)) {
            // if this is a conference call, just use that as the caller name.
            expandedViewLine2 = mContext.getString(R.string.card_title_conf_call);
        } else {
            // If necessary, start asynchronous query to do the caller-id lookup.
            PhoneUtils.CallerInfoToken cit =
                PhoneUtils.startGetCallerInfo(mContext, currentCall, this, this);
            expandedViewLine2 = PhoneUtils.getCompactNameFromCallerInfo(cit.currentInfo, mContext);
            // Note: For an incoming call, the very first time we get here we
            // won't have a contact name yet, since we only just started the
            // caller-id query.  So expandedViewLine2 will start off as a raw
            // phone number, but we'll update it very quickly when the query
            // completes (see onQueryComplete() below.)
        }

        if (DBG) log("- Updating expanded view: line 2 '" + /*expandedViewLine2*/ "xxxxxxx" + "'");
        contentView.setTextViewText(R.id.text2, expandedViewLine2);
        notification.contentView = contentView;

        // TODO: We also need to *update* this notification in some cases,
        // like when a call ends on one line but the other is still in use
        // (ie. make sure the caller info here corresponds to the active
        // line), and maybe even when the user swaps calls (ie. if we only
        // show info here for the "current active call".)

        // Activate a couple of special Notification features if an
        // incoming call is ringing:
        if (hasRingingCall) {
            // We actually want to launch the incoming call UI at this point
            // (rather than just posting a notification to the status bar).
            // Setting fullScreenIntent will cause the InCallScreen to be
            // launched immediately.
            if (DBG) log("- Setting fullScreenIntent: " + inCallPendingIntent);
            notification.fullScreenIntent = inCallPendingIntent;

            // Ugly hack alert:
            //
            // The NotificationManager has the (undocumented) behavior
            // that it will *ignore* the fullScreenIntent field if you
            // post a new Notification that matches the ID of one that's
            // already active.  Unfortunately this is exactly what happens
            // when you get an incoming call-waiting call:  the
            // "ongoing call" notification is already visible, so the
            // InCallScreen won't get launched in this case!
            // (The result: if you bail out of the in-call UI while on a
            // call and then get a call-waiting call, the incoming call UI
            // won't come up automatically.)
            //
            // The workaround is to just notice this exact case (this is a
            // call-waiting call *and* the InCallScreen is not in the
            // foreground) and manually cancel the in-call notification
            // before (re)posting it.
            //
            // TODO: there should be a cleaner way of avoiding this
            // problem (see discussion in bug 3184149.)
            Call ringingCall = mCM.getFirstActiveRingingCall();
            if ((ringingCall.getState() == Call.State.WAITING) && !app.isShowingCallScreen()) {
                Log.i(LOG_TAG, "updateInCallNotification: call-waiting! force relaunch...");
                // Cancel the IN_CALL_NOTIFICATION immediately before
                // (re)posting it; this seems to force the
                // NotificationManager to launch the fullScreenIntent.
                mNotificationMgr.cancel(IN_CALL_NOTIFICATION);
            }
        }

        if (DBG) log("Notifying IN_CALL_NOTIFICATION: " + notification);
        mNotificationMgr.notify(IN_CALL_NOTIFICATION,
                                notification);

        // Finally, refresh the mute and speakerphone notifications (since
        // some phone state changes can indirectly affect the mute and/or
        // speaker state).
        updateSpeakerNotification();
        updateMuteNotification();
    }

    /**
     * Implemented for CallerInfoAsyncQuery.OnQueryCompleteListener interface.
     * refreshes the contentView when called.
     */
    public void onQueryComplete(int token, Object cookie, CallerInfo ci){
        if (DBG) log("CallerInfo query complete (for NotificationMgr), "
                     + "updating in-call notification..");
        if (DBG) log("- cookie: " + cookie);
        if (DBG) log("- ci: " + ci);

        if (cookie == this) {
            // Ok, this is the caller-id query we fired off in
            // updateInCallNotification(), presumably when an incoming call
            // first appeared.  If the caller-id info matched any contacts,
            // compactName should now be a real person name rather than a raw
            // phone number:
            if (DBG) log("- compactName is now: "
                         + PhoneUtils.getCompactNameFromCallerInfo(ci, mContext));

            // Now that our CallerInfo object has been fully filled-in,
            // refresh the in-call notification.
            if (DBG) log("- updating notification after query complete...");
            updateInCallNotification();
        } else {
            Log.w(LOG_TAG, "onQueryComplete: caller-id query from unknown source! "
                  + "cookie = " + cookie);
        }
    }

    private void cancelInCall() {
        if (DBG) log("cancelInCall()...");
        cancelMute();
        cancelSpeakerphone();
        mNotificationMgr.cancel(IN_CALL_NOTIFICATION);
        mInCallResId = 0;
    }

    void cancelCallInProgressNotification() {
        if (DBG) log("cancelCallInProgressNotification()...");
        if (mInCallResId == 0) {
            return;
        }

        if (DBG) log("cancelCallInProgressNotification: " + mInCallResId);
        cancelInCall();
    }

    /**
     * Updates the message waiting indicator (voicemail) notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateMwi(boolean visible) {
        if (DBG) log("updateMwi(): " + visible);
        if (visible) {
            int resId = android.R.drawable.stat_notify_voicemail;

            // This Notification can get a lot fancier once we have more
            // information about the current voicemail messages.
            // (For example, the current voicemail system can't tell
            // us the caller-id or timestamp of a message, or tell us the
            // message count.)

            // But for now, the UI is ultra-simple: if the MWI indication
            // is supposed to be visible, just show a single generic
            // notification.

            String notificationTitle = mContext.getString(R.string.notification_voicemail_title);
            String vmNumber = mPhone.getVoiceMailNumber();
            if (DBG) log("- got vm number: '" + vmNumber + "'");

            // Watch out: vmNumber may be null, for two possible reasons:
            //
            //   (1) This phone really has no voicemail number
            //
            //   (2) This phone *does* have a voicemail number, but
            //       the SIM isn't ready yet.
            //
            // Case (2) *does* happen in practice if you have voicemail
            // messages when the device first boots: we get an MWI
            // notification as soon as we register on the network, but the
            // SIM hasn't finished loading yet.
            //
            // So handle case (2) by retrying the lookup after a short
            // delay.

            if ((vmNumber == null) && !mPhone.getIccRecordsLoaded()) {
                if (DBG) log("- Null vm number: SIM records not loaded (yet)...");

                // TODO: rather than retrying after an arbitrary delay, it
                // would be cleaner to instead just wait for a
                // SIM_RECORDS_LOADED notification.
                // (Unfortunately right now there's no convenient way to
                // get that notification in phone app code.  We'd first
                // want to add a call like registerForSimRecordsLoaded()
                // to Phone.java and GSMPhone.java, and *then* we could
                // listen for that in the CallNotifier class.)

                // Limit the number of retries (in case the SIM is broken
                // or missing and can *never* load successfully.)
                if (mVmNumberRetriesRemaining-- > 0) {
                    if (DBG) log("  - Retrying in " + VM_NUMBER_RETRY_DELAY_MILLIS + " msec...");
                    PhoneApp.getInstance().notifier.sendMwiChangedDelayed(
                            VM_NUMBER_RETRY_DELAY_MILLIS);
                    return;
                } else {
                    Log.w(LOG_TAG, "NotificationMgr.updateMwi: getVoiceMailNumber() failed after "
                          + MAX_VM_NUMBER_RETRIES + " retries; giving up.");
                    // ...and continue with vmNumber==null, just as if the
                    // phone had no VM number set up in the first place.
                }
            }

            if (TelephonyCapabilities.supportsVoiceMessageCount(mPhone)) {
                int vmCount = mPhone.getVoiceMessageCount();
                String titleFormat = mContext.getString(R.string.notification_voicemail_title_count);
                notificationTitle = String.format(titleFormat, vmCount);
            }

            String notificationText;
            if (TextUtils.isEmpty(vmNumber)) {
                notificationText = mContext.getString(
                        R.string.notification_voicemail_no_vm_number);
            } else {
                notificationText = String.format(
                        mContext.getString(R.string.notification_voicemail_text_format),
                        PhoneNumberUtils.formatNumber(vmNumber));
            }

            Intent intent = new Intent(Intent.ACTION_CALL,
                    Uri.fromParts("voicemail", "", null));
            PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

            Notification notification = new Notification(
                    resId,  // icon
                    null, // tickerText
                    System.currentTimeMillis()  // Show the time the MWI notification came in,
                                                // since we don't know the actual time of the
                                                // most recent voicemail message
                    );
            notification.setLatestEventInfo(
                    mContext,  // context
                    notificationTitle,  // contentTitle
                    notificationText,  // contentText
                    pendingIntent  // contentIntent
                    );
            notification.defaults |= Notification.DEFAULT_SOUND;
            notification.flags |= Notification.FLAG_NO_CLEAR;
            configureLedNotification(notification);
            mNotificationMgr.notify(VOICEMAIL_NOTIFICATION, notification);
        } else {
            mNotificationMgr.cancel(VOICEMAIL_NOTIFICATION);
        }
    }

    /**
     * Updates the message call forwarding indicator notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateCfi(boolean visible) {
        if (DBG) log("updateCfi(): " + visible);
        if (visible) {
            // If Unconditional Call Forwarding (forward all calls) for VOICE
            // is enabled, just show a notification.  We'll default to expanded
            // view for now, so the there is less confusion about the icon.  If
            // it is deemed too weird to have CF indications as expanded views,
            // then we'll flip the flag back.

            // TODO: We may want to take a look to see if the notification can
            // display the target to forward calls to.  This will require some
            // effort though, since there are multiple layers of messages that
            // will need to propagate that information.

            Notification notification;
            final boolean showExpandedNotification = true;
            if (showExpandedNotification) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.android.phone",
                        "com.android.phone.CallFeaturesSetting");

                notification = new Notification(
                        mContext,  // context
                        R.drawable.stat_sys_phone_call_forward,  // icon
                        null, // tickerText
                        0,  // The "timestamp" of this notification is meaningless;
                            // we only care about whether CFI is currently on or not.
                        mContext.getString(R.string.labelCF), // expandedTitle
                        mContext.getString(R.string.sum_cfu_enabled_indicator),  // expandedText
                        intent // contentIntent
                        );

            } else {
                notification = new Notification(
                        R.drawable.stat_sys_phone_call_forward,  // icon
                        null,  // tickerText
                        System.currentTimeMillis()  // when
                        );
            }

            notification.flags |= Notification.FLAG_ONGOING_EVENT;  // also implies FLAG_NO_CLEAR

            mNotificationMgr.notify(
                    CALL_FORWARD_NOTIFICATION,
                    notification);
        } else {
            mNotificationMgr.cancel(CALL_FORWARD_NOTIFICATION);
        }
    }

    /**
     * Shows the "data disconnected due to roaming" notification, which
     * appears when you lose data connectivity because you're roaming and
     * you have the "data roaming" feature turned off.
     */
    /* package */ void showDataDisconnectedRoaming() {
        if (DBG) log("showDataDisconnectedRoaming()...");

        Intent intent = new Intent(mContext,
                                   Settings.class);  // "Mobile network settings" screen

        Notification notification = new Notification(
                mContext,  // context
                android.R.drawable.stat_sys_warning,  // icon
                null, // tickerText
                System.currentTimeMillis(),
                mContext.getString(R.string.roaming), // expandedTitle
                mContext.getString(R.string.roaming_reenable_message),  // expandedText
                intent // contentIntent
                );
        mNotificationMgr.notify(
                DATA_DISCONNECTED_ROAMING_NOTIFICATION,
                notification);
    }

    /**
     * Turns off the "data disconnected due to roaming" notification.
     */
    /* package */ void hideDataDisconnectedRoaming() {
        if (DBG) log("hideDataDisconnectedRoaming()...");
        mNotificationMgr.cancel(DATA_DISCONNECTED_ROAMING_NOTIFICATION);
    }

    /**
     * Display the network selection "no service" notification
     * @param operator is the numeric operator number
     */
    private void showNetworkSelection(String operator) {
        if (DBG) log("showNetworkSelection(" + operator + ")...");

        String titleText = mContext.getString(
                R.string.notification_network_selection_title);
        String expandedText = mContext.getString(
                R.string.notification_network_selection_text, operator);

        Notification notification = new Notification();
        notification.icon = android.R.drawable.stat_sys_warning;
        notification.when = 0;
        notification.flags = Notification.FLAG_ONGOING_EVENT;
        notification.tickerText = null;

        // create the target network operators settings intent
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Use NetworkSetting to handle the selection intent
        intent.setComponent(new ComponentName("com.android.phone",
                "com.android.phone.NetworkSetting"));
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        notification.setLatestEventInfo(mContext, titleText, expandedText, pi);

        mNotificationMgr.notify(SELECTED_OPERATOR_FAIL_NOTIFICATION, notification);
    }

    /**
     * Turn off the network selection "no service" notification
     */
    private void cancelNetworkSelection() {
        if (DBG) log("cancelNetworkSelection()...");
        mNotificationMgr.cancel(SELECTED_OPERATOR_FAIL_NOTIFICATION);
    }

    /**
     * Update notification about no service of user selected operator
     *
     * @param serviceState Phone service state
     */
    void updateNetworkSelection(int serviceState) {
        if (TelephonyCapabilities.supportsNetworkSelection(mPhone)) {
            // get the shared preference of network_selection.
            // empty is auto mode, otherwise it is the operator alpha name
            // in case there is no operator name, check the operator numeric
            SharedPreferences sp =
                    PreferenceManager.getDefaultSharedPreferences(mContext);
            String networkSelection =
                    sp.getString(PhoneBase.NETWORK_SELECTION_NAME_KEY, "");
            if (TextUtils.isEmpty(networkSelection)) {
                networkSelection =
                        sp.getString(PhoneBase.NETWORK_SELECTION_KEY, "");
            }

            if (DBG) log("updateNetworkSelection()..." + "state = " +
                    serviceState + " new network " + networkSelection);

            if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                    && !TextUtils.isEmpty(networkSelection)) {
                if (!mSelectedUnavailableNotify) {
                    showNetworkSelection(networkSelection);
                    mSelectedUnavailableNotify = true;
                }
            } else {
                if (mSelectedUnavailableNotify) {
                    cancelNetworkSelection();
                    mSelectedUnavailableNotify = false;
                }
            }
        }
    }

    /* package */ void postTransientNotification(int notifyId, CharSequence msg) {
        if (mToast != null) {
            mToast.cancel();
        }

        mToast = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        mToast.show();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.CallLog.Calls;
import android.provider.Contacts.Phones;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * NotificationManager-related utility code for the Phone app.
 */
public class NotificationMgr implements CallerInfoAsyncQuery.OnQueryCompleteListener{
    private static final String LOG_TAG = PhoneApp.LOG_TAG;
    private static final boolean DBG = false;

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

    private static NotificationMgr sMe = null;
    private Phone mPhone;

    private Context mContext;
    private NotificationManager mNotificationMgr;
    private StatusBarManager mStatusBar;
    private StatusBarMgr mStatusBarMgr;
    private Toast mToast;
    private IBinder mSpeakerphoneIcon;
    private IBinder mMuteIcon;

    // used to track the missed call counter, default to 0.
    private int mNumberMissedCalls = 0;

    // Currently-displayed resource IDs for some status bar icons (or zero
    // if no notification is active):
    private int mInCallResId;

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
    }

    static void init(Context context) {
        sMe = new NotificationMgr(context);
        
        // update the notifications that need to be touched at startup.
        sMe.updateNotifications();
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
     * Makes sure notifications are up to date.
     */
    void updateNotifications() {
        if (DBG) log("begin querying call log");

        // instantiate query handler
        mQueryHandler = new QueryHandler(mContext.getContentResolver());

        // setup query spec, look for all Missed calls that are new.
        StringBuilder where = new StringBuilder("type=");
        where.append(Calls.MISSED_TYPE);
        where.append(" AND new=1");
        
        // start the query
        mQueryHandler.startQuery(CALL_LOG_TOKEN, null, Calls.CONTENT_URI,  CALL_LOG_PROJECTION,
                where.toString(), null, Calls.DEFAULT_SORT_ORDER);
        
        // synchronize the in call notification
        if (mPhone.getState() != Phone.State.OFFHOOK) {
            if (DBG) log("Phone is idle, canceling notification.");
            cancelInCall();
        } else {
            if (DBG) log("Phone is offhook, updating notification.");
            updateInCallNotification();
        }
        
        // Depend on android.app.StatusBarManager to be set to
        // disable(DISABLE_NONE) upon startup.  This will be the
        // case even if the phone app crashes.
    }
    
    /** The projection to use when querying the phones table */
    static final String[] PHONES_PROJECTION = new String[] {
            Phones.NUMBER,
            Phones.NAME
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
                                    Uri.withAppendedPath(Phones.CONTENT_FILTER_URL, n.number), 
                                    PHONES_PROJECTION, null, null, Phones.DEFAULT_SORT_ORDER);
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
                            n.name = cursor.getString(cursor.getColumnIndexOrThrow(Phones.NAME));
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
        mNotificationMgr.notify(
                MISSED_CALL_NOTIFICATION,
                new Notification(
                    mContext,  // context
                    android.R.drawable.stat_notify_missed_call,  // icon
                    mContext.getString(
                            R.string.notification_missedCallTicker, callName), // tickerText
                    date, // when
                    mContext.getText(titleResId), // expandedTitle
                    expandedText,  // expandedText
                    intent // contentIntent
                    ));
    }

    void cancelMissedCallNotification() {
        // reset the number of missed calls to 0.
        mNumberMissedCalls = 0;
        mNotificationMgr.cancel(MISSED_CALL_NOTIFICATION);
    }

    void notifySpeakerphone() {
        if (mSpeakerphoneIcon == null) {
            mSpeakerphoneIcon = mStatusBar.addIcon("speakerphone",
                    android.R.drawable.stat_sys_speakerphone, 0);
        }
    }

    void cancelSpeakerphone() {
        if (mSpeakerphoneIcon != null) {
            mStatusBar.removeIcon(mSpeakerphoneIcon);
            mSpeakerphoneIcon = null;
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

    void notifyMute() {
        if (mMuteIcon == null) {
            mMuteIcon = mStatusBar.addIcon("mute", android.R.drawable.stat_notify_call_mute, 0);
        }
    }

    void cancelMute() {
        if (mMuteIcon != null) {
            mStatusBar.removeIcon(mMuteIcon);
            mMuteIcon = null;
        }
    }

    /**
     * Calls either notifyMute() or cancelMute() based on
     * the actual current mute state of the Phone.
     */
    void updateMuteNotification() {
        if ((mPhone.getState() == Phone.State.OFFHOOK) && mPhone.getMute()) {
            if (DBG) log("updateMuteNotification: MUTED");
            notifyMute();
        } else {
            if (DBG) log("updateMuteNotification: not muted (or not offhook)");
            cancelMute();
        }
    }

    void updateInCallNotification() {
        if (DBG) log("updateInCallNotification()...");

        if (mPhone.getState() != Phone.State.OFFHOOK) {
            return;
        }

        final boolean hasActiveCall = !mPhone.getForegroundCall().isIdle();
        final boolean hasHoldingCall = !mPhone.getBackgroundCall().isIdle();

        // Display the appropriate "in-call" icon in the status bar,
        // which depends on the current phone and/or bluetooth state.
        int resId = android.R.drawable.stat_sys_phone_call;
        if (!hasActiveCall && hasHoldingCall) {
            // There's only one call, and it's on hold.
            resId = android.R.drawable.stat_sys_phone_call_on_hold;
        } else if (PhoneApp.getInstance().showBluetoothIndication()) {
            // Bluetooth is active.
            resId = com.android.internal.R.drawable.stat_sys_phone_call_bluetooth;
        }

        // Note we can't just bail out now if (resId == mInCallResId),
        // since even if the status icon hasn't changed, some *other*
        // notification-related info may be different from the last time
        // we were here (like the caller-id info of the foreground call,
        // if the user swapped calls...)

        if (DBG) log("- Updating status bar icon: " + resId);
        mInCallResId = resId;

        // Even if both lines are in use, we only show a single item in
        // the expanded Notifications UI.  It's labeled "Ongoing call"
        // (or "On hold" if there's only one call, and it's on hold.)

        // The icon in the expanded view is the same as in the status bar.
        int expandedViewIcon = mInCallResId;

        // Also, we don't have room to display caller-id info from two
        // different calls.  So if there's only one call, use that, but if
        // both lines are in use we display the caller-id info from the
        // foreground call and totally ignore the background call.
        Call currentCall = hasActiveCall ? mPhone.getForegroundCall()
                : mPhone.getBackgroundCall();
        Connection currentConn = currentCall.getEarliestConnection();

        // When expanded, the "Ongoing call" notification is (visually)
        // different from most other Notifications, so we need to use a
        // custom view hierarchy.

        Notification notification = new Notification();
        notification.icon = mInCallResId;
        notification.contentIntent = PendingIntent.getActivity(mContext, 0,
                PhoneApp.createInCallIntent(), 0);
        notification.flags |= Notification.FLAG_ONGOING_EVENT;

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
            if (hasHoldingCall && !hasActiveCall) {
                // Only one call, and it's on hold!
                // Note this isn't a format string!  (We want "On hold" here,
                // not "On hold (1:23)".)  That's OK; if you call
                // String.format() with more arguments than format specifiers,
                // the extra arguments are ignored.
                expandedViewLine1 = mContext.getString(R.string.notification_on_hold);
            } else {
                // Format string with a "%s" where the current call time should go.
                expandedViewLine1 = mContext.getString(R.string.notification_ongoing_call_format);
            }

            if (DBG) log("- Updating expanded view: line 1 '" + expandedViewLine1 + "'");
    
            // Text line #1 is actually a Chronometer, not a plain TextView.
            // We format the elapsed time of the current call into a line like
            // "Ongoing call (01:23)".
            contentView.setChronometer(R.id.text1,
                                       chronometerBaseTime,
                                       expandedViewLine1,
                                       true);
        } else if (DBG) {
            log("updateInCallNotification: connection is null, call status not updated.");
        }
        
        // display conference call string if this call is a conference 
        // call, otherwise display the connection information.
        
        // TODO: it may not make sense for every point to make separate 
        // checks for isConferenceCall, so we need to think about
        // possibly including this in startGetCallerInfo or some other
        // common point.
        String expandedViewLine2 = ""; 
        if (PhoneUtils.isConferenceCall(currentCall)) {
            // if this is a conference call, just use that as the caller name.
            expandedViewLine2 = mContext.getString(R.string.card_title_conf_call);
        } else {
            // Start asynchronous call to get the compact name.
            PhoneUtils.CallerInfoToken cit = 
                PhoneUtils.startGetCallerInfo (mContext, currentCall, this, contentView);
            // Line 2 of the expanded view (smaller text):
            expandedViewLine2 = PhoneUtils.getCompactNameFromCallerInfo(cit.currentInfo, mContext);
        }
        
        if (DBG) log("- Updating expanded view: line 2 '" + expandedViewLine2 + "'");
        contentView.setTextViewText(R.id.text2, expandedViewLine2);
        notification.contentView = contentView;

        // TODO: We also need to *update* this notification in some cases,
        // like when a call ends on one line but the other is still in use
        // (ie. make sure the caller info here corresponds to the active
        // line), and maybe even when the user swaps calls (ie. if we only
        // show info here for the "current active call".)

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
        if (DBG) log("callerinfo query complete, updating ui.");

        ((RemoteViews) cookie).setTextViewText(R.id.text2, 
                PhoneUtils.getCompactNameFromCallerInfo(ci, mContext));
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

            if ((vmNumber == null) && !mPhone.getSimRecordsLoaded()) {
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
            notification.flags |= Notification.FLAG_SHOW_LIGHTS;
            notification.ledARGB = 0xff00ff00;
            notification.ledOnMS = 500;
            notification.ledOffMS = 2000;
            
            mNotificationMgr.notify(
                    VOICEMAIL_NOTIFICATION,
                    notification);
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
                        android.R.drawable.stat_sys_phone_call_forward,  // icon
                        null, // tickerText
                        0,  // The "timestamp" of this notification is meaningless;
                            // we only care about whether CFI is currently on or not.
                        mContext.getString(R.string.labelCF), // expandedTitle
                        mContext.getString(R.string.sum_cfu_enabled_indicator),  // expandedText
                        intent // contentIntent
                        );

            } else {
                notification = new Notification(
                        android.R.drawable.stat_sys_phone_call_forward,  // icon
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

    /* package */ void postTransientNotification(int notifyId, CharSequence msg) {
        if (mToast != null) {
            mToast.cancel();
        }

        mToast = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        mToast.show();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[NotificationMgr] " + msg);
    }
}

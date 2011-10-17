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
 *
 * This is a singleton object which acts as the interface to the
 * framework's NotificationManager, and is used to display status bar
 * icons and control other status bar-related behavior.
 *
 * @see PhoneApp.notificationMgr
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

    /** The singleton NotificationMgr instance. */
    private static NotificationMgr sInstance;

    private PhoneApp mApp;
    private Phone mPhone;
    private CallManager mCM;

    private Context mContext;
    private NotificationManager mNotificationManager;
    private StatusBarManager mStatusBarManager;
    private Toast mToast;
    private boolean mShowingSpeakerphoneIcon;
    private boolean mShowingMuteIcon;

    public StatusBarHelper statusBarHelper;

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

    /**
     * Private constructor (this is a singleton).
     * @see init()
     */
    private NotificationMgr(PhoneApp app) {
        mApp = app;
        mContext = app;
        mNotificationManager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        mStatusBarManager =
                (StatusBarManager) app.getSystemService(Context.STATUS_BAR_SERVICE);
        mPhone = app.phone;  // TODO: better style to use mCM.getDefaultPhone() everywhere instead
        mCM = app.mCM;
        statusBarHelper = new StatusBarHelper();
    }

    /**
     * Initialize the singleton NotificationMgr instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the NotificationMgr instance is available via the
     * PhoneApp's public "notificationMgr" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static NotificationMgr init(PhoneApp app) {
        synchronized (NotificationMgr.class) {
            if (sInstance == null) {
                sInstance = new NotificationMgr(app);
                // Update the notifications that need to be touched at startup.
                sInstance.updateNotificationsAtStartup();
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /**
     * Helper class that's a wrapper around the framework's
     * StatusBarManager.disable() API.
     *
     * This class is used to control features like:
     *
     *   - Disabling the status bar "notification windowshade"
     *     while the in-call UI is up
     *
     *   - Disabling notification alerts (audible or vibrating)
     *     while a phone call is active
     *
     *   - Disabling navigation via the system bar (the "soft buttons" at
     *     the bottom of the screen on devices with no hard buttons)
     *
     * We control these features through a single point of control to make
     * sure that the various StatusBarManager.disable() calls don't
     * interfere with each other.
     */
    public class StatusBarHelper {
        // Current desired state of status bar / system bar behavior
        private boolean mIsNotificationEnabled = true;
        private boolean mIsExpandedViewEnabled = true;
        private boolean mIsSystemBarNavigationEnabled = true;

        private StatusBarHelper () {
        }

        /**
         * Enables or disables auditory / vibrational alerts.
         *
         * (We disable these any time a voice call is active, regardless
         * of whether or not the in-call UI is visible.)
         */
        public void enableNotificationAlerts(boolean enable) {
            if (mIsNotificationEnabled != enable) {
                mIsNotificationEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Enables or disables the expanded view of the status bar
         * (i.e. the ability to pull down the "notification windowshade").
         *
         * (This feature is disabled by the InCallScreen while the in-call
         * UI is active.)
         */
        public void enableExpandedView(boolean enable) {
            if (mIsExpandedViewEnabled != enable) {
                mIsExpandedViewEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Enables or disables the navigation via the system bar (the
         * "soft buttons" at the bottom of the screen)
         *
         * (This feature is disabled while an incoming call is ringing,
         * because it's easy to accidentally touch the system bar while
         * pulling the phone out of your pocket.)
         */
        public void enableSystemBarNavigation(boolean enable) {
            if (mIsSystemBarNavigationEnabled != enable) {
                mIsSystemBarNavigationEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Updates the status bar to reflect the current desired state.
         */
        private void updateStatusBar() {
            int state = StatusBarManager.DISABLE_NONE;

            if (!mIsExpandedViewEnabled) {
                state |= StatusBarManager.DISABLE_EXPAND;
            }
            if (!mIsNotificationEnabled) {
                state |= StatusBarManager.DISABLE_NOTIFICATION_ALERTS;
            }
            if (!mIsSystemBarNavigationEnabled) {
                // Disable *all* possible navigation via the system bar.
                state |= StatusBarManager.DISABLE_HOME;
                state |= StatusBarManager.DISABLE_RECENT;
                state |= StatusBarManager.DISABLE_BACK;
            }

            if (DBG) log("updateStatusBar: state = 0x" + Integer.toHexString(state));
            mStatusBarManager.disable(state);
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
         * similar to what happens in CallLogActivity.
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
            // CallLogActivity.java does.
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
        // When the user clicks this notification, we go to the call log.
        final Intent callLogIntent = PhoneApp.createCallLogIntent();

        // Never display the missed call notification on non-voice-capable
        // devices, even if the device does somehow manage to get an
        // incoming call.
        if (!PhoneApp.sVoiceCapable) {
            if (DBG) log("notifyMissedCall: non-voice-capable device, not posting notification");
            return;
        }

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

        // make the notification
        Notification note = new Notification(
                android.R.drawable.stat_notify_missed_call, // icon
                mContext.getString(R.string.notification_missedCallTicker, callName), // tickerText
                date // when
                );
        note.setLatestEventInfo(mContext, mContext.getText(titleResId), expandedText,
                PendingIntent.getActivity(mContext, 0, callLogIntent, 0));
        note.flags |= Notification.FLAG_AUTO_CANCEL;
        // This intent will be called when the notification is dismissed.
        // It will take care of clearing the list of missed calls.
        note.deleteIntent = createClearMissedCallsIntent();

        configureLedNotification(note);
        mNotificationManager.notify(MISSED_CALL_NOTIFICATION, note);
    }

    /** Returns an intent to be invoked when the missed call notification is cleared. */
    private PendingIntent createClearMissedCallsIntent() {
        Intent intent = new Intent(mContext, ClearMissedCallsService.class);
        intent.setAction(ClearMissedCallsService.ACTION_CLEAR_MISSED_CALLS);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    /**
     * Cancels the "missed call" notification.
     *
     * @see ITelephony.cancelMissedCallsNotification()
     */
    void cancelMissedCallNotification() {
        // reset the number of missed calls to 0.
        mNumberMissedCalls = 0;
        mNotificationManager.cancel(MISSED_CALL_NOTIFICATION);
    }

    private void notifySpeakerphone() {
        if (!mShowingSpeakerphoneIcon) {
            mStatusBarManager.setIcon("speakerphone", android.R.drawable.stat_sys_speakerphone, 0,
                    mContext.getString(R.string.accessibility_speakerphone_enabled));
            mShowingSpeakerphoneIcon = true;
        }
    }

    private void cancelSpeakerphone() {
        if (mShowingSpeakerphoneIcon) {
            mStatusBarManager.removeIcon("speakerphone");
            mShowingSpeakerphoneIcon = false;
        }
    }

    /**
     * Shows or hides the "speakerphone" notification in the status bar,
     * based on the actual current state of the speaker.
     *
     * If you already know the current speaker state (e.g. if you just
     * called AudioManager.setSpeakerphoneOn() yourself) then you should
     * directly call {@link updateSpeakerNotification(boolean)} instead.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    public void updateSpeakerNotification() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean showNotification =
                (mPhone.getState() == Phone.State.OFFHOOK) && audioManager.isSpeakerphoneOn();

        if (DBG) log(showNotification
                     ? "updateSpeakerNotification: speaker ON"
                     : "updateSpeakerNotification: speaker OFF (or not offhook)");

        updateSpeakerNotification(showNotification);
    }

    /**
     * Shows or hides the "speakerphone" notification in the status bar.
     *
     * @param showNotification if true, call notifySpeakerphone();
     *                         if false, call cancelSpeakerphone().
     *
     * Use {@link updateSpeakerNotification()} to update the status bar
     * based on the actual current state of the speaker.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    public void updateSpeakerNotification(boolean showNotification) {
        if (DBG) log("updateSpeakerNotification(" + showNotification + ")...");

        // Regardless of the value of the showNotification param, suppress
        // the status bar icon if the the InCallScreen is the foreground
        // activity, since the in-call UI already provides an onscreen
        // indication of the speaker state.  (This reduces clutter in the
        // status bar.)
        if (mApp.isShowingCallScreen()) {
            cancelSpeakerphone();
            return;
        }

        if (showNotification) {
            notifySpeakerphone();
        } else {
            cancelSpeakerphone();
        }
    }

    private void notifyMute() {
        if (!mShowingMuteIcon) {
            mStatusBarManager.setIcon("mute", android.R.drawable.stat_notify_call_mute, 0,
                    mContext.getString(R.string.accessibility_call_muted));
            mShowingMuteIcon = true;
        }
    }

    private void cancelMute() {
        if (mShowingMuteIcon) {
            mStatusBarManager.removeIcon("mute");
            mShowingMuteIcon = false;
        }
    }

    /**
     * Shows or hides the "mute" notification in the status bar,
     * based on the current mute state of the Phone.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    void updateMuteNotification() {
        // Suppress the status bar icon if the the InCallScreen is the
        // foreground activity, since the in-call UI already provides an
        // onscreen indication of the mute state.  (This reduces clutter
        // in the status bar.)
        if (mApp.isShowingCallScreen()) {
            cancelMute();
            return;
        }

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
     *
     * This method will never actually launch the incoming-call UI.
     * (Use updateNotificationAndLaunchIncomingCallUi() for that.)
     */
    public void updateInCallNotification() {
        // allowFullScreenIntent=false means *don't* allow the incoming
        // call UI to be launched.
        updateInCallNotification(false);
    }

    /**
     * Updates the phone app's status bar notification *and* launches the
     * incoming call UI in response to a new incoming call.
     *
     * This is just like updateInCallNotification(), with one exception:
     * If an incoming call is ringing (or call-waiting), the notification
     * will also include a "fullScreenIntent" that will cause the
     * InCallScreen to be launched immediately, unless the current
     * foreground activity is marked as "immersive".
     *
     * (This is the mechanism that actually brings up the incoming call UI
     * when we receive a "new ringing connection" event from the telephony
     * layer.)
     *
     * Watch out: this method should ONLY be called directly from the code
     * path in CallNotifier that handles the "new ringing connection"
     * event from the telephony layer.  All other places that update the
     * in-call notification (like for phone state changes) should call
     * updateInCallNotification() instead.  (This ensures that we don't
     * end up launching the InCallScreen multiple times for a single
     * incoming call, which could cause slow responsiveness and/or visible
     * glitches.)
     *
     * Also note that this method is safe to call even if the phone isn't
     * actually ringing (or, more likely, if an incoming call *was*
     * ringing briefly but then disconnected).  In that case, we'll simply
     * update or cancel the in-call notification based on the current
     * phone state.
     *
     * @see updateInCallNotification()
     */
    public void updateNotificationAndLaunchIncomingCallUi() {
        // Set allowFullScreenIntent=true to indicate that we *should*
        // launch the incoming call UI if necessary.
        updateInCallNotification(true);
    }

    /**
     * Helper method for updateInCallNotification() and
     * updateNotificationAndLaunchIncomingCallUi(): Update the phone app's
     * status bar notification based on the current telephony state, or
     * cancels the notification if the phone is totally idle.
     *
     * @param allowLaunchInCallScreen If true, *and* an incoming call is
     *   ringing, the notification will include a "fullScreenIntent"
     *   pointing at the InCallScreen (which will cause the InCallScreen
     *   to be launched.)
     *   Watch out: This should be set to true *only* when directly
     *   handling the "new ringing connection" event from the telephony
     *   layer (see updateNotificationAndLaunchIncomingCallUi().)
     */
    private void updateInCallNotification(boolean allowFullScreenIntent) {
        int resId;
        if (DBG) log("updateInCallNotification(allowFullScreenIntent = "
                     + allowFullScreenIntent + ")...");

        // Never display the "ongoing call" notification on
        // non-voice-capable devices, even if the phone is actually
        // offhook (like during a non-interactive OTASP call.)
        if (!PhoneApp.sVoiceCapable) {
            if (DBG) log("- non-voice-capable device; suppressing notification.");
            return;
        }

        // If the phone is idle, completely clean up all call-related
        // notifications.
        if (mCM.getState() == Phone.State.IDLE) {
            cancelInCall();
            cancelMute();
            cancelSpeakerphone();
            return;
        }

        final boolean hasRingingCall = mCM.hasActiveRingingCall();
        final boolean hasActiveCall = mCM.hasActiveFgCall();
        final boolean hasHoldingCall = mCM.hasActiveBgCall();
        if (DBG) {
            log("  - hasRingingCall = " + hasRingingCall);
            log("  - hasActiveCall = " + hasActiveCall);
            log("  - hasHoldingCall = " + hasHoldingCall);
        }

        // Suppress the in-call notification if the InCallScreen is the
        // foreground activity, since it's already obvious that you're on a
        // call.  (The status bar icon is needed only if you navigate *away*
        // from the in-call UI.)
        boolean suppressNotification = mApp.isShowingCallScreen();

        // ...except for a couple of cases where we *never* suppress the
        // notification:
        //
        //   - If there's an incoming ringing call: always show the
        //     notification, since the in-call notification is what actually
        //     launches the incoming call UI in the first place (see
        //     notification.fullScreenIntent below.)  This makes sure that we'll
        //     correctly handle the case where a new incoming call comes in but
        //     the InCallScreen is already in the foreground.
        if (hasRingingCall) suppressNotification = false;

        //   - If "voice privacy" mode is active: always show the notification,
        //     since that's the only "voice privacy" indication we have.
        boolean enhancedVoicePrivacy = mApp.notifier.getVoicePrivacyState();
        if (DBG) log("updateInCallNotification: enhancedVoicePrivacy = " + enhancedVoicePrivacy);
        if (enhancedVoicePrivacy) suppressNotification = false;

        if (suppressNotification) {
            cancelInCall();
            // Suppress the mute and speaker status bar icons too
            // (also to reduce clutter in the status bar.)
            cancelSpeakerphone();
            cancelMute();
            return;
        }

        // Display the appropriate icon in the status bar,
        // based on the current phone and/or bluetooth state.


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
        } else if (mApp.showBluetoothIndication()) {
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
        contentView.setTextViewText(R.id.title, expandedViewLine2);
        notification.contentView = contentView;

        // TODO: We also need to *update* this notification in some cases,
        // like when a call ends on one line but the other is still in use
        // (ie. make sure the caller info here corresponds to the active
        // line), and maybe even when the user swaps calls (ie. if we only
        // show info here for the "current active call".)

        // Activate a couple of special Notification features if an
        // incoming call is ringing:
        if (hasRingingCall) {
            if (DBG) log("- Using hi-pri notification for ringing call!");

            // This is a high-priority event that should be shown even if the
            // status bar is hidden or if an immersive activity is running.
            notification.flags |= Notification.FLAG_HIGH_PRIORITY;

            // If an immersive activity is running, we have room for a single
            // line of text in the small notification popup window.
            // We use expandedViewLine2 for this (i.e. the name or number of
            // the incoming caller), since that's more relevant than
            // expandedViewLine1 (which is something generic like "Incoming
            // call".)
            notification.tickerText = expandedViewLine2;

            if (allowFullScreenIntent) {
                // Ok, we actually want to launch the incoming call
                // UI at this point (in addition to simply posting a notification
                // to the status bar).  Setting fullScreenIntent will cause
                // the InCallScreen to be launched immediately *unless* the
                // current foreground activity is marked as "immersive".
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
                if ((ringingCall.getState() == Call.State.WAITING) && !mApp.isShowingCallScreen()) {
                    Log.i(LOG_TAG, "updateInCallNotification: call-waiting! force relaunch...");
                    // Cancel the IN_CALL_NOTIFICATION immediately before
                    // (re)posting it; this seems to force the
                    // NotificationManager to launch the fullScreenIntent.
                    mNotificationManager.cancel(IN_CALL_NOTIFICATION);
                }
            }
        }

        if (DBG) log("Notifying IN_CALL_NOTIFICATION: " + notification);
        mNotificationManager.notify(IN_CALL_NOTIFICATION,
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

    /**
     * Take down the in-call notification.
     * @see updateInCallNotification()
     */
    private void cancelInCall() {
        if (DBG) log("cancelInCall()...");
        mNotificationManager.cancel(IN_CALL_NOTIFICATION);
        mInCallResId = 0;
    }

    /**
     * Completely take down the in-call notification *and* the mute/speaker
     * notifications as well, to indicate that the phone is now idle.
     */
    /* package */ void cancelCallInProgressNotifications() {
        if (DBG) log("cancelCallInProgressNotifications()...");
        if (mInCallResId == 0) {
            return;
        }

        if (DBG) log("cancelCallInProgressNotifications: " + mInCallResId);
        cancelInCall();
        cancelMute();
        cancelSpeakerphone();
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
                    mApp.notifier.sendMwiChangedDelayed(VM_NUMBER_RETRY_DELAY_MILLIS);
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
                    Uri.fromParts(Constants.SCHEME_VOICEMAIL, "", null));
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
            mNotificationManager.notify(VOICEMAIL_NOTIFICATION, notification);
        } else {
            mNotificationManager.cancel(VOICEMAIL_NOTIFICATION);
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
                        R.drawable.stat_sys_phone_call_forward,  // icon
                        null, // tickerText
                        0); // The "timestamp" of this notification is meaningless;
                            // we only care about whether CFI is currently on or not.
                notification.setLatestEventInfo(
                        mContext, // context
                        mContext.getString(R.string.labelCF), // expandedTitle
                        mContext.getString(R.string.sum_cfu_enabled_indicator), // expandedText
                        PendingIntent.getActivity(mContext, 0, intent, 0)); // contentIntent
            } else {
                notification = new Notification(
                        R.drawable.stat_sys_phone_call_forward,  // icon
                        null,  // tickerText
                        System.currentTimeMillis()  // when
                        );
            }

            notification.flags |= Notification.FLAG_ONGOING_EVENT;  // also implies FLAG_NO_CLEAR

            mNotificationManager.notify(
                    CALL_FORWARD_NOTIFICATION,
                    notification);
        } else {
            mNotificationManager.cancel(CALL_FORWARD_NOTIFICATION);
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
                com.android.phone.Settings.class);  // "Mobile network settings" screen / dialog

        Notification notification = new Notification(
                android.R.drawable.stat_sys_warning, // icon
                null, // tickerText
                System.currentTimeMillis());
        notification.setLatestEventInfo(
                mContext, // Context
                mContext.getString(R.string.roaming), // expandedTitle
                mContext.getString(R.string.roaming_reenable_message), // expandedText
                PendingIntent.getActivity(mContext, 0, intent, 0)); // contentIntent

        mNotificationManager.notify(
                DATA_DISCONNECTED_ROAMING_NOTIFICATION,
                notification);
    }

    /**
     * Turns off the "data disconnected due to roaming" notification.
     */
    /* package */ void hideDataDisconnectedRoaming() {
        if (DBG) log("hideDataDisconnectedRoaming()...");
        mNotificationManager.cancel(DATA_DISCONNECTED_ROAMING_NOTIFICATION);
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

        mNotificationManager.notify(SELECTED_OPERATOR_FAIL_NOTIFICATION, notification);
    }

    /**
     * Turn off the network selection "no service" notification
     */
    private void cancelNetworkSelection() {
        if (DBG) log("cancelNetworkSelection()...");
        mNotificationManager.cancel(SELECTED_OPERATOR_FAIL_NOTIFICATION);
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

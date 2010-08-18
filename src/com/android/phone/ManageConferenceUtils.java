/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.TextView;

import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;

import java.util.List;


/**
 * Helper class to initialize and run the InCallScreen's "Manage conference" UI.
 */
public class ManageConferenceUtils
        implements CallerInfoAsyncQuery.OnQueryCompleteListener {
    private static final String LOG_TAG = "ManageConferenceUtils";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    private InCallScreen mInCallScreen;
    private CallManager mCM;

    // "Manage conference" UI elements and state
    private ViewGroup mManageConferencePanel;
    private Button mButtonManageConferenceDone;
    private ViewGroup[] mConferenceCallList;
    private int mNumCallersInConference;
    private Chronometer mConferenceTime;

    // See CallTracker.MAX_CONNECTIONS_PER_CALL
    private static final int MAX_CALLERS_IN_CONFERENCE = 5;

    public ManageConferenceUtils(InCallScreen inCallScreen, CallManager cm) {
        if (DBG) log("ManageConferenceUtils constructor...");
        mInCallScreen = inCallScreen;
        mCM = cm;
    }

    public void initManageConferencePanel() {
        if (DBG) log("initManageConferencePanel()...");
        if (mManageConferencePanel == null) {
            if (DBG) log("initManageConferencePanel: first-time initialization!");

            // Inflate the ViewStub, look up and initialize the UI elements.
            ViewStub stub = (ViewStub) mInCallScreen.findViewById(R.id.manageConferencePanelStub);
            stub.inflate();

            mManageConferencePanel =
                    (ViewGroup) mInCallScreen.findViewById(R.id.manageConferencePanel);
            if (mManageConferencePanel == null) {
                throw new IllegalStateException("Couldn't find manageConferencePanel!");
            }

            // set up the Conference Call chronometer
            mConferenceTime =
                    (Chronometer) mInCallScreen.findViewById(R.id.manageConferencePanelHeader);
            mConferenceTime.setFormat(mInCallScreen.getString(R.string.caller_manage_header));

            // Create list of conference call widgets
            mConferenceCallList = new ViewGroup[MAX_CALLERS_IN_CONFERENCE];

            final int[] viewGroupIdList = { R.id.caller0, R.id.caller1, R.id.caller2,
                                            R.id.caller3, R.id.caller4 };
            for (int i = 0; i < MAX_CALLERS_IN_CONFERENCE; i++) {
                mConferenceCallList[i] =
                        (ViewGroup) mInCallScreen.findViewById(viewGroupIdList[i]);
            }

            mButtonManageConferenceDone = (Button) mInCallScreen.findViewById(R.id.manage_done);
            mButtonManageConferenceDone.setOnClickListener(mInCallScreen);
        }
    }

    /**
     * Shows or hides the manageConferencePanel.
     */
    public void setPanelVisible(boolean visible) {
        if (mManageConferencePanel != null) {
            mManageConferencePanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Starts the "conference time" chronometer.
     */
    public void startConferenceTime(long base) {
        if (mConferenceTime != null) {
            mConferenceTime.setBase(base);
            mConferenceTime.start();
        }
    }

    /**
     * Stops the "conference time" chronometer.
     */
    public void stopConferenceTime() {
        if (mConferenceTime != null) {
            mConferenceTime.stop();
        }
    }

    public int getNumCallersInConference() {
        return mNumCallersInConference;
    }

    /**
     * Updates the "Manage conference" UI based on the specified List of
     * connections.
     *
     * @param connections the List of connections belonging to
     *        the current foreground call; size must be greater than 1
     *        (or it wouldn't be a conference call in the first place.)
     */
    public void updateManageConferencePanel(List<Connection> connections) {
        mNumCallersInConference = connections.size();
        if (DBG) log("updateManageConferencePanel()... num connections in conference = "
                      + mNumCallersInConference);

        // Can we give the user the option to separate out ("go private with") a single
        // caller from this conference?
        final boolean hasActiveCall = mCM.hasActiveFgCall();
        final boolean hasHoldingCall = mCM.hasActiveBgCall();
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
    public void updateManageConferenceRow(final int i,
                                          final Connection connection,
                                          boolean canSeparate) {
        if (DBG) log("updateManageConferenceRow(" + i + ")...  connection = " + connection);

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

            if (DBG) log("- button: " + endButton + ", nameTextView: " + nameTextView);

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
            PhoneUtils.CallerInfoToken info =
                    PhoneUtils.startGetCallerInfo(mInCallScreen,
                                                  connection,
                                                  this,
                                                  mConferenceCallList[i]);
            if (DBG) log("  - got info from startGetCallerInfo(): " + info);

            // display the CallerInfo.
            displayCallerInfoForConferenceRow(info.currentInfo, nameTextView,
                                              numberTypeTextView, numberTextView);
        } else {
            // Disable this row of the Manage conference panel:
            mConferenceCallList[i].setVisibility(View.GONE);
        }
    }

    /**
     * Helper function to fill out the Conference Call(er) information
     * for each item in the "Manage Conference Call" list.
     */
    public final void displayCallerInfoForConferenceRow(CallerInfo ci,
                                                        TextView nameTextView,
                                                        TextView numberTypeTextView,
                                                        TextView numberTextView) {
        // gather the correct name and number information.
        String callerName = "";
        String callerNumber = "";
        String callerNumberType = "";
        if (ci != null) {
            callerName = ci.name;
            if (TextUtils.isEmpty(callerName)) {
                callerName = ci.phoneNumber;
                if (TextUtils.isEmpty(callerName)) {
                    callerName = mInCallScreen.getString(R.string.unknown);
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
    public void endConferenceConnection(int i, Connection connection) {
        if (DBG) log("===> ENDING conference connection " + i
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
    public void separateConferenceConnection(int i, Connection connection) {
        if (DBG) log("===> SEPARATING conference connection " + i
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
     * CallerInfoAsyncQuery.OnQueryCompleteListener implementation.
     *
     * This method listens for results from the caller-id info queries we
     * fire off in updateManageConferenceRow(), and updates the
     * corresponding conference row.
     */
    public void onQueryComplete(int token, Object cookie, CallerInfo ci) {
        if (DBG) log("callerinfo query complete, updating UI." + ci);

        // get the viewgroup (conference call list item) and make it visible
        ViewGroup vg = (ViewGroup) cookie;
        vg.setVisibility(View.VISIBLE);

        // update the list item with this information.
        displayCallerInfoForConferenceRow(ci,
                (TextView) vg.findViewById(R.id.conferenceCallerName),
                (TextView) vg.findViewById(R.id.conferenceCallerNumberType),
                (TextView) vg.findViewById(R.id.conferenceCallerNumber));
    }


    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

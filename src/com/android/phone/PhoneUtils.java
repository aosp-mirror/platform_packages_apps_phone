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

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Contacts;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.IExtendedNetworkService;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

/**
 * Misc utilities for the Phone app.
 */
public class PhoneUtils {
    private static final String LOG_TAG = "PhoneUtils";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    /** Control stack trace for Audio Mode settings */
    private static final boolean DBG_SETAUDIOMODE_STACK = false;

    /** Identifier for the "Add Call" intent extra. */
    static final String ADD_CALL_MODE_KEY = "add_call_mode";

    // Return codes from placeCall()
    static final int CALL_STATUS_DIALED = 0;  // The number was successfully dialed
    static final int CALL_STATUS_DIALED_MMI = 1;  // The specified number was an MMI code
    static final int CALL_STATUS_FAILED = 2;  // The call failed

    // State of the Phone's audio modes
    // Each state can move to the other states, but within the state only certain
    //  transitions for AudioManager.setMode() are allowed.
    static final int AUDIO_IDLE = 0;  /** audio behaviour at phone idle */
    static final int AUDIO_RINGING = 1;  /** audio behaviour while ringing */
    static final int AUDIO_OFFHOOK = 2;  /** audio behaviour while in call. */
    private static int sAudioBehaviourState = AUDIO_IDLE;

    /** Speaker state, persisting between wired headset connection events */
    private static boolean sIsSpeakerEnabled = false;

    /** Hash table to store mute (Boolean) values based upon the connection.*/
    private static Hashtable<Connection, Boolean> sConnectionMuteTable =
        new Hashtable<Connection, Boolean>();

    /** Static handler for the connection/mute tracking */
    private static ConnectionHandler mConnectionHandler;

    /** Phone state changed event*/
    private static final int PHONE_STATE_CHANGED = -1;

    // Extended network service interface instance
    private static IExtendedNetworkService mNwService = null;
    // used to cancel MMI command after 15 seconds timeout for NWService requirement
    private static Message mMmiTimeoutCbMsg = null;

    /**
     * Handler that tracks the connections and updates the value of the
     * Mute settings for each connection as needed.
     */
    private static class ConnectionHandler extends Handler {
        public void handleMessage(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
                case PHONE_STATE_CHANGED:
                    if (DBG) log("ConnectionHandler: updating mute state for each connection");

                    Phone phone = (Phone) ar.userObj;

                    // update the foreground connections, if there are new connections.
                    List<Connection> fgConnections = phone.getForegroundCall().getConnections();
                    for (Connection cn : fgConnections) {
                        if (sConnectionMuteTable.get(cn) == null) {
                            sConnectionMuteTable.put(cn, Boolean.FALSE);
                        }
                    }

                    // update the background connections, if there are new connections.
                    List<Connection> bgConnections = phone.getBackgroundCall().getConnections();
                    for (Connection cn : bgConnections) {
                        if (sConnectionMuteTable.get(cn) == null) {
                            sConnectionMuteTable.put(cn, Boolean.FALSE);
                        }
                    }

                    // Check to see if there are any lingering connections here
                    // (disconnected connections), use old-school iterators to avoid
                    // concurrent modification exceptions.
                    Connection cn;
                    for (Iterator<Connection> cnlist = sConnectionMuteTable.keySet().iterator();
                            cnlist.hasNext();) {
                        cn = cnlist.next();
                        if (!fgConnections.contains(cn) && !bgConnections.contains(cn)) {
                            if (DBG) log("connection: " + cn + "not accounted for, removing.");
                            cnlist.remove();
                        }
                    }

                    // Restore the mute state of the foreground call if we're not IDLE,
                    // otherwise just clear the mute state. This is really saying that
                    // as long as there is one or more connections, we should update
                    // the mute state with the earliest connection on the foreground
                    // call, and that with no connections, we should be back to a
                    // non-mute state.
                    if (phone.getState() != Phone.State.IDLE) {
                        restoreMuteState(phone);
                    } else {
                        setMuteInternal(phone, false);
                    }

                    break;
            }
        }
    }
    

    private static ServiceConnection ExtendedNetworkServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder iBinder) {
            if (DBG) log("Extended NW onServiceConnected");
            mNwService = IExtendedNetworkService.Stub.asInterface(iBinder);
        }

        public void onServiceDisconnected(ComponentName arg0) {
            if (DBG) log("Extended NW onServiceDisconnected");
            mNwService = null;
        }    
    };

    /**
     * Register the ConnectionHandler with the phone, to receive connection events
     */
    public static void initializeConnectionHandler(Phone phone) {
        if (mConnectionHandler == null) {
            mConnectionHandler = new ConnectionHandler();
        }

        phone.registerForPhoneStateChanged(mConnectionHandler, PHONE_STATE_CHANGED, phone);
        // Extended NW service
        Intent intent = new Intent("com.android.ussd.IExtendedNetworkService");
        phone.getContext().bindService(intent, 
                ExtendedNetworkServiceConnection, Context.BIND_AUTO_CREATE);
        if (DBG) log("Extended NW bindService IExtendedNetworkService");

    }

    /** This class is never instantiated. */
    private PhoneUtils() {
    }

    //static method to set the audio control state.
    static void setAudioControlState(int newState) {
        sAudioBehaviourState = newState;
    }

    /**
     * Answer the currently-ringing call.
     *
     * @return true if we answered the call, or false if there wasn't
     *         actually a ringing incoming call, or some other error occurred.
     *
     * @see answerAndEndHolding()
     * @see answerAndEndActive()
     */
    static boolean answerCall(Phone phone) {
        if (DBG) log("answerCall()...");

        // If the ringer is currently ringing and/or vibrating, stop it
        // right now (before actually answering the call.)
        PhoneApp.getInstance().getRinger().stopRing();

        PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_OFFHOOK);

        boolean answered = false;
        Call call = phone.getRingingCall();

        if (call != null && call.isRinging()) {
            if (DBG) log("answerCall: call state = " + call.getState());
            try {
                //if (DBG) log("sPhone.acceptCall");
                phone.acceptCall();
                answered = true;
                setAudioMode(phone.getContext(), AudioManager.MODE_IN_CALL);
                if (phone.getPhoneName().equals("CDMA")) {
                    PhoneApp app = PhoneApp.getInstance();
                    if (app.cdmaPhoneCallState.getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.IDLE) {
                        // This is the FIRST incoming call being answered.
                        // Set the Phone Call State to SINGLE_ACTIVE
                        app.cdmaPhoneCallState.setCurrentCallState(
                                CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE);
                    } else {
                        // This is the CALL WAITING call being answered.
                        // Set the Phone Call State to CONF_CALL
                        app.cdmaPhoneCallState.setCurrentCallState(
                                CdmaPhoneCallState.PhoneCallState.CONF_CALL);
                    }
                }
            } catch (CallStateException ex) {
                Log.w(LOG_TAG, "answerCall: caught " + ex, ex);
            }
        }
        return answered;
    }

    /**
     * Smart "hang up" helper method which hangs up exactly one connection,
     * based on the current Phone state, as follows:
     * <ul>
     * <li>If there's a ringing call, hang that up.
     * <li>Else if there's a foreground call, hang that up.
     * <li>Else if there's a background call, hang that up.
     * <li>Otherwise do nothing.
     * </ul>
     * @return true if we successfully hung up, or false
     *              if there were no active calls at all.
     */
    static boolean hangup(Phone phone) {
        boolean hungup = false;
        Call ringing = phone.getRingingCall();
        Call fg = phone.getForegroundCall();
        Call bg = phone.getBackgroundCall();

        if (!ringing.isIdle()) {
            if (DBG) log("HANGUP ringing call");
            hungup = hangup(ringing);
        } else if (!fg.isIdle()) {
            if (DBG) log("HANGUP foreground call");
            hungup = hangup(fg);
        } else if (!bg.isIdle()) {
            if (DBG) log("HANGUP background call");
            hungup = hangup(bg);
        }

        if (DBG) log("hungup=" + hungup);

        return hungup;
    }

    static boolean hangupRingingCall(Phone phone) {
        if (DBG) log("hangup ringing call");
        return hangup(phone.getRingingCall());
    }

    static boolean hangupActiveCall(Phone phone) {
        if (DBG) log("hangup active call");
        return hangup(phone.getForegroundCall());
    }

    static boolean hangupHoldingCall(Phone phone) {
        if (DBG) log("hangup holding call");
        return hangup(phone.getBackgroundCall());
    }

    static boolean hangup(Call call) {
        try {
            call.hangup();
            return true;
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "Call hangup: caught " + ex, ex);
        }

        return false;
    }

    static void hangup(Connection c) {
        try {
            if (c != null) {
                c.hangup();
            }
        } catch (CallStateException ex) {
            Log.w(LOG_TAG, "Connection hangup: caught " + ex, ex);
        }
    }

    static boolean answerAndEndHolding(Phone phone) {
        if (DBG) log("end holding & answer waiting: 1");
        if (!hangupHoldingCall(phone)) {
            Log.e(LOG_TAG, "end holding failed!");
            return false;
        }

        if (DBG) log("end holding & answer waiting: 2");
        return answerCall(phone);

    }

    static boolean answerAndEndActive(Phone phone) {
        if (DBG) log("answerAndEndActive()...");

        // Unlike the answerCall() method, we *don't* need to stop the
        // ringer or change audio modes here since the user is already
        // in-call, which means that the audio mode is already set
        // correctly, and that we wouldn't have started the ringer in the
        // first place.

        // hanging up the active call also accepts the waiting call
        return hangupActiveCall(phone);
    }

    /**
     * Dial the number using the phone passed in.
     *
     * @param phone the Phone object.
     * @param number the number to be dialed.
     * @return either CALL_STATUS_DIALED, CALL_STATUS_DIALED_MMI, or CALL_STATUS_FAILED
     */
    static int placeCall(Phone phone, String number, Uri contactRef) {
        int status = CALL_STATUS_DIALED;
        try {
            if (DBG) log("placeCall: '" + number + "'...");

            Connection cn = phone.dial(number);
            if (DBG) log("===> phone.dial() returned: " + cn);

            // Presently, null is returned for MMI codes
            if (cn == null) {
                if (DBG) log("dialed MMI code: " + number);
                status = CALL_STATUS_DIALED_MMI;   
                // Set dialed MMI command to service
                if (mNwService != null) {
                    try {
                        mNwService.setMmiString(number);
                        if (DBG) log("Extended NW bindService setUssdString (" + number + ")");
                    } catch (RemoteException e) {
                        mNwService = null;
                    }
                }

            } else {
                PhoneApp app = PhoneApp.getInstance();

                if (phone.getPhoneName().equals("CDMA")){
                    if (app.cdmaPhoneCallState.getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.IDLE) {
                        // This is the first outgoing call. Set the Phone Call State to ACTIVE
                        app.cdmaPhoneCallState.setCurrentCallState(
                                CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE);
                    } else {
                        // This is the second outgoing call. Set the Phone Call State to 3WAY
                        app.cdmaPhoneCallState.setCurrentCallState(
                                CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE);
                    }
                }

                PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_OFFHOOK);

                // phone.dial() succeeded: we're now in a normal phone call.
                // attach the URI to the CallerInfo Object if it is there,
                // otherwise just attach the Uri Reference.
                // if the uri does not have a "content" scheme, then we treat
                // it as if it does NOT have a unique reference.
                String content = phone.getContext().getContentResolver().SCHEME_CONTENT;
                if ((contactRef != null) && (contactRef.getScheme().equals(content))) {
                    Object userDataObject = cn.getUserData();
                    if (userDataObject == null) {
                        cn.setUserData(contactRef);
                    } else {
                        if (userDataObject instanceof CallerInfo) {
                            ((CallerInfo) userDataObject).contactRefUri = contactRef;
                        } else {
                            ((CallerInfoToken) userDataObject).currentInfo.contactRefUri =
                                contactRef;
                        }
                    }
                }
                setAudioMode(phone.getContext(), AudioManager.MODE_IN_CALL);
            }
        } catch (CallStateException ex) {
            Log.w(LOG_TAG, "PhoneUtils: Exception from phone.dial()", ex);
            status = CALL_STATUS_FAILED;
        }

        return status;
    }

    static void switchHoldingAndActive(Phone phone) {
        try {
            if (DBG) log("switchHoldingAndActive");
            phone.switchHoldingAndActive();
        } catch (CallStateException ex) {
            Log.w(LOG_TAG, "switchHoldingAndActive: caught " + ex, ex);
        }
    }

    /**
     * Restore the mute setting from the earliest connection of the
     * foreground call.
     */
    static Boolean restoreMuteState(Phone phone) {
        //get the earliest connection
        Connection c = phone.getForegroundCall().getEarliestConnection();

        // only do this if connection is not null.
        if (c != null) {

            // retrieve the mute value.
            Boolean shouldMute;
            if (phone.getPhoneName().equals("CDMA") &&
                    PhoneApp.getInstance().cdmaPhoneCallState.getCurrentCallState() ==
                    CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                shouldMute = sConnectionMuteTable.get(
                        phone.getForegroundCall().getLatestConnection());
            } else {
                shouldMute = sConnectionMuteTable.get(
                        phone.getForegroundCall().getEarliestConnection());
            }
            if (shouldMute == null) {
                if (DBG) log("problem retrieving mute value for this connection.");
                shouldMute = Boolean.FALSE;
            }

            // set the mute value and return the result.
            setMute (phone, shouldMute.booleanValue());
            return shouldMute;
        }
        return Boolean.valueOf(getMute (phone));
    }

    static void mergeCalls(Phone phone) {
        if (phone.getPhoneName().equals("GSM")) {
            try {
                if (DBG) log("mergeCalls");
                phone.conference();
            } catch (CallStateException ex) {
                Log.w(LOG_TAG, "mergeCalls: caught " + ex, ex);
            }
        } else { // CDMA
            PhoneApp app = PhoneApp.getInstance();
            if (app.cdmaPhoneCallState.getCurrentCallState()
                    == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                // Send flash cmd
                // TODO(Moto): Need to change the call from switchHoldingAndActive to
                // something meaningful as we are not actually trying to swap calls but
                // instead are merging two calls by sending a Flash command.
                switchHoldingAndActive(phone);

                // Set the Phone Call State to conference
                app.cdmaPhoneCallState.setCurrentCallState(
                        CdmaPhoneCallState.PhoneCallState.CONF_CALL);
            }
        }
    }

    static void separateCall(Connection c) {
        try {
            if (DBG) log("separateCall: " + c.getAddress());
            c.separate();
        } catch (CallStateException ex) {
            Log.w(LOG_TAG, "separateCall: caught " + ex, ex);
        }
    }

    /**
     * Handle the MMIInitiate message and put up an alert that lets
     * the user cancel the operation, if applicable.
     *
     * @param context context to get strings.
     * @param mmiCode the MmiCode object being started.
     * @param buttonCallbackMessage message to post when button is clicked.
     * @param previousAlert a previous alert used in this activity.
     * @return the dialog handle
     */
    static Dialog displayMMIInitiate(Context context,
                                          MmiCode mmiCode,
                                          Message buttonCallbackMessage,
                                          Dialog previousAlert) {
        if (DBG) log("displayMMIInitiate: " + mmiCode);
        if (previousAlert != null) {
            previousAlert.dismiss();
        }

        // The UI paradigm we are using now requests that all dialogs have
        // user interaction, and that any other messages to the user should
        // be by way of Toasts.
        //
        // In adhering to this request, all MMI initiating "OK" dialogs
        // (non-cancelable MMIs) that end up being closed when the MMI
        // completes (thereby showing a completion dialog) are being
        // replaced with Toasts.
        //
        // As a side effect, moving to Toasts for the non-cancelable MMIs
        // also means that buttonCallbackMessage (which was tied into "OK")
        // is no longer invokable for these dialogs.  This is not a problem
        // since the only callback messages we supported were for cancelable
        // MMIs anyway.
        //
        // A cancelable MMI is really just a USSD request. The term
        // "cancelable" here means that we can cancel the request when the
        // system prompts us for a response, NOT while the network is
        // processing the MMI request.  Any request to cancel a USSD while
        // the network is NOT ready for a response may be ignored.
        //
        // With this in mind, we replace the cancelable alert dialog with
        // a progress dialog, displayed until we receive a request from
        // the the network.  For more information, please see the comments
        // in the displayMMIComplete() method below.
        //
        // Anything that is NOT a USSD request is a normal MMI request,
        // which will bring up a toast (desribed above).
        // Optional code for Extended USSD running prompt
        if (mNwService != null) {
            if (DBG) log("running USSD code, displaying indeterminate progress.");
            // create the indeterminate progress dialog and display it.
            ProgressDialog pd = new ProgressDialog(context);
            CharSequence textmsg = "";
            try {
                textmsg = mNwService.getMmiRunningText();
                
            } catch (RemoteException e) {
                mNwService = null;
                textmsg = context.getText(R.string.ussdRunning);            
            }
            if (DBG) log("Extended NW displayMMIInitiate (" + textmsg+ ")");
            pd.setMessage(textmsg);
            pd.setCancelable(false);
            pd.setIndeterminate(true);
            pd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            pd.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
            pd.show();
            // trigger a 15 seconds timeout to clear this progress dialog
            mMmiTimeoutCbMsg = buttonCallbackMessage;
            try {
                mMmiTimeoutCbMsg.getTarget().sendMessageDelayed(buttonCallbackMessage, 15000);
            } catch(NullPointerException e) {
                mMmiTimeoutCbMsg = null;
            }
            return pd;              
        }

        boolean isCancelable = (mmiCode != null) && mmiCode.isCancelable();

        if (!isCancelable) {
            if (DBG) log("not a USSD code, displaying status toast.");
            CharSequence text = context.getText(R.string.mmiStarted);
            Toast.makeText(context, text, Toast.LENGTH_SHORT)
                .show();
            return null;
        } else {
            if (DBG) log("running USSD code, displaying indeterminate progress.");

            // create the indeterminate progress dialog and display it.
            ProgressDialog pd = new ProgressDialog(context);
            pd.setMessage(context.getText(R.string.ussdRunning));
            pd.setCancelable(false);
            pd.setIndeterminate(true);
            pd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            pd.show();

            return pd;
        }

    }

    /**
     * Handle the MMIComplete message and fire off an intent to display
     * the message.
     *
     * @param context context to get strings.
     * @param mmiCode MMI result.
     * @param previousAlert a previous alert used in this activity.
     */
    static void displayMMIComplete(final Phone phone, Context context, final MmiCode mmiCode,
            Message dismissCallbackMessage,
            AlertDialog previousAlert) {
        CharSequence text;
        int title = 0;  // title for the progress dialog, if needed.
        MmiCode.State state = mmiCode.getState();

        if (DBG) log("displayMMIComplete: state=" + state);
        // Clear timeout trigger message
        if(mMmiTimeoutCbMsg != null) {
            try{
                mMmiTimeoutCbMsg.getTarget().removeMessages(mMmiTimeoutCbMsg.what);
                if (DBG) log("Extended NW displayMMIComplete removeMsg");
            } catch (NullPointerException e) {
            }
            mMmiTimeoutCbMsg = null;
        }


        switch (state) {
            case PENDING:
                // USSD code asking for feedback from user.
                text = mmiCode.getMessage();
                if (DBG) log("- using text from PENDING MMI message: '" + text + "'");
                break;
            case CANCELLED:
                text = context.getText(R.string.mmiCancelled);
                break;
            case COMPLETE:
                if (PhoneApp.getInstance().getPUKEntryActivity() != null) {
                    // if an attempt to unPUK the device was made, we specify
                    // the title and the message here.
                    title = com.android.internal.R.string.PinMmi;
                    text = context.getText(R.string.puk_unlocked);
                    break;
                }
                // All other conditions for the COMPLETE mmi state will cause
                // the case to fall through to message logic in common with
                // the FAILED case.

            case FAILED:
                text = mmiCode.getMessage();
                if (DBG) log("- using text from MMI message: '" + text + "'");
                break;
            default:
                throw new IllegalStateException("Unexpected MmiCode state: " + state);
        }

        if (previousAlert != null) {
            previousAlert.dismiss();
        }

        // Check to see if a UI exists for the PUK activation.  If it does
        // exist, then it indicates that we're trying to unblock the PUK.
        PhoneApp app = PhoneApp.getInstance();
        if ((app.getPUKEntryActivity() != null) && (state == MmiCode.State.COMPLETE)){
            if (DBG) log("displaying PUK unblocking progress dialog.");

            // create the progress dialog, make sure the flags and type are
            // set correctly.
            ProgressDialog pd = new ProgressDialog(app);
            pd.setTitle(title);
            pd.setMessage(text);
            pd.setCancelable(false);
            pd.setIndeterminate(true);
            pd.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
            pd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            // display the dialog
            pd.show();

            // indicate to the Phone app that the progress dialog has
            // been assigned for the PUK unlock / SIM READY process.
            app.setPukEntryProgressDialog(pd);

        } else {
            // In case of failure to unlock, we'll need to reset the
            // PUK unlock activity, so that the user may try again.
            if (app.getPUKEntryActivity() != null) {
                app.setPukEntryActivity(null);
            }

            // A USSD in a pending state means that it is still
            // interacting with the user.
            if (state != MmiCode.State.PENDING) {
                if (DBG) log("MMI code has finished running.");

                // Replace response message with Extended Mmi wording
                if (mNwService != null) {
                    try {
                        text = mNwService.getUserMessage(text);
                    } catch (RemoteException e) {
                        mNwService = null;
                    }
                    if (DBG) log("Extended NW displayMMIInitiate (" + text + ")");
                    if (text == null || text.length() == 0)
                        return;
                }

                // displaying system alert dialog on the screen instead of
                // using another activity to display the message.  This
                // places the message at the forefront of the UI.
                AlertDialog newDialog = new AlertDialog.Builder(context)
                        .setMessage(text)
                        .setPositiveButton(R.string.ok, null)
                        .setCancelable(true)
                        .create();

                newDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
                newDialog.getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                newDialog.show();
            } else {
                if (DBG) log("USSD code has requested user input. Constructing input dialog.");

                // USSD MMI code that is interacting with the user.  The
                // basic set of steps is this:
                //   1. User enters a USSD request
                //   2. We recognize the request and displayMMIInitiate
                //      (above) creates a progress dialog.
                //   3. Request returns and we get a PENDING or COMPLETE
                //      message.
                //   4. These MMI messages are caught in the PhoneApp
                //      (onMMIComplete) and the InCallScreen
                //      (mHandler.handleMessage) which bring up this dialog
                //      and closes the original progress dialog,
                //      respectively.
                //   5. If the message is anything other than PENDING,
                //      we are done, and the alert dialog (directly above)
                //      displays the outcome.
                //   6. If the network is requesting more information from
                //      the user, the MMI will be in a PENDING state, and
                //      we display this dialog with the message.
                //   7. User input, or cancel requests result in a return
                //      to step 1.  Keep in mind that this is the only
                //      time that a USSD should be canceled.

                // inflate the layout with the scrolling text area for the dialog.
                LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE);
                View dialogView = inflater.inflate(R.layout.dialog_ussd_response, null);

                // get the input field.
                final EditText inputText = (EditText) dialogView.findViewById(R.id.input_field);

                // specify the dialog's click listener, with SEND and CANCEL logic.
                final DialogInterface.OnClickListener mUSSDDialogListener =
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            switch (whichButton) {
                                case DialogInterface.BUTTON1:
                                    phone.sendUssdResponse(inputText.getText().toString());
                                    break;
                                case DialogInterface.BUTTON2:
                                    if (mmiCode.isCancelable()) {
                                        mmiCode.cancel();
                                    }
                                    break;
                            }
                        }
                    };

                // build the dialog
                final AlertDialog newDialog = new AlertDialog.Builder(context)
                        .setMessage(text)
                        .setView(dialogView)
                        .setPositiveButton(R.string.send_button, mUSSDDialogListener)
                        .setNegativeButton(R.string.cancel, mUSSDDialogListener)
                        .setCancelable(false)
                        .create();

                // attach the key listener to the dialog's input field and make
                // sure focus is set.
                final View.OnKeyListener mUSSDDialogInputListener =
                    new View.OnKeyListener() {
                        public boolean onKey(View v, int keyCode, KeyEvent event) {
                            switch (keyCode) {
                                case KeyEvent.KEYCODE_CALL:
                                case KeyEvent.KEYCODE_ENTER:
                                    phone.sendUssdResponse(inputText.getText().toString());
                                    newDialog.dismiss();
                                    return true;
                            }
                            return false;
                        }
                    };
                inputText.setOnKeyListener(mUSSDDialogInputListener);
                inputText.requestFocus();

                // set the window properties of the dialog
                newDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
                newDialog.getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                // now show the dialog!
                newDialog.show();
            }
        }
    }

    /**
     * Cancels the current pending MMI operation, if applicable.
     * @return true if we canceled an MMI operation, or false
     *         if the current pending MMI wasn't cancelable
     *         or if there was no current pending MMI at all.
     *
     * @see displayMMIInitiate
     */
    static boolean cancelMmiCode(Phone phone) {
        List<? extends MmiCode> pendingMmis = phone.getPendingMmiCodes();
        int count = pendingMmis.size();
        if (DBG) log("cancelMmiCode: num pending MMIs = " + count);

        boolean canceled = false;
        if (count > 0) {
            // assume that we only have one pending MMI operation active at a time.
            // I don't think it's possible to enter multiple MMI codes concurrently
            // in the phone UI, because during the MMI operation, an Alert panel
            // is displayed, which prevents more MMI code from being entered.
            MmiCode mmiCode = pendingMmis.get(0);
            if (mmiCode.isCancelable()) {
                mmiCode.cancel();
                canceled = true;
            }
        }

        //clear timeout message and pre-set MMI command
        if (mNwService != null) {
            try {
                mNwService.clearMmiString();
            } catch (RemoteException e) {
                mNwService = null;
            }            
        }
        if (mMmiTimeoutCbMsg != null) {
            mMmiTimeoutCbMsg = null;
        }
        return canceled;
    }

    public static class VoiceMailNumberMissingException extends Exception {
        VoiceMailNumberMissingException() {
            super();
        }

        VoiceMailNumberMissingException(String msg) {
            super(msg);
        }
    }

    /**
     * Gets the phone number to be called from an intent.  Requires a Context
     * to access the contacts database, and a Phone to access the voicemail
     * number.
     *
     * <p>If <code>phone</code> is <code>null</code>, the function will return
     * <code>null</code> for <code>voicemail:</code> URIs;
     * if <code>context</code> is <code>null</code>, the function will return
     * <code>null</code> for person/phone URIs.</p>
     *
     * @param context a context to use (or
     * @param phone the phone on which the number would be called
     * @param intent the intent
     *
     * @throws VoiceMailNumberMissingException if <code>intent</code> contains
     *         a <code>voicemail:</code> URI, but <code>phone</code> does not
     *         have a voicemail number set.
     *
     * @return the phone number that would be called by the intent,
     *         or <code>null</code> if the number cannot be found.
     */
    static String getNumberFromIntent(Context context, Phone phone, Intent intent)
            throws VoiceMailNumberMissingException {
        final String number = PhoneNumberUtils.getNumberFromIntent(intent, context);

        // Check for a voicemail-dailing request.  If the voicemail number is
        // empty, throw a VoiceMailNumberMissingException.
        if (intent.getData().getScheme().equals("voicemail") &&
                (number == null || TextUtils.isEmpty(number)))
            throw new VoiceMailNumberMissingException();

        return number;
    }

    /**
     * Returns the caller-id info corresponding to the specified Connection.
     * (This is just a simple wrapper around CallerInfo.getCallerInfo(): we
     * extract a phone number from the specified Connection, and feed that
     * number into CallerInfo.getCallerInfo().)
     *
     * The returned CallerInfo may be null in certain error cases, like if the
     * specified Connection was null, or if we weren't able to get a valid
     * phone number from the Connection.
     *
     * Finally, if the getCallerInfo() call did succeed, we save the resulting
     * CallerInfo object in the "userData" field of the Connection.
     *
     * NOTE: This API should be avoided, with preference given to the
     * asynchronous startGetCallerInfo API.
     */
    static CallerInfo getCallerInfo(Context context, Connection c) {
        CallerInfo info = null;

        if (c != null) {
            //See if there is a URI attached.  If there is, this means
            //that there is no CallerInfo queried yet, so we'll need to
            //replace the URI with a full CallerInfo object.
            Object userDataObject = c.getUserData();
            if (userDataObject instanceof Uri) {
                info = CallerInfo.getCallerInfo(context, (Uri) userDataObject);
                if (info != null) {
                    c.setUserData(info);
                }
            } else {
                if (userDataObject instanceof CallerInfoToken) {
                    //temporary result, while query is running
                    info = ((CallerInfoToken) userDataObject).currentInfo;
                } else {
                    //final query result
                    info = (CallerInfo) userDataObject;
                }
                if (info == null) {
                    // No URI, or Existing CallerInfo, so we'll have to make do with
                    // querying a new CallerInfo using the connection's phone number.
                    String number = c.getAddress();

                    if (DBG) log("getCallerInfo: number = " + number);

                    if (!TextUtils.isEmpty(number)) {
                        info = CallerInfo.getCallerInfo(context, number);
                        if (info != null) {
                            c.setUserData(info);
                        }
                    }
                }
            }
        }
        return info;
    }

    /**
     * Class returned by the startGetCallerInfo call to package a temporary
     * CallerInfo Object, to be superceded by the CallerInfo Object passed
     * into the listener when the query with token mAsyncQueryToken is complete.
     */
    public static class CallerInfoToken {
        /**indicates that there will no longer be updates to this request.*/
        public boolean isFinal;

        public CallerInfo currentInfo;
        public CallerInfoAsyncQuery asyncQuery;
    }

    /**
     * Start a CallerInfo Query based on the earliest connection in the call.
     */
    static CallerInfoToken startGetCallerInfo(Context context, Call call,
            CallerInfoAsyncQuery.OnQueryCompleteListener listener, Object cookie) {
        PhoneApp app = PhoneApp.getInstance();
        Connection conn = null;
        if (app.phone.getPhoneName().equals("CDMA")) {
            conn = call.getLatestConnection();
        } else {
            conn = call.getEarliestConnection();
        }

        return startGetCallerInfo(context, conn, listener, cookie);
    }

    /**
     * place a temporary callerinfo object in the hands of the caller and notify
     * caller when the actual query is done.
     */
    static CallerInfoToken startGetCallerInfo(Context context, Connection c,
            CallerInfoAsyncQuery.OnQueryCompleteListener listener, Object cookie) {
        CallerInfoToken cit;

        if (c == null) {
            //TODO: perhaps throw an exception here.
            cit = new CallerInfoToken();
            cit.asyncQuery = null;
            return cit;
        }

        // There are now 3 states for the userdata.
        //   1. Uri - query has not been executed yet
        //   2. CallerInfoToken - query is executing, but has not completed.
        //   3. CallerInfo - query has executed.
        // In each case we have slightly different behaviour:
        //   1. If the query has not been executed yet (Uri or null), we start
        //      query execution asynchronously, and note it by attaching a
        //      CallerInfoToken as the userData.
        //   2. If the query is executing (CallerInfoToken), we've essentially
        //      reached a state where we've received multiple requests for the
        //      same callerInfo.  That means that once the query is complete,
        //      we'll need to execute the additional listener requested.
        //   3. If the query has already been executed (CallerInfo), we just
        //      return the CallerInfo object as expected.
        //   4. Regarding isFinal - there are cases where the CallerInfo object
        //      will not be attached, like when the number is empty (caller id
        //      blocking).  This flag is used to indicate that the
        //      CallerInfoToken object is going to be permanent since no
        //      query results will be returned.  In the case where a query
        //      has been completed, this flag is used to indicate to the caller
        //      that the data will not be updated since it is valid.
        //
        //      Note: For the case where a number is NOT retrievable, we leave
        //      the CallerInfo as null in the CallerInfoToken.  This is
        //      something of a departure from the original code, since the old
        //      code manufactured a CallerInfo object regardless of the query
        //      outcome.  From now on, we will append an empty CallerInfo
        //      object, to mirror previous behaviour, and to avoid Null Pointer
        //      Exceptions.
        Object userDataObject = c.getUserData();
        if (userDataObject instanceof Uri) {
            //create a dummy callerinfo, populate with what we know from URI.
            cit = new CallerInfoToken();
            cit.currentInfo = new CallerInfo();
            cit.asyncQuery = CallerInfoAsyncQuery.startQuery(QUERY_TOKEN, context,
                    (Uri) userDataObject, sCallerInfoQueryListener, c);
            cit.asyncQuery.addQueryListener(QUERY_TOKEN, listener, cookie);
            cit.isFinal = false;

            c.setUserData(cit);

            if (DBG) log("startGetCallerInfo: query based on Uri: " + userDataObject);

        } else if (userDataObject == null) {
            // No URI, or Existing CallerInfo, so we'll have to make do with
            // querying a new CallerInfo using the connection's phone number.
            String number = c.getAddress();

            cit = new CallerInfoToken();
            cit.currentInfo = new CallerInfo();

            if (DBG) log("startGetCallerInfo: number = " + number);

            // handling case where number is null (caller id hidden) as well.
            if (!TextUtils.isEmpty(number)) {
                cit.currentInfo.phoneNumber = number;
                // Store CNAP information retrieved from the Connection
                cit.currentInfo.cnapName =  c.getCnapName();
                cit.currentInfo.name = cit.currentInfo.cnapName; // This can still get overwritten
                                                                 // by ContactInfo later
                cit.currentInfo.numberPresentation = c.getNumberPresentation();
                cit.currentInfo.namePresentation = c.getCnapNamePresentation();
                if (DBG) log("startGetCallerInfo: CNAP Info from FW: name="
                        + cit.currentInfo.cnapName
                        + ", Name/Number Pres=" + cit.currentInfo.numberPresentation);
                cit.asyncQuery = CallerInfoAsyncQuery.startQuery(QUERY_TOKEN, context,
                        number, sCallerInfoQueryListener, c);
                cit.asyncQuery.addQueryListener(QUERY_TOKEN, listener, cookie);
                cit.isFinal = false;
            } else {
                // This is the case where we are querying on a number that
                // is null or empty, like a caller whose caller id is
                // blocked or empty (CLIR).  The previous behaviour was to
                // throw a null CallerInfo object back to the user, but
                // this departure is somewhat cleaner.
                if (DBG) log("startGetCallerInfo: No query to start, send trivial reply.");
                cit.isFinal = true; // please see note on isFinal, above.
            }

            c.setUserData(cit);

            if (DBG) log("startGetCallerInfo: query based on number: " + number);

        } else if (userDataObject instanceof CallerInfoToken) {
            // query is running, just tack on this listener to the queue.
            cit = (CallerInfoToken) userDataObject;

            // handling case where number is null (caller id hidden) as well.
            if (cit.asyncQuery != null) {
                cit.asyncQuery.addQueryListener(QUERY_TOKEN, listener, cookie);

                if (DBG) log("startGetCallerInfo: query already running, adding listener: " +
                        listener.getClass().toString());
            } else {
                // handling case where number/name gets updated later on by the network
                String updatedNumber = c.getAddress();
                if (DBG) log("startGetCallerInfo: updatedNumber = " + updatedNumber);
                if (!TextUtils.isEmpty(updatedNumber)) {
                    cit.currentInfo.phoneNumber = updatedNumber;

                    // Store CNAP information retrieved from the Connection
                    cit.currentInfo.cnapName =  c.getCnapName();
                    // This can still get overwritten by ContactInfo
                    cit.currentInfo.name = cit.currentInfo.cnapName;
                    cit.currentInfo.numberPresentation = c.getNumberPresentation();
                    cit.currentInfo.namePresentation = c.getCnapNamePresentation();

                    if (DBG) log("startGetCallerInfo: CNAP Info from FW: name="
                            + cit.currentInfo.cnapName
                            + ", Name/Number Pres=" + cit.currentInfo.numberPresentation);
                    cit.asyncQuery = CallerInfoAsyncQuery.startQuery(QUERY_TOKEN, context,
                            updatedNumber, sCallerInfoQueryListener, c);
                    cit.asyncQuery.addQueryListener(QUERY_TOKEN, listener, cookie);
                    cit.isFinal = false;
                } else {
                    if (DBG) log("startGetCallerInfo: No query to attach to, send trivial reply.");
                    if (cit.currentInfo == null) {
                        cit.currentInfo = new CallerInfo();
                    }
                    cit.isFinal = true; // please see note on isFinal, above.
                }
            }
        } else {
            cit = new CallerInfoToken();
            cit.currentInfo = (CallerInfo) userDataObject;
            cit.asyncQuery = null;
            cit.isFinal = true;
            // since the query is already done, call the listener.
            if (DBG) log("startGetCallerInfo: query already done, returning CallerInfo");
        }
        return cit;
    }

    /**
     * Implemented for CallerInfo.OnCallerInfoQueryCompleteListener interface.
     * Updates the connection's userData when called.
     */
    private static final int QUERY_TOKEN = -1;
    static CallerInfoAsyncQuery.OnQueryCompleteListener sCallerInfoQueryListener =
        new CallerInfoAsyncQuery.OnQueryCompleteListener () {
            public void onQueryComplete(int token, Object cookie, CallerInfo ci){
                if (DBG) log("query complete, updating connection.userdata");

                // Added a check if CallerInfo is coming from ContactInfo or from Connection.
                // If no ContactInfo, then we want to use CNAP information coming from network
                if (DBG) log("- onQueryComplete: contactExists=" + ci.contactExists);
                if (ci.contactExists) {
                    ((Connection) cookie).setUserData(ci);
                } else {
                    CallerInfo newCi = getCallerInfo(null, (Connection) cookie);
                    if (newCi != null) {
                        newCi.phoneNumber = ci.phoneNumber; // To get formatted phone number
                        ((Connection) cookie).setUserData(newCi);
                    }
                    else ((Connection) cookie).setUserData(ci);
                }
            }
        };

    static void saveToContact(Context context, String number) {
        Intent intent = new Intent(Contacts.Intents.Insert.ACTION,
                Contacts.People.CONTENT_URI);
        intent.putExtra(Contacts.Intents.Insert.PHONE, number);
        context.startActivity(intent);
    }

    /**
     * Returns a single "name" for the specified given a CallerInfo object.
     * If the name is null, return defaultString as the default value, usually
     * context.getString(R.string.unknown).
     */
    static String getCompactNameFromCallerInfo(CallerInfo ci, Context context) {
        if (DBG) log("getCompactNameFromCallerInfo: info = " + ci);

        String compactName = null;
        if (ci != null) {
            compactName = ci.name;
            if ((compactName == null) || (TextUtils.isEmpty(compactName))) {
                compactName = ci.phoneNumber;
            }
        }
        // TODO: figure out UNKNOWN, PRIVATE numbers?
        if ((compactName == null) || (TextUtils.isEmpty(compactName))) {
            compactName = context.getString(R.string.unknown);
        }
        return compactName;
    }

    /**
     * Returns true if the specified Call is a "conference call", meaning
     * that it owns more than one Connection object.  This information is
     * used to trigger certain UI changes that appear when a conference
     * call is active (like displaying the label "Conference call", and
     * enabling the "Manage conference" UI.)
     *
     * Watch out: This method simply checks the number of Connections,
     * *not* their states.  So if a Call has (for example) one ACTIVE
     * connection and one DISCONNECTED connection, this method will return
     * true (which is unintuitive, since the Call isn't *really* a
     * conference call any more.)
     *
     * @return true if the specified call has more than one connection (in any state.)
     */
    static boolean isConferenceCall(Call call) {
        // CDMA phones don't have the same concept of "conference call" as
        // GSM phones do; there's no special "conference call" state of
        // the UI or a "manage conference" function.  (Instead, when
        // you're in a 3-way call, all we can do is display the "generic"
        // state of the UI.)  So as far as the in-call UI is concerned,
        // Conference corresponds to generic display.
        PhoneApp app = PhoneApp.getInstance();
        if (app.phone.getPhoneName().equals("CDMA")) {
            if (app.cdmaPhoneCallState.getCurrentCallState()
                    == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                return true;
            }
        } else {
            List<Connection> connections = call.getConnections();
            if (connections != null && connections.size() > 1) {
                return true;
            }
        }
        return false;

        // TODO: We may still want to change the semantics of this method
        // to say that a given call is only really a conference call if
        // the number of ACTIVE connections, not the total number of
        // connections, is greater than one.  (See warning comment in the
        // javadoc above.)
        // Here's an implementation of that:
        //        if (connections == null) {
        //            return false;
        //        }
        //        int numActiveConnections = 0;
        //        for (Connection conn : connections) {
        //            if (DBG) log("  - CONN: " + conn + ", state = " + conn.getState());
        //            if (conn.getState() == Call.State.ACTIVE) numActiveConnections++;
        //            if (numActiveConnections > 1) {
        //                return true;
        //            }
        //        }
        //        return false;
    }

    /**
     * Launch the Dialer to start a new call.
     * This is just a wrapper around the ACTION_DIAL intent.
     */
    static void startNewCall(final Phone phone) {
        final KeyguardManager keyguardManager = PhoneApp.getInstance().getKeyguardManager();
        if (!keyguardManager.inKeyguardRestrictedInputMode()) {
            internalStartNewCall(phone);
        } else {
            keyguardManager.exitKeyguardSecurely(new KeyguardManager.OnKeyguardExitResult() {
                public void onKeyguardExitResult(boolean success) {
                    if (success) {
                        internalStartNewCall(phone);
                    }
                }
            });
        }
    }

    private static void internalStartNewCall(Phone phone) {
        // Sanity-check that this is OK given the current state of the phone.
        if (!okToAddCall(phone)) {
            Log.w(LOG_TAG, "startNewCall: can't add a new call in the current state");
            dumpCallState(phone);
            return;
        }

        // if applicable, mute the call while we're showing the add call UI.
        if (!phone.getForegroundCall().isIdle()) {
            setMuteInternal(phone, true);
            // Inform the phone app that this mute state was NOT done
            // voluntarily by the User.
            PhoneApp.getInstance().setRestoreMuteOnInCallResume(true);
        }

        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // when we request the dialer come up, we also want to inform
        // it that we're going through the "add call" option from the
        // InCallScreen / PhoneUtils.
        intent.putExtra(ADD_CALL_MODE_KEY, true);

        PhoneApp.getInstance().startActivity(intent);
    }

    /**
     * Brings up the UI used to handle an incoming call.
     *
     * Originally, this brought up an IncomingCallPanel instance
     * (which was a subclass of Dialog) on top of whatever app
     * was currently running.  Now, we take you directly to the
     * in-call screen, whose CallCard automatically does the right
     * thing if there's a Call that's currently ringing.
     */
    static void showIncomingCallUi() {
        if (DBG) log("showIncomingCallUi()...");
        PhoneApp app = PhoneApp.getInstance();

        // Before bringing up the "incoming call" UI, force any system
        // dialogs (like "recent tasks" or the power dialog) to close first.
        try {
            ActivityManagerNative.getDefault().closeSystemDialogs("call");
        } catch (RemoteException e) {
        }

        // Go directly to the in-call screen.
        // (No need to do anything special if we're already on the in-call
        // screen; it'll notice the phone state change and update itself.)

        // But first, grab a full wake lock.  We do this here, before we
        // even fire off the InCallScreen intent, to make sure the
        // ActivityManager doesn't try to pause the InCallScreen as soon
        // as it comes up.  (See bug 1648751.)
        //
        // And since the InCallScreen isn't visible yet (we haven't even
        // fired off the intent yet), we DON'T want the screen to actually
        // come on right now.  So *before* acquiring the wake lock we need
        // to call preventScreenOn(), which tells the PowerManager that
        // the screen should stay off even if someone's holding a full
        // wake lock.  (This prevents any flicker during the "incoming
        // call" sequence.  The corresponding preventScreenOn(false) call
        // will come from the InCallScreen when it's finally ready to be
        // displayed.)
        //
        // TODO: this is all a temporary workaround.  The real fix is to add
        // an Activity attribute saying "this Activity wants to wake up the
        // phone when it's displayed"; that way the ActivityManager could
        // manage the wake locks *and* arrange for the screen to come on at
        // the exact moment that the InCallScreen is ready to be displayed.
        // (See bug 1648751.)
        app.preventScreenOn(true);
        app.requestWakeState(PhoneApp.WakeState.FULL);

        // Fire off the InCallScreen intent.
        app.displayCallScreen();
    }

    static void turnOnSpeaker(Context context, boolean flag) {
        if (DBG) log("turnOnSpeaker: " + flag);
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        audioManager.setSpeakerphoneOn(flag);
        // record the speaker-enable value
        sIsSpeakerEnabled = flag;
        if (flag) {
            NotificationMgr.getDefault().notifySpeakerphone();
        } else {
            NotificationMgr.getDefault().cancelSpeakerphone();
        }

        // We also need to make a fresh call to PhoneApp.updateWakeState()
        // any time the speaker state changes, since the screen timeout is
        // sometimes different depending on whether or not the speaker is
        // in use.
        PhoneApp app = PhoneApp.getInstance();
        app.updateWakeState();
    }

    /**
     * Restore the speaker mode, called after a wired headset disconnect
     * event.
     */
    static void restoreSpeakerMode(Context context) {
        if (DBG) log("restoreSpeakerMode, restoring to: " + sIsSpeakerEnabled);

        // change the mode if needed.
        if (isSpeakerOn(context) != sIsSpeakerEnabled) {
            turnOnSpeaker(context, sIsSpeakerEnabled);
        }
    }

    static boolean isSpeakerOn(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return audioManager.isSpeakerphoneOn();
    }

    /**
     * Wrapper around Phone.setMute() that also updates the mute icon in
     * the status bar.
     *
     * All muting / unmuting from the in-call UI should go through this
     * wrapper.
     */
    static void setMute(Phone phone, boolean muted) {
        // make the call to mute the audio
        setMuteInternal(phone, muted);

        // update the foreground connections to match.  This includes
        // all the connections on conference calls.
        for (Connection cn : phone.getForegroundCall().getConnections()) {
            if (sConnectionMuteTable.get(cn) == null) {
                if (DBG) log("problem retrieving mute value for this connection.");
            }
            sConnectionMuteTable.put(cn, Boolean.valueOf(muted));
        }
    }

    /**
     * Internally used muting function.  All UI calls should use {@link setMute}
     */
    static void setMuteInternal(Phone phone, boolean muted) {
        if (DBG) log("setMute: " + muted);
        phone.setMute(muted);
        if (muted) {
            NotificationMgr.getDefault().notifyMute();
        } else {
            NotificationMgr.getDefault().cancelMute();
        }
    }

    static boolean getMute(Phone phone) {
        return phone.getMute();
    }

    /**
     * A really simple wrapper around AudioManager.setMode(),
     * with a bit of extra logging to help debug the exact
     * timing (and call stacks) for all our setMode() calls.
     *
     * Also, add additional state monitoring to determine
     * whether or not certain calls to change the audio mode
     * are ignored.
     */
    /* package */ static void setAudioMode(Context context, int mode) {
        if (DBG) Log.d(LOG_TAG, "PhoneUtils.setAudioMode(" + audioModeToString(mode) + ")...");

        //decide whether or not to ignore the audio setting
        boolean ignore = false;

        switch (sAudioBehaviourState) {
            case AUDIO_RINGING:
                ignore = ((mode == AudioManager.MODE_NORMAL) || (mode == AudioManager.MODE_IN_CALL));
                break;
            case AUDIO_OFFHOOK:
                ignore = ((mode == AudioManager.MODE_NORMAL) || (mode == AudioManager.MODE_RINGTONE));
                break;
            case AUDIO_IDLE:
            default:
                ignore = (mode == AudioManager.MODE_IN_CALL);
                break;
        }

        if (!ignore) {
            AudioManager audioManager = 
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            // Enable stack dump only when actively debugging ("new Throwable()" is expensive!)
            if (DBG_SETAUDIOMODE_STACK) Log.d(LOG_TAG, "Stack:", new Throwable("stack dump"));
            audioManager.setMode(mode);
        } else {
            if (DBG) Log.d(LOG_TAG, "PhoneUtils.setAudioMode(), state is " + sAudioBehaviourState +
                    " ignoring " + audioModeToString(mode) + " request");
        }
    }
    private static String audioModeToString(int mode) {
        switch (mode) {
            case AudioManager.MODE_INVALID: return "MODE_INVALID";
            case AudioManager.MODE_CURRENT: return "MODE_CURRENT";
            case AudioManager.MODE_NORMAL: return "MODE_NORMAL";
            case AudioManager.MODE_RINGTONE: return "MODE_RINGTONE";
            case AudioManager.MODE_IN_CALL: return "MODE_IN_CALL";
            default: return String.valueOf(mode);
        }
    }

    /**
     * Handles the wired headset button while in-call.
     *
     * This is called from the PhoneApp, not from the InCallScreen,
     * since the HEADSETHOOK button means "mute or unmute the current
     * call" *any* time a call is active, even if the user isn't actually
     * on the in-call screen.
     *
     * @return true if we consumed the event.
     */
    /* package */ static boolean handleHeadsetHook(Phone phone) {
        if (DBG) log("handleHeadsetHook()...");

        // If the phone is totally idle, we ignore HEADSETHOOK events
        // (and instead let them fall through to the media player.)
        if (phone.getState() == Phone.State.IDLE) {
            return false;
        }

        // Ok, the phone is in use.
        // The headset button button means "Answer" if an incoming call is
        // ringing.  If not, it toggles the mute / unmute state.
        //
        // And in any case we *always* consume this event; this means
        // that the usual mediaplayer-related behavior of the headset
        // button will NEVER happen while the user is on a call.

        final boolean hasRingingCall = !phone.getRingingCall().isIdle();
        final boolean hasActiveCall = !phone.getForegroundCall().isIdle();
        final boolean hasHoldingCall = !phone.getBackgroundCall().isIdle();

        if (phone.getPhoneName().equals("CDMA")) {
            PhoneApp app = PhoneApp.getInstance();
            if (hasRingingCall) {
                answerCall(phone);
            } else {
                if (app.cdmaPhoneCallState.getCurrentCallState()
                        == CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE) {
                    // Send a flash command to CDMA network for putting the other
                    // party on hold.
                    // For CDMA networks which do not support this the user would just
                    // hear a beep from the network.
                    // For CDMA networks which do support it it will put the other
                    // party on hold.
                    switchHoldingAndActive(phone);
                }

                // No incoming ringing call.  Toggle the mute state.
                if (getMute(phone)) {
                    if (DBG) log("handleHeadsetHook: UNmuting...");
                    setMute(phone, false);
                } else {
                    if (DBG) log("handleHeadsetHook: muting...");
                    setMute(phone, true);
                }
            }
        } else { // GSM
            if (hasRingingCall) {
                // If an incoming call is ringing, answer it (just like with the
                // CALL button):
                if (hasActiveCall && hasHoldingCall) {
                    if (DBG) log("handleHeadsetHook: ringing (both lines in use) ==> answer!");
                    answerAndEndActive(phone);
                } else {
                    if (DBG) log("handleHeadsetHook: ringing ==> answer!");
                    answerCall(phone);  // Automatically holds the current active call,
                                     // if there is one
                }
            } else {
                // No incoming ringing call.  Toggle the mute state.
                if (getMute(phone)) {
                    if (DBG) log("handleHeadsetHook: UNmuting...");
                    setMute(phone, false);
                } else {
                    if (DBG) log("handleHeadsetHook: muting...");
                    setMute(phone, true);
                }
            }
        }

        // Even if the InCallScreen is the current activity, there's no
        // need to force it to update, because (1) if we answered a
        // ringing call, the InCallScreen will imminently get a phone
        // state change event (causing an update), and (2) if we muted or
        // unmuted, the setMute() call automagically updates the status
        // bar, and there's no "mute" indication in the InCallScreen
        // itself (other than the menu item, which only ever stays
        // onscreen for a second anyway.)

        return true;
    }

    /**
     * Look for ANY connections on the phone that qualify as being
     * disconnected.
     *
     * @return true if we find a connection that is disconnected over
     * all the phone's call objects.
     */
    /* package */ static boolean hasDisconnectedConnections(Phone phone) {
        return hasDisconnectedConnections(phone.getForegroundCall()) ||
                hasDisconnectedConnections(phone.getBackgroundCall()) ||
                hasDisconnectedConnections(phone.getRingingCall());
    }

    /**
     * Iterate over all connections in a call to see if there are any
     * that are not alive (disconnected or idle).
     *
     * @return true if we find a connection that is disconnected, and
     * pending removal via
     * {@link com.android.internal.telephony.gsm.GsmCall#clearDisconnected()}.
     */
    private static final boolean hasDisconnectedConnections(Call call) {
        // look through all connections for non-active ones.
        for (Connection c : call.getConnections()) {
            if (!c.isAlive()) {
                return true;
            }
        }
        return false;
    }

    //
    // Misc UI policy helper functions
    //

    /**
     * @return true if we're allowed to swap calls, given the current
     * state of the Phone.
     */
    /* package */ static boolean okToSwapCalls(Phone phone) {
        if (phone.getPhoneName().equals("CDMA")) {
            // CDMA: "Swap" is enabled only when the phone reaches a *generic*.
            // state by either accepting a Call Waiting or by merging two calls
            PhoneApp app = PhoneApp.getInstance();
            return (app.cdmaPhoneCallState.getCurrentCallState()
                    == CdmaPhoneCallState.PhoneCallState.CONF_CALL);
        } else {
            // GSM: "Swap" is available if both lines are in use and there's no
            // incoming call.  (Actually we need to verify that the active
            // call really is in the ACTIVE state and the holding call really
            // is in the HOLDING state, since you *can't* actually swap calls
            // when the foreground call is DIALING or ALERTING.)
            return phone.getRingingCall().isIdle()
                    && (phone.getForegroundCall().getState() == Call.State.ACTIVE)
                    && (phone.getBackgroundCall().getState() == Call.State.HOLDING);
        }
    }

    /**
     * @return true if we're allowed to merge calls, given the current
     * state of the Phone.
     */
    /* package */ static boolean okToMergeCalls(Phone phone) {
        if (phone.getPhoneName().equals("CDMA")) {
            // CDMA: "Merge" is enabled only when the user is in a 3Way call.
            PhoneApp app = PhoneApp.getInstance();
            return (app.cdmaPhoneCallState.getCurrentCallState()
                    == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE);
        } else { //GSM.
            // GSM: "Merge" is available if both lines are in use and there's no
            // incoming call, *and* the current conference isn't already
            // "full".
            return phone.getRingingCall().isIdle() && phone.canConference();
        }
    }

    /**
     * @return true if the UI should let you add a new call, given the current
     * state of the Phone.
     */
    /* package */ static boolean okToAddCall(Phone phone) {
       if (phone.getPhoneName().equals("CDMA")) {
           // CDMA: "Add call" menu item is only enabled when the call is in
           // - SINGLE_ACTIVE state
           // - After 60 seconds of user Ignoring/Missing a Call Waiting call.
            PhoneApp app = PhoneApp.getInstance();
            return ((app.cdmaPhoneCallState.getCurrentCallState()
                    == CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE)
                    && (app.cdmaPhoneCallState.getAddCallMenuStateAfterCallWaiting()));
        } else {
            // GSM: "Add call" is available only if ALL of the following are true:
            // - There's no incoming ringing call
            // - There's < 2 lines in use
            // - The foreground call is ACTIVE or IDLE or DISCONNECTED.
            //   (We mainly need to make sure it *isn't* DIALING or ALERTING.)
            final boolean hasRingingCall = !phone.getRingingCall().isIdle();
            final boolean hasActiveCall = !phone.getForegroundCall().isIdle();
            final boolean hasHoldingCall = !phone.getBackgroundCall().isIdle();
            final boolean allLinesTaken = hasActiveCall && hasHoldingCall;
            final Call.State fgCallState = phone.getForegroundCall().getState();

            return !hasRingingCall
                    && !allLinesTaken
                    && ((fgCallState == Call.State.ACTIVE)
                        || (fgCallState == Call.State.IDLE)
                        || (fgCallState == Call.State.DISCONNECTED));
        }
    }

    //
    // General phone and call state debugging/testing code
    //

    /* package */ static void dumpCallState(Phone phone) {
        Log.d(LOG_TAG, "##### dumpCallState()");
        Log.d(LOG_TAG, "- Phone: " + phone + ", name = " + phone.getPhoneName()
              + ", state = " + phone.getState());
        Log.d(LOG_TAG, "-");

        Call fgCall = phone.getForegroundCall();
        Log.d(LOG_TAG, "- FG call: " + fgCall);
        Log.d(LOG_TAG, "-  state: " + fgCall.getState());
        Log.d(LOG_TAG, "-  isAlive(): " + fgCall.getState().isAlive());
        Log.d(LOG_TAG, "-  isRinging(): " + fgCall.getState().isRinging());
        Log.d(LOG_TAG, "-  isDialing(): " + fgCall.getState().isDialing());
        Log.d(LOG_TAG, "-  isIdle(): " + fgCall.isIdle());
        Log.d(LOG_TAG, "-  hasConnections: " + fgCall.hasConnections());
        Log.d(LOG_TAG, "-");

        Call bgCall = phone.getBackgroundCall();
        Log.d(LOG_TAG, "- BG call: " + bgCall);
        Log.d(LOG_TAG, "-  state: " + bgCall.getState());
        Log.d(LOG_TAG, "-  isAlive(): " + bgCall.getState().isAlive());
        Log.d(LOG_TAG, "-  isRinging(): " + bgCall.getState().isRinging());
        Log.d(LOG_TAG, "-  isDialing(): " + bgCall.getState().isDialing());
        Log.d(LOG_TAG, "-  isIdle(): " + bgCall.isIdle());
        Log.d(LOG_TAG, "-  hasConnections: " + bgCall.hasConnections());
        Log.d(LOG_TAG, "-");

        Call ringingCall = phone.getRingingCall();
        Log.d(LOG_TAG, "- RINGING call: " + ringingCall);
        Log.d(LOG_TAG, "-  state: " + ringingCall.getState());
        Log.d(LOG_TAG, "-  isAlive(): " + ringingCall.getState().isAlive());
        Log.d(LOG_TAG, "-  isRinging(): " + ringingCall.getState().isRinging());
        Log.d(LOG_TAG, "-  isDialing(): " + ringingCall.getState().isDialing());
        Log.d(LOG_TAG, "-  isIdle(): " + ringingCall.isIdle());
        Log.d(LOG_TAG, "-  hasConnections: " + ringingCall.hasConnections());
        Log.d(LOG_TAG, "-");

        final boolean hasRingingCall = !phone.getRingingCall().isIdle();
        final boolean hasActiveCall = !phone.getForegroundCall().isIdle();
        final boolean hasHoldingCall = !phone.getBackgroundCall().isIdle();
        final boolean allLinesTaken = hasActiveCall && hasHoldingCall;
        Log.d(LOG_TAG, "- hasRingingCall: " + hasRingingCall);
        Log.d(LOG_TAG, "- hasActiveCall: " + hasActiveCall);
        Log.d(LOG_TAG, "- hasHoldingCall: " + hasHoldingCall);
        Log.d(LOG_TAG, "- allLinesTaken: " + allLinesTaken);

        // Watch out: the isRinging() call below does NOT tell us anything
        // about the state of the telephony layer; it merely tells us whether
        // the Ringer manager is currently playing the ringtone.
        boolean ringing = PhoneApp.getInstance().getRinger().isRinging();
        Log.d(LOG_TAG, "- ringing (Ringer manager state): " + ringing);
        Log.d(LOG_TAG, "-----");
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

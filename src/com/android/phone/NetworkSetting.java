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

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.gsm.NetworkInfo;
import com.android.internal.telephony.gsm.NetworkInfo.RAT;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.gsm.NetworkInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

/**
 * "Networks" settings UI for the Phone app.
 */
public class NetworkSetting extends PreferenceActivity
        implements DialogInterface.OnCancelListener,
        Preference.OnPreferenceChangeListener {

    private static final String LOG_TAG = "phone";
    private static final boolean DBG = false;

    private static final int EVENT_NETWORK_SCAN_COMPLETED = 100;
    private static final int EVENT_NETWORK_SELECTION_DONE = 200;
    private static final int EVENT_AUTO_SELECT_DONE = 300;
    private static final int EVENT_SERVICE_STATE_CHANGED = 400;

    //dialog ids
    private static final int DIALOG_NETWORK_SELECTION = 100;
    private static final int DIALOG_NETWORK_LIST_LOAD = 200;
    private static final int DIALOG_NETWORK_AUTO_SELECT = 300;
    private static final int DIALOG_NETWORK_FORBIDDEN = 400;

    //String keys for preference lookup
    private static final String LIST_NETWORKS_KEY = "network_list";
    private static final String BUTTON_NETWORK_SEARCH_MODE_KEY = "button_network_search_mode_key";
    private static final String BUTTON_NETWORK_SEARCH_KEY = "button_network_search_key";

    private static final int AUTOMATIC = 1;
    private static final int MANUAL = 0;
    private int mMode = -1;
    private boolean mSkipNextAutoReselect = true;

    //map of network controls to the network data.
    private HashMap<Preference, NetworkInfo> mNetworkMap;

    Phone mPhone;
    protected boolean mIsForeground = false;

    /** message for network selection */
    String mNetworkSelectMsg;

    // flag to check if the activity is onPause.
    private boolean mOnPause = false;

    //preference objects
    private ProgressCategory mNetworkList;
    private ListPreference mButtonNetworkSearchMode;
    private Preference mButtonNetworkSearch;
    private Preference selectedCarrier = null;
    private Preference currentNetwork = null;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;
            switch (msg.what) {
                case EVENT_NETWORK_SCAN_COMPLETED:
                    networksListLoaded ((List<NetworkInfo>) msg.obj, msg.arg1);
                    break;

                case EVENT_AUTO_SELECT_DONE:
                case EVENT_NETWORK_SELECTION_DONE:
                    removeDialog(DIALOG_NETWORK_SELECTION);
                    removeDialog(DIALOG_NETWORK_AUTO_SELECT);

                    ar = (AsyncResult) msg.obj;

                    // Check network selection status
                    if (ar.exception != null) {
                        if (DBG) log("---> network selection failed !");
                        displayNetworkSelectionFailed(ar.exception);

                        // Unselect current network
                        if (currentNetwork != null) {
                            ((NetworkPreference)currentNetwork).unsetCurrentNetwork();
                            currentNetwork = null;
                        }
                        selectedCarrier = null;
                    } else {
                        if (DBG) log("---> network selection succeeded !");

                        // Update selected network
                        if (selectedCarrier != null) {
                            NetworkInfo ni = mNetworkMap.get(selectedCarrier);
                            if (updateCurrentNetwork(ni)) {
                                displayNetworkSelectionSucceeded();
                            }
                        }
                    }
                    break;

                case EVENT_SERVICE_STATE_CHANGED:
                    if (DBG) log("---> network reselected automatically !");
                    ServiceState ss = (ServiceState) ((AsyncResult) msg.obj).result;
                    updateNetworkFromServiceState(ss);
                    break;

                default:
                    if (DBG) log("unknown event: "+msg.what);
                    break;
            }
            return;
        }
    };

    @Override
    protected void onPause() {
        mOnPause = true;
        super.onPause();
    }

    @Override
    protected void onResume() {
        mOnPause = false;
        super.onResume();
    }

    /**
     * Service connection code for the NetworkQueryService.
     * Handles the work of binding to a local object so that we can make
     * the appropriate service calls.
     */

    /** Local service interface */
    private INetworkQueryService mNetworkQueryService = null;

    /** Service connection */
    private final ServiceConnection mNetworkQueryServiceConnection = new ServiceConnection() {

        /** Handle the task of binding the local object to the service */
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) log("connection created, binding local service.");
            mNetworkQueryService = ((NetworkQueryService.LocalBinder) service).getService();
            // as soon as it is bound, run a query.
            loadNetworksList();
        }

        /** Handle the task of cleaning up the local binding */
        public void onServiceDisconnected(ComponentName className) {
            if (DBG) log("connection disconnected, cleaning local binding.");
            mNetworkQueryService = null;
        }
    };

    /**
     * This implementation of INetworkQueryServiceCallback is used to receive
     * callback notifications from the network query service.
     */
    private final INetworkQueryServiceCallback mCallback = new INetworkQueryServiceCallback.Stub() {

        /** place the message on the looper queue upon query completion. */
        public void onQueryComplete(List<NetworkInfo> networkInfoArray, int status) {
            if (DBG) log("notifying message loop of query completion.");
            Message msg = mHandler.obtainMessage(EVENT_NETWORK_SCAN_COMPLETED,
                    status, 0, networkInfoArray);
            msg.sendToTarget();
        }
    };

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        boolean handled = false;

        if (preference == mButtonNetworkSearchMode) {
            mButtonNetworkSearchMode.setValue(Integer.toString(mMode));
            handled = true;
        } else if (preference == mButtonNetworkSearch) {
            loadNetworksList();
            handled = true;
        }  else {
            if (DBG) log("Selected carrier: " + preference.getTitle());
            selectedCarrier = preference;
            NetworkInfo ni = mNetworkMap.get(selectedCarrier);

            // Check network state before selecting it
            if (isNetworkForbidden(ni)) {
                showDialog(DIALOG_NETWORK_FORBIDDEN);
            } else {
                handled = selectNetworkCarrier(selectedCarrier);
            }
        }
        return handled;
    }

    /**
     * Listens to preference changes
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {

        if (preference == mButtonNetworkSearchMode) {
            int selectedSearchMode = Integer.valueOf((String) objValue).intValue();

            // Check if the network search mode has changed
            if (selectedSearchMode != mMode) {
                setMode(selectedSearchMode);

                // Reselect network with the new mode
                if (selectedSearchMode == MANUAL) {
                    if (DBG) log("Manual search");
                    Settings.System.putString(getContentResolver(),
                            Settings.System.NETWORK_SELECTION_MODE, "Manual");

                    if (currentNetwork != null) {
                        selectNetworkCarrier(currentNetwork);
                    }
                } else if (selectedSearchMode == AUTOMATIC) {
                    if (DBG) log("Automatic search");
                    Settings.System.putString(getContentResolver(),
                            Settings.System.NETWORK_SELECTION_MODE, "Automatic");

                    // Network will be selected automatically
                    // after the network search has completed
                    loadNetworksList();
                }
            }
        }

        // always let the preference setting proceed.
        return true;
    }

    private boolean selectNetworkCarrier(Preference carrier) {
        NetworkInfo info = mNetworkMap.get(carrier);

        if (info != null) {
            // Check network selection mode
            NetworkInfo.SelectionMode mode = (mMode == AUTOMATIC ?
                    NetworkInfo.SelectionMode.AUTOMATIC :
                        NetworkInfo.SelectionMode.MANUAL);

            int event = (mMode == AUTOMATIC ? EVENT_AUTO_SELECT_DONE :
                EVENT_NETWORK_SELECTION_DONE);

            String networkStr = carrier.getTitle().toString();
            if (DBG) log("selected network: " + networkStr + " mode: "+mode +"(event: "+event+")");

            // Send network attach request
            Message msg = mHandler.obtainMessage(event);
            mPhone.setNetworkSelection(mode, info, msg);

            // Display progress message
            displayNetworkSeletionInProgress(networkStr);
        }

        return true;
    }

    //implemented for DialogInterface.OnCancelListener
    public void onCancel(DialogInterface dialog) {
        // request that the service stop the query with this callback object.
        try {
            mNetworkQueryService.stopNetworkQuery(mCallback);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
        finish();
    }

    public String getNormalizedCarrierName(NetworkInfo ni) {
        if (ni != null) {
            return ni.getOperatorAlphaLong() + " (" + ni.getOperatorNumeric() + ")";
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Extract UI layout
        addPreferencesFromResource(R.xml.carrier_select);
        mPhone = PhoneApp.getInstance().phone;
        mNetworkMap = new HashMap<Preference, NetworkInfo>();

        // Get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonNetworkSearchMode = (ListPreference)
                prefSet.findPreference(BUTTON_NETWORK_SEARCH_MODE_KEY);
        mButtonNetworkSearch = (Preference) prefSet.findPreference(BUTTON_NETWORK_SEARCH_KEY);
        mNetworkList = (ProgressCategory) prefSet.findPreference(LIST_NETWORKS_KEY);

        // Get operator customization
        if (SystemProperties.getBoolean("ro.network.auto_selection_only", false)) {
            // Remove search mode button
            prefSet.removePreference(mButtonNetworkSearchMode);

            // Use automatic search mode only
            Settings.System.putString(getContentResolver(),
                    Settings.System.NETWORK_SELECTION_MODE, "Automatic");
        } else {
            // Add buttons
            CharSequence[] entries = {
                    (CharSequence)getResources().getString(R.string.select_automatically),
                    (CharSequence)getResources().getString(R.string.clh_settings_manual_selection)};
            mButtonNetworkSearchMode.setEntries(entries);

            CharSequence[] entryValues = {
                    (CharSequence)getResources().getString(
                    R.string.clh_network_search_mode_automatic_value_txt),
                    (CharSequence)getResources().getString(
                    R.string.clh_network_search_mode_manual_value_txt)};
            mButtonNetworkSearchMode.setEntryValues(entryValues);
            mButtonNetworkSearchMode.setOnPreferenceChangeListener(this);
        }

        // Get the network selection mode from system settings
        String selectionMode = Settings.System.getString(getContentResolver(),
                Settings.System.NETWORK_SELECTION_MODE);

        // Set network search mode
        if (selectionMode == null || selectionMode.equals("Automatic") ) {
            setMode(AUTOMATIC);
        } else if (selectionMode.equals("Manual")) {
            setMode(MANUAL);
        }

        // Subscribe to service state changes so we can track current operators
        mPhone.registerForServiceStateChanged(mHandler, EVENT_SERVICE_STATE_CHANGED, null);

        // Start the Network Query service, and bind it.
        // The OS knows to start he service only once and keep the instance around (so
        // long as startService is called) until a stopservice request is made.  Since
        // we want this service to just stay in the background until it is killed, we
        // don't bother stopping it from our end.
        startService(new Intent(this, NetworkQueryService.class));
        bindService(new Intent(this, NetworkQueryService.class), mNetworkQueryServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    /**
     * Override onDestroy() to unbind the query service, avoiding service
     * leak exceptions.
     */
    @Override
    protected void onDestroy() {
        // Unregister the callback
        if (mNetworkQueryService != null) {
            try {
                mNetworkQueryService.stopNetworkQuery(mCallback);
            } catch (RemoteException e) {
                Log.v(LOG_TAG, "Failed to stop network query in NetworkSettings on onDestroy.");
            }
        }

        // unbind the service.
        unbindService(mNetworkQueryServiceConnection);
        super.onDestroy();
    }

    @Override
    protected Dialog onCreateDialog(int id) {

        if ((id == DIALOG_NETWORK_SELECTION) || (id == DIALOG_NETWORK_LIST_LOAD) ||
                (id == DIALOG_NETWORK_AUTO_SELECT)) {
            ProgressDialog dialog = new ProgressDialog(this);
            switch (id) {
                case DIALOG_NETWORK_SELECTION:
                    // It would be more efficient to reuse this dialog by moving
                    // this setMessage() into onPreparedDialog() and NOT use
                    // removeDialog().  However, this is not possible since the
                    // message is rendered only 2 times in the ProgressDialog -
                    // after show() and before onCreate.
                    dialog.setMessage(mNetworkSelectMsg);
                    dialog.setCancelable(true);
                    dialog.setOnCancelListener(this);
                    dialog.setIndeterminate(true);
                    break;
                case DIALOG_NETWORK_AUTO_SELECT:
                    dialog.setMessage(getResources().getString(R.string.register_automatically));
                    dialog.setCancelable(true);
                    dialog.setOnCancelListener(this);
                    dialog.setIndeterminate(true);
                    break;
                case DIALOG_NETWORK_LIST_LOAD:
                default:
                    // Reinstate the cancelablity of the dialog.
                    dialog.setMessage(getResources().getString(R.string.load_networks_progress));
                    dialog.setCancelable(true);
                    dialog.setOnCancelListener(this);
                    break;
            }
            return dialog;
        } else {
            Dialog dialog = null;
            switch (id) {
                case DIALOG_NETWORK_FORBIDDEN:
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setPositiveButton(R.string.gui_yes_txt,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    selectNetworkCarrier(selectedCarrier);
                                }
                            });
                    builder.setNegativeButton(R.string.gui_no_txt,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    //go back to the list
                                }
                            });
                    builder.setMessage(R.string.clh_settings_unavailable_network_selected_txt);
                    dialog = builder.create();
                    break;
                default:
                    break;
            }
            return dialog;
        }
    }

    private void displayEmptyNetworkList(boolean isEmpty) {
        mNetworkList.setTitle(isEmpty ? getResources().getString(R.string.empty_networks_list) :
                                        getResources().getString(R.string.label_available));
    }

    private void displayNetworkSeletionInProgress(String networkStr) {
        // TODO: use notification manager?
        mNetworkSelectMsg = getResources().getString(R.string.register_on_network, networkStr);

        if (mIsForeground) {
            showDialog(DIALOG_NETWORK_SELECTION);
        }
    }

    private void displayNetworkQueryFailed(int error) {
        String status = getResources().getString(R.string.network_query_error);

        NotificationMgr.getDefault().postTransientNotification(
                        NotificationMgr.NETWORK_SELECTION_NOTIFICATION, status);
    }

    private void displayNetworkSelectionFailed(Throwable ex) {
        String status;

        if ((ex != null && ex instanceof CommandException) &&
                ((CommandException)ex).getCommandError()
                  == CommandException.Error.ILLEGAL_SIM_OR_ME)
        {
            status = getResources().getString(R.string.not_allowed);
        } else {
            status = getResources().getString(R.string.connect_later);
        }

        NotificationMgr.getDefault().postTransientNotification(
                        NotificationMgr.NETWORK_SELECTION_NOTIFICATION, status);
    }

    private void displayNetworkSelectionSucceeded() {
        String status = getResources().getString(R.string.registration_done);

        NotificationMgr.getDefault().postTransientNotification(
                        NotificationMgr.NETWORK_SELECTION_NOTIFICATION, status);

    }

    private void loadNetworksList() {
        if (DBG) log("load networks list...");

        clearList();
        currentNetwork = null;

        if (!mOnPause) {
            showDialog(DIALOG_NETWORK_LIST_LOAD);
        }

        try {
            mNetworkQueryService.startNetworkQuery(mCallback);
        } catch (RemoteException e) {
        }
    }

    /**
     * networksListLoaded has been rewritten to take an array of
     * NetworkInfo objects and a status field, instead of an
     * AsyncResult.  Otherwise, the functionality which takes the
     * NetworkInfo array and creates a list of preferences from it,
     * remains unchanged.
     */
    private void networksListLoaded(List<NetworkInfo> result, int status) {
        if (DBG) log("networks list loaded");

        clearList();

        if (status != NetworkQueryService.QUERY_OK) {
            if (DBG) log("error while querying available networks, status="+status);
            displayNetworkQueryFailed(status);
            displayEmptyNetworkList(true);
        } else {
            if (result != null){
                if (DBG) log("number of networks found: " + result.size());

                if (result.isEmpty()) {
                    displayEmptyNetworkList(true);
                } else {
                    displayEmptyNetworkList(false);

                    // Go through all networks
                    for (NetworkInfo ni : result) {
                        if (DBG) log("  " + ni);

                        // Add network name
                        NetworkPreference carrier = new NetworkPreference(this, null);
                        carrier.setTitle(ni.getOperatorAlphaLong());
                        carrier.setPersistent(false);

                        // Add network icon
                        if (isNetworkForbidden(ni)) {
                            carrier.setNetworkNotAvailable();
                        } else {
                            if (isHomeNetwork(ni)) {
                                carrier.setHomeNetwork();
                            }
                            if (isNetworkCurrent(ni)) {
                                carrier.setCurrentNetwork();
                                currentNetwork = carrier;
                            }
                        }

                        if (isNetwork3G(ni)) {
                            carrier.setNetwork3G();
                        }

                        mNetworkList.addPreference(carrier);
                        mNetworkMap.put(carrier, ni);
                    }

                    // Reselect network in automatic mode
                    // except for the first time
                    if (mMode == AUTOMATIC && !mSkipNextAutoReselect) {
                        selectNetworkAutomatic();
                    }
                }
            } else {
                displayEmptyNetworkList(true);
            }
        }

        // Stop rotating icon
        removeDialog(DIALOG_NETWORK_LIST_LOAD);
        mSkipNextAutoReselect = false;
    }

    private String createNameWithPLMN(NetworkInfo ni){
        return ni.getOperatorAlphaLong() + " (" + ni.getOperatorNumeric() + ")";
    }

    private void clearList() {
        for (Preference p : mNetworkMap.keySet()) {
            mNetworkList.removePreference(p);
        }
        mNetworkMap.clear();
    }

    private boolean isNetworkCurrent(NetworkInfo ni){
        return ni.getState().equals(NetworkInfo.State.CURRENT);
    }

    private boolean isNetworkForbidden(NetworkInfo ni){
        return ni.getState().equals(NetworkInfo.State.FORBIDDEN);
    }

    private boolean isNetwork3G(NetworkInfo ni){
        return ni.getRAT().equals(NetworkInfo.RAT.WCDMA);
    }

    private boolean isHomeNetwork(NetworkInfo ni){
        boolean isHomePlmn = false;
        String operator = ni.getOperatorNumeric();
        String imsi =  TelephonyManager.getDefault().getSubscriberId();

        // Check if the first IMSI digits read from SIM match operator's numerical value
        if (operator != null && imsi != null && imsi.length() >= operator.length()) {
            isHomePlmn = imsi.regionMatches(0, operator, 0, operator.length());
        }

        return isHomePlmn;
    }

    /*
     * Updates current network by reading network id and technology from ServiceState event.
     */
    private void updateNetworkFromServiceState(ServiceState ss) {
        String id = ss.getOperatorNumeric();

        if (id != null && id.length() > 0) {
            String niRAT;

            // Read and convert network access technology
            int ssRAT = ss.getRadioTechnology();
            switch (ssRAT) {
                case ServiceState.RADIO_TECHNOLOGY_GPRS:
                case ServiceState.RADIO_TECHNOLOGY_EDGE:
                    niRAT = "gsm";
                    break;

                case ServiceState.RADIO_TECHNOLOGY_UMTS:
                    niRAT = "wcdma";
                    break;

                case ServiceState.RADIO_TECHNOLOGY_UNKNOWN:
                default:
                    niRAT = "undefined";
                    break;
            }

            if (DBG) log("selected network : "+id+" ("+niRAT+")");

            // Update selected network
            updateCurrentNetwork(
                new NetworkInfo(
                  "Fake", "Fake",
                  id, "unknown",
                  niRAT));
        } else {
            if (DBG) log("can't find network id");
        }
    }

    /*
     * Finds a network with same plmn and sets the found network as Current.
     */
    private boolean updateCurrentNetwork(NetworkInfo ni){
        boolean success = false;

        // Check parameters
        if (ni != null) {
            int i = 0;
            int nbrOfCarrieres = mNetworkList.getPreferenceCount();

            if (DBG) log("Update current network : "+ni);

            // Go through all networks
            while (nbrOfCarrieres > i) {
                NetworkPreference carrier = (NetworkPreference)mNetworkList.getPreference(i);
                NetworkInfo niOld = mNetworkMap.get(carrier);
                if (DBG) log("check network : " + niOld);

                // Check if network ID and RAT match the current one
                if (niOld.getOperatorNumeric().equals(ni.getOperatorNumeric()) &&
                    (ni.getRAT().equals(NetworkInfo.RAT.UNDEFINED_OR_NO_CHANGE) ||
                     niOld.getRAT().equals(ni.getRAT()))) {

                    // Update selected icon
                    if (currentNetwork != null) {
                        ((NetworkPreference)currentNetwork).unsetCurrentNetwork();
                    }

                    if (DBG) log("Set network as current: " + carrier.getTitle());
                    carrier.setCurrentNetwork();
                    currentNetwork = carrier;
                    success = true;
                    break;
                }
                i++;
            }
        }

        return success;
    }

    /**
     *  Set the selection mode, updates the buttons and network list accordingly.
     *
     *  @param mode The mode
     */
    private void setMode(int mode){
        mMode = mode;
        updateNetworkSearchModeButton();
    }

    /**
     * Updates the network search mode radio buttons.
     */
    private void updateNetworkSearchModeButton(){
        if (mMode == AUTOMATIC) {
            mButtonNetworkSearchMode.setValue(
                    getResources().getString(R.string.clh_network_search_mode_automatic_value_txt));
            mButtonNetworkSearchMode.setSummary(
                    getResources().getString(R.string.select_automatically));
        } else if (mMode == MANUAL) {
            mButtonNetworkSearchMode.setValue(
                    getResources().getString(R.string.clh_network_search_mode_manual_value_txt));
            mButtonNetworkSearchMode.setSummary(
                    getResources().getString(R.string.clh_settings_manual_selection));
        }
    }

    private void selectNetworkAutomatic() {
        if (DBG) log("select network automatically...");
        if (mIsForeground) {
            showDialog(DIALOG_NETWORK_AUTO_SELECT);
        }

        Message msg = mHandler.obtainMessage(EVENT_AUTO_SELECT_DONE);
        mPhone.setNetworkSelectionModeAutomatic(msg);
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[NetworksList] " + msg);
    }
}

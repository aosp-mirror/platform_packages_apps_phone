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

class IccDepersonalizationConstants {
    // Constants used for internal mapping from Perso Subtypes.
    static final int PERSO_NOT_SUPPORTED = -1;
    static final int SIM_NETWORK = 0;
    static final int SIM_NETWORK_SUBSET = 1;
    static final int SIM_CORPORATE = 2;
    static final int SIM_SERVICE_PROVIDER = 3;
    static final int SIM_SIM = 4;
    static final int RUIM_NETWORK1 = 5;
    static final int RUIM_NETWORK2 = 6;
    static final int RUIM_HRPD = 7;
    static final int RUIM_CORPORATE = 8;
    static final int RUIM_SERVICE_PROVIDER = 9;
    static final int RUIM_RUIM = 10;

    // These are constants from RIL_PersoSubstate in ril.h.
    static final int RIL_PERSOSUBSTATE_SIM_NETWORK = 3;
    static final int RIL_PERSOSUBSTATE_SIM_NETWORK_SUBSET = 4;
    static final int RIL_PERSOSUBSTATE_SIM_CORPORATE = 5;
    static final int RIL_PERSOSUBSTATE_SIM_SERVICE_PROVIDER = 6;
    static final int RIL_PERSOSUBSTATE_SIM_SIM = 7;
    static final int RIL_PERSOSUBSTATE_RUIM_NETWORK1 = 13;
    static final int RIL_PERSOSUBSTATE_RUIM_NETWORK2 = 14;
    static final int RIL_PERSOSUBSTATE_RUIM_HRPD = 15;
    static final int RIL_PERSOSUBSTATE_RUIM_CORPORATE = 16;
    static final int RIL_PERSOSUBSTATE_RUIM_SERVICE_PROVIDER = 17;
    static final int RIL_PERSOSUBSTATE_RUIM_RUIM = 18;

    static int toInternalSubtype(int persoSubtype) {
        int subtype = IccDepersonalizationConstants.SIM_NETWORK;
        switch(persoSubtype) {
            case RIL_PERSOSUBSTATE_SIM_NETWORK:
                subtype = SIM_NETWORK;
                break;
            case RIL_PERSOSUBSTATE_SIM_NETWORK_SUBSET:
                subtype = SIM_NETWORK_SUBSET;
                break;
            case RIL_PERSOSUBSTATE_SIM_CORPORATE:
                subtype = SIM_CORPORATE;
                break;
            case RIL_PERSOSUBSTATE_SIM_SERVICE_PROVIDER:
                subtype = SIM_SERVICE_PROVIDER;
                break;
            case RIL_PERSOSUBSTATE_SIM_SIM:
                subtype = SIM_SIM;
                break;
            case RIL_PERSOSUBSTATE_RUIM_NETWORK1:
                subtype = RUIM_NETWORK1;
                break;
            case RIL_PERSOSUBSTATE_RUIM_NETWORK2:
                subtype = RUIM_NETWORK2;
                break;
            case RIL_PERSOSUBSTATE_RUIM_HRPD:
                subtype = RUIM_HRPD;
                break;
            case RIL_PERSOSUBSTATE_RUIM_CORPORATE:
                subtype = RUIM_CORPORATE;
                break;
            case RIL_PERSOSUBSTATE_RUIM_SERVICE_PROVIDER:
                subtype = RUIM_SERVICE_PROVIDER;
                break;
            case RIL_PERSOSUBSTATE_RUIM_RUIM:
                subtype = RUIM_RUIM;
                break;
            default:
                break;
        }
        return subtype;
    }
}

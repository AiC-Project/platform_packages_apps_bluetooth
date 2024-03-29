/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;


final class RemoteDevices {
    private static final boolean DBG = true;
    private static final String TAG = "BluetoothRemoteDevices";


    private static BluetoothAdapter mAdapter;
    private static AdapterService mAdapterService;
    private static ArrayList<BluetoothDevice> mSdpTracker;

    private Object mObject = new Object();

    private static final int UUID_INTENT_DELAY = 6000;
    private static final int MESSAGE_UUID_INTENT = 1;

    private HashMap<BluetoothDevice, DeviceProperties> mDevices;

    RemoteDevices(AdapterService service) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdapterService = service;
        mSdpTracker = new ArrayList<BluetoothDevice>();
        mDevices = new HashMap<BluetoothDevice, DeviceProperties>();
    }


    void cleanup() {
        if (mSdpTracker !=null)
            mSdpTracker.clear();

        if (mDevices != null)
            mDevices.clear();
    }

    public Object Clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    DeviceProperties getDeviceProperties(BluetoothDevice device) {
        synchronized (mDevices) {
            return mDevices.get(device);
        }
    }

    BluetoothDevice getDevice(byte[] address) {
        for (BluetoothDevice dev : mDevices.keySet()) {
            if (dev.getAddress().equals(Utils.getAddressStringFromByte(address))) {
                return dev;
            }
        }
        return null;
    }

    DeviceProperties addDeviceProperties(byte[] address) {
        synchronized (mDevices) {
            DeviceProperties prop = new DeviceProperties();
            BluetoothDevice device =
                    mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
            prop.mAddress = address;
            mDevices.put(device, prop);
            return prop;
        }
    }

    class DeviceProperties {
        private String mName;
        private byte[] mAddress;
        private int mBluetoothClass;
        private short mRssi;
        private ParcelUuid[] mUuids;
        private int mDeviceType;
        private String mAlias;
        private int mBondState;

        DeviceProperties() {
            mBondState = BluetoothDevice.BOND_NONE;
        }

        /**
         * @return the mName
         */
        String getName() {
            synchronized (mObject) {
                return mName;
            }
        }

        /**
         * @return the mClass
         */
        int getBluetoothClass() {
            synchronized (mObject) {
                return mBluetoothClass;
            }
        }

        /**
         * @return the mUuids
         */
        ParcelUuid[] getUuids() {
            synchronized (mObject) {
                return mUuids;
            }
        }

        /**
         * @return the mAddress
         */
        byte[] getAddress() {
            synchronized (mObject) {
                return mAddress;
            }
        }

        /**
         * @return mRssi
         */
        short getRssi() {
            synchronized (mObject) {
                return mRssi;
            }
        }

        /**
         * @return mDeviceType
         */
        int getDeviceType() {
            synchronized (mObject) {
                return mDeviceType;
            }
        }

        /**
         * @return the mAlias
         */
        String getAlias() {
            synchronized (mObject) {
                return mAlias;
            }
        }

        /**
         * @param mAlias the mAlias to set
         */
        void setAlias(String mAlias) {
            synchronized (mObject) {
                mAdapterService.setDevicePropertyNative(mAddress,
                    AbstractionLayer.BT_PROPERTY_REMOTE_FRIENDLY_NAME, mAlias.getBytes());
            }
        }

        /**
         * @param mBondState the mBondState to set
         */
        void setBondState(int mBondState) {
            synchronized (mObject) {
                this.mBondState = mBondState;
                if (mBondState == BluetoothDevice.BOND_NONE)
                {
                    /* Clearing the Uuids local copy when the device is unpaired. If not cleared,
                    cachedBluetoothDevice issued a connect using the local cached copy of uuids,
                    without waiting for the ACTION_UUID intent.
                    This was resulting in multiple calls to connect().*/
                    mUuids = null;
                }
            }
        }

        /**
         * @return the mBondState
         */
        int getBondState() {
            synchronized (mObject) {
                return mBondState;
            }
        }
    }


    private void sendUuidIntent(BluetoothDevice device) {
        DeviceProperties prop = getDeviceProperties(device);
        Intent intent = new Intent(BluetoothDevice.ACTION_UUID);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_UUID, prop == null? null: prop.mUuids);
        mAdapterService.sendBroadcast(intent, AdapterService.BLUETOOTH_ADMIN_PERM);

        //Remove the outstanding UUID request
        mSdpTracker.remove(device);
    }

    private void sendDisplayPinIntent(byte[] address, int pin) {
        Intent intent = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, getDevice(address));
        intent.putExtra(BluetoothDevice.EXTRA_PAIRING_KEY, pin);
        intent.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,
                    BluetoothDevice.PAIRING_VARIANT_DISPLAY_PIN);
        mAdapterService.sendOrderedBroadcast(intent, mAdapterService.BLUETOOTH_ADMIN_PERM);
    }

    void devicePropertyChangedCallback(byte[] address, int[] types, byte[][] values) {
        Intent intent;
        byte[] val;
        int type;
        BluetoothDevice bdDevice = getDevice(address);
        DeviceProperties device;
        if (bdDevice == null) {
            device = addDeviceProperties(address);
            bdDevice = getDevice(address);
        } else {
            device = getDeviceProperties(bdDevice);
        }

        for (int j = 0; j < types.length; j++) {
            type = types[j];
            val = values[j];
            if(val.length <= 0)
                errorLog("devicePropertyChangedCallback: bdDevice: " + bdDevice + ", value is empty for type: " + type);
            else {
                synchronized(mObject) {
                    switch (type) {
                        case AbstractionLayer.BT_PROPERTY_BDNAME:
                            device.mName = new String(val);
                            intent = new Intent(BluetoothDevice.ACTION_NAME_CHANGED);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, bdDevice);
                            intent.putExtra(BluetoothDevice.EXTRA_NAME, device.mName);
                            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                            mAdapterService.sendBroadcast(intent, mAdapterService.BLUETOOTH_PERM);
                            debugLog("Remote Device name is: " + device.mName);
                            break;
                        case AbstractionLayer.BT_PROPERTY_REMOTE_FRIENDLY_NAME:
                            if (device.mAlias != null) {
                                System.arraycopy(val, 0, device.mAlias, 0, val.length);
                            }
                            else {
                                device.mAlias = new String(val);
                            }
                            break;
                        case AbstractionLayer.BT_PROPERTY_BDADDR:
                            device.mAddress = val;
                            debugLog("Remote Address is:" + Utils.getAddressStringFromByte(val));
                            break;
                        case AbstractionLayer.BT_PROPERTY_CLASS_OF_DEVICE:
                            device.mBluetoothClass =  Utils.byteArrayToInt(val);
                            intent = new Intent(BluetoothDevice.ACTION_CLASS_CHANGED);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, bdDevice);
                            intent.putExtra(BluetoothDevice.EXTRA_CLASS,
                                    new BluetoothClass(device.mBluetoothClass));
                            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                            mAdapterService.sendBroadcast(intent, mAdapterService.BLUETOOTH_PERM);
                            debugLog("Remote class is:" + device.mBluetoothClass);
                            break;
                        case AbstractionLayer.BT_PROPERTY_UUIDS:
                            int numUuids = val.length/AbstractionLayer.BT_UUID_SIZE;
                            device.mUuids = Utils.byteArrayToUuid(val);
                            sendUuidIntent(bdDevice);
                            break;
                        case AbstractionLayer.BT_PROPERTY_TYPE_OF_DEVICE:
                            // The device type from hal layer, defined in bluetooth.h,
                            // matches the type defined in BluetoothDevice.java
                            device.mDeviceType = Utils.byteArrayToInt(val);
                            break;
                        case AbstractionLayer.BT_PROPERTY_REMOTE_RSSI:
                            // RSSI from hal is in one byte
                            device.mRssi = val[0];
                            break;
                    }
                }
            }
        }
    }

    void deviceFoundCallback(byte[] address) {
        // The device properties are already registered - we can send the intent
        // now
        BluetoothDevice device = getDevice(address);
        debugLog("deviceFoundCallback: Remote Address is:" + device);
        DeviceProperties deviceProp = getDeviceProperties(device);
        if (deviceProp == null) {
            errorLog("Device Properties is null for Device:" + device);
            return;
        }

        Intent intent = new Intent(BluetoothDevice.ACTION_FOUND);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_CLASS,
                new BluetoothClass(Integer.valueOf(deviceProp.mBluetoothClass)));
        intent.putExtra(BluetoothDevice.EXTRA_RSSI, deviceProp.mRssi);
        intent.putExtra(BluetoothDevice.EXTRA_NAME, deviceProp.mName);

        mAdapterService.sendBroadcast(intent, mAdapterService.BLUETOOTH_PERM);
    }

    void pinRequestCallback(byte[] address, byte[] name, int cod) {
        //TODO(BT): Get wakelock and update name and cod
        BluetoothDevice bdDevice = getDevice(address);
        if (bdDevice == null) {
            addDeviceProperties(address);
        }
        BluetoothClass btClass = bdDevice.getBluetoothClass();
        int btDeviceClass = btClass.getDeviceClass();
        if (btDeviceClass == BluetoothClass.Device.PERIPHERAL_KEYBOARD ||
            btDeviceClass == BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING) {
            // Its a keyboard. Follow the HID spec recommendation of creating the
            // passkey and displaying it to the user. If the keyboard doesn't follow
            // the spec recommendation, check if the keyboard has a fixed PIN zero
            // and pair.
            //TODO: Add sFixedPinZerosAutoPairKeyboard() and maintain list of devices that have fixed pin
            /*if (mAdapterService.isFixedPinZerosAutoPairKeyboard(address)) {
                               mAdapterService.setPin(address, BluetoothDevice.convertPinToBytes("0000"));
                               return;
                     }*/
            // Generate a variable PIN. This is not truly random but good enough.
            int pin = (int) Math.floor(Math.random() * 1000000);
            sendDisplayPinIntent(address, pin);
            return;
        }
        infoLog("pinRequestCallback: " + address + " name:" + name + " cod:" +
                cod);
        Intent intent = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, getDevice(address));
        intent.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,
                BluetoothDevice.PAIRING_VARIANT_PIN);
        mAdapterService.sendOrderedBroadcast(intent, mAdapterService.BLUETOOTH_ADMIN_PERM);
        return;
    }

    void sspRequestCallback(byte[] address, byte[] name, int cod, int pairingVariant,
            int passkey) {
        //TODO(BT): Get wakelock and update name and cod
        BluetoothDevice bdDevice = getDevice(address);
        if (bdDevice == null) {
            addDeviceProperties(address);
        }

        infoLog("sspRequestCallback: " + address + " name: " + name + " cod: " +
                cod + " pairingVariant " + pairingVariant + " passkey: " + passkey);
        int variant;
        boolean displayPasskey = false;
        if (pairingVariant == AbstractionLayer.BT_SSP_VARIANT_PASSKEY_CONFIRMATION) {
            variant = BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION;
            displayPasskey = true;
        } else if (pairingVariant == AbstractionLayer.BT_SSP_VARIANT_CONSENT) {
            variant = BluetoothDevice.PAIRING_VARIANT_CONSENT;
        } else if (pairingVariant == AbstractionLayer.BT_SSP_VARIANT_PASSKEY_ENTRY) {
            variant = BluetoothDevice.PAIRING_VARIANT_PASSKEY;
        } else if (pairingVariant == AbstractionLayer.BT_SSP_VARIANT_PASSKEY_NOTIFICATION) {
            variant = BluetoothDevice.PAIRING_VARIANT_DISPLAY_PASSKEY;
            displayPasskey = true;
        } else {
            errorLog("SSP Pairing variant not present");
            return;
        }
        BluetoothDevice device = getDevice(address);
        if (device == null) {
           warnLog("Device is not known for:" + Utils.getAddressStringFromByte(address));
           addDeviceProperties(address);
           device = getDevice(address);
        }
        Intent intent = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        if (displayPasskey) {
            intent.putExtra(BluetoothDevice.EXTRA_PAIRING_KEY, passkey);
        }
        intent.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, variant);
        mAdapterService.sendOrderedBroadcast(intent, mAdapterService.BLUETOOTH_ADMIN_PERM);
    }

    void aclStateChangeCallback(int status, byte[] address, int newState) {
        BluetoothDevice device = getDevice(address);

        if (device == null) {
            errorLog("aclStateChangeCallback: Device is NULL");
            return;
        }

        Intent intent = null;
        if (newState == AbstractionLayer.BT_ACL_STATE_CONNECTED) {
            intent = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED);
            debugLog("aclStateChangeCallback: State:Connected to Device:" + device);
        } else {
            intent = new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            debugLog("aclStateChangeCallback: State:DisConnected to Device:" + device);
        }
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mAdapterService.sendBroadcast(intent, mAdapterService.BLUETOOTH_PERM);
    }

    void fetchUuids(BluetoothDevice device) {
        if (mSdpTracker.contains(device)) return;
        mSdpTracker.add(device);

        Message message = mHandler.obtainMessage(MESSAGE_UUID_INTENT);
        message.obj = device;
        mHandler.sendMessageDelayed(message, UUID_INTENT_DELAY);

        //mAdapterService.getDevicePropertyNative(Utils.getBytesFromAddress(device.getAddress()), AbstractionLayer.BT_PROPERTY_UUIDS);
        mAdapterService.getRemoteServicesNative(Utils.getBytesFromAddress(device.getAddress()));
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_UUID_INTENT:
                BluetoothDevice device = (BluetoothDevice)msg.obj;
                if (device != null) {
                    sendUuidIntent(device);
                }
                break;
            }
        }
    };

    private void errorLog(String msg) {
        Log.e(TAG, msg);
    }

    private void debugLog(String msg) {
        if (DBG) Log.d(TAG, msg);
    }

    private void infoLog(String msg) {
        if (DBG) Log.i(TAG, msg);
    }

    private void warnLog(String msg) {
        Log.w(TAG, msg);
    }

}

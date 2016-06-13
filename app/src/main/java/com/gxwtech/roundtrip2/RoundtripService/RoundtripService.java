package com.gxwtech.roundtrip2.RoundtripService;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.gxwtech.roundtrip2.RT2Const;
import com.gxwtech.roundtrip2.RoundtripService.RileyLink.PumpManager;
import com.gxwtech.roundtrip2.RoundtripService.RileyLinkBLE.RFSpy;
import com.gxwtech.roundtrip2.RoundtripService.RileyLinkBLE.RileyLinkBLE;
import com.gxwtech.roundtrip2.RoundtripService.medtronic.PumpData.Page;
import com.gxwtech.roundtrip2.RoundtripService.medtronic.PumpModel;
import com.gxwtech.roundtrip2.util.ByteUtil;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * RoundtripService is intended to stay running when the gui-app is closed.
 */
public class RoundtripService extends Service {
    private static final String TAG="RoundtripService";
    private static final String WAKELOCKNAME = "com.gxwtech.roundtrip2.RoundtripServiceWakeLock";
    private static volatile PowerManager.WakeLock lockStatic = null;

    private boolean needBluetoothPermission = true;

    private BroadcastReceiver mBroadcastReceiver;
    private Context mContext;
    private RoundtripServiceIPCConnection serviceConnection;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;


    // saved settings

    private SharedPreferences sharedPref;
    private String pumpIDString;
    private byte[] pumpIDBytes;
    private String mRileylinkAddress;

    // cache of most recently received set of pump history pages. Probably shouldn't be here.
    ArrayList<Page> mHistoryPages;


    // Our hardware/software connection
    private RileyLinkBLE rileyLinkBLE; // android-bluetooth management
    private RFSpy rfspy; // interface for 916MHz radio.
    private PumpManager pumpManager; // interface to Minimed


    public RoundtripService() {
        super();
        Log.d(TAG, "RoundtripService newly constructed");
    }

    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        mContext = getApplicationContext();
        serviceConnection = new RoundtripServiceIPCConnection(mContext);

        sharedPref = mContext.getSharedPreferences(RT2Const.serviceLocal.sharedPreferencesKey, Context.MODE_PRIVATE);

        // get most recently used pumpID
        pumpIDString = sharedPref.getString(RT2Const.serviceLocal.pumpIDKey,"000000");
        pumpIDBytes = ByteUtil.fromHexString(pumpIDString);
        if (pumpIDBytes.length != 3) {
            Log.e(TAG,"Invalid pump ID? " + ByteUtil.shortHexString(pumpIDBytes));
            pumpIDBytes = new byte[] {0,0,0};
            pumpIDString = "000000";
        }
        if (pumpIDString.equals("000000")) {
            Log.e(TAG,"Using pump ID "+pumpIDString);
        } else {
            Log.i(TAG,"Using pump ID "+pumpIDString);
        }

        // get most recently used RileyLink address
        mRileylinkAddress = sharedPref.getString(RT2Const.serviceLocal.rileylinkAddressKey,"");

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
            /* here we can listen for local broadcasts, then send ourselves
             * a specific intent to deal with them, if we wish
              */
                if (intent == null) {
                    Log.e(TAG,"onReceive: received null intent");
                } else {
                    String action = intent.getAction();
                    if (action == null) {
                        Log.e(TAG,"onReceive: null action");
                    } else {
                        if (action.equals(RT2Const.serviceLocal.bluetooth_connected)) {
                            rileyLinkBLE.discoverServices();
                            // If this is successful,
                            // We will get a broadcast of RT2Const.serviceLocal.BLE_services_discovered
                        } else if (action.equals(RT2Const.serviceLocal.BLE_services_discovered)) {
                            rileyLinkBLE.enableNotifications();
                            rfspy = new RFSpy(context, rileyLinkBLE);
                            rfspy.startReader(); // call startReader from outside?
                            Log.i(TAG, "Announcing RileyLink open For business");
                            serviceConnection.sendMessage(RT2Const.IPC.MSG_BLE_RileyLinkReady);
                            pumpManager = new PumpManager(rfspy, pumpIDBytes);
                            PumpModel reportedPumpModel = pumpManager.getPumpModel();
                            if (!reportedPumpModel.equals(PumpModel.UNSET)) {
                                serviceConnection.sendMessage(RT2Const.IPC.MSG_PUMP_pumpFound);
                            } else {
                                serviceConnection.sendMessage(RT2Const.IPC.MSG_PUMP_pumpLost);
                            }
                        } else if (action.equals(RT2Const.serviceLocal.ipcBound)) {
                            // If we still need permission for bluetooth, ask now.
                            if (needBluetoothPermission) {
                                sendBLERequestForAccess();
                            }

                        } else if (RT2Const.IPC.MSG_BLE_accessGranted.equals(action)) {
                            //initializeLeAdapter();
                            //BluetoothInit();
                        } else if (RT2Const.IPC.MSG_BLE_accessDenied.equals(action)) {
                            stopSelf(); // This will stop the service.
                        } else if (RT2Const.IPC.MSG_BLE_useThisDevice.equals(action)) {
                            Bundle bundle = intent.getBundleExtra(RT2Const.IPC.bundleKey);
                            String deviceAddress = bundle.getString(RT2Const.IPC.MSG_BLE_useThisDevice_addressKey);
                            if (deviceAddress == null) {
                                Log.e(TAG,"handleIPCMessage: null RL address passed");
                            } else {
                                Toast.makeText(mContext, "Using RL " + deviceAddress, Toast.LENGTH_SHORT).show();
                                Log.d(TAG,"handleIPCMessage: Using RL " + deviceAddress);
                                if (mBluetoothAdapter == null) {
                                    mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                                }
                                if (mBluetoothAdapter != null) {
                                    if (mBluetoothAdapter.isEnabled()) {
                                        // FIXME: this may be a long running function:
                                        rileyLinkBLE.findRileyLink(deviceAddress);
                                        // If successful, we will get a broadcast from RileyLinkBLE: RT2Const.serviceLocal.bluetooth_connected
                                    } else {
                                        Log.e(TAG, "Bluetooth is not enabled.");
                                    }
                                } else {
                                    Log.e(TAG, "Failed to get adapter");
                                }

                            }
                        } else if (action.equals(RT2Const.IPC.MSG_PUMP_tunePump)) {
                            pumpManager.tunePump();
                        } else if (action.equals(RT2Const.IPC.MSG_PUMP_fetchHistory)) {
                            mHistoryPages = pumpManager.getAllHistoryPages();
                            final boolean savePages = true;
                            if (savePages) {
                                for (int i = 0; i < mHistoryPages.size(); i++) {
                                    String filename = "PumpHistoryPage-" + i;
                                    Log.w(TAG, "Saving history page to file " + filename);
                                    FileOutputStream outputStream;
                                    try {
                                        outputStream = openFileOutput(filename, 0);
                                        outputStream.write(mHistoryPages.get(i).getRawData());
                                        outputStream.close();
                                    } catch (FileNotFoundException fnf) {
                                        fnf.printStackTrace();
                                    } catch (IOException ioe) {
                                        ioe.printStackTrace();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }

                                }
                            }

                            Message msg = Message.obtain(null, RT2Const.IPC.MSG_IPC, 0, 0);
                            // Create a bundle with the data
                            Bundle bundle = new Bundle();
                            bundle.putString(RT2Const.IPC.messageKey, RT2Const.IPC.MSG_PUMP_history);
                            ArrayList<Bundle> packedPages = new ArrayList<>();
                            for (Page page : mHistoryPages) {
                                packedPages.add(page.pack());
                            }
                            bundle.putParcelableArrayList(RT2Const.IPC.MSG_PUMP_history_key, packedPages);

                            // Set payload
                            msg.setData(bundle);
                            serviceConnection.sendMessage(msg);
                            Log.d(TAG, "sendMessage: sent Full history report");
                        } else if (RT2Const.IPC.MSG_PUMP_fetchSavedHistory.equals(action)) {
                            FileInputStream inputStream;
                            ArrayList<Page> storedHistoryPages = new ArrayList<>();
                            for (int i = 0; i < 16; i++) {

                                String filename = "PumpHistoryPage-" + i;
                                try {
                                    inputStream = openFileInput(filename);
                                    byte[] buffer = new byte[1024];
                                    int numRead = inputStream.read(buffer, 0, 1024);
                                    if (numRead == 1024) {
                                        Page p = new Page();
                                        p.parseFrom(buffer, PumpModel.MM522);
                                        storedHistoryPages.add(p);
                                    } else {
                                        Log.e(TAG, filename + " error: short file");
                                    }
                                } catch (FileNotFoundException fnf) {
                                    Log.e(TAG, "Failed to open " + filename + " for reading.");
                                } catch (IOException e) {
                                    Log.e(TAG, "Failed to read " + filename);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                            mHistoryPages = storedHistoryPages;
                            if (storedHistoryPages.isEmpty()) {
                                Log.e(TAG, "No stored history pages loaded");
                            } else {
                                Message msg = Message.obtain(null, RT2Const.IPC.MSG_IPC, 0, 0);
                                // Create a bundle with the data
                                Bundle bundle = new Bundle();
                                bundle.putString(RT2Const.IPC.messageKey, RT2Const.IPC.MSG_PUMP_history);
                                ArrayList<Bundle> packedPages = new ArrayList<>();
                                for (Page page : mHistoryPages) {
                                    packedPages.add(page.pack());
                                }
                                bundle.putParcelableArrayList(RT2Const.IPC.MSG_PUMP_history_key, packedPages);

                                // Set payload
                                msg.setData(bundle);
                                serviceConnection.sendMessage(msg);

                            }
                        } else if (RT2Const.IPC.MSG_PUMP_useThisAddress.equals(action)) {
                            Bundle bundle = intent.getBundleExtra(RT2Const.IPC.bundleKey);
                            String idString = bundle.getString("pumpID");
                            if ((idString != null) && (idString.length()==6)) {
                                setPumpIDString(idString);
                            }
                        } else {
                            Log.e(TAG, "Unhandled broadcast: action=" + action);
                        }
                    }
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(RT2Const.serviceLocal.bluetooth_connected);
        intentFilter.addAction(RT2Const.serviceLocal.bluetooth_disconnected);
        intentFilter.addAction(RT2Const.serviceLocal.BLE_services_discovered);
        intentFilter.addAction(RT2Const.serviceLocal.ipcBound);
        intentFilter.addAction(RT2Const.IPC.MSG_BLE_accessGranted);
        intentFilter.addAction(RT2Const.IPC.MSG_BLE_accessDenied);
        intentFilter.addAction(RT2Const.IPC.MSG_BLE_useThisDevice);
        intentFilter.addAction(RT2Const.IPC.MSG_PUMP_tunePump);
        intentFilter.addAction(RT2Const.IPC.MSG_PUMP_fetchHistory);
        intentFilter.addAction(RT2Const.IPC.MSG_PUMP_useThisAddress);
        intentFilter.addAction(RT2Const.IPC.MSG_PUMP_fetchSavedHistory);

        LocalBroadcastManager.getInstance(mContext).registerReceiver(mBroadcastReceiver, intentFilter);

        Log.d(TAG, "onCreate(): It's ALIVE!");

        rileyLinkBLE = new RileyLinkBLE(this);
        if (mRileylinkAddress.length() > 0) {
            rileyLinkBLE.findRileyLink(mRileylinkAddress);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return serviceConnection.doOnBind(intent);
    }

    // Here is where the wake-lock begins:
    // We've received a service startCommand, we grab the lock.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        if (intent != null) {
            PowerManager.WakeLock lock = getLock(this.getApplicationContext());

            if (!lock.isHeld() || (flags & START_FLAG_REDELIVERY) != 0) {
                lock.acquire();
            }

            // This will end up running onHandleIntent
            super.onStartCommand(intent, flags, startId);
        } else {
            Log.e(TAG, "Received null intent?");
        }
        BluetoothInit();
        return (START_REDELIVER_INTENT | START_STICKY);
    }
    synchronized private static PowerManager.WakeLock getLock(Context context) {
        if (lockStatic == null) {
            PowerManager mgr =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);

            lockStatic = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCKNAME);
            lockStatic.setReferenceCounted(true);
        }

        return lockStatic;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.e(TAG, "I die! I die!");
    }

    /* private functions */

    void BluetoothInit() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
            }
        }
        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if ((mBluetoothAdapter==null) || (!mBluetoothAdapter.isEnabled())) {
            sendBLERequestForAccess();
        } else {
            needBluetoothPermission = false;
            initializeLeAdapter();
        }
    }

    public boolean initializeLeAdapter() {
        Log.d(TAG,"initializeLeAdapter: attempting to get an adapter");
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        } else if (!mBluetoothAdapter.isEnabled()) {
            // NOTE: This does not work!
            Log.e(TAG, "Bluetooth is not enabled.");
        }
        return true;
    }

    private void setPumpIDString(String idString) {
        if (idString.length() != 6) {
            Log.e(TAG,"setPumpIDString: invalid pump id string: " + idString);
        }
        pumpIDString = idString;
        pumpIDBytes = ByteUtil.fromHexString(pumpIDString);
        SharedPreferences prefs = mContext.getSharedPreferences(RT2Const.serviceLocal.sharedPreferencesKey, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(RT2Const.serviceLocal.pumpIDKey,pumpIDString);
        editor.apply();
        Log.i(TAG,"setPumpIDString: saved pumpID "+pumpIDString);
    }

    private void sendBLERequestForAccess() {
        serviceConnection.sendMessage(RT2Const.IPC.MSG_BLE_requestAccess);
    }

    private void reportPumpFound() {
        serviceConnection.sendMessage(RT2Const.IPC.MSG_PUMP_pumpFound);
    }
}


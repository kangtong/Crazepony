package com.kangtong.crazepony;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.design.widget.BottomNavigationView;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    private final static String TAG = MainActivity.class.getSimpleName();
    private final static int REQUEST_CONNECT_DEVICE = 1; // 宏定义查询设备句柄
    //向BLE发送数据的周期，现在是两类数据，一是摇杆的4通道值，二是请求IMU数据跟新命令
    //BLE模块本身传输速率有限，尽量减少数据发送量
    private final static int WRITE_DATA_PERIOD = 40;
    private static int IMU_CNT = 0; //update IMU period，跟新IMU数据周期，40*10ms
    Handler timeHandler = new Handler();    //定时器周期，用于跟新IMU数据等
    private String mDeviceName;
    private String mDeviceAddress;
    private BluetoothLeService mBluetoothLeService; //BLE收发服务
    // Code to manage Service lifecycle.
    // 管理BLE数据收发服务整个生命周期
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };
    private boolean mConnected = false;
    private TextView mTextState;
    private Button mBtnPower;
    private RockerView mRockerAltitude, mRockerDirection, mRockerForward;
    private BottomNavigationView bottomNavigationView;

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    // 定义处理BLE收发服务的各类事件接收机mGattUpdateReceiver，主要包括下面几种：
    // ACTION_GATT_CONNECTED: 连接到GATT
    // ACTION_GATT_DISCONNECTED: 断开GATT
    // ACTION_GATT_SERVICES_DISCOVERED: 发现GATT下的服务
    // ACTION_DATA_AVAILABLE: BLE收到数据
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            int reCmd = -2;

            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                mTextState.setText(R.string.Disconnect);
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                mTextState.setText(R.string.Connect);
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {

                // Show all the supported services and characteristics on the user interface.
                // 获得所有的GATT服务，对于Crazepony的BLE透传模块，包括GAP（General Access Profile），
                // GATT（General Attribute Profile），还有Unknown（用于数据读取）
                mBluetoothLeService.getSupportedGattServices();

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {

                final byte[] data = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);

                if (data != null && data.length > 0) {
                    final StringBuilder stringBuilder = new StringBuilder(data.length);
                    for (byte byteChar : data)
                        stringBuilder.append(String.format("%02X ", byteChar));

                    Log.i(TAG, "RX Data:" + stringBuilder);
                }


                //解析得到的数据，获得MSP命令编号
                reCmd = Protocol.processDataIn(data, data.length);
//                updateLogData(1);   //跟新IMU数据，update the IMU data
            }
        }
    };

    Runnable runnable = new Runnable() {
        @Override
        public void run() {
            try {
                timeHandler.postDelayed(this, WRITE_DATA_PERIOD);

                if (IMU_CNT >= 10) {
                    IMU_CNT = 0;
                    //request for IMU data update，请求IMU跟新
                    btSendBytes(Protocol.getSendData(Protocol.FLY_STATE,
                            Protocol.getCommandData(Protocol.FLY_STATE)));
                }
                IMU_CNT++;


                // process stick movement，处理摇杆数据
                if (mRockerAltitude.touchReadyToSend == true) {
                    btSendBytes(Protocol.getSendData(Protocol.SET_4CON,
                            Protocol.getCommandData(Protocol.SET_4CON)));

                    Log.i(TAG, "Thro: " + Protocol.throttle + ",yaw: " + Protocol.yaw + ",roll: "
                            + Protocol.roll + ",pitch: " + Protocol.pitch);

                    mRockerAltitude.touchReadyToSend = false;
                }

                //跟新显示摇杆数据，update the joystick data
//                updateLogData(0);

            } catch (Exception e) {

            }
        }
    };

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    public void btSendBytes(byte[] data) {
        //当已经连接上时才发送
        if (mConnected) {
            mBluetoothLeService.writeCharacteristic(data);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTextState = findViewById(R.id.text_state);
        mBtnPower = findViewById(R.id.btn_power);
        mRockerAltitude = findViewById(R.id.rocker_altitude);
        mRockerDirection = findViewById(R.id.rocker_direction);
        mRockerForward = findViewById(R.id.rocker_forward);
        bottomNavigationView = findViewById(R.id.navigation);
        bottomNavigationView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                btSendBytes(Protocol.getSendData(Protocol.MSP_ACC_CALIBRATION, Protocol.getCommandData(Protocol.MSP_ACC_CALIBRATION)));
            }
        });

        //绑定BLE收发服务mServiceConnection
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        //开启IMU数据跟新定时器
        timeHandler.postDelayed(runnable, WRITE_DATA_PERIOD); //每隔1s执行

        mRockerAltitude.setOnShakeListener(RockerView.DirectionMode.DIRECTION_2_VERTICAL, new RockerView.OnShakeListener() {
            @Override
            public void onStart() {

            }

            @Override
            public void direction(RockerView.Direction direction, float percent) {
                switch (direction) {
                    case DIRECTION_UP:
                    case DIRECTION_LEFT:
                        Protocol.throttle = (int) (1500 + 300 * percent);
                        Protocol.throttle = constrainRange(Protocol.throttle, 1000, 2000);
                        break;
                    case DIRECTION_DOWN:
                    case DIRECTION_RIGHT:
                        Protocol.throttle = (int) (1000 - 300 * percent);
                        Protocol.throttle = constrainRange(Protocol.throttle, 1000, 2000);
                        break;

                }
//                Protocol.roll = (int) (1500 + 1000 * ((SmallRockerCircleX2 - rightTouchStartX)) / (BackRectRight2 - BackRectLeft2));
//                Protocol.roll = constrainRange(Protocol.roll, 1000, 2000);
                mRockerAltitude.touchReadyToSend = true;

            }

            @Override
            public void onFinish() {

            }
        });
        mRockerDirection.setOnShakeListener(RockerView.DirectionMode.DIRECTION_2_HORIZONTAL, new RockerView.OnShakeListener() {
            @Override
            public void onStart() {

            }

            @Override
            public void direction(RockerView.Direction direction, float percent) {
                switch (direction) {
                    case DIRECTION_UP:
                    case DIRECTION_LEFT:
                        Protocol.yaw = (int) (1500 - 1000 * percent);
                        Protocol.yaw = constrainRange(Protocol.yaw, 1000, 2000);
                        break;
                    case DIRECTION_DOWN:
                    case DIRECTION_RIGHT:
                        Protocol.yaw = (int) (1500 + 1000 * percent);
                        Protocol.yaw = constrainRange(Protocol.yaw, 1000, 2000);
                        break;

                }
            }

            @Override
            public void onFinish() {

            }
        });
        mRockerForward.setOnShakeListener(RockerView.DirectionMode.DIRECTION_2_VERTICAL, new RockerView.OnShakeListener() {
            @Override
            public void onStart() {

            }

            @Override
            public void direction(RockerView.Direction direction, float percent) {
                switch (direction) {
                    case DIRECTION_UP:
                    case DIRECTION_LEFT:
                        Protocol.pitch = (int) (1500 + 1000 * percent);
                        Protocol.pitch = constrainRange(Protocol.pitch, 1000, 2000);
                        break;
                    case DIRECTION_DOWN:
                    case DIRECTION_RIGHT:
                        Protocol.pitch = (int) (1500 + 1000 * percent);
                        Protocol.pitch = constrainRange(Protocol.pitch, 1000, 2000);
                        break;

                }
            }

            @Override
            public void onFinish() {

            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        //注册BLE收发服务接收机mGattUpdateReceiver
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            Log.d(TAG, "mBluetoothLeService NOT null");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        //注销BLE收发服务接收机mGattUpdateReceiver
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //解绑BLE收发服务mServiceConnection
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    // 接收扫描结果，响应startActivityForResult()
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                if (resultCode == Activity.RESULT_OK) {
                    mDeviceName = data.getExtras().getString(EXTRAS_DEVICE_NAME);
                    mDeviceAddress = data.getExtras().getString(EXTRAS_DEVICE_ADDRESS);

                    Log.i(TAG, "mDeviceName:" + mDeviceName + ",mDeviceAddress:" + mDeviceAddress);

                    //连接该BLE Crazepony模块
                    if (mBluetoothLeService != null) {
                        final boolean result = mBluetoothLeService.connect(mDeviceAddress);
                        Log.d(TAG, "Connect request result=" + result);
                    }
                }
                break;
            default:
                break;
        }
    }

    // 连接按键响应函数
    public void onConnectButtonClicked(View v) {
        if (!mConnected) {
            //进入扫描页面
            Intent serverIntent = new Intent(this, DeviceScanActivity.class); // 跳转程序设置
            startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE); // 设置返回宏定义

        } else {
            //断开连接
            mBluetoothLeService.disconnect();
        }
    }

    //Take off , land down
    public void onlauchLandButtonClicked(View v) {
        String launch = getResources().getString(R.string.Launch);
        String land = getResources().getString(R.string.Land);
        String disconnectToast = getResources().getString(R.string.DisconnectToast);

        if (mConnected) {
            if (mBtnPower.getText() != land) {
                btSendBytes(Protocol.getSendData(Protocol.ARM_IT, Protocol.getCommandData(Protocol.ARM_IT)));
                btSendBytes(Protocol.getSendData(Protocol.LAUCH, Protocol.getCommandData(Protocol.LAUCH)));
                mBtnPower.setText(land);
                Protocol.throttle = Protocol.LAUCH_THROTTLE;
                mRockerAltitude.touchReadyToSend = true;
            } else {
                btSendBytes(Protocol.getSendData(Protocol.DISARM_IT, Protocol.getCommandData(Protocol.DISARM_IT)));
                btSendBytes(Protocol.getSendData(Protocol.LAND_DOWN, Protocol.getCommandData(Protocol.LAND_DOWN)));
                mBtnPower.setText(launch);
                Protocol.throttle = Protocol.LAND_THROTTLE;
                mRockerAltitude.touchReadyToSend = true;
            }
        } else {
            Toast.makeText(this, disconnectToast, Toast.LENGTH_SHORT).show();
        }
    }

    public int constrainRange(int x, int min, int max) {
        if (x < min) x = min;
        if (x > max) x = max;

        return x;

    }
}

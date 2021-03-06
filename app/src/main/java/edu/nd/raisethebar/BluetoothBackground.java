package edu.nd.raisethebar;

import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * A class that connects to the TI SensorTag, reads the data out, and converts it into a usable form.
 *
 * @author JohnAMeyer
 * @since 10/20/2016
 */
public class BluetoothBackground extends Service {
    private static final String TAG = "RTB-BluetoothBackground";
    private static final UUID UUID_MOV_SERV = UUID.fromString("f000aa80-0451-4000-b000-000000000000");
    private static final UUID UUID_MOV_DATA = UUID.fromString("f000aa81-0451-4000-b000-000000000000");
    private static final UUID UUID_MOV_CONF = UUID.fromString("f000aa82-0451-4000-b000-000000000000");
    private static final UUID UUID_MOV_PERI = UUID.fromString("f000aa83-0451-4000-b000-000000000000");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final byte[] ALL_MOTION = {0b01111111, 0b0};
    private static final byte[] NOTIFY = {0b1, 0b0};
    BluetoothDevice bd;
    BluetoothGatt bg;
    Binder b = new LocalBinder();
    private BLECallback bc;
    private Queue<Runnable> writes = new LinkedList<>();
    private ArrayList<RecordActivity.Tuple> acc = new ArrayList<>();
    private ArrayList<RecordActivity.Tuple> gyr = new ArrayList<>();
    private ArrayList<RecordActivity.Tuple> mag = new ArrayList<>();
    private boolean isRecording = false;
    private RecordActivity a;

    @Override
    /**
     * Returns a binder to the corresponding calling activity. Fails if Bluetooth has not been enabled on the device.
     */
    public IBinder onBind(Intent intent) {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba.isEnabled()) {
            bd = ba.getRemoteDevice(intent.getStringExtra("MAC"));
        } else {
            stopSelf();
            return null;
            //how to handle softly?
        }
        bc = new BLECallback();
        bg = bd.connectGatt(this, true, bc);
        return b;
    }

    /**
     * Starts recording the incoming data packets.
     */
    public void startRecording() {
        isRecording = true;
    }

    /**
     * Stops recording incoming data packets.
     *
     * @return the data collection
     */
    public ArrayList<RecordActivity.Tuple>[] stopRecording() {
        isRecording = false;
        ArrayList<RecordActivity.Tuple>[] arr = new ArrayList[3];
        arr[0] = acc;
        arr[1] = gyr;
        arr[2] = mag;
        return arr;
    }

    /**
     * Debug method to represent byte arrays as Strings.
     *
     * @param data the byte array to convert
     * @return the string representation of the data
     */
    private String string(byte[] data) {
        String s = "";
        for (byte b : data) {
            s += b + " ,";
        }
        return s;
    }

    /**
     * Associates the (RecordActivity) activity with this instance.
     *
     * @param activity the associated activity
     */
    void register(Activity activity) {
        assert activity instanceof RecordActivity;
        this.a = (RecordActivity) activity;
    }

    @Override
    /**
     * Unbinds from the previously associated activity.
     */
    public boolean onUnbind(Intent intent) {
        if (bg != null) {
            bg.disconnect();
            bg.close();
        }
        return super.onUnbind(intent);
    }

    float gyroConvert(short data) {
        return (float) data / (32768F / 500F);//((data * 1.0D) / (65536D / 500D));
    }

    float accConvert(int data) {//assumes acceleration in range -2, +2
        return data / (32768F / 8F);
    }

    float magConvert(int data) {
        return data / (32768F / 2450F); // documentation and code disagree here
    }

    /**
     * A utility class used as part of the Android Service pattern/framework
     */
    public class LocalBinder extends Binder {
        BluetoothBackground getService() {
            return BluetoothBackground.this;
        }
    }

    /**
     * Class handling Bluetooth Low Energy messages.
     * Also deals with updating the connection progress bar.
     */
    private class BLECallback extends BluetoothGattCallback {
        private boolean hasReceived = false;

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected");
                gatt.discoverServices();
                a.progress(15);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected");
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            Log.d(TAG, characteristic.toString());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.d(TAG, "Written: " + (status == BluetoothGatt.GATT_SUCCESS));
            if (writes.size() > 0) new Handler(getMainLooper()).post(writes.poll());
        }

        @Override
        /**
         * Receives and processes data packets.
         */
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (!hasReceived) a.progress(100);
            hasReceived = true;
            super.onCharacteristicChanged(gatt, characteristic);

            if (isRecording) {
                byte[] data = characteristic.getValue();//Data received
                float gyrX = gyroConvert((short) (((data[1] & 0xFF) << 8) | (data[0] & 0xFF)));
                float gyrY = gyroConvert((short) (((data[3] & 0xFF) << 8) | (data[2] & 0xFF)));
                float gyrZ = gyroConvert((short) (((data[5] & 0xFF) << 8) | (data[4] & 0xFF)));

                float accX = accConvert((short) (((data[7] & 0xFF) << 8) | (data[6] & 0xFF)));
                float accY = accConvert((short) (((data[9] & 0xFF) << 8) | (data[8] & 0xFF)));
                float accZ = accConvert((short) (((data[11] & 0xFF) << 8) | (data[10] & 0xFF)));

                float magX = magConvert((short) (((data[13] & 0xFF) << 8) | (data[12] & 0xFF)));
                float magY = magConvert((short) (((data[15] & 0xFF) << 8) | (data[14] & 0xFF)));
                float magZ = magConvert((short) (((data[17] & 0xFF) << 8) | (data[16] & 0xFF)));

                long time = SystemClock.elapsedRealtimeNanos();
                acc.add(new RecordActivity.Tuple(new float[]{accX, accY, accZ}, time));
                gyr.add(new RecordActivity.Tuple(new float[]{gyrX, gyrY, gyrZ}, time));
                mag.add(new RecordActivity.Tuple(new float[]{magX, magY, magZ}, time));
                Log.v(TAG, time + ";" + accX + ";" + accY + ";" + accZ + ";" + gyrX + ";" + gyrY + ";" + gyrZ + ";" + magX + ";" + magY + ";" + magZ);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Log.d(TAG, "Written: " + (status == BluetoothGatt.GATT_SUCCESS));
            if (writes.size() > 0) new Handler(getMainLooper()).post(writes.poll());
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            Log.d(TAG, "Discovered Services");
            a.progress(30);

            final BluetoothGattService motionService = gatt.getService(UUID_MOV_SERV);
            final BluetoothGattCharacteristic motionConfigChar = motionService.getCharacteristic(UUID_MOV_CONF);
            final BluetoothGattCharacteristic motionDataChar = motionService.getCharacteristic(UUID_MOV_DATA);

            writes.add(new Runnable() {
                public void run() {
                    Log.d(TAG, "Local Enable: " + gatt.setCharacteristicNotification(motionDataChar, true));//Enabled locally
                    a.progress(40);

                    BluetoothGattDescriptor config = motionDataChar.getDescriptor(CCC);
                    config.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    Log.d(TAG, "Remote Enable: " + gatt.writeDescriptor(config));//Enabled remotely
                    a.progress(50);
                }
            });
            writes.add(new Runnable() {
                public void run() {
                    motionService.getCharacteristic(UUID_MOV_PERI).setValue(new byte[]{0x0A});
                    Log.d(TAG, "Sensor on: " + gatt.writeCharacteristic(motionService.getCharacteristic(UUID_MOV_PERI)));
                }
            });
            writes.add(new Runnable() {
                public void run() {
                    motionConfigChar.setValue(ALL_MOTION);
                    Log.d(TAG, "Sensor on: " + gatt.writeCharacteristic(motionConfigChar));
                    a.progress(70);
                }
            });
            new Handler(getMainLooper()).post(writes.poll());
        }
    }
}

package com.health.openscale.core.bluetooth;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;

import timber.log.Timber;

import static com.health.openscale.core.utils.Converters.WeightUnit.LB;
import static com.health.openscale.core.utils.Converters.WeightUnit.ST;

public class BluetoothOKOK extends BluetoothCommunication {
    private static final int MANUFACTURER_DATA_ID_V20 = 0x20ca; // 16-bit little endian "header" 0xca 0x20
    private static final int MANUFACTURER_DATA_ID_V11 = 0x11ca; // 16-bit little endian "header" 0xca 0x11
    private static final int MANUFACTURER_DATA_ID_VF0 = 0xf0ff; // 16-bit little endian "header" 0xff 0xf0
    private static final int IDX_V20_FINAL = 6;
    private static final int IDX_V20_WEIGHT_MSB = 8;
    private static final int IDX_V20_WEIGHT_LSB = 9;
    private static final int IDX_V20_IMPEDANCE_MSB = 10;
    private static final int IDX_V20_IMPEDANCE_LSB = 11;
    private static final int IDX_V20_CHECKSUM = 12;

    private static final int IDX_V11_WEIGHT_MSB = 3;
    private static final int IDX_V11_WEIGHT_LSB = 4;
    private static final int IDX_V11_BODY_PROPERTIES = 9;
    private static final int IDX_V11_CHECKSUM = 16;

    private static final int IDX_VF0_WEIGHT_MSB = 3;
    private static final int IDX_VF0_WEIGHT_LSB = 2;

    private BluetoothCentralManager central;
    private final BluetoothCentralManagerCallback btCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull ScanResult scanResult) {
            SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
            StringBuilder x = new StringBuilder();
            for(int i = 0; i < manufacturerSpecificData.size(); i++) {
                x.append(manufacturerSpecificData.keyAt(i));
                x.append(" ");
            }
            Timber.w("manufacturerSpecificData : %s", x.toString());

            if (manufacturerSpecificData.indexOfKey(MANUFACTURER_DATA_ID_V20) > -1) {
                byte[] data = manufacturerSpecificData.get(MANUFACTURER_DATA_ID_V20);
                float divider = 10.0f;
                byte checksum = 0x20; // Version field is part of the checksum, but not in array
                if (data == null || data.length != 19)
                    return;
                if ((data[IDX_V20_FINAL] & 1) == 0)
                    return;
                for (int i = 0; i < IDX_V20_CHECKSUM; i++)
                    checksum ^= data[i];
                if (data[IDX_V20_CHECKSUM] != checksum) {
                    Timber.d("Checksum error, got %x, expected %x", data[IDX_V20_CHECKSUM] & 0xff, checksum & 0xff);
                    return;
                }
                if ((data[IDX_V20_FINAL] & 4) == 4)
                    divider = 100.0f;
                int weight = data[IDX_V20_WEIGHT_MSB] & 0xff;
                weight = weight << 8 | (data[IDX_V20_WEIGHT_LSB] & 0xff);
                int impedance = data[IDX_V20_IMPEDANCE_MSB] & 0xff;
                impedance = impedance << 8 | (data[IDX_V20_IMPEDANCE_LSB] & 0xff);
                Timber.d("Got weight: %f and impedance %f", weight / divider, impedance / 10f);
                ScaleMeasurement entry = new ScaleMeasurement();
                entry.setWeight(weight / divider);
                addScaleMeasurement(entry);
                disconnect();
            } else if (manufacturerSpecificData.indexOfKey(MANUFACTURER_DATA_ID_V11) > -1) {
                byte[] data = manufacturerSpecificData.get(MANUFACTURER_DATA_ID_V11);
                float divider = 10.0f;
                float extraWeight = 0;
                byte checksum = (byte)0xca ^ (byte)0x11; // Version and magic fields are part of the checksum, but not in array
                if (data == null || data.length != IDX_V11_CHECKSUM + 6 + 1)
                    return;
                for (int i = 0; i < IDX_V11_CHECKSUM; i++)
                    checksum ^= data[i];
                if (data[IDX_V11_CHECKSUM] != checksum) {
                    Timber.d("Checksum error, got %x, expected %x", data[IDX_V11_CHECKSUM] & 0xff, checksum & 0xff);
                    return;
                }

                int weight = data[IDX_V11_WEIGHT_MSB] & 0xff;
                weight = weight << 8 | (data[IDX_V11_WEIGHT_LSB] & 0xff);

                switch ((data[IDX_V11_BODY_PROPERTIES] >> 1) & 3) {
                default:
                    Timber.w("Invalid weight scale received, assuming 1 decimal");
                    /* fall-through */
                case 0:
                    divider = 10.0f;
                    break;
                case 1:
                    divider = 1.0f;
                    break;
                case 2:
                    divider = 100.0f;
                    break;
                }

                switch ((data[IDX_V11_BODY_PROPERTIES] >> 3) & 3) {
                case 0: // kg
                    break;
                case 1: // Jin
                    divider *= 2;
                    break;
                case 3: // st & lb
                    extraWeight = (weight >> 8) * 6.350293f;
                    weight &= 0xff;
                    /* fall-through */
                case 2: // lb
                    divider *= 2.204623;
                    break;
                }
                Timber.d("Got weight: %f", weight / divider);
                ScaleMeasurement entry = new ScaleMeasurement();
                entry.setWeight(extraWeight + weight / divider);
                addScaleMeasurement(entry);
                disconnect();
            } else if (manufacturerSpecificData.indexOfKey(MANUFACTURER_DATA_ID_VF0) > -1) {
                byte[] data = manufacturerSpecificData.get(MANUFACTURER_DATA_ID_VF0);
                float divider = 10.0f;
                int weight = data[IDX_VF0_WEIGHT_MSB] & 0xff;
                weight = weight << 8 | (data[IDX_VF0_WEIGHT_LSB] & 0xff);
                Timber.d("Got weight: %f", weight / divider);
                ScaleMeasurement entry = new ScaleMeasurement();
                entry.setWeight(weight / divider);
                addScaleMeasurement(entry);
                disconnect();
            } else if (manufacturerSpecificData.size() > 0) {
                int key = -1;
                for(int i = 0; i < manufacturerSpecificData.size(); i++) {
                    int mfgVal = manufacturerSpecificData.keyAt(i);
                    if ((mfgVal & 0xff) == 0xc0 && (mfgVal >> 8) > 0) { // ignore 0x00c0
                        key = mfgVal;
                        break;
                    }
                }
                if (key == -1) {
                    return;
                }
                byte[] data = manufacturerSpecificData.get(key);
                StringBuilder sb = new StringBuilder(data.length * 2);
                for(byte b: data)
                    sb.append(String.format("%02x ", b));
                Timber.d("onDiscoveredPeripheral : %s", sb.toString());

                float weight = 0;
                switch ((data[6] >> 2) & 7) {
                    case 1: // KG
                    {
                        int val = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
                        weight = val / 100f;
                        Timber.d("Got weight: %f KG", weight);
                        break;
                    }
                    case 4: // LB
                    {
                        int val = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
                        weight = val / 10f;
                        Timber.d("Got weight: %f LB",  weight);
                        weight = Converters.toKilogram(weight, LB);
                        break;
                    }
                    case 6: // ST:LB
                    {
                        Timber.d("Got weight: %d.%d ST:LB", data[0], data[1]);
//                        weight = 14f * data[0] + data[1];
//                        weight = Converters.toKilogram(weight, LB);
                        weight = data[0] /*ST*/ + data[1] /*LB*/ / 14f;
                        weight = Converters.toKilogram(weight, ST);
                        break;
                    }
                }
                ScaleMeasurement entry = new ScaleMeasurement();
                entry.setWeight(weight);
                addScaleMeasurement(entry);
                disconnect();
            }
        }
    };

    public BluetoothOKOK(Context context)
    {
        super(context);
        central = new BluetoothCentralManager(context, btCallback, new Handler(Looper.getMainLooper()));
    }

    @Override
    public String driverName() {
        return "OKOK";
    }

    @Override
    public void connect(String macAddress) {
        Timber.d("Mac address: %s", macAddress);
        List<ScanFilter> filters = new LinkedList<ScanFilter>();

        ScanFilter.Builder b = new ScanFilter.Builder();
        b.setDeviceAddress(macAddress);
        filters.add(b.build());

        b.setDeviceName("ADV");
        b.setManufacturerData(MANUFACTURER_DATA_ID_V20, null, null);
        filters.add(b.build());

        b.setDeviceName("Chipsea-BLE");
        b.setManufacturerData(MANUFACTURER_DATA_ID_V11, null, null);
        filters.add(b.build());

        central.scanForPeripheralsUsingFilters(filters);
    }

    @Override
    public void disconnect() {
        if (central != null)
            central.stopScan();
        central = null;
        super.disconnect();
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        return false;
    }
}

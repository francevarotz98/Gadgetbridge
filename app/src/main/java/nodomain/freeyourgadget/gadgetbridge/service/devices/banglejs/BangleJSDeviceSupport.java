package nodomain.freeyourgadget.gadgetbridge.service.devices.banglejs;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;


import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCallControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.banglejs.BangleJSConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.no1f1.No1F1Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.no1f1.No1F1SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.No1F1ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CalendarEventSpec;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.CannedMessagesSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class BangleJSDeviceSupport extends AbstractBTLEDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(BangleJSDeviceSupport.class);
    public BluetoothGattCharacteristic rxCharacteristic = null;
    public BluetoothGattCharacteristic txCharacteristic = null;

    private String receivedLine = "";

    public BangleJSDeviceSupport() {
        super(LOG);
        addSupportedService(BangleJSConstants.UUID_SERVICE_NORDIC_UART);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        LOG.info("Initializing");

        gbDevice.setState(GBDevice.State.INITIALIZING);
        gbDevice.sendDeviceUpdateIntent(getContext());

        rxCharacteristic = getCharacteristic(BangleJSConstants.UUID_CHARACTERISTIC_NORDIC_UART_RX);
        txCharacteristic = getCharacteristic(BangleJSConstants.UUID_CHARACTERISTIC_NORDIC_UART_TX);
        builder.setGattCallback(this);
        builder.notify(rxCharacteristic, true);

        uartTx(builder, " \u0003"); // clear active line
        setTime(builder);
        //sendSettings(builder);

        // get version

        gbDevice.setState(GBDevice.State.INITIALIZED);
        gbDevice.sendDeviceUpdateIntent(getContext());

        LOG.info("Initialization Done");

        return builder;
    }

    /// Write a string of data, and chunk it up
    public void uartTx(TransactionBuilder builder, String str) {
        LOG.info("UART TX: ", str);
        byte bytes[];
        try {
            bytes = str.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            LOG.error("TX: UnsupportedEncodingException");
            return;
        }
        for (int i=0;i<bytes.length;i+=20) {
            int l = bytes.length-i;
            if (l>20) l=20;
            byte packet[] = new byte[l];
            for (int b=0;b<l;b++)
                packet[b] = bytes[i+b];
            builder.write(txCharacteristic, packet);
        }
    }

    /// Write a string of data, and chunk it up
    public void uartTxJSON(String taskName, JSONObject json) {
        try {
            TransactionBuilder builder = performInitialized(taskName);
            uartTx(builder, "\u0010GB("+json.toString()+")\n");
            builder.queue(getQueue());
        } catch (IOException e) {
            GB.toast(getContext(), "Error in "+taskName+": " + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR);
        }
    }

    void handleUartRxLine(String line) {
        LOG.info("UART RX LINE: " + line);

        if (line==">Uncaught ReferenceError: \"gb\" is not defined")
          GB.toast(getContext(), "Gadgetbridge plugin not installed on Bangle.js", Toast.LENGTH_LONG, GB.ERROR);
        else if (line.charAt(0)=='{') {
            // JSON - we hope!
            try {
                JSONObject json = new JSONObject(line);
                handleUartRxJSON(json);
            } catch (JSONException e) {
                GB.toast(getContext(), "Malformed JSON from Bangle.js: " + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR);
            }
        }
    }

    void handleUartRxJSON(JSONObject json) throws JSONException {
        switch (json.getString("t")) {
            case "info":
                GB.toast(getContext(), "Bangle.js: " + json.getString("msg"), Toast.LENGTH_LONG, GB.INFO);
                break;
            case "warn":
                GB.toast(getContext(), "Bangle.js: " + json.getString("msg"), Toast.LENGTH_LONG, GB.WARN);
                break;
            case "error":
                GB.toast(getContext(), "Bangle.js: " + json.getString("msg"), Toast.LENGTH_LONG, GB.ERROR);
                break;
            case "status": {
                Context context = getContext();
                if (json.has("bat")) {
                    int b = json.getInt("bat");
                    if (b<0) b=0;
                    if (b>100) b=100;
                    gbDevice.setBatteryLevel((short)b);
                    if (b < 30) {
                        gbDevice.setBatteryState(BatteryState.BATTERY_LOW);
                        GB.updateBatteryNotification(context.getString(R.string.notif_battery_low_percent, gbDevice.getName(), String.valueOf(b)), "", context);
                    } else {
                        gbDevice.setBatteryState(BatteryState.BATTERY_NORMAL);
                        GB.removeBatteryNotification(context);
                    }
                }
                if (json.has("volt"))
                    gbDevice.setBatteryVoltage((float)json.getDouble("volt"));
                gbDevice.sendDeviceUpdateIntent(context);
            } break;
            case "findPhone": {
                boolean start = json.has("n") && json.getBoolean("n");
                GBDeviceEventFindPhone deviceEventFindPhone = new GBDeviceEventFindPhone();
                deviceEventFindPhone.event = start ? GBDeviceEventFindPhone.Event.START : GBDeviceEventFindPhone.Event.STOP;
                evaluateGBDeviceEvent(deviceEventFindPhone);
            } break;
            case "music": {
                GBDeviceEventMusicControl deviceEventMusicControl = new GBDeviceEventMusicControl();
                deviceEventMusicControl.event = GBDeviceEventMusicControl.Event.valueOf(json.getString("n").toUpperCase());
                evaluateGBDeviceEvent(deviceEventMusicControl);
            } break;
            case "call": {
                GBDeviceEventCallControl deviceEventCallControl = new GBDeviceEventCallControl();
                deviceEventCallControl.event = GBDeviceEventCallControl.Event.valueOf(json.getString("n").toUpperCase());
                evaluateGBDeviceEvent(deviceEventCallControl);
            } break;
            /*case "activity": {
                BangleJSActivitySample sample = new BangleJSActivitySample();
                sample.setTimestamp((int) (GregorianCalendar.getInstance().getTimeInMillis() / 1000L));
                sample.setHeartRate(json.getInteger("hrm"));
                try (DBHandler dbHandler = GBApplication.acquireDB()) {
                    Long userId = DBHelper.getUser(dbHandler.getDaoSession()).getId();
                    Long deviceId = DBHelper.getDevice(getDevice(), dbHandler.getDaoSession()).getId();
                    BangleJSSampleProvider provider = new BangleJSSampleProvider(getDevice(), dbHandler.getDaoSession());
                    sample.setDeviceId(deviceId);
                    sample.setUserId(userId);
                    provider.addGBActivitySample(sample);
                } catch (Exception ex) {
                    LOG.warn("Error saving current heart rate: " + ex.getLocalizedMessage());
                }
            } break;*/
        }
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic) {
        if (super.onCharacteristicChanged(gatt, characteristic)) {
            return true;
        }
        if (BangleJSConstants.UUID_CHARACTERISTIC_NORDIC_UART_RX.equals(characteristic.getUuid())) {
            byte chars[] = characteristic.getValue();
            String packetStr = new String(chars);
            LOG.info("RX: " + packetStr);
            receivedLine += packetStr;
            while (receivedLine.indexOf("\n")>=0) {
                int p = receivedLine.indexOf("\n");
                String line =  receivedLine.substring(0,p-1);
                receivedLine = receivedLine.substring(p+1);
                handleUartRxLine(line);
            }
        }
        return false;
    }


    void setTime(TransactionBuilder builder) {
        uartTx(builder, "\u0010setTime("+(System.currentTimeMillis()/1000)+");E.setTimeZone("+(TimeZone.getDefault().getRawOffset()/3600000)+");\n");
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "notify");
            o.put("id", notificationSpec.getId());
            o.put("src", notificationSpec.sourceName);
            o.put("title", notificationSpec.title);
            o.put("subject", notificationSpec.subject);
            o.put("body", notificationSpec.body);
            o.put("sender", notificationSpec.sender);
            o.put("tel", notificationSpec.phoneNumber);
            uartTxJSON("onNotification", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
    }

    @Override
    public void onDeleteNotification(int id) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "notify-");
            o.put("id", id);
            uartTxJSON("onDeleteNotification", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
    }

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder builder = performInitialized("setTime");
            setTime(builder);
            builder.queue(getQueue());
        } catch (Exception e) {
            GB.toast(getContext(), "Error setting time: " + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR);
        }
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "alarm");
            JSONArray jsonalarms = new JSONArray();
            o.put("d", jsonalarms);

            for (Alarm alarm : alarms) {
                if (!alarm.getEnabled()) continue;
                JSONObject jsonalarm = new JSONObject();
                jsonalarms.put(jsonalarm);

                Calendar calendar = AlarmUtils.toCalendar(alarm);
                // TODO: getRepetition to ensure it only happens on correct day?
                jsonalarm.put("h", alarm.getHour());
                jsonalarm.put("m", alarm.getMinute());
            }
            uartTxJSON("onSetAlarms", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "call");
            String cmdString[] = {"","undefined","accept","incoming","outgoing","reject","start","end"};
            o.put("cmd", cmdString[callSpec.command]);
            o.put("name", callSpec.name);
            o.put("number", callSpec.number);
            uartTxJSON("onSetCallState", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
}

    @Override
    public void onSetCannedMessages(CannedMessagesSpec cannedMessagesSpec) {

    }

    @Override
    public void onSetMusicState(MusicStateSpec stateSpec) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "musicstate");
            String musicStates[] = {"play","pause","stop",""};
            o.put("state", musicStates[stateSpec.state]);
            o.put("position", stateSpec.position);
            o.put("shuffle", stateSpec.shuffle);
            o.put("repeat", stateSpec.repeat);
            uartTxJSON("onSetMusicState", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
    }

    @Override
    public void onSetMusicInfo(MusicSpec musicSpec) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "musicinfo");
            o.put("artist", musicSpec.artist);
            o.put("album", musicSpec.album);
            o.put("track", musicSpec.track);
            o.put("dur", musicSpec.duration);
            o.put("c", musicSpec.trackCount);
            o.put("n", musicSpec.trackNr);
            uartTxJSON("onSetMusicInfo", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {

    }

    @Override
    public void onInstallApp(Uri uri) {

    }

    @Override
    public void onAppInfoReq() {

    }

    @Override
    public void onAppStart(UUID uuid, boolean start) {

    }

    @Override
    public void onAppDelete(UUID uuid) {

    }

    @Override
    public void onAppConfiguration(UUID appUuid, String config, Integer id) {

    }

    @Override
    public void onAppReorder(UUID[] uuids) {

    }

    @Override
    public void onFetchRecordedData(int dataTypes) {

    }

    @Override
    public void onReset(int flags) {

    }

    @Override
    public void onHeartRateTest() {

    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(boolean enable) {

    }

    @Override
    public void onFindDevice(boolean start) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "find");
            o.put("n", start);
            uartTxJSON("onFindDevice", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
    }

    @Override
    public void onSetConstantVibration(int integer) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "vibrate");
            o.put("n", integer);
            uartTxJSON("onSetConstantVibration", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
    }

    @Override
    public void onScreenshotReq() {

    }

    @Override
    public void onEnableHeartRateSleepSupport(boolean enable) {

    }

    @Override
    public void onSetHeartRateMeasurementInterval(int seconds) {

    }

    @Override
    public void onAddCalendarEvent(CalendarEventSpec calendarEventSpec) {

    }

    @Override
    public void onDeleteCalendarEvent(byte type, long id) {

    }

    @Override
    public void onSendConfiguration(String config) {

    }

    @Override
    public void onReadConfiguration(String config) {

    }

    @Override
    public void onTestNewFunction() {

    }

    @Override
    public void onSendWeather(WeatherSpec weatherSpec) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "weather");
            o.put("temp", weatherSpec.currentTemp);
            o.put("hum", weatherSpec.currentHumidity);
            o.put("txt", weatherSpec.currentCondition);
            o.put("wind", weatherSpec.windSpeed);
            o.put("loc", weatherSpec.location);
            uartTxJSON("onSendWeather", o);
        } catch (JSONException e) {
            LOG.info("JSONException: " + e.getLocalizedMessage());
        }
    }
}

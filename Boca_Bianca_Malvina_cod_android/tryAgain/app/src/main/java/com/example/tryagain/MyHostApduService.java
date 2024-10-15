package com.example.tryagain;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;

public class MyHostApduService extends HostApduService {
    private static final String TAG = "NFC_DEBUG";
    private static final byte[] AID = HexStringToByteArray("D2960000850101");
    private static final byte[] SELECT_APDU_HEADER = HexStringToByteArray("00A40400");
    private static final byte[] READ_BINARY_APDU_HEADER = HexStringToByteArray("00B00000");
    private final byte[] SELECT_OK_SW = HexStringToByteArray("9000");
    private final byte[] UNKNOWN_CMD_SW = HexStringToByteArray("0000");



    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MyHostApduService created");
        Log.d(TAG, "AID: " + bytesToHex(AID));
        Log.d(TAG, "SELECT_APDU_HEADER: " + bytesToHex(SELECT_APDU_HEADER));
    }
    private boolean isSelectFileApdu(byte[] apdu) {
        return apdu.length >= 5 && Arrays.equals(Arrays.copyOf(apdu, 2), new byte[]{0x00, (byte)0xA4})
                && apdu[2] == 0x00 && apdu[3] == 0x0C;
    }

    private byte[] handleSelectFile(byte[] commandApdu) {

        Log.i(TAG, "NDEF file selected");
        return SELECT_OK_SW;
    }

    private byte[] handleSelectAid(byte[] commandApdu) {
        byte[] receivedAid = Arrays.copyOfRange(commandApdu, 5, 5 + commandApdu[4]);
        if (Arrays.equals(AID, receivedAid)) {
            Log.i(TAG, "Correct AID selected");
            return SELECT_OK_SW;
        } else {
            Log.i(TAG, "Wrong AID selected");
            return UNKNOWN_CMD_SW;
        }
    }

    private byte[] handleReadBinary() {
        Log.i(TAG, "Read Binary command received");

        try {
            byte[] ndefHeader = new byte[] {0x00, 0x0F};
            byte[] ndefRecord = new byte[] {
                    (byte) 0xD1,
                    0x01,
                    0x08,
                    0x54,
                    0x02,
                    0x65, 0x6E,
                    0x75, 0x6E, 0x6C, 0x6F, 0x63, 0x6B
            };

            byte[] ndefMessage = ConcatArrays(ndefHeader, ndefRecord);
            byte[] response = ConcatArrays(ndefMessage, SELECT_OK_SW);

            Log.d(TAG, "Sending NDEF response: " + bytesToHex(response));
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error in handleReadBinary: " + e.getMessage());
            return UNKNOWN_CMD_SW;
        }
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        Log.d(TAG, "Received command APDU: " + bytesToHex(commandApdu));
        byte[] response;
        if (isSelectAidApdu(commandApdu)) {
            response = handleSelectAid(commandApdu);
        } else if (isSelectFileApdu(commandApdu)) {
            response = handleSelectFile(commandApdu);
        } else if (isReadBinaryApdu(commandApdu) && MainActivity.apduServiceEnabled) {
            response = handleReadBinary();
        } else {
            Log.i(TAG, "Unrecognized command");
            response = UNKNOWN_CMD_SW;
        }
        Log.d(TAG, "Sending response: " + bytesToHex(response));
        return response;
    }

    private boolean isSelectAidApdu(byte[] apdu) {
        return apdu.length >= 5 && Arrays.equals(SELECT_APDU_HEADER, Arrays.copyOf(apdu, 4)) && apdu[4] == AID.length;
    }

    private boolean isReadBinaryApdu(byte[] apdu) {
        return apdu.length >= 5 && Arrays.equals(READ_BINARY_APDU_HEADER, Arrays.copyOf(apdu, 4));
    }

    @Override
    public void onDeactivated(int reason) {
        Log.i(TAG, "Deactivated: " + (reason == DEACTIVATION_LINK_LOSS ? "Link Loss" : "Deselected"));
    }

    private static byte[] HexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private static byte[] ConcatArrays(byte[] first, byte[]... rest) {
        int totalLength = first.length;
        for (byte[] array : rest) {
            totalLength += array.length;
        }
        byte[] result = Arrays.copyOf(first, totalLength);
        int offset = first.length;
        for (byte[] array : rest) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}


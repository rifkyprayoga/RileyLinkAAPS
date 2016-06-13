package com.gxwtech.roundtrip2.RoundtripService.medtronic.PumpData.records;

import android.os.Bundle;

import com.gxwtech.roundtrip2.RoundtripService.medtronic.PumpModel;
import com.gxwtech.roundtrip2.RoundtripService.medtronic.PumpTimeStamp;

abstract public class Record {
    private static final String TAG = "Record";
    protected byte recordOp;
    protected int length;
    //protected String recordTypeName = this.getClass().getSimpleName();

    public String getRecordTypeName() { return this.getClass().getSimpleName(); }
    public String getShortTypeName() {
        return this.getClass().getSimpleName();
    }

    public Record() {
        length = 1;
    }

    public boolean parseFrom(byte[] data, PumpModel model) {
        if (data == null) {
            return false;
        }
        if (data.length < 1) {
            return false;
        }
        recordOp = data[0];
        return true;
    }

    public PumpTimeStamp getTimestamp() {
        return new PumpTimeStamp();
    }

    public int getLength() {
        return length;
    }

    public byte getRecordOp() {
        return recordOp;
    }

    protected static int asUINT8(byte b) {
        return (b < 0) ? b + 256 : b;
    }

    public Bundle dictionaryRepresentation() {
        Bundle rval = new Bundle();
        writeToBundle(rval);
        return rval;
    }

    public boolean readFromBundle(Bundle in) {
        // length is determined at instantiation
        // record type name is "static"
        // opcode has already been read.
        return true;
    }

    public void writeToBundle(Bundle in) {
        in.putInt("length",length);
        in.putInt("_opcode",recordOp);
        in.putString("_type", getRecordTypeName());
        in.putString("_stype", getShortTypeName());
    }

}

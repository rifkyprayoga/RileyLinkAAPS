package com.gxwtech.roundtrip2.RoundtripService.medtronic.PumpData.records;


import com.gxwtech.roundtrip2.RoundtripService.medtronic.PumpModel;

public class ChangeTimePumpEvent extends TimeStampedRecord {
    public ChangeTimePumpEvent() {

    }

    @Override
    public String getShortTypeName() {
        return "Change Time";
    }

    @Override
    public boolean parseFrom(byte[] data, PumpModel model) {
        return simpleParse(14,data,2);
    }
}

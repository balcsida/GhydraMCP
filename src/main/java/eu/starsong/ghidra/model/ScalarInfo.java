package eu.starsong.ghidra.model;

import java.util.HashMap;
import java.util.Map;

public class ScalarInfo {
    private String address;
    private long value;
    private String hexValue;
    private int bitLength;
    private boolean signed;
    private int operandIndex;
    private String instruction;
    private String inFunction;
    private String inFunctionAddress;
    private String toFunction;
    private String toFunctionAddress;

    // Use Builder pattern
    public static class Builder {
        private final ScalarInfo info = new ScalarInfo();
        public Builder address(String a) { info.address = a; return this; }
        public Builder value(long v) { info.value = v; return this; }
        public Builder hexValue(String h) { info.hexValue = h; return this; }
        public Builder bitLength(int b) { info.bitLength = b; return this; }
        public Builder signed(boolean s) { info.signed = s; return this; }
        public Builder operandIndex(int o) { info.operandIndex = o; return this; }
        public Builder instruction(String i) { info.instruction = i; return this; }
        public Builder inFunction(String f) { info.inFunction = f; return this; }
        public Builder inFunctionAddress(String a) { info.inFunctionAddress = a; return this; }
        public Builder toFunction(String f) { info.toFunction = f; return this; }
        public Builder toFunctionAddress(String a) { info.toFunctionAddress = a; return this; }
        public ScalarInfo build() { return info; }
    }
    public static Builder builder() { return new Builder(); }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("address", address);
        map.put("value", value);
        map.put("hexValue", hexValue);
        map.put("bitLength", bitLength);
        map.put("signed", signed);
        map.put("operandIndex", operandIndex);
        map.put("instruction", instruction);
        if (inFunction != null) { map.put("inFunction", inFunction); map.put("inFunctionAddress", inFunctionAddress); }
        if (toFunction != null) { map.put("toFunction", toFunction); map.put("toFunctionAddress", toFunctionAddress); }
        return map;
    }
}

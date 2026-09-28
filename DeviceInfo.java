class DeviceInfo {
    String hostname;
    String deviceId;
    String ipAddress;
    String platform;
    String interfaceInfo;
    String portId;

    public DeviceInfo(String hostname, String deviceId, String ipAddress, String platform, String interfaceInfo, String portId) {
        this.hostname = hostname;
        this.deviceId = deviceId;
        this.ipAddress = ipAddress;
        this.platform = platform;
        this.interfaceInfo = interfaceInfo;
        this.portId = portId;
    }

    @Override
    public String toString() {
        return hostname + "," + deviceId + "," + ipAddress + "," + platform + "," + interfaceInfo + "," + portId;
    }
}
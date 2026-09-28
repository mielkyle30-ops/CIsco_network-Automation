public class CollectorSwitchData {

    String hostname;
    String NtpSyncStatus;
    String IPaddress;
    String CDPinfo;
    String loginType;
    String version;
    String SerialNumber;
    String remainport;

    public String getRemainport() {
        return remainport;
    }

    public void setRemainport(String remainport) {
        this.remainport = remainport;
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSerialNumber() {
        return this.SerialNumber;
    }

    public void setSerialNumber(String SerialNumber) {
        this.SerialNumber = SerialNumber;
    }

    public String getHostname() {
        return this.hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getNtpSyncStatus() {
        return this.NtpSyncStatus;
    }

    public void setNtpSyncStatus(String NtpSyncStatus) {
        this.NtpSyncStatus = NtpSyncStatus;
    }

    public String getIPaddress() {
        return this.IPaddress;
    }

    public void setIPaddress(String IPaddress) {
        this.IPaddress = IPaddress;
    }

    public String getCDPinfo() {
        return this.CDPinfo;
    }

    public void setCDPinfo(String CDPinfo) {
        this.CDPinfo = CDPinfo;
    }

    public String getLoginType() {
        return this.loginType;
    }

    public void setLoginType(String loginType) {
        this.loginType = loginType;
    }

    void CollectorSwitchData() {

        this.hostname = "";
        this.NtpSyncStatus = "";
        this.IPaddress = "";
        this.CDPinfo = "";
        this.loginType = "";

    }

    public void getHostnameInRun(String text) {

        String temp = text;
        String[] tempLine = temp.split(System.lineSeparator());
        String result = "none";
        for (String line : tempLine) {
            if (line.contains("hostname")) {
                result = line.split(" ")[1];
                result.trim();
                setHostname(result);
                break;
            }
        }
    }

    public void getNTPSyncStatus(String text) {
        String temp = text;
        String[] tempLine = temp.split(System.lineSeparator());
        String result = "none";
        try {
            if (tempLine.length != 0) {
                String[] temp2 = tempLine[1].split(",");
                result = temp2[0] + " " + temp2[2];
            }
        } catch (java.lang.ArrayIndexOutOfBoundsException e) {
            result = "error";
        } finally {
            setNtpSyncStatus(result);
        }

    }

    public void getCDPDatas(String text) {
        String temp = text;
        String[] tempLine = temp.split(System.lineSeparator());
        String result = "none";
        for (int i = 0; i < tempLine.length; i++) {
            String line = tempLine[i];
            if (line.contains("Holdtme")) {
                result = "";
                i++;
                for (int j = i; j < tempLine.length; j++) {
                    line = tempLine[j];

                    if (!line.contains("#") && !line.contains("displayed")) {
                        result = result + line + "**";

                    } else {
                        setCDPinfo(result);
                        break;
                    }
                }
            }
        }
    }

    public void getVersiondata(String text) {
        String temp = text;
        String[] tempLine = temp.split(System.lineSeparator());
        String result = "none";
        if (tempLine.length != 0) {
            String[] temp2 = tempLine[1].split(",");
            if (temp2.length > 2) {
                setVersion(temp2[1] + " " + temp2[2]);
            } else if (temp2.length == 2) {
                setVersion(temp2[1]);
            } else {
                temp2 = tempLine[2].split(",");
                if (temp2.length > 2) {
                    setVersion(temp2[0] + " " + temp2[1]);
                } else if (temp2.length == 2) {
                    setVersion(temp2[0]);
                } else {
                    setVersion(temp2[0]);
                }
            }
            for (String line : tempLine) {
                if (line.contains("Processor board ID")) {
                    result = line.split("Processor board ID")[1];

                    setSerialNumber(result.trim());
                    break;
                }
            }
        }
    }

    public String ToString() {
        String result = hostname + "," + SerialNumber + "," + remainport + "," + version + "," + NtpSyncStatus + ","
                + IPaddress + ","
                + CDPinfo + "," + loginType;

        // System.out.println(result);
        return result;
    }

    public void GetRemainPort(String text) {

        String[] lines = text.split("\\r?\\n");

        int totalPorts = 0;
        int unusedPorts = 0;

        String portPattern = ".*(Gi|Fa|Te)\\d+(?:/\\d+){1,2}.*";

        for (String line : lines) {
            if (line.matches(portPattern)) {
                totalPorts++;

                // 여러 공백을 1칸으로 줄이되, Name 컬럼 뒤의 Status 위치부터 추출
                String[] parts = line.trim().split("\\s+");

                // Status는 항상 Name 다음에 위치하므로 connected/notconnect 중 하나 찾기
                for (String part : parts) {
                    if (part.equalsIgnoreCase("connected") ||
                            part.equalsIgnoreCase("notconnect") ||
                            part.equalsIgnoreCase("err-disabled") ||
                            part.equalsIgnoreCase("disabled")) {
                        if (part.equalsIgnoreCase("notconnect")) {
                            unusedPorts++;
                        }
                        break;
                    }
                }
            }
        }

        setRemainport("'" + unusedPorts + "/" + totalPorts);

        System.out.println("[PORT CHECK] 미사용 포트/전체 포트: " + getRemainport());
    }

}

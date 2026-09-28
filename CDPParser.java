import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class CDPParser {

    public void parsing() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        Date now = new Date();
        String date = sdf.format(now);
        String folderPath = "./cisco/" + date + "/"; // 기본 폴더 경로
        File folder = new File(folderPath);
        File[] listOfFiles = folder.listFiles((dir, name) -> name.contains("show_cdp_neighbors_detail"));

        List<DeviceInfo> deviceInfoList = new ArrayList<>();

        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line;
                    String hostname = null;
                    String deviceId = null, ipAddress = null, platform = null, interfaceInfo = null, portId = null;

                    List<String> lines = new ArrayList<>();
                    while ((line = br.readLine()) != null) {
                        lines.add(line.trim());
                    }

                    // 호스트 이름 추출
                    if (!lines.isEmpty()) {
                        hostname = lines.get(lines.size() - 1);
                        if (hostname.endsWith("#")) {
                            hostname = hostname.substring(0, hostname.length() - 1);
                        }
                    }

                    for (String ln : lines) {
                        if (ln.startsWith("Device ID:")) {
                            deviceId = ln.split(":")[1].trim();
                        } else if (ln.startsWith("IP address:")) {
                            ipAddress = ln.split(":")[1].trim();
                        } else if (ln.startsWith("Platform:")) {
                            platform = ln.split(":")[1].trim().split(",")[0];
                        } else if (ln.startsWith("Interface:")) {
                            String[] parts = ln.split(",");
                            interfaceInfo = parts[0].split(":")[1].trim();
                            portId = parts[1].split(":")[1].trim();
                        } else if (ln.equals("-------------------------")) {
                            if (deviceId != null && ipAddress != null && platform != null && interfaceInfo != null
                                    && portId != null) {
                                deviceInfoList.add(
                                        new DeviceInfo(hostname, deviceId, ipAddress, platform, interfaceInfo, portId));
                            }
                            deviceId = ipAddress = platform = interfaceInfo = portId = null; // reset for next device
                        }
                    }
                    // 마지막 장치 정보 추가
                    if (deviceId != null && ipAddress != null && platform != null && interfaceInfo != null
                            && portId != null) {
                        deviceInfoList
                                .add(new DeviceInfo(hostname, deviceId, ipAddress, platform, interfaceInfo, portId));
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            // 호스트명 기준으로 내림차순 정렬
            deviceInfoList.sort(Comparator.comparing((DeviceInfo d) -> d.hostname));

            // CSV 파일로 저장
            File csvFile = new File(folderPath + date + "_CDP_detail.csv");
            if (csvFile.exists()) {
                csvFile.delete(); // 기존 파일 삭제
            }

            try (FileWriter writer = new FileWriter(csvFile)) {
                writer.append("hostname,deviceId,ipAddress,platform,interfaceInfo,portId\n");
                for (DeviceInfo device : deviceInfoList) {
                    writer.append(device.toString()).append("\n");
                }
                System.out.println("Data has been written to CDP_detail.csv");
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("No files found containing 'show_cdp_neighbors_detail'");
        }
    }
}
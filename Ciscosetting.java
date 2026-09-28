
// Ciscosetting.java
import java.io.*;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.net.telnet.TelnetClient;

public class Ciscosetting {
    private static final int DUM_PORT = 9999;
    private static final int TELNET_PORT = 23;

    private static final int DELAY_MS = 1000;
    private static final long SWITCH_TIMEOUT_SEC = 500; // 사용자가 안정적이라고 한 500초
    private static final long READUNTIL_MAX_MS = 30000; // readUntil 최대 대기
    private static final long READ_IDLE_MS = 150; // 패턴 본 뒤 idle
    private static final int POOL_SIZE = 5; // 동시 5개
    private static final int PING_THREADS = 10; // 사전 ping 10개
    private static final int PING_TIMEOUT_MS = 1200; // ping 타임아웃

    // 실행 런(폴더/날짜) 컨텍스트
    private static String RUN_DATE; // yyyy-MM-dd (실행 시점 고정)
    private static Path RUN_DIR; // .\cisco\<yyyy-MM-dd [fileBase][ (n)]>
    private static String total_result = "";

    private static void checkstatus() {
        try (ServerSocket serverSocket = new ServerSocket(DUM_PORT)) {
            System.out.println("프로그램 실행 중...");
        } catch (IOException e) {
            System.out.println("이미 실행 중인 인스턴스가 있습니다. 종료합니다.");
            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {
        // 날짜 고정
        RUN_DATE = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

        // 입력 파일명
        String listFile;
        if (args.length > 0) {
            listFile = ".\\" + args[0];
            System.out.println("입력 받은 파일명: " + listFile);
        } else {
            listFile = ".\\switchlist.txt";
        }

        // 실행 폴더 구성
        String fileBase = baseNameWithoutExt(listFile); // 경로/확장자 제거
        String folderLabel = RUN_DATE + (fileBase != null && !fileBase.isEmpty() ? (" " + fileBase) : "");
        RUN_DIR = ensureUniqueDirectory(Paths.get(".\\cisco"), folderLabel);
        System.out.println("실행 결과 폴더: " + RUN_DIR);

        checkstatus();

        // 목록 읽기/정렬
        List<String[]> switches = readlist(listFile);
        sortByIPAddress(switches);
        writeListToFile(listFile, switches);

        System.out.println("원본 스위치 수: " + switches.size());

        // 1) 사전 PING 필터링
        ConcurrentLinkedQueue<String> logParts = new ConcurrentLinkedQueue<>();
        List<String[]> reachable = prePingFilter(switches, logParts);
        System.out.println("PING 성공 스위치 수: " + reachable.size() + " (실패 " + (switches.size() - reachable.size()) + ")");

        String startLog = "Start switch count (reachable) " + reachable.size() + "\n";
        System.out.println(startLog);

        // 수집 컨테이너/실패리스트
        List<CollectorSwitchData> items = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<String[]> failedSwitches = new ConcurrentLinkedQueue<>();

        // 수집 스레드풀
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
            Thread t = new Thread(r);
            t.setDaemon(false);
            return t;
        });

        // 1차 수행
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String[] s : reachable) {
            final String[] info = Arrays.copyOf(s, s.length);
            futures.add(
                    CompletableFuture.runAsync(() -> processSwitch(info, items, logParts, failedSwitches, false), pool)
                            .exceptionally(ex -> {
                                Throwable root = unwrap(ex);
                                String err = "[" + now() + "] " + info[0] + ": EXCEPTION " + describeThrowable(root);
                                System.err.println(err);
                                logParts.add(err + "\n");
                                failedSwitches.add(info);
                                return null;
                            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 2차 재시도 (실패만)
        List<String[]> retryList = new ArrayList<>(failedSwitches);
        failedSwitches.clear();
        if (!retryList.isEmpty()) {
            System.out.println("재시도 대상: " + retryList.size() + "개. 재시도 시작...");
            List<CompletableFuture<Void>> retryFutures = new ArrayList<>();
            for (String[] info : retryList) {
                final String[] rinfo = Arrays.copyOf(info, info.length);
                retryFutures.add(
                        CompletableFuture
                                .runAsync(() -> processSwitch(rinfo, items, logParts, failedSwitches, true), pool)
                                .orTimeout(SWITCH_TIMEOUT_SEC, TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    Throwable root = unwrap(ex);
                                    String err = "[" + now() + "] " + rinfo[0] + ": RETRY EXCEPTION "
                                            + describeThrowable(root);
                                    System.err.println(err);
                                    logParts.add(err + "\n");
                                    failedSwitches.add(rinfo);
                                    return null;
                                }));
            }
            CompletableFuture.allOf(retryFutures.toArray(new CompletableFuture[0])).join();
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // 로그 합치기 + 최종 실패 목록
        StringBuilder log = new StringBuilder(startLog);
        for (String s : logParts)
            log.append(s);
        if (!failedSwitches.isEmpty()) {
            log.append("\n=== FINAL FAILED LIST (AFTER RETRY) ===\n");
            for (String[] f : failedSwitches) {
                log.append(f[0]).append(" ").append(Arrays.toString(f)).append("\n");
            }
        }

        total_result += "---------------------------------------------------\n";
        saveResult("", "log.txt", log.toString() + "\n" + total_result);
        System.out.println("System finished");

        // items CSV 저장 (시간으로 파일명 구분)
        String time = new SimpleDateFormat("HH-mm").format(new Date());
        StringBuilder temp = new StringBuilder();
        for (CollectorSwitchData i : items)
            temp.append(i.ToString()).append(System.lineSeparator());
        saveResult("Total", time + "_List.csv", temp.toString());

        // CDP Parser
        CDPParser cdp = new CDPParser();
        cdp.parsing();

        // Zip
        ZipTxtFiles zip = new ZipTxtFiles();
        zip.ziping(RUN_DIR);
    }

    // ---------- 사전 PING ----------
    private static List<String[]> prePingFilter(List<String[]> switches, Queue<String> logParts)
            throws InterruptedException {
        ExecutorService pingPool = Executors.newFixedThreadPool(PING_THREADS);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            List<String[]> ordered = new ArrayList<>(switches);
            for (String[] sw : ordered) {
                final String ip = sw[0];
                futures.add(pingPool.submit(() -> pingHost(ip, PING_TIMEOUT_MS)));
            }
            List<String[]> ok = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
                boolean reachable = false;
                try {
                    reachable = futures.get(i).get();
                } catch (ExecutionException e) {
                    reachable = false;
                }
                if (reachable) {
                    ok.add(ordered.get(i));
                } else {
                    String ip = ordered.get(i)[0];
                    String msg = "[" + now() + "] " + ip + ": PING failed -> skip this switch\n";
                    System.out.print(msg);
                    logParts.add(msg);
                }
            }
            return ok;
        } finally {
            pingPool.shutdown();
            pingPool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static boolean pingHost(String ip, int timeoutMs) {
        String os = System.getProperty("os.name", "generic").toLowerCase();
        List<String> cmd;
        if (os.contains("win")) {
            cmd = Arrays.asList("cmd.exe", "/c", "ping -n 1 -w " + timeoutMs + " " + ip);
        } else {
            int sec = Math.max(1, (int) Math.ceil(timeoutMs / 1000.0));
            cmd = Arrays.asList("sh", "-c", "ping -c 1 -W " + sec + " " + ip);
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(Math.max(1, timeoutMs + 500), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 수집 ----------
    private static void processSwitch(String[] switchInfo,
            List<CollectorSwitchData> items,
            Queue<String> logParts,
            Queue<String[]> failedSwitches,
            boolean isRetry) {
        String switchIp = switchInfo[0];
        Thread.currentThread().setName("switch-" + switchIp);

        TelnetClient telnet = null;
        CollectorSwitchData mydata = new CollectorSwitchData();
        mydata.setIPaddress(switchIp);
        mydata.setLoginType(switchInfo[1]);

        StringBuilder localLog = new StringBuilder();
        String currentStep = "init";

        try {
            currentStep = "connect";
            telnet = new TelnetClient("VT100");
            telnet.setDefaultTimeout(8000);
            // telnet.setConnectTimeout(8000); // Commons Net 3.6+ 가능
            telnet.connect(switchIp, TELNET_PORT);
            telnet.setSoTimeout(10000);

            InputStream in = telnet.getInputStream();
            OutputStream out = telnet.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.ISO_8859_1));

            String output;
            String loginType = switchInfo[1];

            if ("radious".equals(loginType)) {
                String username = switchInfo[2];
                String password = switchInfo[3];
                currentStep = "wait Username:";
                readUntil(reader, "Username: ");
                currentStep = "send username";
                sendCommand(writer, username + "\r\n");
                Thread.sleep(DELAY_MS);
                currentStep = "wait Password:";
                readUntil(reader, "Password: ");
                currentStep = "send password";
                sendCommand(writer, password + "\r\n");
                Thread.sleep(DELAY_MS);

            } else if ("core".equals(loginType)) {
                String username = switchInfo[2];
                String password = switchInfo[3];
                currentStep = "wait login:";
                readUntil(reader, "login: ");
                currentStep = "send username";
                sendCommand(writer, username + "\r\n");
                Thread.sleep(DELAY_MS);
                currentStep = "wait Password:";
                readUntil(reader, "Password: ");
                currentStep = "send password";
                sendCommand(writer, password + "\r\n");
                Thread.sleep(DELAY_MS);

            } else if ("router".equals(loginType)) {
                String username = switchInfo[2];
                String password = switchInfo[3];
                String enpassword = switchInfo[4];
                currentStep = "wait Username:";
                readUntil(reader, "Username: ");
                currentStep = "send username";
                sendCommand(writer, username + "\r\n");
                Thread.sleep(DELAY_MS);
                currentStep = "wait Password:";
                readUntil(reader, "Password: ");
                currentStep = "send password";
                sendCommand(writer, password + "\r\n");
                Thread.sleep(DELAY_MS);

                currentStep = "confirm '>'";
                output = readUntil(reader, ">");
                if (!output.trim().endsWith(">")) {
                    failEarly("Login failed", switchInfo, localLog, failedSwitches);
                    return;
                }
                Thread.sleep(DELAY_MS);

                currentStep = "enable";
                sendCommand(writer, "enable\r\n");
                Thread.sleep(DELAY_MS);
                currentStep = "enable pass?";
                readUntil(reader, "Password: ");
                currentStep = "send enpass";
                sendCommand(writer, enpassword + "\r\n");
                Thread.sleep(DELAY_MS);

            } else {
                String password = switchInfo[2];
                String enpassword = switchInfo[3];
                currentStep = "wait Password:";
                readUntil(reader, "Password: ");
                currentStep = "send password";
                sendCommand(writer, password + "\r\n");
                Thread.sleep(DELAY_MS);

                currentStep = "confirm '>'";
                output = readUntil(reader, ">");
                if (!output.trim().endsWith(">")) {
                    failEarly("Login failed", switchInfo, localLog, failedSwitches);
                    return;
                }
                Thread.sleep(DELAY_MS);

                currentStep = "enable";
                sendCommand(writer, "enable\r\n");
                Thread.sleep(DELAY_MS);
                currentStep = "enable pass?";
                readUntil(reader, "Password: ");
                currentStep = "send enpass";
                sendCommand(writer, enpassword + "\r\n");
                Thread.sleep(DELAY_MS);
            }

            currentStep = "confirm '#'";
            output = readUntil(reader, "#");
            if (!output.trim().endsWith("#")) {
                failEarly("Failed to enable", switchInfo, localLog, failedSwitches);
                return;
            }
            localLog.append(switchIp).append(": Enable successful").append(isRetry ? " (retry)" : "").append("\n");

            sendCommand(writer, "terminal length 0\r\n");
            Thread.sleep(DELAY_MS);

            // 이하 쇼 명령들 (성공/실패마다 조기 리턴)
            if (!runAndSave(writer, reader, switchIp, "show clock", "show_clock.txt", localLog, mydata, null))
                return;
            if (!runAndSave(writer, reader, switchIp, "show ntp status", "show_ntp_status.txt", localLog, mydata,
                    "ntp"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show version", "show_version.txt", localLog, mydata, "ver"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show ip interface brief", "show_ip_interface_brief.txt",
                    localLog, mydata, null))
                return;
            if (!runAndSave(writer, reader, switchIp, "show interfaces status", "show_interface_status.txt", localLog,
                    mydata, "remain"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show cdp neighbors", "show_cdp_neighbors.txt", localLog, mydata,
                    "cdp"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show cdp neighbors detail", "show_cdp_neighbors_detail.txt",
                    localLog, mydata, "cdp"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show ip arp", "show_ip_arp.txt", localLog, mydata, "cdp"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show mac address-table", "show_mac_address-table.txt", localLog,
                    mydata, "cdp"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show vlan", "show_vlan.txt", localLog, mydata, "cdp"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show processes", "show_processes.txt", localLog, mydata, "cdp"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show ip route", "show_ip_route.txt", localLog, mydata, "cdp"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show run", "show_run.txt", localLog, mydata, "host"))
                return;
            if (!runAndSave(writer, reader, switchIp, "show log", "show_log.txt", localLog, mydata, null))
                return;

            items.add(mydata);

        } catch (ConnectException e) {
            String err = "[" + now() + "] " + switchIp + " ConnectException - " + e.getMessage() + "\n"
                    + stackTraceOf(e);
            System.err.println(err);
            logParts.add(err + "\n");
            failedSwitches.add(switchInfo);
        } catch (Exception e) {
            String err = "[" + now() + "] " + switchIp + " EXCEPTION at step '" + currentStep + "' - "
                    + describeThrowable(e);
            System.err.println(err);
            logParts.add(err + "\n");
            failedSwitches.add(switchInfo);
        } finally {
            try {
                /* telnet 종료는 saveResult 예외와 분리 */ } catch (Exception ignore) {
            }
            try {
                /* no-op */ } catch (Exception ignore) {
            }
            try {
                if (telnet != null && telnet.isConnected())
                    telnet.disconnect();
            } catch (IOException ignore) {
            }
            if (localLog.length() > 0)
                System.out.print(localLog.toString());
            logParts.add(localLog.toString());
        }
    }

    private static boolean runAndSave(Writer writer, BufferedReader reader, String ip,
            String cmd, String file, StringBuilder localLog,
            CollectorSwitchData my, String tag) throws Exception {
        sendCommand(writer, cmd + "\r\n");
        Thread.sleep(DELAY_MS);
        String out = readUntil(reader, "#");
        if ("show clock".equals(cmd)) {
            out = "real time : " + gettime() + "\n" + out;
        }
        saveResult(ip, file, out);

        boolean ok = out.trim().endsWith("#");
        String label = prettyCmd(cmd);
        if (ok) {
            if ("ntp".equals(tag))
                my.getNTPSyncStatus(out);
            if ("ver".equals(tag))
                my.getVersiondata(out);
            if ("remain".equals(tag))
                my.GetRemainPort(out);
            if ("cdp".equals(tag))
                my.getCDPDatas(out);
            if ("host".equals(tag))
                my.getHostnameInRun(out);

            localLog.append(ip).append(": ").append(label).append(" successful").append("\n");
            return true;
        } else {
            localLog.append(ip).append(": Failed to ").append(label).append("\n");
            return false;
        }
    }

    private static String prettyCmd(String cmd) {
        // 로그 가독성용
        switch (cmd) {
            case "show clock":
                return "Show clock";
            case "show ntp status":
                return "Show NTP status";
            case "show version":
                return "Show version";
            case "show ip interface brief":
                return "Show ip interface brief";
            case "show interfaces status":
                return "show interfaces status";
            case "show cdp neighbors":
                return "show cdp neighbors";
            case "show cdp neighbors detail":
                return "show cdp neighbors detail";
            case "show ip arp":
                return "show ip arp";
            case "show mac address-table":
                return "show mac address-table";
            case "show vlan":
                return "show vlan";
            case "show processes":
                return "show processes";
            case "show ip route":
                return "show ip route";
            case "show run":
                return "Show run";
            case "show log":
                return "Show log";
        }
        return cmd;
    }

    private static void failEarly(String msg, String[] switchInfo, StringBuilder localLog, Queue<String[]> failed) {
        localLog.append(switchInfo[0]).append(": ").append(msg).append("\n");
        failed.add(switchInfo);
    }

    private static void sendCommand(Writer writer, String command) throws IOException {
        writer.write(command);
        writer.flush();
    }

    private static String readUntil(BufferedReader reader, String pattern) throws IOException, TimeoutException {
        StringBuilder builder = new StringBuilder();
        char[] buf = new char[2048];
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READUNTIL_MAX_MS);
        long lastDataAt = System.nanoTime();
        boolean seenPattern = false;

        while (System.nanoTime() < deadline) {
            if (!reader.ready()) {
                if (seenPattern && (System.nanoTime() - lastDataAt) >= TimeUnit.MILLISECONDS.toNanos(READ_IDLE_MS)) {
                    return builder.toString();
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }

            int n = reader.read(buf);
            if (n == -1)
                break;
            builder.append(buf, 0, n);
            lastDataAt = System.nanoTime();

            int tailLen = Math.min(1024, builder.length());
            String tail = builder.substring(builder.length() - tailLen);
            if (tail.lastIndexOf(pattern) != -1) {
                seenPattern = true;
            }
        }
        throw new TimeoutException("readUntil timeout waiting for '" + pattern + "'");
    }

    private static String gettime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    // ----- 파일 저장: 폴더/파일명 규칙 적용 -----
    private static synchronized void saveResult(String switchIp, String fileName, String result) throws IOException {
        // 파일명: 아이피_날자_파일명 (IP가 없으면 ALL)
        String ipPart = (switchIp == null || switchIp.isEmpty()) ? "ALL" : switchIp;
        String filenm = ipPart + "_" + RUN_DATE + "_" + fileName;

        total_result += "--------------" + filenm + "-----------------\n";
        total_result += result + "\n";

        // 폴더 생성 (RUN_DIR는 실행 시점에 한 번 결정)
        Files.createDirectories(RUN_DIR);

        Path out = RUN_DIR.resolve(filenm);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out.toFile()))) {
            w.write(result);
            System.out.println(switchIp + ": " + fileName + " saved successfully");
        } catch (FileNotFoundException e) {
            total_result += e.getMessage();
            System.out.println(switchIp + ": " + fileName + " saved Fail");
        }
    }

    private static List<String[]> readlist(String csvFile) {
        String line;
        List<String[]> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("#") && !line.trim().isEmpty()) {
                    String[] temp = line.split(",");
                    boolean dup = false;
                    for (String[] ex : list) {
                        if (Arrays.equals(ex, temp)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup)
                        list.add(temp);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }

    private static void sortByIPAddress(List<String[]> list) {
        list.sort((a, b) -> {
            String[] ipA = a[0].split("\\.");
            String[] ipB = b[0].split("\\.");
            for (int i = 0; i < 4; i++) {
                int va = Integer.parseInt(ipA[i]);
                int vb = Integer.parseInt(ipB[i]);
                if (va != vb)
                    return va - vb;
            }
            return 0;
        });
    }

    private static void writeListToFile(String csvFile, List<String[]> list) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvFile))) {
            for (String[] row : list) {
                bw.write(String.join(",", row));
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ----- 경로/유틸 -----
    private static String baseNameWithoutExt(String pathStr) {
        if (pathStr == null)
            return null;
        Path p = Paths.get(pathStr);
        String name = p.getFileName() != null ? p.getFileName().toString() : pathStr;
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private static Path ensureUniqueDirectory(Path root, String desiredName) throws IOException {
        Files.createDirectories(root);
        Path dir = root.resolve(desiredName);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            return dir;
        }
        int idx = 1;
        while (true) {
            Path candidate = root.resolve(desiredName + " (" + idx + ")");
            if (!Files.exists(candidate)) {
                Files.createDirectories(candidate);
                return candidate;
            }
            idx++;
        }
    }

    // ----- 예외/시간 유틸 -----
    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String describeThrowable(Throwable t) {
        return t.getClass().getName()
                + (t.getMessage() != null ? (" - " + t.getMessage()) : "")
                + "\n" + stackTraceOf(t);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}

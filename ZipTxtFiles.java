import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipTxtFiles {

    /**
     * 기존 기본 동작: ./cisco/yyyy-MM-dd/ 아래의 .txt를 압축 후 삭제
     * (RUN_DIR을 쓰는 쪽이 더 안전합니다. 필요 없으면 제거하세요.)
     */
    public void ziping() {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Path dir = Paths.get("./cisco").resolve(date);
        try {
            Path created = zipTxtFiles(dir);
            if (created != null) {
                deleteTxtFiles(dir);
                System.out.println("[" + dir + "] 파일이 성공적으로 압축되고 삭제되었습니다. -> " + created);
            }
        } catch (NoSuchFileException nsfe) {
            System.out.println("대상 폴더가 없습니다: " + dir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 권장: 실행 컨텍스트에서 계산된 RUN_DIR(Path)을 직접 넘겨주세요.
     */
    public void ziping(Path dir) {
        if (dir == null) {
            System.out.println("ziping(Path): dir가 null 입니다. 작업을 건너뜁니다.");
            return;
        }
        try {
            Path created = zipTxtFiles(dir);
            if (created != null) {
                deleteTxtFiles(dir);
                System.out.println("[" + dir + "] 파일이 성공적으로 압축되고 삭제되었습니다. -> " + created);
            }
        } catch (NoSuchFileException nsfe) {
            System.out.println("대상 폴더가 없습니다: " + dir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * dir 안의 *.txt 파일을 Switch_log.zip (또는 Switch_log (n).zip)으로 압축
     * 
     * @return 생성된 zip 파일 경로 (없으면 null)
     */
    private static Path zipTxtFiles(Path dir) throws IOException {
        // 폴더 존재 확인
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            System.out.println("폴더가 존재하지 않거나 디렉터리가 아닙니다: " + dir);
            return null;
        }

        // 압축 대상이 있는지 먼저 확인
        int txtCount = countTxtFiles(dir);
        if (txtCount == 0) {
            System.out.println("압축할 .txt 파일이 없습니다: " + dir);
            return null;
        }

        // zip 파일 이름 결정 (중복 시 (1), (2) 증가)
        Path zipFile = nextAvailableZipPath(dir, "Switch_log.zip");

        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(zipFile));
                DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {

            byte[] buffer = new byte[8192];
            int added = 0;

            for (Path filePath : stream) {
                // 파일만 대상
                if (!Files.isRegularFile(filePath))
                    continue;

                ZipEntry entry = new ZipEntry(filePath.getFileName().toString());
                zipOut.putNextEntry(entry);
                try (InputStream in = new BufferedInputStream(Files.newInputStream(filePath))) {
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        zipOut.write(buffer, 0, len);
                    }
                }
                zipOut.closeEntry();
                added++;
            }

            if (added == 0) {
                // 혹시 레이스 컨디션 등으로 실제론 못 넣었다면 zip 삭제
                Files.deleteIfExists(zipFile);
                System.out.println("압축에 추가된 항목이 없어 zip을 생성하지 않습니다: " + dir);
                return null;
            }
        }

        System.out.println("ZIP 생성 완료: " + zipFile);
        return zipFile;
    }

    private static void deleteTxtFiles(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            int deleted = 0;
            for (Path filePath : stream) {
                if (Files.isRegularFile(filePath)) {
                    Files.delete(filePath);
                    deleted++;
                }
            }
            System.out.println("삭제된 .txt 개수: " + deleted);
        }
    }

    private static int countTxtFiles(Path dir) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path ignored : stream)
                count++;
        }
        return count;
    }

    private static Path nextAvailableZipPath(Path dir, String preferredName) throws IOException {
        Path preferred = dir.resolve(preferredName);
        if (!Files.exists(preferred)) {
            // 부모 디렉터리 보장
            Files.createDirectories(dir);
            return preferred;
        }
        // Switch_log (n).zip 패턴
        String base = preferredName;
        String name = base;
        String stem;
        String ext;

        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            stem = base.substring(0, dot);
            ext = base.substring(dot); // ".zip"
        } else {
            stem = base;
            ext = "";
        }

        int n = 1;
        while (true) {
            name = String.format("%s (%d)%s", stem, n, ext);
            Path candidate = dir.resolve(name);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            n++;
        }
    }
}

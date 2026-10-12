package negocut.file.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import negocut.file.entity.ImageUsage;

// 이미지 파일을 로컬 디렉터리에 저장하고 읽고 지운다. (기능 명세서 3-3)
// 저장 경로는 기준 폴더 아래 uploads/{용도}/{년월}/{식별자}.{확장자}다. 파일명은 서버가 만든 식별자만 쓴다.
// 읽거나 지울 때는 경로가 업로드 폴더 밖으로 나가지 못하게 확인한다.
@Component
public class ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String UPLOADS = "uploads";

    // 저장된 파일의 식별자와, DB에 넣는 기준 폴더 아래의 상대 경로
    public record StoredFile(String storedName, String relativePath) {
    }

    private final Path root;

    // app.upload.base-dir: uploads 폴더가 놓이는 기준 폴더. 기본값은 실행 위치(백엔드 폴더)다.
    public ImageStorage(@Value("${app.upload.base-dir:.}") String baseDir) {
        this.root = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    public StoredFile store(ImageUsage usage, String extension, byte[] bytes) {
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String relativePath = UPLOADS + "/" + usage.name().toLowerCase(Locale.ROOT) + "/"
                + YEAR_MONTH.format(YearMonth.now()) + "/" + storedName;
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            // 쓰다가 실패하면(디스크가 가득 찬 경우 등) 일부만 쓰인 파일이 남지 않게 지운다.
            deleteQuietly(relativePath);
            throw new UncheckedIOException("이미지 파일을 저장하지 못했습니다.", e);
        }
        return new StoredFile(storedName, relativePath);
    }

    // 상대 경로를 실제 경로로 바꾼다. 업로드 폴더 밖을 가리키면 거부한다.
    public Path resolve(String relativePath) {
        Path uploadsRoot = root.resolve(UPLOADS).normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(uploadsRoot)) {
            throw new IllegalArgumentException("업로드 폴더 밖의 경로입니다: " + relativePath);
        }
        return resolved;
    }

    // 파일을 지운다. 이미 없거나 지우지 못해도 호출한 쪽의 처리는 막지 않고 기록만 남긴다.
    public void deleteQuietly(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException | RuntimeException e) {
            log.warn("이미지 파일을 지우지 못했습니다. path={}", relativePath, e);
        }
    }
}

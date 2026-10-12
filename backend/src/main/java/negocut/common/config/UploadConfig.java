package negocut.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.MultipartConfigFactory;

// 이미지 업로드 크기 한도. 스프링의 기본값(파일 1MB)은 이미지 한 장 5MB 규칙(기능 명세서 3-3)보다 작아서 직접 정한다.
// 요청 전체는 한 장에 다른 필드가 붙는 것을 감안해 조금 더 크게 둔다. 한도를 넘으면 FILE_TOO_LARGE(400)로 응답한다.
@Configuration
public class UploadConfig {

    public static final long MAX_IMAGE_BYTES = DataSize.ofMegabytes(5).toBytes();

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(MAX_IMAGE_BYTES));
        factory.setMaxRequestSize(DataSize.ofMegabytes(6));
        return factory.createMultipartConfig();
    }
}

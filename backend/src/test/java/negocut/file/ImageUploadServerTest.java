package negocut.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;

import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;

// MockMvc는 서블릿 컨테이너의 멀티파트 처리를 거치지 않으므로, 실제 서버를 띄워 HTTP로 보내야만 확인되는 것을 검증한다.
// 5MB 한도 설정(UploadConfig)과 그 예외 처리, 깨진 멀티파트 본문의 처리.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImageUploadServerTest {

    private static final Path UPLOAD_ROOT = createUploadRoot();
    private static final String BOUNDARY = "----negocutTestBoundary";
    private static final byte[] JPEG_HEAD = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00};
    private static final int LIMIT = 5 * 1024 * 1024;

    @Value("${local.server.port}") private int port;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;

    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("app.upload.base-dir", () -> UPLOAD_ROOT.toString());
    }

    @AfterAll
    static void deleteUploadRoot() throws IOException {
        try (Stream<Path> paths = Files.walk(UPLOAD_ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static Path createUploadRoot() {
        try {
            return Files.createTempDirectory("negocut-upload-server-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String accessToken() {
        String suffix = TestSuffix.next();
        Member member = memberRepository.save(Member.create("sv" + suffix, passwordEncoder.encode("abcd1234"),
                "nick" + suffix.substring(2), "sv" + suffix + "@example.com", "010-1111-2222"));
        pointBalanceRepository.save(PointBalance.createEmpty(member));
        return tokenProvider.createAccessToken(member.getId(), MemberRole.MEMBER);
    }

    // 파일 한 장과 용도 필드로 이루어진 멀티파트 본문
    private byte[] multipartBody(byte[] fileContent, String fileName, boolean includeFile) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"usageType\"\r\n\r\nPRODUCT\r\n")
                .getBytes(StandardCharsets.UTF_8));
        if (includeFile) {
            out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                    + "\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(fileContent);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private HttpResponse<String> post(String token, String contentType, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/images"))
                .header("Content-Type", contentType)
                .header("Cookie", "ACCESS_TOKEN=" + token)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request, BodyHandlers.ofString());
    }

    private byte[] jpegOfSize(int size) {
        byte[] content = new byte[size];
        System.arraycopy(JPEG_HEAD, 0, content, 0, JPEG_HEAD.length);
        return content;
    }

    @Test
    void 실제_서버로_작은_이미지를_올리고_다시_받아_온다() throws Exception {
        String token = accessToken();

        HttpResponse<String> uploaded = post(token, "multipart/form-data; boundary=" + BOUNDARY,
                multipartBody(jpegOfSize(2048), "small.jpg", true));

        assertThat(uploaded.statusCode()).isEqualTo(200);
        long id = ((Number) JsonPath.read(uploaded.body(), "$.data.imageId")).longValue();
        HttpRequest get = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/images/" + id))
                .header("Cookie", "ACCESS_TOKEN=" + token).GET().build();
        HttpResponse<byte[]> downloaded = client.send(get, BodyHandlers.ofByteArray());
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.body()).hasSize(2048);
    }

    @Test
    void 실제_서버에서도_5MB를_넘는_파일은_FILE_TOO_LARGE_400이다() throws Exception {
        String token = accessToken();

        // 5MB 한도는 서블릿 컨테이너의 멀티파트 처리에서 먼저 걸린다. (서비스의 크기 검사까지 가지 않는다.)
        HttpResponse<String> response = post(token, "multipart/form-data; boundary=" + BOUNDARY,
                multipartBody(jpegOfSize(LIMIT + 1), "over.jpg", true));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.error.code")).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void 정확히_5MB인_파일은_실제_서버에서_올라간다() throws Exception {
        String token = accessToken();

        HttpResponse<String> response = post(token, "multipart/form-data; boundary=" + BOUNDARY,
                multipartBody(jpegOfSize(LIMIT), "exact.jpg", true));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void 깨진_멀티파트_본문과_파일이_빠진_요청은_500이_아니라_400이다() throws Exception {
        String token = accessToken();

        // 경계 문자열이 없는 멀티파트 요청
        HttpResponse<String> noBoundary = post(token, "multipart/form-data", "garbage".getBytes(StandardCharsets.UTF_8));
        assertThat(noBoundary.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(noBoundary.body(), "$.error.code")).isEqualTo("INVALID_INPUT_VALUE");

        // 경계는 있지만 본문이 형식에 맞지 않음
        HttpResponse<String> brokenBody = post(token, "multipart/form-data; boundary=" + BOUNDARY,
                "not a multipart body".getBytes(StandardCharsets.UTF_8));
        assertThat(brokenBody.statusCode()).isEqualTo(400);

        // 파일 파트가 없음
        HttpResponse<String> noFile = post(token, "multipart/form-data; boundary=" + BOUNDARY,
                multipartBody(new byte[0], "x.jpg", false));
        assertThat(noFile.statusCode()).isEqualTo(400);
    }
}

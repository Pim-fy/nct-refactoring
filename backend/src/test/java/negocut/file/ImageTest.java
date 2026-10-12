package negocut.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import negocut.auction.entity.Category;
import negocut.auction.entity.Product;
import negocut.auction.entity.ProductImage;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductImageRepository;
import negocut.auction.repository.ProductRepository;
import negocut.auth.token.JwtTokenProvider;
import negocut.file.entity.ImageFile;
import negocut.file.repository.ImageFileRepository;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;

// 이미지 업로드·조회·삭제 (기능 명세서 3-3)
// 파일은 실제 uploads 폴더가 아니라 테스트마다 만든 임시 폴더에 저장한다.
@SpringBootTest
@AutoConfigureMockMvc
class ImageTest {

    private static final Path UPLOAD_ROOT = createUploadRoot();

    // 형식 판별에 필요한 앞부분만 갖춘 최소 바이트열이다. (실제 이미지로 열리지는 않는다.)
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00);
    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00);
    private static final byte[] WEBP = bytes('R', 'I', 'F', 'F', 0x10, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ');

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private ImageFileRepository imageFileRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;

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
            return Files.createTempDirectory("negocut-upload-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("im" + suffix, passwordEncoder.encode("abcd1234"),
                "nick" + suffix.substring(2), "im" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        ReflectionTestUtils.setField(member, "memberStatus", status);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            pointBalanceRepository.save(PointBalance.createEmpty(member));
        }
        return member;
    }

    private Member createMember() {
        return createMember(MemberRole.MEMBER, MemberStatus.NORMAL);
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    private MockMultipartFile file(String originalName, String contentType, byte[] content) {
        return new MockMultipartFile("file", originalName, contentType, content);
    }

    private ResultActions upload(Cookie cookie, MockMultipartFile file, String usageType) throws Exception {
        var request = multipart("/api/images").file(file);
        if (usageType != null) {
            request.param("usageType", usageType);
        }
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private long uploadOk(Cookie cookie, String usageType) throws Exception {
        String response = upload(cookie, file("photo.jpg", "image/jpeg", JPEG), usageType)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.imageId")).longValue();
    }

    // 상품에 연결해 "경매에 쓰인" 이미지로 만든다.
    private void attachToProduct(long imageId) {
        Category category = categoryRepository.findByDeletedFalseAndActiveTrueOrderBySortOrderAscIdAsc().get(0);
        Product product = productRepository.save(Product.create(category, "상품" + TestSuffix.next(), "설명", "상태"));
        ImageFile image = imageFileRepository.findById(imageId).orElseThrow();
        productImageRepository.save(ProductImage.create(product, image, 1));
    }

    private Path pathOf(long imageId) {
        return UPLOAD_ROOT.resolve(imageFileRepository.findById(imageId).orElseThrow().getFilePath());
    }

    // ---- 업로드 ----

    @Test
    void JPEG_PNG_WebP를_올리면_저장되고_정보를_돌려준다() throws Exception {
        Cookie cookie = cookieOf(createMember());

        upload(cookie, file("camera.jpg", "image/jpeg", JPEG), "PROFILE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageId").isNumber())
                .andExpect(jsonPath("$.data.originalName").value("camera.jpg"))
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.data.fileSize").value(JPEG.length));

        // PNG와 WebP는 상품 용도로 올린다. (프로필은 쓰지 않는 이미지 1장 한도가 있다.)
        long png = ((Number) JsonPath.read(upload(cookie, file("a.png", "image/png", PNG), "PRODUCT")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.imageId")).longValue();
        long webp = ((Number) JsonPath.read(upload(cookie, file("b.WEBP", "image/webp", WEBP), "PRODUCT")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.imageId")).longValue();

        // 서버가 만든 식별자로, 용도와 년월 폴더 아래에 판별한 형식의 확장자로 저장된다.
        assertThat(imageFileRepository.findById(png).orElseThrow().getFilePath()).matches("uploads/product/\\d{6}/[0-9a-f]{32}\\.png");
        assertThat(imageFileRepository.findById(webp).orElseThrow().getFilePath()).matches("uploads/product/\\d{6}/[0-9a-f]{32}\\.webp");
        assertThat(Files.readAllBytes(pathOf(png))).isEqualTo(PNG);
    }

    @Test
    void 파일명의_경로와_제어문자는_지우고_저장_경로에는_쓰지_않는다() throws Exception {
        Cookie cookie = cookieOf(createMember());

        String response = upload(cookie, file("../../etc/pass\u0001wd.jpg", "image/jpeg", JPEG), "PRODUCT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalName").value("passwd.jpg"))
                .andReturn().getResponse().getContentAsString();

        long id = ((Number) JsonPath.read(response, "$.data.imageId")).longValue();
        assertThat(pathOf(id).normalize()).startsWith(UPLOAD_ROOT.resolve("uploads"));
    }

    @Test
    void 파일명의_글자_방향_제어_문자는_지우고_길면_글자_단위로_잘라_쪼개지지_않는다() throws Exception {
        Cookie cookie = cookieOf(createMember());

        // 글자 방향을 바꾸는 U+202E를 지우지 않으면 화면에서 확장자가 거꾸로 보여 속을 수 있다.
        upload(cookie, file("photo‮.jpg", "image/jpeg", JPEG), "PRODUCT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalName").value("photo.jpg"));

        // 이모지(UTF-16 두 칸)로 255자를 넘기면 글자 중간이 잘려 깨진 문자열이 되지 않아야 한다.
        String longName = "😀".repeat(300) + ".jpg";
        String response = upload(cookie, file(longName, "image/jpeg", JPEG), "PRODUCT")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String stored = JsonPath.read(response, "$.data.originalName");
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(255);
        assertThat(stored).endsWith(".jpg");
        // 글자가 쪼개져 짝이 깨진 문자가 있으면 UTF-8로 바꿨다 되돌릴 때 값이 달라진다.
        assertThat(new String(stored.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(stored);
    }

    @Test
    void 이미지가_아니거나_형식이_맞지_않으면_400이다() throws Exception {
        Cookie cookie = cookieOf(createMember());

        // 이름만 jpg인 텍스트 파일 (시그니처가 없음)
        upload(cookie, file("fake.jpg", "image/jpeg", "not an image".getBytes(StandardCharsets.UTF_8)), "PRODUCT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_TYPE_NOT_ALLOWED"));
        // 시그니처는 PNG인데 JPEG라고 속임
        upload(cookie, file("a.png", "image/jpeg", PNG), "PRODUCT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_TYPE_NOT_ALLOWED"));
        // 시그니처는 JPEG인데 확장자가 다름
        upload(cookie, file("a.png", "image/jpeg", JPEG), "PRODUCT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_TYPE_NOT_ALLOWED"));
        // 확장자 없음, Content-Type 없음
        upload(cookie, file("noextension", "image/jpeg", JPEG), "PRODUCT").andExpect(status().isBadRequest());
        upload(cookie, file("a.jpg", null, JPEG), "PRODUCT").andExpect(status().isBadRequest());
        // 허용하지 않는 형식(GIF)
        upload(cookie, file("a.gif", "image/gif", "GIF89a....".getBytes(StandardCharsets.UTF_8)), "PRODUCT")
                .andExpect(status().isBadRequest());
    }

    @Test
    void 빈_파일과_필수_입력이_없으면_400이다() throws Exception {
        Cookie cookie = cookieOf(createMember());

        upload(cookie, file("a.jpg", "image/jpeg", new byte[0]), "PRODUCT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        upload(cookie, file("a.jpg", "image/jpeg", JPEG), null).andExpect(status().isBadRequest());   // 용도 없음
        upload(cookie, file("a.jpg", "image/jpeg", JPEG), "AVATAR").andExpect(status().isBadRequest());   // 없는 용도
        mockMvc.perform(multipart("/api/images").param("usageType", "PRODUCT").cookie(cookie))   // 파일 없음
                .andExpect(status().isBadRequest());
    }

    @Test
    void 한도_5MB를_넘으면_400이고_정확히_5MB는_허용한다() throws Exception {
        Cookie cookie = cookieOf(createMember());
        int limit = 5 * 1024 * 1024;

        byte[] exact = new byte[limit];
        System.arraycopy(JPEG, 0, exact, 0, JPEG.length);
        upload(cookie, file("exact.jpg", "image/jpeg", exact), "PRODUCT").andExpect(status().isOk());

        byte[] over = new byte[limit + 1];
        System.arraycopy(JPEG, 0, over, 0, JPEG.length);
        upload(cookie, file("over.jpg", "image/jpeg", over), "PRODUCT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void 쓰지_않은_상품_이미지는_10장까지이고_경매에_쓰인_이미지는_세지_않는다() throws Exception {
        Cookie cookie = cookieOf(createMember());
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(uploadOk(cookie, "PRODUCT"));
        }

        upload(cookie, file("11.jpg", "image/jpeg", JPEG), "PRODUCT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FILE_COUNT_EXCEEDED"));

        // 한 장이 경매에 쓰이면 쓰지 않은 이미지가 9장이 되어 다시 올릴 수 있다.
        attachToProduct(ids.get(0));
        uploadOk(cookie, "PRODUCT");
    }

    @Test
    void 쓰지_않는_프로필_이미지는_1장까지이고_현재_프로필로_쓰는_이미지는_세지_않는다() throws Exception {
        Member member = createMember();
        Cookie cookie = cookieOf(member);
        long first = uploadOk(cookie, "PROFILE");

        upload(cookie, file("b.jpg", "image/jpeg", JPEG), "PROFILE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FILE_COUNT_EXCEEDED"));

        // 첫 이미지를 현재 프로필로 쓰면(프로필 수정) 새 이미지를 올려 교체할 수 있다.
        ReflectionTestUtils.setField(member, "profileImageId", first);
        memberRepository.save(member);
        uploadOk(cookie, "PROFILE");
    }

    @Test
    void 같은_회원이_동시에_올려도_쓰지_않는_상품_이미지는_10장을_넘지_않는다() throws Exception {
        Member member = createMember();
        Cookie cookie = cookieOf(member);
        int threads = 14;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return upload(cookie, file("c.jpg", "image/jpeg", JPEG), "PRODUCT").andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(10);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 10);
        assertThat(imageFileRepository.countUnusedProductImages(member.getId())).isEqualTo(10);
    }

    // ---- 업로드 권한 ----

    @Test
    void 비로그인은_401이고_관리자는_일반_기능이라_403이다() throws Exception {
        upload(null, file("a.jpg", "image/jpeg", JPEG), "PRODUCT")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        Cookie admin = cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL));
        upload(admin, file("a.jpg", "image/jpeg", JPEG), "PROFILE")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void 거래_제한_회원은_프로필_이미지는_올릴_수_있고_상품_이미지는_올릴_수_없다() throws Exception {
        Cookie restricted = cookieOf(createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE));

        upload(restricted, file("a.jpg", "image/jpeg", JPEG), "PROFILE").andExpect(status().isOk());
        upload(restricted, file("b.jpg", "image/jpeg", JPEG), "PRODUCT")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_TRADE_RESTRICTED"));
    }

    // ---- 조회 ----

    // 업로드한 프로필 이미지를 회원의 현재 프로필로 쓰게 한다. (프로필 수정 API는 7단계라, 여기서는 직접 반영한다.)
    private void applyProfile(Member member, long imageId) {
        ReflectionTestUtils.setField(member, "profileImageId", imageId);
        memberRepository.save(member);
    }

    @Test
    void 현재_프로필로_쓰는_이미지는_비로그인도_볼_수_있고_바이너리와_짧은_공개_캐시_헤더로_응답한다() throws Exception {
        Member member = createMember();
        long id = uploadOk(cookieOf(member), "PROFILE");
        applyProfile(member, id);

        mockMvc.perform(get("/api/images/" + id))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(JPEG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=600")));   // 프로필은 바뀔 수 있어 10분
    }

    @Test
    void 아직_프로필로_쓰지_않은_프로필_이미지는_올린_본인만_보고_다른_사람과_비로그인은_404다() throws Exception {
        Cookie owner = cookieOf(createMember());
        Cookie other = cookieOf(createMember());
        long id = uploadOk(owner, "PROFILE");

        mockMvc.perform(get("/api/images/" + id).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mockMvc.perform(get("/api/images/" + id).cookie(other)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/images/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void 쓰지_않은_상품_이미지는_올린_본인만_보고_다른_사람과_비로그인은_404다() throws Exception {
        Cookie owner = cookieOf(createMember());
        Cookie other = cookieOf(createMember());
        long id = uploadOk(owner, "PRODUCT");

        mockMvc.perform(get("/api/images/" + id).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().bytes(JPEG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mockMvc.perform(get("/api/images/" + id).cookie(other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
        mockMvc.perform(get("/api/images/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void 경매에_쓰인_상품_이미지는_비로그인도_볼_수_있다() throws Exception {
        long id = uploadOk(cookieOf(createMember()), "PRODUCT");
        attachToProduct(id);

        mockMvc.perform(get("/api/images/" + id))
                .andExpect(status().isOk())
                .andExpect(content().bytes(JPEG));
    }

    @Test
    void 없거나_삭제됐거나_디스크에서_사라진_이미지는_404다() throws Exception {
        Cookie owner = cookieOf(createMember());
        long deleted = uploadOk(owner, "PROFILE");
        mockMvc.perform(delete("/api/images/" + deleted).cookie(owner)).andExpect(status().isOk());
        Member missingOwner = createMember();
        long missingFile = uploadOk(cookieOf(missingOwner), "PROFILE");
        applyProfile(missingOwner, missingFile);   // 공개 이미지인데 디스크의 파일만 사라진 경우
        Files.delete(pathOf(missingFile));

        mockMvc.perform(get("/api/images/999999999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/images/" + deleted)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/images/" + missingFile)).andExpect(status().isNotFound());
    }

    // ---- 삭제 ----

    @Test
    void 쓰지_않은_상품_이미지는_올린_본인이_지울_수_있고_파일도_지워지며_한도가_풀린다() throws Exception {
        Cookie owner = cookieOf(createMember());
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(uploadOk(owner, "PRODUCT"));
        }
        Path file = pathOf(ids.get(0));
        assertThat(file).exists();

        mockMvc.perform(delete("/api/images/" + ids.get(0)).cookie(owner)).andExpect(status().isOk());

        assertThat(file).doesNotExist();   // 디스크에서도 지워진다.
        assertThat(imageFileRepository.findById(ids.get(0)).orElseThrow().isDeleted()).isTrue();   // 기록은 남는다.
        mockMvc.perform(get("/api/images/" + ids.get(0)).cookie(owner)).andExpect(status().isNotFound());
        uploadOk(owner, "PRODUCT");   // 한도가 풀려 다시 올릴 수 있다.
    }

    @Test
    void 경매에_쓰인_상품_이미지는_지울_수_없다() throws Exception {
        Cookie owner = cookieOf(createMember());
        long id = uploadOk(owner, "PRODUCT");
        attachToProduct(id);

        mockMvc.perform(delete("/api/images/" + id).cookie(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FILE_IN_USE"));
        assertThat(pathOf(id)).exists();
    }

    @Test
    void 올린_본인이_아니면_지울_수_없다_공개_이미지는_403_비공개는_404다() throws Exception {
        Member ownerMember = createMember();
        Cookie owner = cookieOf(ownerMember);
        Cookie other = cookieOf(createMember());
        long profile = uploadOk(owner, "PROFILE");
        applyProfile(ownerMember, profile);   // 현재 프로필(공개 이미지)
        long unusedProfile = uploadOk(owner, "PROFILE");   // 아직 프로필로 쓰지 않은 이미지(비공개)
        long unused = uploadOk(owner, "PRODUCT");

        mockMvc.perform(delete("/api/images/" + profile).cookie(other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FILE_ACCESS_DENIED"));
        mockMvc.perform(delete("/api/images/" + unusedProfile).cookie(other)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/images/" + unused).cookie(other)).andExpect(status().isNotFound());
        assertThat(pathOf(profile)).exists();
        assertThat(pathOf(unusedProfile)).exists();
        assertThat(pathOf(unused)).exists();
    }

    @Test
    void 현재_프로필로_쓰던_이미지를_지우면_프로필_참조가_비워진다() throws Exception {
        Member member = createMember();
        Cookie cookie = cookieOf(member);
        long id = uploadOk(cookie, "PROFILE");
        ReflectionTestUtils.setField(member, "profileImageId", id);
        memberRepository.save(member);

        mockMvc.perform(delete("/api/images/" + id).cookie(cookie)).andExpect(status().isOk());

        assertThat(memberRepository.findById(member.getId()).orElseThrow().getProfileImageId()).isNull();
    }

    @Test
    void 거래_제한_회원도_본인_이미지를_지울_수_있다() throws Exception {
        Member member = createMember();
        long id = uploadOk(cookieOf(member), "PROFILE");
        ReflectionTestUtils.setField(member, "memberStatus", MemberStatus.RESTRICTED_TRADE);
        memberRepository.save(member);

        mockMvc.perform(delete("/api/images/" + id).cookie(cookieOf(member))).andExpect(status().isOk());
    }

    @Test
    void 없는_이미지를_지우면_404이고_비로그인은_401이다() throws Exception {
        mockMvc.perform(delete("/api/images/999999999").cookie(cookieOf(createMember())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
        mockMvc.perform(delete("/api/images/1")).andExpect(status().isUnauthorized());
    }
}

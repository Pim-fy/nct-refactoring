package negocut.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.Category;
import negocut.auction.entity.ProductImage;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductImageRepository;
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

// 경매 등록과 내 경매 목록 (기능 명세서 3-4)
// 이미지는 실제 업로드 API로 올리고, 파일은 테스트마다 만든 임시 폴더에 저장한다.
@SpringBootTest
@AutoConfigureMockMvc
class AuctionRegisterTest {

    private static final Path UPLOAD_ROOT = createUploadRoot();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00};
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private ImageFileRepository imageFileRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private PlatformTransactionManager transactionManager;

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
            return Files.createTempDirectory("negocut-auction-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("au" + suffix, passwordEncoder.encode("abcd1234"),
                "nick" + suffix.substring(2), "au" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        ReflectionTestUtils.setField(member, "memberStatus", status);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            pointBalanceRepository.save(PointBalance.createEmpty(member));
        }
        return member;
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    private Cookie newSeller() {
        return cookieOf(createMember(MemberRole.MEMBER, MemberStatus.NORMAL));
    }

    private long categoryId() {
        List<Category> categories = categoryRepository.findByDeletedFalseAndActiveTrueOrderBySortOrderAscIdAsc();
        return categories.get(0).getId();
    }

    private long upload(Cookie cookie, String usageType) throws Exception {
        String response = mockMvc.perform(multipart("/api/images")
                        .file(new MockMultipartFile("file", "p.jpg", "image/jpeg", JPEG))
                        .param("usageType", usageType).cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.imageId")).longValue();
    }

    private String endAt(long minutesFromNow) {
        return OffsetDateTime.now(ZoneOffset.ofHours(9)).plusMinutes(minutesFromNow).truncatedTo(ChronoUnit.SECONDS).format(ISO);
    }

    private String body(String name, Long categoryId, List<Long> imageIds, String startPrice, String bidUnit, String endAt) {
        String images = imageIds == null ? "null" : imageIds.stream().map(id -> String.valueOf(id)).collect(Collectors.joining(",", "[", "]"));
        return "{\"product\":{\"name\":\"" + name + "\",\"categoryId\":" + categoryId
                + ",\"description\":\"설명입니다\",\"conditionNote\":\"사용감 적음\",\"imageIds\":" + images + "},"
                + "\"auction\":{\"startPrice\":" + startPrice + ",\"bidUnit\":" + bidUnit + ",\"endAt\":\"" + endAt + "\"}}";
    }

    private String validBody(List<Long> imageIds) {
        return body("상품" + TestSuffix.next(), categoryId(), imageIds, "300000", "5000", endAt(120));
    }

    private ResultActions register(Cookie cookie, String json) throws Exception {
        var request = post("/api/auctions").contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private long registerOk(Cookie cookie, String json) throws Exception {
        String response = register(cookie, json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.auctionId")).longValue();
    }

    // ---- 등록 성공 ----

    @Test
    void 정상으로_등록하면_진행_상태로_저장되고_이미지가_순서대로_연결되어_공개된다() throws Exception {
        Cookie seller = newSeller();
        long first = upload(seller, "PRODUCT");
        long second = upload(seller, "PRODUCT");
        long third = upload(seller, "PRODUCT");

        long auctionId = registerOk(seller, body("캐논 EOS R10", categoryId(), List.of(second, first, third), "300000", "5000", endAt(120)));

        // 지연 로딩 객체(상품·이미지)를 읽으므로 트랜잭션 안에서 확인한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(txStatus ->
                assertSavedAuction(auctionId, first, second, third));

        // 경매에 쓰인 이미지는 이제 누구나 볼 수 있다.
        mockMvc.perform(get("/api/images/" + first)).andExpect(status().isOk());
    }

    private void assertSavedAuction(long auctionId, long first, long second, long third) {
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(auction.getAuctionStatus()).isEqualTo(AuctionStatus.IN_PROGRESS);
        assertThat(auction.getStartPrice()).isEqualByComparingTo(new BigDecimal("300000"));
        assertThat(auction.getBidUnit()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(auction.getBidCount()).isZero();
        assertThat(auction.getCurrentPrice()).isNull();
        assertThat(auction.getTopBidId()).isNull();
        assertThat(auction.getProduct().getName()).isEqualTo("캐논 EOS R10");

        // 요청한 순서대로 1, 2, 3이 붙고 첫 번째(second)가 대표 이미지다.
        List<ProductImage> all = productImageRepository.findAll().stream()
                .filter(image -> image.getProduct().getId().equals(auction.getProduct().getId()))
                .sorted(Comparator.comparingInt(image -> image.getSortOrder()))
                .toList();
        assertThat(all.stream().map(image -> image.getImageFile().getId()).toList()).containsExactly(second, first, third);
        assertThat(all.stream().map(image -> image.getSortOrder()).toList()).containsExactly(1, 2, 3);
    }

    @Test
    void 가격과_글자_수의_경계값은_등록된다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");

        // 시작가 최소, 입찰 단위도 최소
        registerOk(seller, body("경계1", categoryId(), List.of(image), "100", "100", endAt(15)));

        // 시작가 최대(5천만원), 설명 5,000자·상품명 100자·상태 설명 500자
        long image2 = upload(seller, "PRODUCT");
        String json = "{\"product\":{\"name\":\"" + "가".repeat(100) + "\",\"categoryId\":" + categoryId()
                + ",\"description\":\"" + "나".repeat(5000) + "\",\"conditionNote\":\"" + "다".repeat(500)
                + "\",\"imageIds\":[" + image2 + "]},\"auction\":{\"startPrice\":50000000,\"bidUnit\":1000000,\"endAt\":\"" + endAt(60 * 24 * 29) + "\"}}";
        registerOk(seller, json);
    }

    @Test
    void 종료_시각은_다른_시간대_표기도_받고_응답은_한국_시간으로_보여준다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");
        String utc = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3).truncatedTo(ChronoUnit.SECONDS).format(ISO);

        registerOk(seller, body("UTC표기", categoryId(), List.of(image), "10000", "1000", utc));

        mockMvc.perform(get("/api/members/me/auctions").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].endAt").value(org.hamcrest.Matchers.endsWith("+09:00")));
    }

    // ---- 가격 규칙 ----

    @Test
    void 가격이_100원_단위가_아니거나_시작가가_입찰_단위의_배수가_아니면_400이다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");
        long cat = categoryId();

        register(seller, body("a", cat, List.of(image), "100150", "100", endAt(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUCTION_INPUT_INVALID"))
                .andExpect(jsonPath("$.error.details[0].field").value("auction.startPrice"));
        register(seller, body("a", cat, List.of(image), "100000", "150", endAt(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("auction.bidUnit"));
        // 300,000은 7,000의 배수가 아니라 아무도 입찰할 수 없는 경매가 된다.
        register(seller, body("a", cat, List.of(image), "300000", "7000", endAt(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].message").value("시작가는 입찰 단위의 배수여야 합니다."));
        // 소수점이 있는 가격
        register(seller, body("a", cat, List.of(image), "100.5", "100", endAt(60))).andExpect(status().isBadRequest());
    }

    @Test
    void 가격이_허용_범위_밖이면_400이다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");
        long cat = categoryId();

        register(seller, body("a", cat, List.of(image), "0", "100", endAt(60))).andExpect(status().isBadRequest());
        register(seller, body("a", cat, List.of(image), "50", "100", endAt(60))).andExpect(status().isBadRequest());
        register(seller, body("a", cat, List.of(image), "50000100", "100", endAt(60))).andExpect(status().isBadRequest());
        register(seller, body("a", cat, List.of(image), "1000", "50", endAt(60))).andExpect(status().isBadRequest());
        register(seller, body("a", cat, List.of(image), "-1000", "100", endAt(60))).andExpect(status().isBadRequest());
        register(seller, body("a", cat, List.of(image), "null", "100", endAt(60))).andExpect(status().isBadRequest());
    }

    @Test
    void 입찰_단위가_상한을_넘거나_지수_표기로_아주_큰_값이면_바로_400이다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");
        long cat = categoryId();

        register(seller, body("a", cat, List.of(image), "100000", "50000100", endAt(60))).andExpect(status().isBadRequest());
        // 나머지 계산까지 가면 끝나지 않는 값이다. 요청 형식 검사에서 걸러져야 한다.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                register(seller, body("a", cat, List.of(image), "100000", "1E+100000000", endAt(60)))
                        .andExpect(status().isBadRequest()));
    }

    @Test
    void 종료_시각에_오프셋이_없으면_400이다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");

        register(seller, body("a", categoryId(), List.of(image), "100000", "1000", "2099-01-01T10:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }

    // ---- 종료 시각 ----

    @Test
    void 종료_시각이_10분_뒤보다_이르거나_30일을_넘으면_AUCTION_END_TIME_INVALID다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");
        long cat = categoryId();

        for (long minutes : new long[] {-60, 0, 9, 60 * 24 * 31}) {
            register(seller, body("t" + minutes, cat, List.of(image), "10000", "1000", endAt(minutes)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("AUCTION_END_TIME_INVALID"));
        }
        // 시각 형식이 맞지 않으면 일반 입력 오류다.
        register(seller, body("t", cat, List.of(image), "10000", "1000", "내일 저녁")).andExpect(status().isBadRequest());
    }

    // ---- 이미지 ----

    @Test
    void 이미지가_없으면_AUCTION_IMAGE_REQUIRED다() throws Exception {
        Cookie seller = newSeller();
        long cat = categoryId();

        register(seller, body("a", cat, List.of(), "10000", "1000", endAt(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUCTION_IMAGE_REQUIRED"));
        register(seller, body("a", cat, null, "10000", "1000", endAt(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUCTION_IMAGE_REQUIRED"));
    }

    @Test
    void 이미지가_10장을_넘거나_식별자가_중복되면_400이다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");

        register(seller, validBody(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUCTION_INPUT_INVALID"))
                .andExpect(jsonPath("$.error.details[0].field").value("product.imageIds"));
        register(seller, validBody(List.of(image, image))).andExpect(status().isBadRequest());
    }

    @Test
    void 없는_이미지와_프로필_이미지와_삭제된_이미지는_쓸_수_없다() throws Exception {
        Cookie seller = newSeller();
        long profile = upload(seller, "PROFILE");
        long deleted = upload(seller, "PRODUCT");
        mockMvc.perform(delete("/api/images/" + deleted).cookie(seller)).andExpect(status().isOk());

        register(seller, validBody(List.of(999_999_999L))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUCTION_INPUT_INVALID"));
        register(seller, validBody(List.of(profile))).andExpect(status().isBadRequest());
        register(seller, validBody(List.of(deleted))).andExpect(status().isBadRequest());
    }

    @Test
    void 다른_회원이_올린_이미지는_없는_이미지와_같은_400이고_경매는_만들어지지_않는다() throws Exception {
        Cookie seller = newSeller();
        Cookie other = newSeller();
        long mine = upload(seller, "PRODUCT");
        long theirs = upload(other, "PRODUCT");
        long before = auctionRepository.count();

        String notOwned = register(seller, validBody(List.of(mine, theirs)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUCTION_INPUT_INVALID"))
                .andReturn().getResponse().getContentAsString();
        String missing = register(seller, validBody(List.of(mine, 999_999_999L)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        // 남의 이미지와 없는 이미지는 응답으로 구별되지 않는다. (이미지 식별자로 존재 여부를 알아내지 못하게 한다.)
        assertThat(notOwned).isEqualTo(missing);

        assertThat(auctionRepository.count()).isEqualTo(before);
        assertThat(productImageRepository.existsByImageFileId(mine)).isFalse();   // 내 이미지도 연결되지 않는다.
    }

    @Test
    void 이미_다른_경매에_쓴_이미지는_다시_쓸_수_없다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");
        registerOk(seller, validBody(List.of(image)));

        register(seller, validBody(List.of(image)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUCTION_INPUT_INVALID"));
    }

    // ---- 입력 검증 ----

    @Test
    void 상품_정보의_길이와_필수값을_확인한다() throws Exception {
        Cookie seller = newSeller();
        long cat = categoryId();
        long image = upload(seller, "PRODUCT");
        String end = endAt(60);

        register(seller, body("   ", cat, List.of(image), "10000", "1000", end)).andExpect(status().isBadRequest());
        register(seller, body("가".repeat(101), cat, List.of(image), "10000", "1000", end)).andExpect(status().isBadRequest());
        String longDescription = "{\"product\":{\"name\":\"a\",\"categoryId\":" + cat + ",\"description\":\"" + "나".repeat(5001)
                + "\",\"conditionNote\":\"b\",\"imageIds\":[" + image + "]},\"auction\":{\"startPrice\":10000,\"bidUnit\":1000,\"endAt\":\"" + end + "\"}}";
        register(seller, longDescription)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("product.description"));
        String longCondition = "{\"product\":{\"name\":\"a\",\"categoryId\":" + cat + ",\"description\":\"d\",\"conditionNote\":\""
                + "다".repeat(501) + "\",\"imageIds\":[" + image + "]},\"auction\":{\"startPrice\":10000,\"bidUnit\":1000,\"endAt\":\"" + end + "\"}}";
        register(seller, longCondition).andExpect(status().isBadRequest());
        // 객체가 통째로 빠짐
        register(seller, "{\"auction\":{\"startPrice\":10000,\"bidUnit\":1000,\"endAt\":\"" + end + "\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUCTION_INPUT_INVALID"));
        register(seller, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void 카테고리는_삭제되지_않고_사용_중인_것만_고를_수_있다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");
        Category inactive = categoryRepository.save(Category.create("비활성" + TestSuffix.next(), 8800));
        inactive.changeActive(false);
        categoryRepository.save(inactive);
        Category deleted = categoryRepository.save(Category.create("삭제" + TestSuffix.next(), 8801));
        deleted.delete();
        categoryRepository.save(deleted);

        for (Long id : new Long[] {inactive.getId(), deleted.getId(), 999_999_999L}) {
            register(seller, body("c", id, List.of(image), "10000", "1000", endAt(60)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("product.categoryId"));
        }
        register(seller, body("c", null, List.of(image), "10000", "1000", endAt(60))).andExpect(status().isBadRequest());
    }

    // ---- 권한 ----

    @Test
    void 비로그인은_401이고_관리자는_403이고_거래_제한_회원은_MEMBER_TRADE_RESTRICTED다() throws Exception {
        register(null, validBody(List.of(1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        Cookie admin = cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL));
        register(admin, validBody(List.of(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

        Cookie restricted = cookieOf(createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE));
        register(restricted, validBody(List.of(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_TRADE_RESTRICTED"));
    }

    // ---- 동시성 ----

    @Test
    void 같은_이미지로_동시에_등록해도_한_건만_성공한다() throws Exception {
        Cookie seller = newSeller();
        long image = upload(seller, "PRODUCT");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            // 상품명을 다르게 해, 같은 요청으로 보는 중복 요청 방지가 아니라 이미지 사용 규칙까지 도달시킨다.
            String json = body("동시" + i + TestSuffix.next(), categoryId(), List.of(image), "10000", "1000", endAt(60));
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return register(seller, json).andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 400).count()).isEqualTo(threads - 1);
    }

    @Test
    void 카테고리_삭제와_경매_등록이_겹쳐도_쓰이는_카테고리는_삭제되지_않는다() throws Exception {
        Cookie admin = cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL));
        for (int round = 0; round < 12; round++) {
            Cookie seller = newSeller();   // 등록이 거부된 회차의 이미지가 쓰지 않은 이미지 한도(10장)를 채우지 않게 매번 새 회원으로 한다.
            Category category = categoryRepository.save(Category.create("경쟁" + TestSuffix.next(), 8900));
            long image = upload(seller, "PRODUCT");
            String json = body("경쟁" + TestSuffix.next(), category.getId(), List.of(image), "10000", "1000", endAt(60));
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> registered = pool.submit(() -> {
                go.await();
                return register(seller, json).andReturn().getResponse().getStatus();
            });
            Future<Integer> deleted = pool.submit(() -> {
                go.await();
                return mockMvc.perform(delete("/api/admin/categories/" + category.getId()).cookie(admin))
                        .andReturn().getResponse().getStatus();
            });
            go.countDown();
            int registerStatus = registered.get();
            int deleteStatus = deleted.get();
            pool.shutdown();

            boolean isDeleted = categoryRepository.findById(category.getId()).orElseThrow().isDeleted();
            long products = new TransactionTemplate(transactionManager).execute(status -> auctionRepository.findAll().stream()
                    .filter(a -> a.getProduct().getCategory().getId().equals(category.getId())).count());
            // 불변식: 삭제된 카테고리에 상품이 있으면 안 된다. 등록이 성공했으면 삭제는 409, 삭제가 성공했으면 등록은 400이다.
            assertThat(isDeleted && products > 0).as("round %d: 등록 %d, 삭제 %d", round, registerStatus, deleteStatus).isFalse();
            assertThat(registerStatus == 200).isEqualTo(products == 1);
            assertThat(deleteStatus == 200).isEqualTo(isDeleted);
        }
    }

    @Test
    void 이미지_삭제와_경매_등록이_겹쳐도_쓰인_이미지가_지워지지_않는다() throws Exception {
        Cookie seller = newSeller();
        for (int round = 0; round < 12; round++) {
            long image = upload(seller, "PRODUCT");
            String json = validBody(List.of(image));
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> registered = pool.submit(() -> {
                go.await();
                return register(seller, json).andReturn().getResponse().getStatus();
            });
            Future<Integer> deleted = pool.submit(() -> {
                go.await();
                return mockMvc.perform(delete("/api/images/" + image).cookie(seller)).andReturn().getResponse().getStatus();
            });
            go.countDown();
            int registerStatus = registered.get();
            int deleteStatus = deleted.get();
            pool.shutdown();

            ImageFile saved = imageFileRepository.findById(image).orElseThrow();
            boolean used = productImageRepository.existsByImageFileId(image);
            // 불변식: 경매에 쓰였으면 지워지지 않았고, 지워졌으면 경매에 쓰이지 않았다.
            assertThat(used && saved.isDeleted()).as("round %d: 등록 %d, 삭제 %d", round, registerStatus, deleteStatus).isFalse();
            assertThat(registerStatus == 200).isEqualTo(used);
            if (used) {
                assertThat(deleteStatus).isEqualTo(409);
                assertThat(UPLOAD_ROOT.resolve(saved.getFilePath())).exists();
            }
        }
    }

    // ---- 내 경매 목록 ----

    @Test
    void 내_경매_목록은_최신순이고_명세의_형식으로_응답하며_다른_회원의_경매는_보이지_않는다() throws Exception {
        Cookie seller = newSeller();
        Cookie other = newSeller();
        long firstImage = upload(seller, "PRODUCT");
        long secondImage = upload(seller, "PRODUCT");
        long firstId = registerOk(seller, body("먼저", categoryId(), List.of(firstImage), "100000", "1000", endAt(120)));
        long secondId = registerOk(seller, body("나중", categoryId(), List.of(secondImage), "200000", "2000", endAt(240)));
        registerOk(other, validBody(List.of(upload(other, "PRODUCT"))));

        mockMvc.perform(get("/api/members/me/auctions").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.content[0].auctionId").value(secondId))
                .andExpect(jsonPath("$.data.content[0].productName").value("나중"))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("/api/images/" + secondImage))
                .andExpect(jsonPath("$.data.content[0].startPrice").value(200000))
                .andExpect(jsonPath("$.data.content[0].currentPrice").isEmpty())
                .andExpect(jsonPath("$.data.content[0].bidCount").value(0))
                .andExpect(jsonPath("$.data.content[0].auctionStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].remainingSeconds").value(org.hamcrest.Matchers.greaterThan(60 * 60 * 3)))
                .andExpect(jsonPath("$.data.content[0].categoryName").isString())
                .andExpect(jsonPath("$.data.content[1].auctionId").value(firstId));
    }

    @Test
    void 내_경매_목록은_페이지로_나뉘고_잘못된_페이지_값은_400이다() throws Exception {
        Cookie seller = newSeller();
        for (int i = 0; i < 3; i++) {
            registerOk(seller, validBody(List.of(upload(seller, "PRODUCT"))));
        }

        mockMvc.perform(get("/api/members/me/auctions?page=1&size=2").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(1));
        mockMvc.perform(get("/api/members/me/auctions?size=0").cookie(seller)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/members/me/auctions?size=101").cookie(seller)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/members/me/auctions?page=-1").cookie(seller)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/members/me/auctions?page=abc").cookie(seller)).andExpect(status().isBadRequest());
    }

    @Test
    void 경매가_없으면_빈_목록이고_비로그인은_401_관리자는_403이며_거래_제한_회원은_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/api/members/me/auctions").cookie(newSeller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/members/me/auctions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/members/me/auctions").cookie(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/members/me/auctions").cookie(cookieOf(createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE))))
                .andExpect(status().isOk());
    }
}

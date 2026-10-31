package negocut.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.Bid;
import negocut.auction.entity.Category;
import negocut.auction.entity.Product;
import negocut.auction.entity.ProductImage;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductImageRepository;
import negocut.auction.repository.ProductRepository;
import negocut.auction.service.AuctionCloseProcessor;
import negocut.auth.token.JwtTokenProvider;
import negocut.file.repository.ImageFileRepository;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;

// 관리자 경매 관리: 경매 목록(중단 포함)과 강제 종료·숨김 (기능 명세서 3-10 경매 강제 종료·숨김)
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuctionTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Path UPLOAD_ROOT = createUploadRoot();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00};

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
            return Files.createTempDirectory("negocut-admin-auction-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private ImageFileRepository imageFileRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private AuctionCloseProcessor auctionCloseProcessor;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role) {
        String suffix = TestSuffix.next();
        Member member = Member.create("aa" + suffix, passwordEncoder.encode("abcd1234"),
                "경매" + suffix.substring(2), "aa" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            PointBalance balance = pointBalanceRepository.save(PointBalance.createEmpty(member));
            ReflectionTestUtils.setField(balance, "totalPoint", BigDecimal.valueOf(10_000_000));
            pointBalanceRepository.save(balance);
        }
        return member;
    }

    private Member newMember() {
        return createMember(MemberRole.MEMBER);
    }

    private Member newAdmin() {
        return createMember(MemberRole.ADMIN);
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    private Category newCategory() {
        return categoryRepository.save(Category.create("중단" + TestSuffix.next(), 8600));
    }

    // 저장소로 직접 만든다. 시작가 300,000원, 입찰 단위 5,000원이다.
    private Auction newAuction(Member seller, Category category, String name, long minutesToEnd) {
        Product product = productRepository.save(Product.create(category, name, "설명", "상태 설명"));
        return auctionRepository.save(Auction.register(product, seller, BigDecimal.valueOf(300_000), BigDecimal.valueOf(5_000),
                LocalDateTime.now(ZONE).plusMinutes(minutesToEnd)));
    }

    private Auction newAuction(Member seller, String name) {
        return newAuction(seller, newCategory(), name, 120);
    }

    private Auction setStatus(Auction auction, AuctionStatus status) {
        Auction saved = auctionRepository.findById(auction.getId()).orElseThrow();
        ReflectionTestUtils.setField(saved, "auctionStatus", status);
        return auctionRepository.save(saved);
    }

    private Auction reload(Auction auction) {
        entityManager.clear();
        return auctionRepository.findById(auction.getId()).orElseThrow();
    }

    private ResultActions bid(Member bidder, Auction auction, long price) throws Exception {
        return mockMvc.perform(post("/api/auctions/" + auction.getId() + "/bids").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidPrice\":" + price + "}").cookie(cookieOf(bidder)));
    }

    private ResultActions stop(Cookie cookie, long auctionId, String reason) throws Exception {
        var request = post("/api/admin/auctions/" + auctionId + "/stop").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stoppedReason\":\"" + reason + "\"}");
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private void setTotalPoint(Member member, long total) {
        PointBalance balance = pointBalanceRepository.findByMemberId(member.getId()).orElseThrow();
        ReflectionTestUtils.setField(balance, "totalPoint", BigDecimal.valueOf(total));
        pointBalanceRepository.save(balance);
    }

    private BigDecimal reservedOf(Member member) {
        entityManager.clear();
        return pointBalanceRepository.findByMemberId(member.getId()).orElseThrow().getReservedPoint();
    }

    private BigDecimal totalOf(Member member) {
        entityManager.clear();
        return pointBalanceRepository.findByMemberId(member.getId()).orElseThrow().getTotalPoint();
    }

    private long histories(Member member, Auction auction, String type) {
        return entityManager.createQuery("select count(h) from PointHistory h where h.member.id = :m and h.auction.id = :a and h.historyType = "
                + "negocut.point.entity.PointHistoryType." + type, Long.class)
                .setParameter("m", member.getId()).setParameter("a", auction.getId()).getSingleResult();
    }

    private long reservedBidCount(Auction auction) {
        return entityManager.createQuery("select count(b) from Bid b where b.auction.id = :a and b.reserved = true", Long.class)
                .setParameter("a", auction.getId()).getSingleResult();
    }

    private long tradeCount(Auction auction) {
        return entityManager.createQuery("select count(t) from Trade t where t.auctionResult.auction.id = :a", Long.class)
                .setParameter("a", auction.getId()).getSingleResult();
    }

    // ---- 중단 ----

    @Test
    void 진행_중인_경매를_중단하면_상태와_사유가_남고_입찰이_없으면_해제할_예약이_없다() throws Exception {
        Member admin = newAdmin();
        Auction auction = newAuction(newMember(), "중단 대상 경매");

        stop(cookieOf(admin), auction.getId(), "금지 물품에 해당합니다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auctionId").value(auction.getId()))
                .andExpect(jsonPath("$.data.auctionStatus").value("STOPPED"))
                .andExpect(jsonPath("$.data.stoppedAt").isString())
                .andExpect(jsonPath("$.data.releasedReservationCount").value(0));

        Auction saved = reload(auction);
        assertThat(saved.getAuctionStatus()).isEqualTo(AuctionStatus.STOPPED);
        assertThat(saved.getStoppedReason()).isEqualTo("금지 물품에 해당합니다");
        assertThat(saved.getStoppedByAdminId()).isEqualTo(admin.getId());
        assertThat(saved.getStoppedAt()).isNotNull();
    }

    @Test
    void 입찰이_있으면_최고_입찰자의_예약만_풀리고_밀려난_입찰자와_포인트_총량은_그대로다() throws Exception {
        Member admin = newAdmin();
        Member first = newMember();
        Member second = newMember();
        Auction auction = newAuction(newMember(), "입찰 있는 경매");
        bid(first, auction, 305_000).andExpect(status().isOk());
        bid(second, auction, 310_000).andExpect(status().isOk());
        assertThat(reservedOf(first)).isEqualByComparingTo("0");   // 밀려나며 이미 풀렸다.
        assertThat(reservedOf(second)).isEqualByComparingTo("310000");
        long firstReleases = histories(first, auction, "RELEASE");

        stop(cookieOf(admin), auction.getId(), "허위 정보가 확인됩니다")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.releasedReservationCount").value(1));

        assertThat(reservedOf(second)).isEqualByComparingTo("0");
        assertThat(totalOf(second)).isEqualByComparingTo("10000000");   // 예약만 풀리고 보유 포인트는 그대로다.
        assertThat(histories(second, auction, "RELEASE")).isEqualTo(1);
        assertThat(histories(first, auction, "RELEASE")).isEqualTo(firstReleases);
        assertThat(reservedBidCount(auction)).isZero();
        assertThat(tradeCount(auction)).isZero();
    }

    @Test
    void 등록과_진행_상태만_중단할_수_있고_종료_시각이_지난_진행_경매도_중단할_수_있다() throws Exception {
        Member admin = newAdmin();
        Cookie cookie = cookieOf(admin);
        Member seller = newMember();

        Auction registered = setStatus(newAuction(seller, "등록 상태"), AuctionStatus.REGISTERED);
        stop(cookie, registered.getId(), "등록 상태 중단").andExpect(status().isOk());
        assertThat(reload(registered).getAuctionStatus()).isEqualTo(AuctionStatus.STOPPED);

        // 종료 시각이 지났지만 종료 처리 전인 진행 중 경매
        Auction overdue = newAuction(seller, newCategory(), "종료 직전 경매", 120);
        ReflectionTestUtils.setField(overdue, "endAt", LocalDateTime.now(ZONE).minusMinutes(1));
        auctionRepository.save(overdue);
        stop(cookie, overdue.getId(), "종료 직전 중단").andExpect(status().isOk());
        assertThat(reload(overdue).getAuctionStatus()).isEqualTo(AuctionStatus.STOPPED);

        for (AuctionStatus finished : new AuctionStatus[] {AuctionStatus.SOLD, AuctionStatus.FAILED, AuctionStatus.STOPPED}) {
            Auction auction = setStatus(newAuction(seller, "상태 " + finished), finished);
            stop(cookie, auction.getId(), "허용되지 않는 상태").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("AUCTION_STATUS_INVALID"));
            assertThat(reload(auction).getAuctionStatus()).isEqualTo(finished);
        }
    }

    @Test
    void 최고_입찰자가_더_높게_다시_입찰한_경매를_중단하면_새_입찰_전체가_풀린다() throws Exception {
        Member admin = newAdmin();
        Member bidder = newMember();
        Auction auction = newAuction(newMember(), "재입찰 경매");
        bid(bidder, auction, 305_000).andExpect(status().isOk());
        bid(bidder, auction, 315_000).andExpect(status().isOk());   // 최고 입찰자 본인의 재입찰은 차액만 더 예약한다.
        assertThat(reservedOf(bidder)).isEqualByComparingTo("315000");
        assertThat(reservedBidCount(auction)).isEqualTo(1);

        stop(cookieOf(admin), auction.getId(), "재입찰 경매 중단")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.releasedReservationCount").value(1));

        assertThat(reservedOf(bidder)).isEqualByComparingTo("0");
        assertThat(totalOf(bidder)).isEqualByComparingTo("10000000");
        assertThat(reservedBidCount(auction)).isZero();
        // 해제 이력은 한 건이고 금액은 새 입찰 전체다. 처리 뒤 예약 잔액도 0이다.
        List<Object[]> releases = entityManager.createQuery("select h.amount, h.reservedPointAfter from PointHistory h "
                + "where h.member.id = :m and h.auction.id = :a and h.historyType = negocut.point.entity.PointHistoryType.RELEASE", Object[].class)
                .setParameter("m", bidder.getId()).setParameter("a", auction.getId()).getResultList();
        assertThat(releases).hasSize(1);
        assertThat((BigDecimal) releases.get(0)[0]).isEqualByComparingTo("315000");
        assertThat((BigDecimal) releases.get(0)[1]).isEqualByComparingTo("0");
    }

    @Test
    void 같은_입찰자가_걸린_두_경매의_중단과_충전이_겹쳐도_멈추지_않고_예약이_모두_풀린다() throws Exception {
        Member admin = newAdmin();
        for (int round = 0; round < 5; round++) {
            Member bidder = newMember();
            setTotalPoint(bidder, 5_000_000);   // 충전 한도(1,000만)에 걸리지 않도록 보유 포인트를 낮춘다.
            Auction first = newAuction(newMember(), "교차 경매 A" + round);
            Auction second = newAuction(newMember(), "교차 경매 B" + round);
            bid(bidder, first, 305_000).andExpect(status().isOk());
            bid(bidder, second, 310_000).andExpect(status().isOk());
            int amount = 1_000 * (round + 1);   // 본문이 라운드마다 달라야 중복 요청 방지가 재생하지 않는다.

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            List<Future<Integer>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                go.await();
                return stop(cookieOf(admin), first.getId(), "교차 중단 A " + System.nanoTime()).andReturn().getResponse().getStatus();
            }));
            results.add(pool.submit(() -> {
                go.await();
                return stop(cookieOf(admin), second.getId(), "교차 중단 B " + System.nanoTime()).andReturn().getResponse().getStatus();
            }));
            results.add(pool.submit(() -> {
                go.await();
                return mockMvc.perform(post("/api/members/me/points/charge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}").cookie(cookieOf(bidder))).andReturn().getResponse().getStatus();
            }));
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, java.util.concurrent.TimeUnit.SECONDS)).as("round %d", round).isEqualTo(200);   // 교착 없이 끝난다.
            }
            pool.shutdown();

            assertThat(reservedOf(bidder)).as("round %d", round).isEqualByComparingTo("0");
            assertThat(totalOf(bidder)).as("round %d", round).isEqualByComparingTo(BigDecimal.valueOf(5_000_000 + amount));
        }
    }

    @Test
    void 같은_중단_요청을_바로_다시_보내도_이전_응답을_재생하지_않고_409다() throws Exception {
        Member admin = newAdmin();
        Auction auction = newAuction(newMember(), "반복 중단");

        stop(cookieOf(admin), auction.getId(), "같은 본문 반복").andExpect(status().isOk());
        stop(cookieOf(admin), auction.getId(), "같은 본문 반복").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUCTION_STATUS_INVALID"));
    }

    @Test
    void 없는_경매는_404이고_사유가_잘못되면_400이며_아무것도_바뀌지_않는다() throws Exception {
        Member admin = newAdmin();
        Cookie cookie = cookieOf(admin);
        Member bidder = newMember();
        Auction auction = newAuction(newMember(), "검증 경매");
        bid(bidder, auction, 305_000).andExpect(status().isOk());

        stop(cookie, 999999999L, "없는 경매").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("AUCTION_NOT_FOUND"));
        long id = auction.getId();
        stop(cookie, id, "").andExpect(status().isBadRequest());
        stop(cookie, id, "   ").andExpect(status().isBadRequest());
        stop(cookie, id, "\\u200b\\u3164").andExpect(status().isBadRequest());
        stop(cookie, id, "\\u115f\\u0000").andExpect(status().isBadRequest());
        stop(cookie, id, "사유\\u0000").andExpect(status().isBadRequest());
        // 글자 순서를 뒤집는 방향 전환 문자가 섞이면 400이다.
        stop(cookie, id, "금지\\u202e물품").andExpect(status().isBadRequest());
        stop(cookie, id, "금지\\u2067물품").andExpect(status().isBadRequest());
        stop(cookie, id, "가".repeat(501)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/auctions/" + id + "/stop").contentType(MediaType.APPLICATION_JSON).content("{}").cookie(cookie))
                .andExpect(status().isBadRequest());

        Auction saved = reload(auction);
        assertThat(saved.getAuctionStatus()).isEqualTo(AuctionStatus.IN_PROGRESS);
        assertThat(reservedOf(bidder)).isEqualByComparingTo("305000");   // 거부되면 예약도 그대로다.

        stop(cookie, id, "가".repeat(500)).andExpect(status().isOk());   // 경계
    }

    @Test
    void 관리자만_쓸_수_있다() throws Exception {
        Member member = newMember();
        Auction auction = newAuction(newMember(), "권한 경매");

        stop(null, auction.getId(), "사유").andExpect(status().isUnauthorized());
        stop(cookieOf(member), auction.getId(), "사유").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/auctions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/auctions").cookie(cookieOf(member))).andExpect(status().isForbidden());

        assertThat(reload(auction).getAuctionStatus()).isEqualTo(AuctionStatus.IN_PROGRESS);
    }

    // ---- 중단 뒤의 노출 ----

    @Test
    void 중단한_경매는_목록에서_빠지고_입찰을_받지_않고_판매자와_입찰자만_사유와_함께_상세를_본다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member bidder = newMember();
        Member outsider = newMember();
        Category category = newCategory();
        Auction auction = newAuction(seller, category, "숨길 경매", 120);
        bid(bidder, auction, 305_000).andExpect(status().isOk());
        mockMvc.perform(get("/api/auctions").param("category", String.valueOf(category.getId())))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        stop(cookieOf(admin), auction.getId(), "운영 정책 위반").andExpect(status().isOk());

        mockMvc.perform(get("/api/auctions").param("category", String.valueOf(category.getId())))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        bid(outsider, auction, 310_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUCTION_NOT_IN_PROGRESS"));
        mockMvc.perform(get("/api/auctions/" + auction.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auctions/" + auction.getId()).cookie(cookieOf(outsider))).andExpect(status().isNotFound());
        for (Member viewer : new Member[] {seller, bidder}) {
            mockMvc.perform(get("/api/auctions/" + auction.getId()).cookie(cookieOf(viewer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.auctionStatus").value("STOPPED"))
                    .andExpect(jsonPath("$.data.stoppedReason").value("운영 정책 위반"));
        }
        assertThat(reservedOf(outsider)).isEqualByComparingTo("0");
    }

    @Test
    void 중단한_경매의_상품_이미지는_공개하지_않고_판매자와_입찰자와_관리자만_본다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member bidder = newMember();
        Member outsider = newMember();
        Category category = newCategory();
        long imageId = upload(cookieOf(seller));
        long auctionId = register(seller, category, imageId);
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        bid(bidder, auction, 305_000).andExpect(status().isOk());
        // 중단 전에는 누구나 본다. 중단되면 비공개로 바뀌므로 공유 캐시에 남는 시간은 5분으로 짧다.
        mockMvc.perform(get("/api/images/" + imageId)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("max-age=300"), org.hamcrest.Matchers.containsString("public"))));
        mockMvc.perform(get("/api/images/" + imageId).cookie(cookieOf(outsider))).andExpect(status().isOk());

        stop(cookieOf(admin), auctionId, "금지 물품").andExpect(status().isOk());

        // 이미지가 있는지도 알리지 않도록 없는 이미지와 같은 404다.
        mockMvc.perform(get("/api/images/" + imageId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/images/" + imageId).cookie(cookieOf(outsider))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/images/" + imageId).cookie(cookieOf(seller))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mockMvc.perform(get("/api/images/" + imageId).cookie(cookieOf(bidder))).andExpect(status().isOk());
        mockMvc.perform(get("/api/images/" + imageId).cookie(cookieOf(admin))).andExpect(status().isOk());

        // 중단된 경매의 이미지는 지울 수도 없다. 올린 본인은 사용 중이라 409, 그 밖에는 없는 이미지와 같은 404다.
        mockMvc.perform(delete("/api/images/" + imageId).cookie(cookieOf(seller))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FILE_IN_USE"));
        mockMvc.perform(delete("/api/images/" + imageId).cookie(cookieOf(outsider))).andExpect(status().isNotFound());
    }

    @Test
    void 관리자도_경매에_쓰이지_않은_이미지는_볼_수_없다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        long unusedId = upload(cookieOf(seller));   // 올리기만 하고 경매에 연결하지 않았다.

        mockMvc.perform(get("/api/images/" + unusedId).cookie(cookieOf(admin))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/images/" + unusedId).cookie(cookieOf(seller))).andExpect(status().isOk());
    }

    @Test
    void 같은_이미지가_중단된_경매와_진행_경매에_함께_연결돼도_공개하지_않는다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Category category = newCategory();
        long imageId = upload(cookieOf(seller));
        long stoppedId = register(seller, category, imageId);
        // 데이터가 어긋나 같은 이미지가 다른 진행 경매의 상품에도 연결됐다.
        Product other = productRepository.save(Product.create(category, "어긋난 연결", "설명", "상태 설명"));
        productImageRepository.save(ProductImage.create(other, imageFileRepository.findById(imageId).orElseThrow(), 1));
        auctionRepository.save(Auction.register(other, seller, BigDecimal.valueOf(300_000), BigDecimal.valueOf(5_000),
                LocalDateTime.now(ZONE).plusHours(2)));
        mockMvc.perform(get("/api/images/" + imageId)).andExpect(status().isOk());

        stop(cookieOf(admin), stoppedId, "중복 연결 중단").andExpect(status().isOk());

        mockMvc.perform(get("/api/images/" + imageId)).andExpect(status().isNotFound());
    }

    // ---- 동시성 ----

    @Test
    void 종료_처리와_중단이_동시에_들어와도_한쪽만_성립한다() throws Exception {
        Member admin = newAdmin();
        for (int round = 0; round < 8; round++) {
            Member bidder = newMember();
            Auction auction = newAuction(newMember(), "종료 경쟁 " + round);
            bid(bidder, auction, 305_000).andExpect(status().isOk());
            // 입찰로 바뀐 최고 입찰 정보를 덮어쓰지 않도록 다시 읽은 경매의 종료 시각만 바꾼다.
            Auction fresh = reload(auction);
            ReflectionTestUtils.setField(fresh, "endAt", LocalDateTime.now(ZONE).minusSeconds(5));
            auctionRepository.save(fresh);

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Boolean> closed = pool.submit(() -> {
                go.await();
                return auctionCloseProcessor.close(auction.getId());
            });
            Future<Integer> stopped = pool.submit(() -> {
                go.await();
                return stop(cookieOf(admin), auction.getId(), "경쟁 중단 " + System.nanoTime()).andReturn().getResponse().getStatus();
            });
            go.countDown();
            boolean closeWon = closed.get();
            int stopStatus = stopped.get();
            pool.shutdown();

            AuctionStatus finalStatus = reload(auction).getAuctionStatus();
            if (finalStatus == AuctionStatus.SOLD) {
                // 낙찰이 먼저다. 거래가 한 건 만들어지고 낙찰자의 예약은 유지되며, 중단은 거부된다.
                assertThat(closeWon).as("round %d", round).isTrue();
                assertThat(stopStatus).as("round %d", round).isEqualTo(409);
                assertThat(tradeCount(auction)).as("round %d", round).isEqualTo(1);
                assertThat(reservedOf(bidder)).as("round %d", round).isEqualByComparingTo("305000");
            } else {
                // 중단이 먼저다. 거래는 없고 예약은 풀리며, 종료 처리는 건너뛴다.
                assertThat(finalStatus).as("round %d", round).isEqualTo(AuctionStatus.STOPPED);
                assertThat(closeWon).as("round %d", round).isFalse();
                assertThat(stopStatus).as("round %d", round).isEqualTo(200);
                assertThat(tradeCount(auction)).as("round %d", round).isZero();
                assertThat(reservedOf(bidder)).as("round %d", round).isEqualByComparingTo("0");
            }
        }
    }

    @Test
    void 입찰과_중단이_동시에_들어와도_중단된_경매에는_예약이_남지_않는다() throws Exception {
        Member admin = newAdmin();
        for (int round = 0; round < 6; round++) {
            Member early = newMember();
            Member late = newMember();
            Auction auction = newAuction(newMember(), "입찰 경쟁 " + round);
            bid(early, auction, 305_000).andExpect(status().isOk());

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> bidStatus = pool.submit(() -> {
                go.await();
                return bid(late, auction, 310_000).andReturn().getResponse().getStatus();
            });
            Future<Integer> stopStatus = pool.submit(() -> {
                go.await();
                return stop(cookieOf(admin), auction.getId(), "입찰 경쟁 중단 " + System.nanoTime()).andReturn().getResponse().getStatus();
            });
            go.countDown();
            int bidResult = bidStatus.get();
            int stopResult = stopStatus.get();
            pool.shutdown();

            // 입찰이 먼저든 나중이든 중단은 성립하고, 어느 쪽 입찰자에게도 예약이 남지 않는다.
            assertThat(stopResult).as("round %d", round).isEqualTo(200);
            assertThat(bidResult).as("round %d", round).isIn(200, 409);
            assertThat(reload(auction).getAuctionStatus()).isEqualTo(AuctionStatus.STOPPED);
            assertThat(reservedBidCount(auction)).as("round %d", round).isZero();
            assertThat(reservedOf(early)).as("round %d", round).isEqualByComparingTo("0");
            assertThat(reservedOf(late)).as("round %d", round).isEqualByComparingTo("0");
        }
    }

    // ---- 목록 ----

    @Test
    void 경매_목록은_중단을_포함하고_상태와_상품명으로_거르며_최신순이다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Category category = newCategory();
        String tag = "관리목록" + TestSuffix.next();
        Auction progress = newAuction(seller, category, tag + " 진행", 120);
        Auction stopped = newAuction(seller, category, tag + " 중단", 120);
        stop(cookieOf(admin), stopped.getId(), "목록 확인 사유").andExpect(status().isOk());
        Auction sold = setStatus(newAuction(seller, category, tag + " 낙찰", 120), AuctionStatus.SOLD);

        // 중단과 다른 상태가 함께 나오고 최신 등록 순이며, 판매자 닉네임은 가리지 않는다. 중단 사유는 중단된 경매에만 있다.
        mockMvc.perform(get("/api/admin/auctions").param("keyword", tag).cookie(cookieOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].auctionId").value(sold.getId()))
                .andExpect(jsonPath("$.data.content[1].auctionId").value(stopped.getId()))
                .andExpect(jsonPath("$.data.content[1].auctionStatus").value("STOPPED"))
                .andExpect(jsonPath("$.data.content[1].stoppedReason").value("목록 확인 사유"))
                .andExpect(jsonPath("$.data.content[1].sellerNickname").value(seller.getNickname()))
                .andExpect(jsonPath("$.data.content[2].auctionId").value(progress.getId()))
                .andExpect(jsonPath("$.data.content[2].stoppedReason").doesNotExist())
                .andExpect(jsonPath("$.data.content[2].bidCount").value(0));
        mockMvc.perform(get("/api/admin/auctions").param("keyword", tag).param("status", "STOPPED").cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].auctionId").value(stopped.getId()));
        mockMvc.perform(get("/api/admin/auctions").param("keyword", tag).param("status", "FAILED").cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/admin/auctions").param("status", "NOPE").cookie(cookieOf(admin))).andExpect(status().isBadRequest());
    }

    @Test
    void 검색어의_와일드카드_문자는_글자_그대로_찾고_page와_size_검색어_길이가_잘못되면_400이다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Category category = newCategory();
        String suffix = TestSuffix.next();
        Auction underscore = newAuction(seller, category, "ea_" + suffix, 120);
        newAuction(seller, category, "eax" + suffix, 120);
        Auction percent = newAuction(seller, category, "pa%" + suffix, 120);
        newAuction(seller, category, "pay" + suffix, 120);

        assertOnlyMatch(admin, "ea_" + suffix, underscore);
        assertOnlyMatch(admin, "_" + suffix, underscore);
        assertOnlyMatch(admin, "pa%" + suffix, percent);
        assertOnlyMatch(admin, "%" + suffix, percent);
        // 대소문자는 구분하지 않는다. (공개 검색과 같다)
        Auction mixed = newAuction(seller, category, "CaNoN" + suffix, 120);
        assertOnlyMatch(admin, "canon" + suffix, mixed);
        assertOnlyMatch(admin, "CANON" + suffix, mixed);

        for (String[] bad : new String[][] {{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"page", "abc"}}) {
            mockMvc.perform(get("/api/admin/auctions").param(bad[0], bad[1]).cookie(cookieOf(admin))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/admin/auctions").param("keyword", "a".repeat(100)).cookie(cookieOf(admin))).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/auctions").param("keyword", "a".repeat(101)).cookie(cookieOf(admin))).andExpect(status().isBadRequest());
    }

    private void assertOnlyMatch(Member admin, String keyword, Auction expected) throws Exception {
        mockMvc.perform(get("/api/admin/auctions").param("keyword", keyword).cookie(cookieOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].auctionId").value(expected.getId()));
    }

    // ---- 이미지와 등록 ----

    private long upload(Cookie cookie) throws Exception {
        String response = mockMvc.perform(multipart("/api/images")
                        .file(new MockMultipartFile("file", "p.jpg", "image/jpeg", JPEG))
                        .param("usageType", "PRODUCT").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.imageId")).longValue();
    }

    private long register(Member seller, Category category, long imageId) throws Exception {
        String endAt = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(3).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String body = "{\"product\":{\"name\":\"이미지 경매\",\"categoryId\":" + category.getId()
                + ",\"description\":\"설명\",\"conditionNote\":\"상태\",\"imageIds\":[" + imageId + "]},"
                + "\"auction\":{\"startPrice\":300000,\"bidUnit\":5000,\"endAt\":\"" + endAt + "\"}}";
        String response = mockMvc.perform(post("/api/auctions").contentType(MediaType.APPLICATION_JSON).content(body).cookie(cookieOf(seller)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.auctionId")).longValue();
    }
}

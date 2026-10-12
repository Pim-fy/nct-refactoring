package negocut.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.Category;
import negocut.auction.entity.Product;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductRepository;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;

// 경매 목록과 상세 조회 (기능 명세서 3-4)
// 경매는 저장소로 직접 만들고(상태·현재가 같은 값을 정하기 위해), 이미지가 필요한 상세 확인만 실제 등록 API를 쓴다.
// 데이터베이스를 다른 테스트와 함께 쓰므로, 목록 확인은 테스트마다 새 카테고리로 범위를 좁힌다.
@SpringBootTest
@AutoConfigureMockMvc
class AuctionListDetailTest {

    private static final Path UPLOAD_ROOT = createUploadRoot();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00};
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
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
            return Files.createTempDirectory("negocut-auction-list-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("lt" + suffix, passwordEncoder.encode("abcd1234"),
                "판매자" + suffix.substring(2), "lt" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        ReflectionTestUtils.setField(member, "memberStatus", status);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            pointBalanceRepository.save(PointBalance.createEmpty(member));
        }
        return member;
    }

    private Member newMember() {
        return createMember(MemberRole.MEMBER, MemberStatus.NORMAL);
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    private Category newCategory() {
        return categoryRepository.save(Category.create("목록" + TestSuffix.next(), 8700));
    }

    // 경매를 만든다. 종료 시각은 지금부터 minutesToEnd분 뒤다.
    private Auction auction(Member seller, Category category, String name, String description, long startPrice, long minutesToEnd) {
        Product product = productRepository.save(Product.create(category, name, description, "상태 설명"));
        return auctionRepository.save(Auction.register(product, seller, BigDecimal.valueOf(startPrice), BigDecimal.valueOf(1000),
                LocalDateTime.now(ZONE).plusMinutes(minutesToEnd)));
    }

    private Auction auction(Member seller, Category category, String name, long startPrice, long minutesToEnd) {
        return auction(seller, category, name, "설명", startPrice, minutesToEnd);
    }

    private Auction withBids(Auction auction, long currentPrice, int bidCount) {
        ReflectionTestUtils.setField(auction, "currentPrice", BigDecimal.valueOf(currentPrice));
        ReflectionTestUtils.setField(auction, "bidCount", bidCount);
        return auctionRepository.save(auction);
    }

    private Auction withStatus(Auction auction, AuctionStatus status) {
        ReflectionTestUtils.setField(auction, "auctionStatus", status);
        if (status == AuctionStatus.STOPPED) {
            ReflectionTestUtils.setField(auction, "stoppedReason", "운영 정책 위반");
        }
        return auctionRepository.save(auction);
    }

    private ResultActions list(Long category, String query) throws Exception {
        return mockMvc.perform(get("/api/auctions" + (category == null ? "?x=1" : "?category=" + category) + (query == null ? "" : "&" + query)));
    }

    private List<Integer> ids(ResultActions result) throws Exception {
        String json = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(json, "$.data.content[*].auctionId");
        return ids.stream().map(id -> id.intValue()).toList();
    }

    private static List<Integer> idList(Auction... auctions) {
        return Arrays.stream(auctions).map(a -> a.getId().intValue()).toList();
    }

    // ---- 목록 ----

    @Test
    void 비로그인도_목록을_볼_수_있고_기본은_마감_임박순이며_진행_중이_먼저고_중단은_보이지_않는다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction later = auction(seller, category, "나중에 끝남", 1000, 300);
        Auction soon = auction(seller, category, "곧 끝남", 1000, 30);
        Auction sold = withStatus(auction(seller, category, "이미 끝남", 1000, -60), AuctionStatus.SOLD);
        Auction failed = withStatus(auction(seller, category, "유찰됨", 1000, -10), AuctionStatus.FAILED);
        withStatus(auction(seller, category, "중단됨", 1000, 100), AuctionStatus.STOPPED);

        // 진행 중은 마감이 가까운 순, 그 뒤에 종료된 경매는 최근에 끝난 순이다.
        assertThat(ids(list(category.getId(), null))).isEqualTo(idList(soon, later, failed, sold));
        assertThat(ids(list(category.getId(), "sort=endAt"))).isEqualTo(idList(soon, later, failed, sold));
    }

    @Test
    void 목록은_명세의_형식으로_응답한다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction withBid = withBids(auction(seller, category, "입찰 있음", 5000, 120), 7000, 3);

        list(category.getId(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].auctionId").value(withBid.getId()))
                .andExpect(jsonPath("$.data.content[0].productName").value("입찰 있음"))
                .andExpect(jsonPath("$.data.content[0].categoryName").value(category.getName()))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value((Object) null))
                .andExpect(jsonPath("$.data.content[0].startPrice").value(5000))
                .andExpect(jsonPath("$.data.content[0].currentPrice").value(7000))
                .andExpect(jsonPath("$.data.content[0].bidCount").value(3))
                .andExpect(jsonPath("$.data.content[0].auctionStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].endAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void 정렬은_최신순_현재가_낮은순_입찰_많은순을_지원하고_진행_중이_먼저다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction a = withBids(auction(seller, category, "가", 9000, 100), 20000, 2);      // 현재가 20,000, 입찰 2
        Auction b = auction(seller, category, "나", 8000, 200);                           // 입찰 없음: 시작가 8,000, 입찰 0
        Auction c = withBids(auction(seller, category, "다", 1000, 300), 15000, 7);      // 현재가 15,000, 입찰 7
        Auction done = withStatus(withBids(auction(seller, category, "라", 100, -5), 500, 9), AuctionStatus.SOLD);

        assertThat(ids(list(category.getId(), "sort=createdAt"))).isEqualTo(idList(c, b, a, done));
        // 입찰이 없으면 시작가를 현재가로 본다. 낮은 것부터: 나(8,000) < 다(15,000) < 가(20,000)
        assertThat(ids(list(category.getId(), "sort=price"))).isEqualTo(idList(b, c, a, done));
        assertThat(ids(list(category.getId(), "sort=bidCount"))).isEqualTo(idList(c, a, b, done));
    }

    @Test
    void 검색은_상품명과_설명에서_대소문자_구분_없이_찾고_특수문자는_글자_그대로_찾는다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction byName = auction(seller, category, "Canon 카메라", "설명 없음", 1000, 100);
        Auction byDescription = auction(seller, category, "필름", "오래된 CANON 렌즈 포함", 1000, 200);
        Auction percent = auction(seller, category, "할인 100% 상품", "설명", 1000, 300);
        Auction underscore = auction(seller, category, "ab_cd", "설명", 1000, 400);
        auction(seller, category, "abXcd", "설명", 1000, 500);   // _ 가 한 글자 대체 문자로 동작하면 걸린다.

        assertThat(ids(list(category.getId(), "keyword=canon"))).isEqualTo(idList(byName, byDescription));
        assertThat(ids(list(category.getId(), "keyword=%"))).isEqualTo(idList(percent));    // 퍼센트 문자
        assertThat(ids(list(category.getId(), "keyword=_"))).isEqualTo(idList(underscore));
        assertThat(ids(list(category.getId(), "keyword=ab_cd"))).isEqualTo(idList(underscore));
        assertThat(ids(list(category.getId(), "keyword=!"))).isEmpty();
        // 공백만이면 검색어가 없는 것으로 본다.
        assertThat(ids(mockMvc.perform(get("/api/auctions").param("category", String.valueOf(category.getId())).param("keyword", "   ")))).hasSize(5);
        // 전각 공백과 줄바꿈 같은 유니코드 공백도 마찬가지다.
        assertThat(ids(mockMvc.perform(get("/api/auctions").param("category", String.valueOf(category.getId())).param("keyword", "　  \n")))).hasSize(5);
        assertThat(ids(list(category.getId(), "keyword=없는검색어"))).isEmpty();
    }

    @Test
    void 카테고리를_고르면_그_카테고리만_나온다() throws Exception {
        Member seller = newMember();
        Category first = newCategory();
        Category second = newCategory();
        Auction inFirst = auction(seller, first, "첫째", 1000, 100);
        Auction inSecond = auction(seller, second, "둘째", 1000, 100);

        assertThat(ids(list(first.getId(), null))).isEqualTo(idList(inFirst));
        assertThat(ids(list(second.getId(), null))).isEqualTo(idList(inSecond));
        assertThat(ids(list(999_999_999L, null))).isEmpty();
    }

    @Test
    void 목록은_페이지로_나뉘고_잘못된_값은_400이다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction first = auction(seller, category, "하나", 1000, 10);
        Auction second = auction(seller, category, "둘", 1000, 20);
        Auction third = auction(seller, category, "셋", 1000, 30);

        assertThat(ids(list(category.getId(), "size=2&page=0"))).isEqualTo(idList(first, second));
        assertThat(ids(list(category.getId(), "size=2&page=1"))).isEqualTo(idList(third));
        list(category.getId(), "size=2&page=1").andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        assertThat(ids(list(category.getId(), "size=2&page=5"))).isEmpty();

        // page * size가 int 범위를 넘는 값은 조회하지 않고 400이다.
        for (String bad : new String[] {"size=0", "size=101", "page=-1", "sort=unknown", "keyword=" + "가".repeat(101),
                "page=2147483647&size=100", "page=100000000&size=100"}) {
            list(category.getId(), bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        }
    }

    @Test
    void 로그인한_회원과_관리자도_목록과_상세를_볼_수_있다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction auction = auction(seller, category, "누구나", 1000, 100);

        for (Member viewer : List.of(newMember(), createMember(MemberRole.ADMIN, MemberStatus.NORMAL))) {
            mockMvc.perform(get("/api/auctions?category=" + category.getId()).cookie(cookieOf(viewer))).andExpect(status().isOk());
            mockMvc.perform(get("/api/auctions/" + auction.getId()).cookie(cookieOf(viewer))).andExpect(status().isOk());
        }
    }

    // ---- 상세 ----

    @Test
    void 상세는_명세의_형식으로_응답하고_판매자_닉네임은_가린다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction auction = auction(seller, category, "캐논 EOS", "2년 사용", 300000, 120);

        mockMvc.perform(get("/api/auctions/" + auction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auctionId").value(auction.getId()))
                .andExpect(jsonPath("$.data.auctionStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.seller.maskedNickname").value(seller.getNickname().substring(0, 2) + "***"))
                .andExpect(jsonPath("$.data.seller.isMine").value(false))
                .andExpect(jsonPath("$.data.product.name").value("캐논 EOS"))
                .andExpect(jsonPath("$.data.product.categoryName").value(category.getName()))
                .andExpect(jsonPath("$.data.product.description").value("2년 사용"))
                .andExpect(jsonPath("$.data.product.conditionNote").value("상태 설명"))
                .andExpect(jsonPath("$.data.product.images").isEmpty())
                .andExpect(jsonPath("$.data.terms.startPrice").value(300000))
                .andExpect(jsonPath("$.data.terms.bidUnit").value(1000))
                .andExpect(jsonPath("$.data.terms.endAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.terms.deliveryMethod").value("PARCEL"))
                .andExpect(jsonPath("$.data.terms.shippingFeeBearer").value("SELLER"))
                .andExpect(jsonPath("$.data.bidding.currentPrice").value((Object) null))
                .andExpect(jsonPath("$.data.bidding.bidCount").value(0))
                .andExpect(jsonPath("$.data.bidding.minimumBidPrice").value(300000))
                .andExpect(jsonPath("$.data.bidding.remainingSeconds").value(org.hamcrest.Matchers.greaterThan(7000)))
                .andExpect(jsonPath("$.data.bidding.canBid").value(false))   // 비로그인
                .andExpect(jsonPath("$.data.result").value((Object) null))
                .andExpect(jsonPath("$.data.stoppedReason").value((Object) null));
    }

    @Test
    void 입찰이_있으면_다음_입찰_최소_금액은_현재가에_입찰_단위를_더한_값이다() throws Exception {
        Member seller = newMember();
        Auction auction = withBids(auction(seller, newCategory(), "입찰 있음", 300000, 120), 320000, 4);

        mockMvc.perform(get("/api/auctions/" + auction.getId()))
                .andExpect(jsonPath("$.data.bidding.currentPrice").value(320000))
                .andExpect(jsonPath("$.data.bidding.bidCount").value(4))
                .andExpect(jsonPath("$.data.bidding.minimumBidPrice").value(321000));
    }

    @Test
    void 입찰_가능_여부는_로그인_회원_상태_판매자_경매_상태_마감_여부로_정한다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction open = auction(seller, category, "진행", 1000, 120);
        String path = "/api/auctions/" + open.getId();

        // 다른 정상 회원만 입찰할 수 있다.
        mockMvc.perform(get(path).cookie(cookieOf(newMember()))).andExpect(jsonPath("$.data.bidding.canBid").value(true));
        // 판매자 본인
        mockMvc.perform(get(path).cookie(cookieOf(seller)))
                .andExpect(jsonPath("$.data.seller.isMine").value(true))
                .andExpect(jsonPath("$.data.bidding.canBid").value(false));
        // 관리자, 거래 제한 회원
        mockMvc.perform(get(path).cookie(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL))))
                .andExpect(jsonPath("$.data.bidding.canBid").value(false));
        mockMvc.perform(get(path).cookie(cookieOf(createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bidding.canBid").value(false));
        // 종료된 경매, 마감 시각이 지난 경매(종료 처리 대기)
        Auction sold = withStatus(auction(seller, category, "낙찰", 1000, -5), AuctionStatus.SOLD);
        mockMvc.perform(get("/api/auctions/" + sold.getId()).cookie(cookieOf(newMember())))
                .andExpect(jsonPath("$.data.auctionStatus").value("SOLD"))
                .andExpect(jsonPath("$.data.bidding.canBid").value(false));
        Auction expired = auction(seller, category, "마감 지남", 1000, -1);
        mockMvc.perform(get("/api/auctions/" + expired.getId()).cookie(cookieOf(newMember())))
                .andExpect(jsonPath("$.data.bidding.remainingSeconds").value(org.hamcrest.Matchers.lessThanOrEqualTo(0)))
                .andExpect(jsonPath("$.data.bidding.canBid").value(false));
    }

    @Test
    void 없는_경매는_404_AUCTION_NOT_FOUND다() throws Exception {
        mockMvc.perform(get("/api/auctions/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void 중단된_경매는_판매자에게만_사유와_함께_보이고_나머지는_404이며_목록에도_없다() throws Exception {
        Member seller = newMember();
        Category category = newCategory();
        Auction stopped = withStatus(auction(seller, category, "중단됨", 1000, 120), AuctionStatus.STOPPED);
        String path = "/api/auctions/" + stopped.getId();

        mockMvc.perform(get(path).cookie(cookieOf(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auctionStatus").value("STOPPED"))
                .andExpect(jsonPath("$.data.stoppedReason").value("운영 정책 위반"))
                .andExpect(jsonPath("$.data.bidding.canBid").value(false));
        mockMvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("AUCTION_NOT_FOUND"));
        mockMvc.perform(get(path).cookie(cookieOf(newMember()))).andExpect(status().isNotFound());
        mockMvc.perform(get(path).cookie(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL)))).andExpect(status().isNotFound());
        assertThat(ids(list(category.getId(), null))).isEmpty();
    }

    @Test
    void 등록한_이미지는_순서대로_상세와_목록에_나오고_비로그인도_받을_수_있다() throws Exception {
        Member seller = newMember();
        Cookie cookie = cookieOf(seller);
        Category category = newCategory();
        long first = upload(cookie);
        long second = upload(cookie);
        String endAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)).plusHours(3)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String body = "{\"product\":{\"name\":\"이미지 경매\",\"categoryId\":" + category.getId()
                + ",\"description\":\"설명\",\"conditionNote\":\"상태\",\"imageIds\":[" + second + "," + first + "]},"
                + "\"auction\":{\"startPrice\":10000,\"bidUnit\":1000,\"endAt\":\"" + endAt + "\"}}";
        String response = mockMvc.perform(post("/api/auctions").contentType(MediaType.APPLICATION_JSON).content(body).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long auctionId = ((Number) JsonPath.read(response, "$.data.auctionId")).longValue();

        mockMvc.perform(get("/api/auctions/" + auctionId))
                .andExpect(jsonPath("$.data.product.images[0].imageId").value(second))
                .andExpect(jsonPath("$.data.product.images[0].url").value("/api/images/" + second))
                .andExpect(jsonPath("$.data.product.images[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data.product.images[1].imageId").value(first))
                .andExpect(jsonPath("$.data.product.images[1].sortOrder").value(2));
        list(category.getId(), null).andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("/api/images/" + second));
        // 경매에 쓰인 이미지는 로그인하지 않아도 받을 수 있다.
        mockMvc.perform(get("/api/images/" + second)).andExpect(status().isOk());
    }

    private long upload(Cookie cookie) throws Exception {
        String response = mockMvc.perform(multipart("/api/images")
                        .file(new MockMultipartFile("file", "p.jpg", "image/jpeg", JPEG))
                        .param("usageType", "PRODUCT").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.imageId")).longValue();
    }
}

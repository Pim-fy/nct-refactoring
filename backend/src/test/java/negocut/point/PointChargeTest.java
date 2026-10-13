package negocut.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import jakarta.servlet.http.Cookie;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.entity.PointHistory;
import negocut.point.entity.PointHistoryType;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.repository.PointHistoryRepository;
import negocut.support.TestSuffix;

// 포인트 조회와 충전 (기능 명세서 3-5)
@SpringBootTest
@AutoConfigureMockMvc
class PointChargeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("pt" + suffix, passwordEncoder.encode("abcd1234"),
                "nick" + suffix.substring(2), "pt" + suffix + "@example.com", "010-1111-2222");
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

    // 보유 포인트가 total, 예약 포인트가 reserved인 회원을 만든다.
    private Member memberWithPoints(long total, long reserved) {
        Member member = createMember(MemberRole.MEMBER, MemberStatus.NORMAL);
        PointBalance balance = pointBalanceRepository.findByMemberId(member.getId()).orElseThrow();
        ReflectionTestUtils.setField(balance, "totalPoint", BigDecimal.valueOf(total));
        ReflectionTestUtils.setField(balance, "reservedPoint", BigDecimal.valueOf(reserved));
        pointBalanceRepository.save(balance);
        return member;
    }

    private ResultActions charge(Cookie cookie, String json) throws Exception {
        var request = post("/api/members/me/points/charge").contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private ResultActions charge(Cookie cookie, long amount) throws Exception {
        return charge(cookie, "{\"amount\":" + amount + "}");
    }

    private BigDecimal totalOf(Member member) {
        return pointBalanceRepository.findByMemberId(member.getId()).orElseThrow().getTotalPoint();
    }

    private long historyCount(Member member) {
        return pointHistoryRepository.findAll().stream().filter(h -> h.getMember().getId().equals(member.getId())).count();
    }

    // ---- 조회 ----

    @Test
    void 내_포인트는_보유_예약_사용_가능_포인트로_응답한다() throws Exception {
        Member member = memberWithPoints(120000, 32000);

        mockMvc.perform(get("/api/members/me/points").cookie(cookieOf(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalPoint").value(120000))
                .andExpect(jsonPath("$.data.reservedPoint").value(32000))
                .andExpect(jsonPath("$.data.availablePoint").value(88000));
    }

    @Test
    void 처음_가입한_회원의_포인트는_모두_0이다() throws Exception {
        Member member = createMember(MemberRole.MEMBER, MemberStatus.NORMAL);

        mockMvc.perform(get("/api/members/me/points").cookie(cookieOf(member)))
                .andExpect(jsonPath("$.data.totalPoint").value(0))
                .andExpect(jsonPath("$.data.availablePoint").value(0));
    }

    // ---- 충전 ----

    @Test
    void 충전하면_보유_포인트가_늘고_충전_이력이_남는다() throws Exception {
        Member member = memberWithPoints(120000, 32000);

        charge(cookieOf(member), 100000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalPoint").value(220000))
                .andExpect(jsonPath("$.data.reservedPoint").value(32000))
                .andExpect(jsonPath("$.data.availablePoint").value(188000));

        assertThat(totalOf(member)).isEqualByComparingTo("220000");
        List<PointHistory> histories = pointHistoryRepository.findAll().stream()
                .filter(h -> h.getMember().getId().equals(member.getId())).toList();
        assertThat(histories).hasSize(1);
        PointHistory history = histories.get(0);
        assertThat(history.getHistoryType()).isEqualTo(PointHistoryType.CHARGE);
        assertThat(history.getAmount()).isEqualByComparingTo("100000");
        assertThat(history.getTotalPointAfter()).isEqualByComparingTo("220000");
        assertThat(history.getReservedPointAfter()).isEqualByComparingTo("32000");
        assertThat(history.getAuction()).isNull();
        assertThat(history.getTradeId()).isNull();
        // 조회도 같은 값이다.
        mockMvc.perform(get("/api/members/me/points").cookie(cookieOf(member))).andExpect(jsonPath("$.data.totalPoint").value(220000));
    }

    @Test
    void 충전_금액의_경계값은_충전된다() throws Exception {
        Member member = createMember(MemberRole.MEMBER, MemberStatus.NORMAL);
        Cookie cookie = cookieOf(member);

        charge(cookie, 1000).andExpect(status().isOk());
        charge(cookie, 1000000).andExpect(status().isOk());
        charge(cookie, 999000).andExpect(status().isOk());

        assertThat(totalOf(member)).isEqualByComparingTo("2000000");
        assertThat(historyCount(member)).isEqualTo(3);
    }

    @Test
    void 충전_금액이_범위_밖이거나_1000_단위가_아니면_400이고_잔액은_그대로다() throws Exception {
        Member member = memberWithPoints(50000, 0);
        Cookie cookie = cookieOf(member);

        for (String amount : new String[] {"0", "-1000", "500", "999", "1500", "1000001", "2000000", "1000.5", "1500.0", "100000.0", "1000.00", "1E+5", "1E+3", "null"}) {
            charge(cookie, "{\"amount\":" + amount + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("POINT_CHARGE_AMOUNT_INVALID"));
        }
        charge(cookie, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("POINT_CHARGE_AMOUNT_INVALID"));
        // 숫자가 아닌 값은 요청 형식 오류다.
        charge(cookie, "{\"amount\":\"abc\"}").andExpect(status().isBadRequest());
        charge(cookie, "").andExpect(status().isBadRequest());

        assertThat(totalOf(member)).isEqualByComparingTo("50000");
        assertThat(historyCount(member)).isZero();
    }

    @Test
    void 지수_표기로_아주_큰_금액도_바로_400이다() throws Exception {
        Member member = createMember(MemberRole.MEMBER, MemberStatus.NORMAL);
        Cookie cookie = cookieOf(member);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                charge(cookie, "{\"amount\":1E+100000000}").andExpect(status().isBadRequest()));
        assertThat(totalOf(member)).isEqualByComparingTo("0");
    }

    @Test
    void 충전_후_보유_포인트가_1000만을_넘으면_409이고_정확히_1000만까지는_된다() throws Exception {
        Member member = memberWithPoints(9_500_000, 100_000);
        Cookie cookie = cookieOf(member);

        charge(cookie, 501000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_BALANCE_LIMIT_EXCEEDED"));
        assertThat(totalOf(member)).isEqualByComparingTo("9500000");
        assertThat(historyCount(member)).isZero();

        charge(cookie, 500000).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalPoint").value(10000000));
        charge(cookie, 1000).andExpect(status().isConflict());
        assertThat(totalOf(member)).isEqualByComparingTo("10000000");
        assertThat(historyCount(member)).isEqualTo(1);
    }

    @Test
    void 같은_금액을_바로_다시_보내면_첫_응답을_돌려주고_한_번만_충전된다() throws Exception {
        Member member = createMember(MemberRole.MEMBER, MemberStatus.NORMAL);
        Cookie cookie = cookieOf(member);

        charge(cookie, 100000).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalPoint").value(100000));
        charge(cookie, 100000).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalPoint").value(100000));

        assertThat(totalOf(member)).isEqualByComparingTo("100000");
        assertThat(historyCount(member)).isEqualTo(1);
        // 같은 금액을 다른 표기로 보내 중복 요청 방지를 피할 수 없다. (정수 표기만 받는다.)
        charge(cookie, "{\"amount\":100000.0}").andExpect(status().isBadRequest());
        charge(cookie, "{\"amount\":1E+5}").andExpect(status().isBadRequest());
        assertThat(totalOf(member)).isEqualByComparingTo("100000");
        // 다른 금액은 그대로 충전된다.
        charge(cookie, 50000).andExpect(jsonPath("$.data.totalPoint").value(150000));
    }

    // ---- 권한 ----

    @Test
    void 비로그인은_401이고_관리자는_403이며_거래_제한_회원은_충전할_수_없지만_조회는_된다() throws Exception {
        mockMvc.perform(get("/api/members/me/points")).andExpect(status().isUnauthorized());
        charge(null, 1000).andExpect(status().isUnauthorized());

        Cookie admin = cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL));
        mockMvc.perform(get("/api/members/me/points").cookie(admin)).andExpect(status().isForbidden());
        charge(admin, 1000).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

        Member restricted = createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE);
        mockMvc.perform(get("/api/members/me/points").cookie(cookieOf(restricted))).andExpect(status().isOk());
        charge(cookieOf(restricted), 1000).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_TRADE_RESTRICTED"));
        assertThat(totalOf(restricted)).isEqualByComparingTo("0");
    }

    // ---- 동시성 ----

    @Test
    void 같은_회원의_충전이_동시에_겹쳐도_보유_한도를_넘지_않고_이력이_잔액과_맞는다() throws Exception {
        // 남은 충전 가능 금액이 100만이고, 서로 다른 금액 6건(40만~40.5만)을 동시에 보낸다. 두 건까지만 들어갈 수 있다.
        Member member = memberWithPoints(9_000_000, 0);
        Cookie cookie = cookieOf(member);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long amount = 400_000 + i * 1000L;
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return charge(cookie, amount).andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(2);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 2);

        List<PointHistory> histories = pointHistoryRepository.findAll().stream()
                .filter(h -> h.getMember().getId().equals(member.getId())).toList();
        BigDecimal charged = histories.stream().map(h -> h.getAmount()).reduce(BigDecimal.ZERO, (a, b) -> a.add(b));
        assertThat(histories).hasSize(2);
        assertThat(totalOf(member)).isEqualByComparingTo(BigDecimal.valueOf(9_000_000).add(charged));
        assertThat(totalOf(member)).isLessThanOrEqualTo(BigDecimal.valueOf(10_000_000));
        // 이력의 처리 후 잔액이 이어진다: 먼저 처리된 이력의 잔액 + 다음 충전 금액 = 다음 이력의 잔액
        List<PointHistory> ordered = histories.stream().sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
        assertThat(ordered.get(0).getTotalPointAfter().add(ordered.get(1).getAmount())).isEqualByComparingTo(ordered.get(1).getTotalPointAfter());
    }
}

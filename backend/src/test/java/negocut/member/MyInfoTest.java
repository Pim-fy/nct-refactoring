package negocut.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import negocut.member.entity.Member;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;

// GET /api/members/me: 로그인한 회원의 현재 정보와 포인트
@SpringBootTest
@AutoConfigureMockMvc
class MyInfoTest {

    private static final String PASSWORD = "abcd1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member createMember() {
        String n = String.valueOf(System.nanoTime());
        String suffix = n.substring(n.length() - 8);
        Member member = memberRepository.save(Member.create("me" + suffix, passwordEncoder.encode(PASSWORD),
                "nick" + suffix.substring(2), "me" + suffix + "@example.com", "010-1111-2222"));
        pointBalanceRepository.save(PointBalance.createEmpty(member));
        return member;
    }

    private Cookie loginCookie(Member member) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + member.getLoginId() + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        return new Cookie("ACCESS_TOKEN", result.getResponse().getCookie("ACCESS_TOKEN").getValue());
    }

    @Test
    void 로그인한_회원의_정보와_포인트를_돌려준다() throws Exception {
        Member member = createMember();

        mockMvc.perform(get("/api/members/me").cookie(loginCookie(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(member.getId()))
                .andExpect(jsonPath("$.data.loginId").value(member.getLoginId()))
                .andExpect(jsonPath("$.data.nickname").value(member.getNickname()))
                .andExpect(jsonPath("$.data.email").value(member.getEmail()))
                .andExpect(jsonPath("$.data.phone").value("010-1111-2222"))
                .andExpect(jsonPath("$.data.memberStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.memberRole").value("MEMBER"))
                .andExpect(jsonPath("$.data.profileImageId").isEmpty())
                .andExpect(jsonPath("$.data.profileImageUrl").isEmpty())
                .andExpect(jsonPath("$.data.point.totalPoint").value(0))
                .andExpect(jsonPath("$.data.point.reservedPoint").value(0))
                .andExpect(jsonPath("$.data.point.availablePoint").value(0));
    }

    @Test
    void 사용_가능_포인트는_보유에서_예약을_뺀_값이다() throws Exception {
        Member member = createMember();
        PointBalance balance = pointBalanceRepository.findByMemberId(member.getId()).orElseThrow();
        ReflectionTestUtils.setField(balance, "totalPoint", new BigDecimal("120000"));
        ReflectionTestUtils.setField(balance, "reservedPoint", new BigDecimal("32000"));
        pointBalanceRepository.save(balance);

        mockMvc.perform(get("/api/members/me").cookie(loginCookie(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.point.totalPoint").value(120000))
                .andExpect(jsonPath("$.data.point.reservedPoint").value(32000))
                .andExpect(jsonPath("$.data.point.availablePoint").value(88000));
    }

    @Test
    void 프로필_이미지가_있으면_이미지_주소를_돌려준다() throws Exception {
        Member member = createMember();
        ReflectionTestUtils.setField(member, "profileImageId", 91L);
        memberRepository.save(member);

        mockMvc.perform(get("/api/members/me").cookie(loginCookie(member)))
                .andExpect(jsonPath("$.data.profileImageId").value(91))
                .andExpect(jsonPath("$.data.profileImageUrl").value("/api/images/91"));
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mockMvc.perform(get("/api/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void 로그인_후_정지된_회원은_액세스_토큰이_유효해도_403이다() throws Exception {
        Member member = createMember();
        Cookie cookie = loginCookie(member);
        ReflectionTestUtils.setField(member, "memberStatus", MemberStatus.RESTRICTED_LOGIN);
        memberRepository.save(member);

        mockMvc.perform(get("/api/members/me").cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"));
    }
}

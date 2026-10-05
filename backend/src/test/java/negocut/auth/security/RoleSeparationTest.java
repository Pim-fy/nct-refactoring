package negocut.auth.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;

// 관리자는 관리자 기능만 쓰고, 일반 회원 기능은 회원 계정만 쓴다.
// /api/admin/** 은 ADMIN만, 로그아웃과 내 정보 조회는 둘 다, 나머지 인증이 필요한 경로는 기본으로 MEMBER만 허용한다.
@SpringBootTest
@AutoConfigureMockMvc
class RoleSeparationTest {

    private static final String PASSWORD = "abcd1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;

    // 관리자는 데이터베이스에서 직접 만들고 포인트 잔액이 없으므로, 관리자에게는 잔액을 만들지 않는다.
    private Member createMember(MemberRole role) {
        return createMember(role, MemberStatus.NORMAL);
    }

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("rl" + suffix, passwordEncoder.encode(PASSWORD),
                "nick" + suffix.substring(2), "rl" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        ReflectionTestUtils.setField(member, "memberStatus", status);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            pointBalanceRepository.save(PointBalance.createEmpty(member));
        }
        return member;
    }

    private Cookie accessCookie(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    // ---- 관리자 ----

    @Test
    void 관리자는_관리자_경로의_권한_검사를_통과한다() throws Exception {
        Member admin = createMember(MemberRole.ADMIN);

        // 관리자 API가 아직 없어 404지만, 권한 검사(403)에는 걸리지 않는다.
        mockMvc.perform(get("/api/admin/anything").cookie(accessCookie(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 관리자는_내_정보를_조회할_수_있고_포인트는_비어_있다() throws Exception {
        Member admin = createMember(MemberRole.ADMIN);

        mockMvc.perform(get("/api/members/me").cookie(accessCookie(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(admin.getId()))
                .andExpect(jsonPath("$.data.memberRole").value("ADMIN"))
                .andExpect(jsonPath("$.data.point").isEmpty());
    }

    // 관리자에게 열어 둔 것은 내 정보 "조회"뿐이다. 같은 주소의 다른 메서드는 일반 회원 기능이므로 거부된다.
    @Test
    void 관리자는_내_정보_조회만_허용되고_같은_주소의_다른_메서드는_거부된다() throws Exception {
        Member admin = createMember(MemberRole.ADMIN);
        Member member = createMember(MemberRole.MEMBER);

        mockMvc.perform(get("/api/members/me").cookie(accessCookie(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/members/me").cookie(accessCookie(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

        // 같은 요청이 회원에게는 권한 검사를 통과한다. (이 메서드는 아직 구현되지 않아 405지만 403은 아니다.)
        mockMvc.perform(patch("/api/members/me").cookie(accessCookie(member))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void 정지된_관리자는_내_정보_조회도_상태_거부로_응답한다() throws Exception {
        Member stoppedAdmin = createMember(MemberRole.ADMIN, MemberStatus.RESTRICTED_LOGIN);

        mockMvc.perform(get("/api/members/me").cookie(accessCookie(stoppedAdmin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"));
    }

    @Test
    void 관리자도_로그아웃할_수_있다() throws Exception {
        Member admin = createMember(MemberRole.ADMIN);

        mockMvc.perform(post("/api/auth/logout").cookie(accessCookie(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void 관리자는_일반_회원_기능을_쓸_수_없다_조회와_쓰기_모두() throws Exception {
        Member admin = createMember(MemberRole.ADMIN);

        mockMvc.perform(get("/api/test/trade/read").cookie(accessCookie(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/test/trade/allowed").cookie(accessCookie(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void 관리자도_공개_경로와_로그인은_쓸_수_있다() throws Exception {
        Member admin = createMember(MemberRole.ADMIN);

        mockMvc.perform(get("/api/agreements").cookie(accessCookie(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + admin.getLoginId() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    // ---- 회원 ----

    @Test
    void 회원은_관리자_경로가_거부된다() throws Exception {
        Member member = createMember(MemberRole.MEMBER);

        mockMvc.perform(get("/api/admin/anything").cookie(accessCookie(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void 회원은_일반_기능과_내_정보_조회를_쓸_수_있고_포인트가_있다() throws Exception {
        Member member = createMember(MemberRole.MEMBER);

        mockMvc.perform(get("/api/test/trade/read").cookie(accessCookie(member)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/members/me").cookie(accessCookie(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberRole").value("MEMBER"))
                .andExpect(jsonPath("$.data.point.totalPoint").value(0));
    }

    // ---- 비로그인 ----

    @Test
    void 로그인하지_않으면_관리자_경로와_일반_경로_모두_401이다() throws Exception {
        mockMvc.perform(get("/api/admin/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/test/trade/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }
}

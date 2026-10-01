package negocut.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import negocut.auth.entity.VerificationPurpose;
import negocut.auth.mail.VerificationMailSender;
import negocut.auth.repository.EmailVerificationRepository;
import negocut.member.entity.Member;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;

// 아이디 찾기·비밀번호 재설정의 인증번호 발송 계정 일치 확인 (API-4)
@SpringBootTest
@AutoConfigureMockMvc
class EmailCodeAccountMatchTest {

    private static final String SEND = "/api/auth/email-codes";
    private static final String VERIFY = "/api/auth/email-codes/verify";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private EmailVerificationRepository verificationRepository;

    @MockitoBean private VerificationMailSender mailSender;

    // 실행마다 값이 겹치지 않는 계정을 만든다.
    private Member createMember() {
        String n = String.valueOf(System.nanoTime());
        String suffix = n.substring(n.length() - 8);
        return memberRepository.save(Member.create(
                "acc" + suffix, "encoded-password", "nick" + suffix.substring(2), "m" + suffix + "@example.com", "010-0000-0000"));
    }

    private String body(String email, String purpose, String nickname, String loginId) {
        StringBuilder sb = new StringBuilder("{\"email\":\"" + email + "\",\"purpose\":\"" + purpose + "\"");
        if (nickname != null) {
            sb.append(",\"nickname\":\"").append(nickname).append("\"");
        }
        if (loginId != null) {
            sb.append(",\"loginId\":\"").append(loginId).append("\"");
        }
        return sb.append("}").toString();
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 아이디_찾기는_닉네임과_이메일이_맞으면_메일을_보낸다() throws Exception {
        Member member = createMember();

        send(body(member.getEmail(), "FIND_ID", member.getNickname(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").exists());

        verify(mailSender).send(eq(member.getEmail()), anyString());
        assertThat(verificationRepository.findFirstByEmailAndPurposeOrderByIdDesc(member.getEmail(), VerificationPurpose.FIND_ID)).isPresent();
    }

    @Test
    void 아이디_찾기는_닉네임이_다르면_메일을_보내지_않고_같은_성공_응답을_준다() throws Exception {
        Member member = createMember();

        send(body(member.getEmail(), "FIND_ID", "다른닉네임", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.expiresAt").exists());

        verify(mailSender, never()).send(anyString(), anyString());
        assertThat(verificationRepository.findFirstByEmailAndPurposeOrderByIdDesc(member.getEmail(), VerificationPurpose.FIND_ID)).isEmpty();
    }

    @Test
    void 가입하지_않은_이메일도_메일을_보내지_않고_같은_성공_응답을_준다() throws Exception {
        send(body("nobody" + System.nanoTime() + "@example.com", "FIND_ID", "누구", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").exists());

        verify(mailSender, never()).send(anyString(), anyString());
    }

    @Test
    void 일치하지_않은_계정은_이후_인증번호_검증도_통과하지_못한다() throws Exception {
        Member member = createMember();
        send(body(member.getEmail(), "FIND_ID", "다른닉네임", null)).andExpect(status().isOk());

        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + member.getEmail() + "\",\"purpose\":\"FIND_ID\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));
    }

    @Test
    void 비밀번호_재설정은_로그인_ID와_이메일이_맞으면_메일을_보낸다() throws Exception {
        Member member = createMember();

        send(body(member.getEmail(), "RESET_PASSWORD", null, member.getLoginId()))
                .andExpect(status().isOk());

        verify(mailSender).send(eq(member.getEmail()), anyString());
    }

    @Test
    void 비밀번호_재설정은_로그인_ID가_다르면_메일을_보내지_않는다() throws Exception {
        Member member = createMember();

        send(body(member.getEmail(), "RESET_PASSWORD", null, "wrongid123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").exists());

        verify(mailSender, never()).send(anyString(), anyString());
    }

    @Test
    void 탈퇴한_회원은_메일을_보내지_않는다() throws Exception {
        Member member = createMember();
        ReflectionTestUtils.setField(member, "memberStatus", MemberStatus.WITHDRAWN);
        memberRepository.save(member);

        send(body(member.getEmail(), "FIND_ID", member.getNickname(), null)).andExpect(status().isOk());
        send(body(member.getEmail(), "RESET_PASSWORD", null, member.getLoginId())).andExpect(status().isOk());

        verify(mailSender, never()).send(anyString(), anyString());
    }

    @Test
    void 아이디_찾기에_닉네임이_없으면_400이다() throws Exception {
        Member member = createMember();

        send(body(member.getEmail(), "FIND_ID", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.error.details[0].field").value("nickname"));
    }

    @Test
    void 비밀번호_재설정에_로그인_ID가_없으면_400이다() throws Exception {
        Member member = createMember();

        send(body(member.getEmail(), "RESET_PASSWORD", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("loginId"));
    }

    @Test
    void 회원가입_용도는_계정_확인_없이_메일을_보낸다() throws Exception {
        String email = "signup" + System.nanoTime() + "@example.com";

        send(body(email, "SIGN_UP", null, null)).andExpect(status().isOk());

        verify(mailSender).send(eq(email), anyString());
    }
}

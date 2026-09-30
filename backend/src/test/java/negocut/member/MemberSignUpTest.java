package negocut.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import negocut.auth.entity.EmailVerification;
import negocut.auth.entity.VerificationPurpose;
import negocut.auth.mail.VerificationMailSender;
import negocut.auth.repository.EmailVerificationRepository;
import negocut.member.entity.Agreement;
import negocut.member.entity.Member;
import negocut.member.entity.MemberAgreement;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.AgreementRepository;
import negocut.member.repository.MemberAgreementRepository;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class MemberSignUpTest {

    private static final String MEMBERS = "/api/members";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberAgreementRepository memberAgreementRepository;
    @Autowired private AgreementRepository agreementRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private EmailVerificationRepository verificationRepository;

    @MockitoBean private VerificationMailSender mailSender;

    // 실행마다 값이 겹치지 않도록 하는 접미사 (DB를 비우지 않고 여러 테스트가 같은 테이블을 쓴다)
    private String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8);
    }

    private record Account(String loginId, String nickname, String email) {
        static Account unique(String suffix) {
            return new Account("user" + suffix, "nick" + suffix.substring(2), "u" + suffix + "@example.com");
        }
    }

    // 실제 발송·검증 API를 거쳐 해당 이메일의 검증 토큰을 얻는다.
    private String issueToken(String email) throws Exception {
        mockMvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"purpose\":\"SIGN_UP\"}")).andExpect(status().isOk());

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailSender, atLeastOnce()).send(eq(email), code.capture());

        MvcResult result = mockMvc.perform(post("/api/auth/email-codes/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"purpose\":\"SIGN_UP\",\"code\":\"" + code.getValue() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get("verificationToken").asString();
    }

    // 시행 중인 약관 전체에 대해 필수는 동의, 선택은 optionalAgreed 값으로 보낸다.
    private String agreementsJson(boolean optionalAgreed) {
        return agreementRepository.findAll().stream()
                .map(a -> "{\"agreementId\":" + a.getId() + ",\"isAgreed\":" + (a.isRequired() || optionalAgreed) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String signUpBody(Account a, String password, String token, String agreements) {
        return "{\"loginId\":\"" + a.loginId() + "\",\"password\":\"" + password + "\",\"nickname\":\"" + a.nickname()
                + "\",\"email\":\"" + a.email() + "\",\"phone\":\"010-1234-5678\",\"verificationToken\":\"" + token
                + "\",\"agreements\":" + agreements + "}";
    }

    private org.springframework.test.web.servlet.ResultActions signUp(String body) throws Exception {
        return mockMvc.perform(post(MEMBERS).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 회원가입하면_계정_약관_동의_이력_포인트_잔액이_함께_만들어진다() throws Exception {
        Account a = Account.unique(suffix());
        String token = issueToken(a.email());

        MvcResult result = signUp(signUpBody(a, "abcd1234", token, agreementsJson(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.memberId").exists())
                .andReturn();
        Long memberId = objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("memberId").asLong();

        Member member = memberRepository.findById(memberId).orElseThrow();
        assertThat(member.getMemberRole()).isEqualTo(MemberRole.MEMBER);
        assertThat(member.getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
        assertThat(member.getPassword()).isNotEqualTo("abcd1234");
        assertThat(passwordEncoder.matches("abcd1234", member.getPassword())).isTrue();

        // 선택 약관을 동의하지 않은 이력도 남는다.
        List<MemberAgreement> agreements = memberAgreementRepository.findByMemberId(memberId);
        assertThat(agreements).hasSize(agreementRepository.findAll().size());
        // 연관 엔티티(agreement)는 지연 로딩이라 트랜잭션 밖에서 읽지 못하므로 약관 목록을 따로 조회해 비교한다.
        Map<Long, Boolean> requiredById = agreementRepository.findAll().stream()
                .collect(Collectors.toMap(agreement -> agreement.getId(), agreement -> agreement.isRequired()));
        assertThat(agreements).allSatisfy(ma ->
                assertThat(ma.isAgreed()).isEqualTo(requiredById.get(ma.getAgreement().getId())));

        PointBalance balance = pointBalanceRepository.findByMemberId(memberId).orElseThrow();
        assertThat(balance.getTotalPoint()).isEqualByComparingTo("0");
        assertThat(balance.getReservedPoint()).isEqualByComparingTo("0");

        // 가입에 쓴 인증 이력은 사용 완료가 된다.
        EmailVerification verification = verificationRepository
                .findFirstByEmailAndPurposeOrderByIdDesc(a.email(), VerificationPurpose.SIGN_UP).orElseThrow();
        assertThat(verification.isUsed()).isTrue();
    }

    @Test
    void 사용된_토큰은_다른_계정_가입에도_쓸_수_없다() throws Exception {
        Account a = Account.unique(suffix());
        String token = issueToken(a.email());
        signUp(signUpBody(a, "abcd1234", token, agreementsJson(true))).andExpect(status().isOk());

        // 같은 이메일이 아니면 토큰의 이메일과 달라 거부된다.
        Account other = Account.unique(suffix());
        signUp(signUpBody(other, "abcd1234", token, agreementsJson(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_TOKEN_INVALID"));
    }

    @Test
    void 토큰의_이메일과_다른_이메일로는_가입할_수_없다() throws Exception {
        Account a = Account.unique(suffix());
        String tokenOfOther = issueToken("other" + suffix() + "@example.com");

        signUp(signUpBody(a, "abcd1234", tokenOfOther, agreementsJson(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_TOKEN_INVALID"));
        assertThat(memberRepository.existsByLoginId(a.loginId())).isFalse();
    }

    @Test
    void 아이디_닉네임_이메일이_중복이면_각각의_코드로_400이다() throws Exception {
        Account a = Account.unique(suffix());
        signUp(signUpBody(a, "abcd1234", issueToken(a.email()), agreementsJson(true))).andExpect(status().isOk());

        Account b1 = Account.unique(suffix());
        signUp(signUpBody(new Account(a.loginId(), b1.nickname(), b1.email()), "abcd1234", issueToken(b1.email()), agreementsJson(true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MEMBER_ID_DUPLICATED"));

        Account b2 = Account.unique(suffix());
        signUp(signUpBody(new Account(b2.loginId(), a.nickname(), b2.email()), "abcd1234", issueToken(b2.email()), agreementsJson(true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MEMBER_NICKNAME_DUPLICATED"));

        Account b3 = Account.unique(suffix());
        signUp(signUpBody(new Account(b3.loginId(), b3.nickname(), a.email()), "abcd1234", issueToken(a.email()), agreementsJson(true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MEMBER_EMAIL_DUPLICATED"));
    }

    @Test
    void 중복으로_실패한_가입은_검증_토큰을_소모하지_않는다() throws Exception {
        Account a = Account.unique(suffix());
        signUp(signUpBody(a, "abcd1234", issueToken(a.email()), agreementsJson(true))).andExpect(status().isOk());

        Account b = Account.unique(suffix());
        String tokenB = issueToken(b.email());
        signUp(signUpBody(new Account(a.loginId(), b.nickname(), b.email()), "abcd1234", tokenB, agreementsJson(true)))
                .andExpect(status().isBadRequest());

        // 같은 토큰으로 아이디만 고쳐 다시 가입하면 성공한다.
        signUp(signUpBody(b, "abcd1234", tokenB, agreementsJson(true))).andExpect(status().isOk());
    }

    @Test
    void 필수_약관에_동의하지_않으면_400이고_가입되지_않는다() throws Exception {
        Account a = Account.unique(suffix());
        String token = issueToken(a.email());
        Agreement required = agreementRepository.findAll().stream().filter(agreement -> agreement.isRequired()).findFirst().orElseThrow();
        String agreements = agreementRepository.findAll().stream()
                .map(x -> "{\"agreementId\":" + x.getId() + ",\"isAgreed\":" + (x.getId().equals(required.getId()) ? "false" : "true") + "}")
                .collect(Collectors.joining(",", "[", "]"));

        signUp(signUpBody(a, "abcd1234", token, agreements))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEMBER_INPUT_INVALID"))
                .andExpect(jsonPath("$.error.details[0].field").value("agreements"));
        assertThat(memberRepository.existsByLoginId(a.loginId())).isFalse();
    }

    @Test
    void 시행_중인_약관을_빠뜨리면_400이다() throws Exception {
        Account a = Account.unique(suffix());
        String token = issueToken(a.email());
        Agreement first = agreementRepository.findAll().get(0);
        String onlyOne = "[{\"agreementId\":" + first.getId() + ",\"isAgreed\":true}]";

        signUp(signUpBody(a, "abcd1234", token, onlyOne))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEMBER_INPUT_INVALID"));
    }

    @Test
    void 형식이_틀리면_필드별_사유와_함께_400이다() throws Exception {
        Account bad = new Account("ab", "닉", "not-an-email");

        signUp(signUpBody(bad, "short", "token", agreementsJson(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEMBER_INPUT_INVALID"))
                .andExpect(jsonPath("$.error.details.length()").value(4));
    }

    @Test
    void 중복_확인_API는_사용_가능_여부를_알려준다() throws Exception {
        Account a = Account.unique(suffix());
        signUp(signUpBody(a, "abcd1234", issueToken(a.email()), agreementsJson(true))).andExpect(status().isOk());
        Account free = Account.unique(suffix());

        mockMvc.perform(get("/api/members/check-login-id").param("loginId", a.loginId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isAvailable").value(false));
        mockMvc.perform(get("/api/members/check-login-id").param("loginId", free.loginId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isAvailable").value(true));
        mockMvc.perform(get("/api/members/check-nickname").param("nickname", a.nickname()))
                .andExpect(jsonPath("$.data.isAvailable").value(false));
        mockMvc.perform(get("/api/members/check-email").param("email", a.email()))
                .andExpect(jsonPath("$.data.isAvailable").value(false));
        mockMvc.perform(get("/api/members/check-email").param("email", free.email()))
                .andExpect(jsonPath("$.data.isAvailable").value(true));
    }

    @Test
    void 중복_확인_API는_형식이_틀리거나_값이_없으면_400이다() throws Exception {
        mockMvc.perform(get("/api/members/check-login-id").param("loginId", "ab"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MEMBER_INPUT_INVALID"));
        mockMvc.perform(get("/api/members/check-nickname"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MEMBER_INPUT_INVALID"));
    }
}

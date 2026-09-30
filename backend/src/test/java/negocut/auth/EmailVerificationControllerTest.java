package negocut.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import negocut.auth.entity.EmailVerification;
import negocut.auth.entity.VerificationPurpose;
import negocut.auth.mail.VerificationMailSender;
import negocut.auth.repository.EmailVerificationRepository;
import negocut.auth.token.VerificationTokenProvider;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationControllerTest {

    private static final String SEND = "/api/auth/email-codes";
    private static final String VERIFY = "/api/auth/email-codes/verify";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmailVerificationRepository verificationRepository;

    @Autowired
    private VerificationTokenProvider tokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VerificationMailSender mailSender;

    private String uniqueEmail() {
        return "t" + System.nanoTime() + "@example.com";
    }

    private String sendBody(String email) {
        return "{\"email\":\"" + email + "\",\"purpose\":\"SIGN_UP\"}";
    }

    private String verifyBody(String email, String code) {
        return "{\"email\":\"" + email + "\",\"purpose\":\"SIGN_UP\",\"code\":\"" + code + "\"}";
    }

    // 발송 요청으로 실제 저장된 인증번호를 메일 발송기에 전달된 값으로 얻는다.
    private String sendAndCaptureCode(String email) throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content(sendBody(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").exists());

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq(email), code.capture());
        return code.getValue();
    }

    @Test
    void 인증번호를_발송하고_검증하면_검증_토큰이_발급된다() throws Exception {
        String email = uniqueEmail();
        String code = sendAndCaptureCode(email);
        assertThat(code).matches("\\d{6}");

        MvcResult result = mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(verifyBody(email, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationToken").exists())
                .andExpect(jsonPath("$.data.tokenExpiresAt").exists())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        String token = data.get("verificationToken").asString();

        // 토큰은 발송한 이메일·용도로만 유효하고, 다른 이메일이면 거부된다.
        assertThat(tokenProvider.parse(token, email, VerificationPurpose.SIGN_UP)).isNotNull();
        try {
            tokenProvider.parse(token, "other@example.com", VerificationPurpose.SIGN_UP);
            throw new AssertionError("다른 이메일의 토큰이 통과함");
        } catch (BusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }
    }

    @Test
    void 인증번호가_다르면_400이다() throws Exception {
        String email = uniqueEmail();
        String code = sendAndCaptureCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(verifyBody(email, wrong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));
    }

    @Test
    void 발송한_적_없는_이메일은_400이다() throws Exception {
        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(verifyBody(uniqueEmail(), "123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));
    }

    @Test
    void 만료된_인증번호는_409이다() throws Exception {
        String email = uniqueEmail();
        verificationRepository.save(EmailVerification.create(
                email, VerificationPurpose.SIGN_UP, "123456", LocalDateTime.now().minusMinutes(1)));

        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(verifyBody(email, "123456")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_EXPIRED"));
    }

    @Test
    void 사용_완료된_인증번호는_다시_쓸_수_없다() throws Exception {
        String email = uniqueEmail();
        EmailVerification used = EmailVerification.create(
                email, VerificationPurpose.SIGN_UP, "123456", LocalDateTime.now().plusMinutes(5));
        used.markUsed();
        verificationRepository.save(used);

        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(verifyBody(email, "123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));
    }

    @Test
    void 재발송하면_가장_최근_인증번호만_유효하다() throws Exception {
        String email = uniqueEmail();
        EmailVerification old = verificationRepository.save(EmailVerification.create(
                email, VerificationPurpose.SIGN_UP, "111111", LocalDateTime.now().plusMinutes(5)));
        verificationRepository.save(EmailVerification.create(
                email, VerificationPurpose.SIGN_UP, "222222", LocalDateTime.now().plusMinutes(5)));

        assertThat(old.getId()).isNotNull();
        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(verifyBody(email, "111111")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(verifyBody(email, "222222")))
                .andExpect(status().isOk());
    }

    @Test
    void 메일_발송에_실패해도_인증_이력은_남고_503을_응답한다() throws Exception {
        String email = uniqueEmail();
        doThrow(new BusinessException(ErrorCode.EMAIL_SEND_FAILED)).when(mailSender).send(anyString(), anyString());

        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content(sendBody(email)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EMAIL_SEND_FAILED"));

        assertThat(verificationRepository.findFirstByEmailAndPurposeOrderByIdDesc(email, VerificationPurpose.SIGN_UP)).isPresent();
    }

    @Test
    void 형식이_잘못된_요청은_400이다() throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"purpose\":\"SIGN_UP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        mockMvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(verifyBody(uniqueEmail(), "12ab")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }
}

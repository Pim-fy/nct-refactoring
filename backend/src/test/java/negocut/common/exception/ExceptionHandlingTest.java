package negocut.common.exception;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 클라이언트의 잘못된 요청(깨진 본문, 없는 enum 값, 파라미터 오류, 잘못된 Content-Type)은 500이 아니라 4xx로 응답해야 한다.
@SpringBootTest
@AutoConfigureMockMvc
class ExceptionHandlingTest {

    private static final String SEND = "/api/auth/email-codes";

    @Autowired private MockMvc mockMvc;

    @Test
    void 깨진_JSON은_400이다() throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content("{\"email\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 없는_enum_값은_400이다() throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"purpose\":\"FOO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 본문이_없으면_400이다() throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void JSON이_아닌_Content_Type은_415다() throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void 필수_파라미터가_없으면_400이다() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/param").with(user("tester")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 파라미터_타입이_맞지_않으면_400이다() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/param").param("n", "abc").with(user("tester")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 올바른_파라미터는_정상_처리된다() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/param").param("n", "7").with(user("tester")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(7));
    }
}

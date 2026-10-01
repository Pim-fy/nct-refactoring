package negocut.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import negocut.auth.mail.VerificationMailSender;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyInterceptorTest {

    private static final String BASE = "/api/test/idempotency";

    @Autowired private MockMvc mockMvc;
    @Autowired private InMemoryRequestFingerprintStore store;

    @MockitoBean private VerificationMailSender mailSender;

    @BeforeEach
    void setUp() {
        store.clear();
        IdempotencyTestController.reset();
    }

    private MvcResult send(String path, String user, String body) throws Exception {
        return mockMvc.perform(post(BASE + path).with(user(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    @Test
    void 같은_요청을_반복하면_한_번만_처리하고_첫_응답을_돌려준다() throws Exception {
        MvcResult first = send("/count", "tester", "{\"a\":1}");
        MvcResult second = send("/count", "tester", "{\"a\":1}");

        assertThat(IdempotencyTestController.EXECUTIONS.get()).isEqualTo(1);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(first.getResponse().getHeader(IdempotencyInterceptor.REPLAY_HEADER)).isNull();
        assertThat(second.getResponse().getHeader(IdempotencyInterceptor.REPLAY_HEADER)).isEqualTo("true");
    }

    @Test
    void 바디가_다르면_다른_요청이다() throws Exception {
        send("/count", "tester", "{\"a\":1}");
        send("/count", "tester", "{\"a\":2}");

        assertThat(IdempotencyTestController.EXECUTIONS.get()).isEqualTo(2);
    }

    @Test
    void 사용자가_다르면_같은_바디도_다른_요청이다() throws Exception {
        send("/count", "userA", "{\"a\":1}");
        send("/count", "userB", "{\"a\":1}");

        assertThat(IdempotencyTestController.EXECUTIONS.get()).isEqualTo(2);
    }

    @Test
    void 예외_어노테이션이_붙은_API는_반복해도_매번_처리한다() throws Exception {
        send("/skip", "tester", "{\"a\":1}");
        send("/skip", "tester", "{\"a\":1}");

        assertThat(IdempotencyTestController.EXECUTIONS.get()).isEqualTo(2);
    }

    @Test
    void 실패한_요청은_저장하지_않아서_다시_시도하면_다시_처리한다() throws Exception {
        MvcResult first = send("/fail", "tester", "{\"a\":1}");
        MvcResult second = send("/fail", "tester", "{\"a\":1}");

        assertThat(first.getResponse().getStatus()).isEqualTo(400);
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(IdempotencyTestController.EXECUTIONS.get()).isEqualTo(2);
        assertThat(second.getResponse().getHeader(IdempotencyInterceptor.REPLAY_HEADER)).isNull();
    }

    @Test
    void DELETE도_대상이고_GET은_대상이_아니다() throws Exception {
        mockMvc.perform(delete(BASE + "/count").with(user("tester"))).andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/count").with(user("tester")))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyInterceptor.REPLAY_HEADER, "true"));
        assertThat(IdempotencyTestController.EXECUTIONS.get()).isEqualTo(1);

        mockMvc.perform(get(BASE + "/count").with(user("tester"))).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/count").with(user("tester")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyInterceptor.REPLAY_HEADER));
        assertThat(IdempotencyTestController.EXECUTIONS.get()).isEqualTo(3);
    }

    @Test
    void 처리_중에_같은_요청이_동시에_오면_한_번만_처리한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<MvcResult>> results = List.of(
                pool.submit(() -> { go.await(); return send("/slow", "tester", "{\"a\":1}"); }),
                pool.submit(() -> { go.await(); return send("/slow", "tester", "{\"a\":1}"); }));
        go.countDown();

        int ok = 0;
        int conflict = 0;
        for (Future<MvcResult> result : results) {
            int code = result.get().getResponse().getStatus();
            if (code == 200) {
                ok++;
            } else if (code == 409) {
                assertThat(result.get().getResponse().getContentAsString()).contains("IDEMPOTENCY_PROCESSING");
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(IdempotencyTestController.EXECUTIONS.get()).isEqualTo(1);
        assertThat(ok + conflict).isEqualTo(2);
        assertThat(ok).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 로그인하지_않은_요청은_접속_IP로_구분한다() throws Exception {
        String body = "{\"email\":\"dup" + System.nanoTime() + "@example.com\",\"purpose\":\"SIGN_UP\"}";

        mockMvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyInterceptor.REPLAY_HEADER, "true"))
                .andExpect(jsonPath("$.data.expiresAt").exists());

        // 인증번호 메일이 한 번만 나간다.
        verify(mailSender, times(1)).send(anyString(), anyString());
    }
}

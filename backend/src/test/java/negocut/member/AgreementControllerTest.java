package negocut.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AgreementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 초기 데이터(약관 4종)가 들어가 있고, 로그인 없이 유형 순서로 조회된다.
    @Test
    void 약관_목록은_로그인_없이_유형_순서로_조회된다() throws Exception {
        mockMvc.perform(get("/api/agreements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.agreements.length()").value(4))
                .andExpect(jsonPath("$.data.agreements[0].agreementType").value("TERMS_OF_SERVICE"))
                .andExpect(jsonPath("$.data.agreements[1].agreementType").value("PRIVACY"))
                .andExpect(jsonPath("$.data.agreements[2].agreementType").value("AGE_14"))
                .andExpect(jsonPath("$.data.agreements[3].agreementType").value("MARKETING"))
                .andExpect(jsonPath("$.data.agreements[0].isRequired").value(true))
                .andExpect(jsonPath("$.data.agreements[3].isRequired").value(false));
    }

    // 사용하지 않는 버전과 시행 전 약관은 목록에 나오지 않는다. (테스트 계획서 2-1)
    @Test
    void 사용하지_않는_버전과_시행_전_약관은_목록에_없다() throws Exception {
        String insert = "INSERT INTO agreement (agreement_type, version, title, content, is_required, effective_at, is_active, created_at, updated_at) VALUES ('MARKETING', ?, ?, 'x', 0, %s, %d, NOW(6), NOW(6))";
        jdbcTemplate.update(String.format(insert, "NOW(6)", 0), "test-inactive", "사용 안 함");                       // 사용 여부가 꺼진 버전
        jdbcTemplate.update(String.format(insert, "DATE_ADD(NOW(6), INTERVAL 1 DAY)", 1), "test-future", "시행 전");   // 시행 시각 전

        try {
            mockMvc.perform(get("/api/agreements"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.agreements.length()").value(4))
                    .andExpect(jsonPath("$.data.agreements[?(@.version == 'test-inactive')]").isEmpty())
                    .andExpect(jsonPath("$.data.agreements[?(@.version == 'test-future')]").isEmpty());
        } finally {
            // 다른 테스트가 약관 전체를 가정하므로 넣은 행은 반드시 지운다.
            jdbcTemplate.update("DELETE FROM agreement WHERE version LIKE 'test-%'");
        }
    }
}

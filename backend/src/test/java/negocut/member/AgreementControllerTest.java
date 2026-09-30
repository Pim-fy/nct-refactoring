package negocut.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AgreementControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
}

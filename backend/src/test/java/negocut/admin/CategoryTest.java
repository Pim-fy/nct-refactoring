package negocut.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import negocut.auction.entity.Category;
import negocut.auction.entity.Product;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductRepository;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;

// 카테고리 조회(공개)와 관리자 카테고리 관리 (기능 명세서 3-4, 3-10)
// 데이터베이스를 다른 테스트와 공유하므로 테스트가 만드는 카테고리는 고유한 이름과 큰 순서값을 쓰고, 시드 8개의 순서만 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
class CategoryTest {

    private static final List<String> SEED_NAMES = List.of(
            "디지털·가전", "패션·잡화", "생활·주방", "도서·음반", "스포츠·레저", "취미·게임", "유아·출산", "기타");

    @Autowired private MockMvc mockMvc;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;

    private Cookie cookieOf(MemberRole role) {
        String suffix = TestSuffix.next();
        Member member = Member.create("ct" + suffix, passwordEncoder.encode("abcd1234"),
                "nick" + suffix.substring(2), "ct" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            pointBalanceRepository.save(PointBalance.createEmpty(member));
        }
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), role));
    }

    private String uniqueName() {
        return "분류" + TestSuffix.next();
    }

    private String body(String name, Integer sortOrder) {
        return sortOrder == null
                ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"sortOrder\":" + sortOrder + "}";
    }

    private ResultActions createRaw(Cookie admin, String json) throws Exception {
        return mockMvc.perform(post("/api/admin/categories").cookie(admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Long create(Cookie admin, String name, Integer sortOrder) throws Exception {
        String response = createRaw(admin, body(name, sortOrder))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.categoryId")).longValue();
    }

    private ResultActions patchRaw(Cookie admin, Long id, String json) throws Exception {
        return mockMvc.perform(patch("/api/admin/categories/" + id).cookie(admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String adminList(Cookie admin) throws Exception {
        return mockMvc.perform(get("/api/admin/categories").cookie(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String publicList() throws Exception {
        return mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<String> publicNames() throws Exception {
        return JsonPath.read(publicList(), "$.data.categories[*].name");
    }

    private List<String> adminNames(Cookie admin) throws Exception {
        return JsonPath.read(adminList(admin), "$.data.categories[*].name");
    }

    // ---- 공개 목록 ----

    @Test
    void 비로그인도_카테고리_목록을_보고_시드_8개가_순서대로_있다() throws Exception {
        List<String> seeds = new ArrayList<>(publicNames());
        seeds.retainAll(SEED_NAMES);

        assertThat(seeds).containsExactlyElementsOf(SEED_NAMES);
        mockMvc.perform(get("/api/categories"))
                .andExpect(jsonPath("$.data.categories[0].name").value("디지털·가전"))
                .andExpect(jsonPath("$.data.categories[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data.categories[0].categoryId").isNumber());
    }

    @Test
    void 사용하지_않거나_삭제된_카테고리는_공개_목록에_없다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String inactive = uniqueName();
        String deleted = uniqueName();
        String normal = uniqueName();
        Long inactiveId = create(admin, inactive, 6001);
        Long deletedId = create(admin, deleted, 6002);
        create(admin, normal, 6003);

        patchRaw(admin, inactiveId, "{\"isActive\":false}").andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/categories/" + deletedId).cookie(admin)).andExpect(status().isOk());

        assertThat(publicNames()).contains(normal).doesNotContain(inactive, deleted);
    }

    // ---- 관리자 목록 ----

    @Test
    void 관리자_목록은_사용하지_않는_카테고리를_포함하고_삭제된_것은_제외하며_상품_수를_준다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String inactive = uniqueName();
        String deleted = uniqueName();
        String used = uniqueName();
        Long inactiveId = create(admin, inactive, 6101);
        Long deletedId = create(admin, deleted, 6102);
        Long usedId = create(admin, used, 6103);
        patchRaw(admin, inactiveId, "{\"isActive\":false}").andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/categories/" + deletedId).cookie(admin)).andExpect(status().isOk());
        Category usedCategory = categoryRepository.findById(usedId).orElseThrow();
        productRepository.save(Product.create(usedCategory, "상품1", "설명", "상태"));
        productRepository.save(Product.create(usedCategory, "상품2", "설명", "상태"));

        String json = adminList(admin);

        assertThat((List<String>) JsonPath.read(json, "$.data.categories[*].name")).contains(inactive, used).doesNotContain(deleted);
        assertThat((List<Integer>) JsonPath.read(json, "$.data.categories[?(@.name=='" + inactive + "')].productCount")).containsExactly(0);
        assertThat((List<Integer>) JsonPath.read(json, "$.data.categories[?(@.name=='" + used + "')].productCount")).containsExactly(2);
        assertThat((List<Boolean>) JsonPath.read(json, "$.data.categories[?(@.name=='" + inactive + "')].isActive")).containsExactly(false);
    }

    // ---- 추가 ----

    @Test
    void 순서를_생략하면_현재_최대값_다음_순서를_받고_같은_순서도_허용한다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String first = uniqueName();
        create(admin, first, 7000);

        String second = uniqueName();
        create(admin, second, null);
        String json = adminList(admin);

        List<Integer> secondOrder = JsonPath.read(json, "$.data.categories[?(@.name=='" + second + "')].sortOrder");
        assertThat(secondOrder.get(0)).isGreaterThan(7000);

        // 같은 순서를 허용한다.
        String third = uniqueName();
        String fourth = uniqueName();
        create(admin, third, 7500);
        create(admin, fourth, 7500);
        assertThat(adminNames(admin)).contains(third, fourth);
    }

    @Test
    void 자동으로_받는_순서도_상한_9999를_넘지_않는다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String top = uniqueName();
        String auto = uniqueName();
        Long topId = create(admin, top, 9999);
        Long autoId = create(admin, auto, null);

        List<Integer> autoOrder = JsonPath.read(adminList(admin), "$.data.categories[?(@.name=='" + auto + "')].sortOrder");
        assertThat(autoOrder).containsExactly(9999);

        // 다른 테스트의 자동 순서 계산에 영향을 주지 않도록 삭제해 둔다.
        mockMvc.perform(delete("/api/admin/categories/" + topId).cookie(admin)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/categories/" + autoId).cookie(cookieOf(MemberRole.ADMIN))).andExpect(status().isOk());
    }

    @Test
    void 이름이_같은_카테고리는_추가할_수_없다_앞뒤_공백_차이도_같은_이름이다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String name = uniqueName();
        create(admin, name, 7100);

        createRaw(admin, body(name, 7101))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_NAME_DUPLICATED"));
        createRaw(admin, body("  " + name + "  ", 7102))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_NAME_DUPLICATED"));
    }

    @Test
    void 삭제된_카테고리와_같은_이름으로_추가하면_같은_카테고리가_되살아난다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String name = uniqueName();
        Long id = create(admin, name, 7200);
        patchRaw(admin, id, "{\"isActive\":false}").andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/categories/" + id).cookie(admin)).andExpect(status().isOk());
        assertThat(adminNames(admin)).doesNotContain(name);

        Long restoredId = create(admin, name, 7201);

        assertThat(restoredId).isEqualTo(id);   // 새 행이 아니라 같은 카테고리
        String json = adminList(admin);
        assertThat((List<Integer>) JsonPath.read(json, "$.data.categories[?(@.name=='" + name + "')].sortOrder")).containsExactly(7201);
        assertThat((List<Boolean>) JsonPath.read(json, "$.data.categories[?(@.name=='" + name + "')].isActive")).containsExactly(true);   // 사용 여부도 켜짐
        assertThat(publicNames()).contains(name);
    }

    @Test
    void 추가할_때_입력값이_잘못되면_400이다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);

        createRaw(admin, "{\"name\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        createRaw(admin, "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        createRaw(admin, "{}").andExpect(status().isBadRequest());
        createRaw(admin, body("가".repeat(51), 7300)).andExpect(status().isBadRequest());
        createRaw(admin, body(uniqueName(), -1)).andExpect(status().isBadRequest());
        createRaw(admin, body(uniqueName(), 10000)).andExpect(status().isBadRequest());
    }

    @Test
    void 같은_이름을_동시에_추가해도_하나만_성공하고_나머지는_중복_오류다() throws Exception {
        String name = uniqueName();
        int threads = 4;
        // 같은 사용자의 같은 요청은 중복 요청 방지가 먼저 막으므로, 사용자를 다르게 해서 데이터베이스 제약까지 도달시킨다.
        List<Cookie> admins = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            admins.add(cookieOf(MemberRole.ADMIN));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (Cookie admin : admins) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return createRaw(admin, body(name, 7400)).andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 1);
    }

    // ---- 수정 ----

    @Test
    void 보낸_필드만_수정한다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String name = uniqueName();
        Long id = create(admin, name, 8000);

        String renamed = uniqueName();
        patchRaw(admin, id, "{\"name\":\"" + renamed + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").value(id))
                .andExpect(jsonPath("$.data.name").value(renamed))
                .andExpect(jsonPath("$.data.sortOrder").value(8000))
                .andExpect(jsonPath("$.data.isActive").value(true));

        patchRaw(admin, id, "{\"sortOrder\":8001,\"isActive\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(renamed))
                .andExpect(jsonPath("$.data.sortOrder").value(8001))
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    @Test
    void 수정할_때_다른_카테고리와_같은_이름이거나_입력이_없으면_거부한다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String a = uniqueName();
        String b = uniqueName();
        create(admin, a, 8100);
        Long bId = create(admin, b, 8101);

        patchRaw(admin, bId, "{\"name\":\"" + a + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_NAME_DUPLICATED"));
        patchRaw(admin, bId, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        patchRaw(admin, bId, "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        // 자기 자신의 이름으로 바꾸는 것은 중복이 아니다.
        patchRaw(admin, bId, "{\"name\":\"" + b + "\"}").andExpect(status().isOk());
    }

    @Test
    void 없거나_삭제된_카테고리는_수정할_수_없다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        Long id = create(admin, uniqueName(), 8200);
        mockMvc.perform(delete("/api/admin/categories/" + id).cookie(admin)).andExpect(status().isOk());

        patchRaw(admin, id, "{\"isActive\":false}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_NOT_FOUND"));
        patchRaw(admin, 999_999_999L, "{\"isActive\":false}").andExpect(status().isNotFound());
    }

    // ---- 삭제 ----

    @Test
    void 상품이_없으면_삭제되고_목록에서_사라진다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String name = uniqueName();
        Long id = create(admin, name, 8300);

        mockMvc.perform(delete("/api/admin/categories/" + id).cookie(admin)).andExpect(status().isOk());

        assertThat(adminNames(admin)).doesNotContain(name);
        assertThat(publicNames()).doesNotContain(name);
        assertThat(categoryRepository.findById(id)).isPresent();   // 기록은 남는다.
    }

    @Test
    void 쓰는_상품이_있으면_삭제할_수_없고_사용_여부만_끌_수_있다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        String name = uniqueName();
        Long id = create(admin, name, 8400);
        productRepository.save(Product.create(categoryRepository.findById(id).orElseThrow(), "상품", "설명", "상태"));

        mockMvc.perform(delete("/api/admin/categories/" + id).cookie(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_IN_USE"));
        assertThat(adminNames(admin)).contains(name);

        patchRaw(admin, id, "{\"isActive\":false}").andExpect(status().isOk());
        assertThat(publicNames()).doesNotContain(name);
        assertThat(adminNames(admin)).contains(name);
    }

    @Test
    void 없거나_이미_삭제된_카테고리는_삭제할_수_없다() throws Exception {
        Cookie admin = cookieOf(MemberRole.ADMIN);
        Long id = create(admin, uniqueName(), 8500);
        mockMvc.perform(delete("/api/admin/categories/" + id).cookie(admin)).andExpect(status().isOk());

        // 같은 관리자의 같은 요청은 5초 안에 중복 요청 방지가 첫 응답을 다시 돌려주므로, 다른 관리자로 다시 삭제를 시도한다.
        Cookie otherAdmin = cookieOf(MemberRole.ADMIN);
        mockMvc.perform(delete("/api/admin/categories/" + id).cookie(otherAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_NOT_FOUND"));
        mockMvc.perform(delete("/api/admin/categories/999999999").cookie(admin)).andExpect(status().isNotFound());
    }

    // ---- 권한 ----

    @Test
    void 회원과_비로그인은_관리자_카테고리_API를_쓸_수_없다() throws Exception {
        Cookie member = cookieOf(MemberRole.MEMBER);

        mockMvc.perform(get("/api/admin/categories").cookie(member))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/admin/categories").cookie(member).contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueName(), 9000)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/categories/1").cookie(member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/categories/1").cookie(member)).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/categories"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }
}

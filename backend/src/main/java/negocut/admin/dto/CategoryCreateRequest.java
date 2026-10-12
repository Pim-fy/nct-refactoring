package negocut.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// sortOrder를 생략하면 맨 뒤 순서를 자동으로 부여한다. 같은 순서가 있어도 허용한다.
public record CategoryCreateRequest(
        @NotBlank(message = "카테고리 이름을 입력해 주세요.")
        @Size(max = 50, message = "카테고리 이름은 50자 이하여야 합니다.")
        String name,
        @Min(value = 0, message = "순서는 0 이상이어야 합니다.")
        @Max(value = 9999, message = "순서는 9999 이하여야 합니다.")
        Integer sortOrder) {

    // 앞뒤 공백을 지워 같은 이름이 공백 차이로 따로 만들어지지 않게 한다.
    public CategoryCreateRequest {
        name = name == null ? null : name.trim();
    }
}

package negocut.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 보낸 필드만 수정한다. 아무 필드도 보내지 않으면 입력값 오류다.
public record CategoryUpdateRequest(
        @Size(max = 50, message = "카테고리 이름은 50자 이하여야 합니다.")
        @Pattern(regexp = ".*\\S.*", message = "카테고리 이름을 입력해 주세요.")
        String name,
        @Min(value = 0, message = "순서는 0 이상이어야 합니다.")
        @Max(value = 9999, message = "순서는 9999 이하여야 합니다.")
        Integer sortOrder,
        Boolean isActive) {

    public CategoryUpdateRequest {
        name = name == null ? null : name.trim();
    }

    public boolean isEmpty() {
        return name == null && sortOrder == null && isActive == null;
    }
}

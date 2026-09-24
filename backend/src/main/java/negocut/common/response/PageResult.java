package negocut.common.response;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.Getter;

@Getter 
public class PageResult<T> {
    
    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    private PageResult(Page<T> pageData){
        this.content = pageData.getContent();
        this.page = pageData.getNumber();
        this.size = pageData.getSize();
        this.totalElements = pageData.getTotalElements();
        this.totalPages = pageData.getTotalPages();
    }

    public static <T> PageResult<T> from(Page<T> page){
        return new PageResult<>(page);
    }

    
}

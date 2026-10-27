package com.rightpath.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Slim, stable envelope for paginated endpoints.
 *
 * <p>Spring's own {@link Page} serialises a large, version-dependent structure
 * (nested {@code pageable}, {@code sort}, {@code number}, …). This DTO exposes
 * only the fields clients actually consume, with {@code page} rather than
 * {@code number}:</p>
 *
 * <pre>
 * { "content": [ ... ], "totalElements": 137, "totalPages": 7, "page": 0, "size": 20 }
 * </pre>
 *
 * @param <T> element type carried in {@code content}
 */
@Getter
@Setter
@NoArgsConstructor
public class PaginatedResponse<T> {

    /** Elements on the requested page (never {@code null}, possibly empty). */
    private List<T> content;

    /** Total number of elements matching the query across all pages. */
    private long totalElements;

    /** Total number of pages for the requested page size. */
    private int totalPages;

    /** Zero-based index of the returned page. */
    private int page;

    /** Requested (and applied) page size. */
    private int size;

    public PaginatedResponse(Page<T> source) {
        this.content = source.getContent();
        this.totalElements = source.getTotalElements();
        this.totalPages = source.getTotalPages();
        this.page = source.getNumber();
        this.size = source.getSize();
    }
}

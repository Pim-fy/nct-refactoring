package negocut.auction.entity;

// 경매 목록의 정렬 기준. 요청의 sort 값으로 고른다. (API 명세서 3장)
public enum AuctionSort {
    END_AT("endAt"),
    CREATED_AT("createdAt"),
    PRICE("price"),
    BID_COUNT("bidCount");

    private final String value;

    AuctionSort(String value) {
        this.value = value;
    }

    // 값이 없으면 기본값인 마감 임박순이다. 모르는 값이면 null을 돌려준다.
    public static AuctionSort from(String value) {
        if (value == null || value.isBlank()) {
            return END_AT;
        }
        for (AuctionSort sort : values()) {
            if (sort.value.equals(value)) {
                return sort;
            }
        }
        return null;
    }
}

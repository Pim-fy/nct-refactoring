package negocut.file.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import negocut.member.entity.Member;

// 업로드한 이미지의 정보. 파일 자체는 디스크에 저장하고 여기에는 경로와 메타데이터만 둔다. (DB 설계서 2-3)
// 삭제는 소프트 삭제(is_deleted)이고, 삭제하면 디스크의 파일도 지운다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "image_file",
        uniqueConstraints = @UniqueConstraint(name = "uk_image_file_stored_name", columnNames = "stored_name"))
public class ImageFile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 업로드한 회원. 경매 등록 때 본인이 올린 이미지인지 확인하는 데 쓴다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_id", nullable = false)
    private Member uploader;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ImageUsage usageType;

    // 서버가 만든 식별자. 사용자가 올린 파일명은 저장 경로에 쓰지 않는다.
    @Column(nullable = false, length = 100)
    private String storedName;

    @Column(nullable = false, length = 255)
    private String originalName;

    // 업로드 기준 폴더 아래의 상대 경로. 예: uploads/product/202610/{식별자}.jpg
    @Column(nullable = false, length = 255)
    private String filePath;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private long fileSize;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    public static ImageFile create(Member uploader, ImageUsage usageType, String storedName, String originalName,
            String filePath, String contentType, long fileSize) {
        ImageFile image = new ImageFile();
        image.uploader = uploader;
        image.usageType = usageType;
        image.storedName = storedName;
        image.originalName = originalName;
        image.filePath = filePath;
        image.contentType = contentType;
        image.fileSize = fileSize;
        image.deleted = false;
        return image;
    }

    public void delete() {
        this.deleted = true;
    }
}

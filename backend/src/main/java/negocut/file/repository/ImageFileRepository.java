package negocut.file.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import negocut.file.entity.ImageFile;

public interface ImageFileRepository extends JpaRepository<ImageFile, Long> {

    Optional<ImageFile> findByIdAndDeletedFalse(Long id);

    // 이미지를 지우는 처리와 경매에 연결하는 처리가 같은 이미지를 두고 경쟁하지 않도록 이미지 행을 잠그고 읽는다.
    // 삭제는 잠금을 얻은 뒤에 경매에 쓰였는지 확인하고, 경매 등록은 잠근 뒤 삭제 여부와 사용 여부를 확인한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ImageFile i where i.id = :id and i.deleted = false")
    Optional<ImageFile> findByIdForUpdate(Long id);

    // 올린 사람이 본인인 이미지만 한꺼번에 잠그고 읽는다. 남의 이미지는 존재 여부를 드러내지 않고 잠그지도 않는다.
    // 식별자 순서로 잠가, 같은 이미지를 두고 겹치는 요청끼리 교착 상태가 되지 않게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ImageFile i where i.id in :ids and i.uploader.id = :uploaderId order by i.id")
    List<ImageFile> findAllByIdAndUploaderIdForUpdate(Collection<Long> ids, Long uploaderId);

    // 올렸지만 아직 경매에 쓰지 않은 내 상품 이미지 수. 10장 한도를 판단한다. (기능 명세서 3-3)
    @Query("select count(i) from ImageFile i where i.uploader.id = :memberId "
            + "and i.usageType = negocut.file.entity.ImageUsage.PRODUCT and i.deleted = false "
            + "and not exists (select 1 from ProductImage p where p.imageFile = i)")
    long countUnusedProductImages(Long memberId);

    // 현재 프로필로 쓰지 않는 내 프로필 이미지 수. 새 이미지를 올리기 전에 1장이 넘지 않게 한다.
    @Query("select count(i) from ImageFile i where i.uploader.id = :memberId "
            + "and i.usageType = negocut.file.entity.ImageUsage.PROFILE and i.deleted = false "
            + "and (:currentProfileImageId is null or i.id <> :currentProfileImageId)")
    long countUnusedProfileImages(Long memberId, Long currentProfileImageId);
}

package negocut.file.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.file.entity.ImageFile;

public interface ImageFileRepository extends JpaRepository<ImageFile, Long> {

    Optional<ImageFile> findByIdAndDeletedFalse(Long id);

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

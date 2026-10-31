package negocut.file.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import negocut.auction.repository.ProductImageRepository;
import negocut.auth.security.AuthMember;
import negocut.common.config.UploadConfig;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.file.dto.ImageUploadResponse;
import negocut.file.entity.ImageFile;
import negocut.file.entity.ImageUsage;
import negocut.file.repository.ImageFileRepository;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;

// 이미지 업로드·조회·삭제. (기능 명세서 3-3)
@Service
@RequiredArgsConstructor
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    // 올렸지만 아직 경매에 쓰지 않은 상품 이미지는 10장까지 올릴 수 있다. 경매 한 건의 최대 장수와 같다.
    private static final int MAX_UNUSED_PRODUCT_IMAGES = 10;
    // 현재 프로필로 쓰지 않는 프로필 이미지는 1장까지. 교체는 새로 올린 뒤 프로필을 바꾸고 이전 이미지를 지우는 순서다.
    private static final int MAX_UNUSED_PROFILE_IMAGES = 1;
    private static final int MAX_ORIGINAL_NAME_LENGTH = 255;

    private final ImageFileRepository imageFileRepository;
    private final ProductImageRepository productImageRepository;
    private final MemberRepository memberRepository;
    private final ImageStorage imageStorage;

    // 조회 결과: 파일, 형식, 용도, 누구에게나 공개되는 이미지인지
    public record ImageContent(Resource resource, String contentType, ImageUsage usageType, boolean isPublic) {
    }

    @Transactional
    public ImageUploadResponse upload(AuthMember uploader, MultipartFile file, ImageUsage usage) {
        // 거래 제한 회원도 본인 정보 관리(프로필 이미지)는 할 수 있지만, 경매 등록에 쓰는 상품 이미지는 올릴 수 없다. (기능 명세서 2-2)
        if (usage == ImageUsage.PRODUCT && uploader.status() != MemberStatus.NORMAL) {
            throw new BusinessException(ErrorCode.MEMBER_TRADE_RESTRICTED);
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (file.getSize() > UploadConfig.MAX_IMAGE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        byte[] bytes = readBytes(file);
        String originalName = sanitizeOriginalName(file.getOriginalFilename());
        ImageSignature signature = requireAllowedImage(file, originalName, bytes);

        // 같은 회원의 동시 업로드가 한도를 함께 넘지 않도록, 회원 행을 잠가 한 줄로 처리한다.
        Member member = memberRepository.findByIdForUpdate(uploader.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        requireWithinLimit(member, usage);

        ImageStorage.StoredFile stored = imageStorage.store(usage, signature.storedExtension(), bytes);
        // 저장 뒤 트랜잭션이 취소되면 올린 파일이 남지 않게 지운다.
        deleteFileUnlessCommitted(stored.relativePath());

        ImageFile saved = imageFileRepository.saveAndFlush(ImageFile.create(
                member, usage, stored.storedName(), originalName, stored.relativePath(),
                signature.contentType(), bytes.length));
        return ImageUploadResponse.from(saved);
    }

    // 현재 프로필로 쓰는 프로필 이미지와 경매에 쓰인 상품 이미지는 누구나 볼 수 있다. 다만 중단된 경매의 상품 이미지는 공개하지 않고,
    // 그 경매의 상세를 볼 수 있는 사람(판매자·입찰한 적 있는 회원)과 관리자만 본다. (기능 명세서 3-10 경매 강제 종료·숨김)
    // 아직 프로필로 쓰지 않았거나 경매에 쓰지 않은 이미지는 올린 본인만 본다.
    // 볼 수 없는 경우에는 이미지가 있는지 알리지 않으려고 없는 이미지와 같은 404로 응답한다.
    @Transactional(readOnly = true)
    public ImageContent get(Long imageId, AuthMember viewer) {
        ImageFile image = imageFileRepository.findByIdAndDeletedFalse(imageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));

        boolean isPublic = isPublic(image);
        if (!isPublic && !isUploader(image, viewer) && !canViewStoppedProductImage(image, viewer)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }

        Path path = imageStorage.resolve(image.getFilePath());
        if (!Files.isRegularFile(path)) {
            log.warn("이미지 파일이 디스크에 없습니다. imageId={}", imageId);
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return new ImageContent(new FileSystemResource(path), image.getContentType(), image.getUsageType(), isPublic);
    }

    // 올린 본인만 지울 수 있다. 경매에 쓰인 상품 이미지는 지울 수 없다. DB는 소프트 삭제하고 파일은 디스크에서 지운다.
    // 현재 프로필로 쓰던 이미지를 지우면 프로필 이미지 참조도 비운다.
    // READ_COMMITTED: 이미지 행을 잠근 뒤에 경매 사용 여부를 읽을 때 최신 커밋 상태를 보게 한다. (AuctionService.register와 같은 이유)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long imageId, AuthMember requester) {
        // 이 요청의 첫 조회여야 한다. 이미지 행을 잠근 뒤에 경매 사용 여부를 읽어야, 같은 이미지를 경매에 연결하는 요청이 먼저
        // 끝났다면 그 결과를 볼 수 있다. (경매 등록이 같은 이미지 행을 잠그고 연결한다.)
        ImageFile image = imageFileRepository.findByIdForUpdate(imageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));

        if (!isUploader(image, requester)) {
            // 공개된 이미지는 권한 없음을, 비공개 이미지는 존재 자체를 알리지 않는다.
            throw new BusinessException(isPublic(image) ? ErrorCode.FILE_ACCESS_DENIED : ErrorCode.FILE_NOT_FOUND);
        }
        if (image.getUsageType() == ImageUsage.PRODUCT && productImageRepository.existsByImageFileId(imageId)) {
            throw new BusinessException(ErrorCode.FILE_IN_USE);
        }
        if (image.getUsageType() == ImageUsage.PROFILE) {
            Member member = memberRepository.findByIdForUpdate(requester.memberId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
            if (imageId.equals(member.getProfileImageId())) {
                member.clearProfileImage();
            }
        }

        image.delete();
        deleteFileAfterCommit(image.getFilePath());
    }

    // 프로필 이미지는 어떤 회원의 현재 프로필로 쓰일 때, 상품 이미지는 상품에 연결(경매에 사용)됐을 때 공개된다.
    private boolean isPublic(ImageFile image) {
        if (image.getUsageType() == ImageUsage.PROFILE) {
            return memberRepository.existsByProfileImageId(image.getId());
        }
        return productImageRepository.existsPublicByImageFileId(image.getId());
    }

    // 중단된 경매의 상품 이미지를 볼 수 있는 사람인지. 관리자는 모두, 회원은 그 경매의 판매자와 입찰한 적 있는 회원이다.
    // 중단되지 않은 경매의 이미지는 이미 공개이므로 여기까지 오는 상품 이미지 중 상품에 연결된 것은 모두 중단된 경매의 이미지다.
    private boolean canViewStoppedProductImage(ImageFile image, AuthMember viewer) {
        if (viewer == null || image.getUsageType() != ImageUsage.PRODUCT) {
            return false;
        }
        if (viewer.role() == MemberRole.ADMIN) {
            return productImageRepository.existsByImageFileId(image.getId());
        }
        return productImageRepository.existsStoppedAuctionParticipant(image.getId(), viewer.memberId());
    }

    private boolean isUploader(ImageFile image, AuthMember member) {
        return member != null && image.getUploader().getId().equals(member.memberId());
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드한 파일을 읽지 못했습니다.", e);
        }
    }

    // 시그니처로 형식을 판별하고, 올린 Content-Type과 파일명 확장자가 그 형식과 맞는지 확인한다.
    private ImageSignature requireAllowedImage(MultipartFile file, String originalName, byte[] bytes) {
        ImageSignature signature = ImageSignature.detect(bytes);
        if (signature == null) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        String declaredType = file.getContentType();
        if (declaredType == null || !signature.contentType().equalsIgnoreCase(declaredType.trim())) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        if (!signature.allowsExtension(extensionOf(originalName))) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        return signature;
    }

    private void requireWithinLimit(Member member, ImageUsage usage) {
        if (usage == ImageUsage.PRODUCT) {
            if (imageFileRepository.countUnusedProductImages(member.getId()) >= MAX_UNUSED_PRODUCT_IMAGES) {
                throw new BusinessException(ErrorCode.FILE_COUNT_EXCEEDED);
            }
            return;
        }
        if (imageFileRepository.countUnusedProfileImages(member.getId(), member.getProfileImageId()) >= MAX_UNUSED_PROFILE_IMAGES) {
            throw new BusinessException(ErrorCode.FILE_COUNT_EXCEEDED);
        }
    }

    // 파일명에서 경로를 떼고, 제어 문자와 글자 방향을 바꾸는 서식 문자(U+202E 등)를 지우고, 255자로 맞춘다.
    // 저장 경로에는 쓰지 않고 표시용으로만 보관한다. 자를 때는 글자(코드 포인트) 단위로 잘라 한 글자가 쪼개지지 않게 한다.
    private String sanitizeOriginalName(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        int length = name.codePointCount(0, name.length());
        if (length > MAX_ORIGINAL_NAME_LENGTH) {
            name = name.substring(name.offsetByCodePoints(0, length - MAX_ORIGINAL_NAME_LENGTH));
        }
        return name;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : fileName.substring(dot + 1);
    }

    private void deleteFileUnlessCommitted(String relativePath) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    imageStorage.deleteQuietly(relativePath);
                }
            }
        });
    }

    private void deleteFileAfterCommit(String relativePath) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                imageStorage.deleteQuietly(relativePath);
            }
        });
    }
}
